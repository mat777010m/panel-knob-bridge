package com.local.knobbridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class BridgeService extends Service {
    private static final String TAG = "KnobBridge";

    private String brokerUri = "tcp://192.168.0.7:1883";
    private String mqttUser = "admin";
    private String mqttPass = "admin";
    private String baseTopic = "panel";

    private MqttClient client;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean isCustomScreenActive = false;
    private boolean clockEnabled = true;
    private Thread inputEventThread;
    private boolean running = true;

    // Nasłuchiwanie sprzętowych zmian głośności (z broadcastów systemowych)
    private final BroadcastReceiver hardwareVolumeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int room = intent.getIntExtra("room", 1);
            int volume = intent.getIntExtra("volume", -1);
            if (volume != -1) {
                publishVolumeState(room, volume);
            }
        }
    };

    // Zegar co minutę i wybudzanie
    private final BroadcastReceiver timeAndScreenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                wakeDisplayHardware();
            }
            if (!isCustomScreenActive && clockEnabled) {
                drawClock();
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        startForegroundNoti();
        wakeDisplayHardware();
        loadConfigAndRegisterReceiver();

        IntentFilter timeFilter = new IntentFilter();
        timeFilter.addAction(Intent.ACTION_TIME_TICK);
        timeFilter.addAction(Intent.ACTION_SCREEN_ON);
        registerReceiver(timeAndScreenReceiver, timeFilter);

        IntentFilter volFilter = new IntentFilter();
        volFilter.addAction("com.naner.volume.changed");
        volFilter.addAction("com.naner.volume.userchange");
        registerReceiver(hardwareVolumeReceiver, volFilter);

        startInputMonitoring();
        initMqtt();
        drawClock();
    }

    private void startForegroundNoti() {
        NotificationChannel chan = new NotificationChannel("knob", "Knob Bridge", NotificationManager.IMPORTANCE_LOW);
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(chan);
        Notification noti = new Notification.Builder(this, "knob")
                .setContentTitle("Knob Bridge Active")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .build();
        startForeground(101, noti);
    }

    private void wakeDisplayHardware() {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c",
                    "echo on > /proc/vendor/lcd_ctrl && echo on > /proc/vendor/led_ctrl && chmod 666 /dev/graphics/fb1"});
            p.waitFor();
        } catch (Exception e) {
            Log.e(TAG, "Display wake failed", e);
        }
    }

    // Monitorowanie obrotów gałki i kliknięcia z /dev/input/event0
    private void startInputMonitoring() {
        inputEventThread = new Thread(() -> {
            try {
                Process process = Runtime.getRuntime().exec(new String[]{"sh", "-c", "getevent -l /dev/input/event0"});
                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                String line;
                while (running && (line = reader.readLine()) != null) {
                    // Reagujemy tylko na zdarzenie wciśnięcia (DOWN), ignorując puszczenie (UP)
                    if (line.contains("DOWN") || line.contains(" 00000001")) {
                        if (line.contains("KEY_F1")) {
                            Log.i(TAG, "Knob -> Rotate Left");
                            publishRaw(baseTopic + "/knob/action", "rotate_left", false);
                        } else if (line.contains("KEY_F2")) {
                            Log.i(TAG, "Knob -> Rotate Right");
                            publishRaw(baseTopic + "/knob/action", "rotate_right", false);
                        } else if (line.contains("KEY_F3")) {
                            Log.i(TAG, "Knob -> Click");
                            publishRaw(baseTopic + "/knob/action", "click", false);
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error reading /dev/input/event0", e);
            }
        });
        inputEventThread.setDaemon(true);
        inputEventThread.start();
    }

    private void loadConfigAndRegisterReceiver() {
        SharedPreferences prefs = getSharedPreferences("knob_prefs", MODE_PRIVATE);
        this.brokerUri = prefs.getString("broker", this.brokerUri);
        this.mqttUser = prefs.getString("user", this.mqttUser);
        this.mqttPass = prefs.getString("pass", this.mqttPass);
        this.baseTopic = prefs.getString("topic", this.baseTopic);

        IntentFilter filter = new IntentFilter("com.local.knobbridge.SET_CONFIG");
        registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                SharedPreferences.Editor ed = prefs.edit();
                if (intent.hasExtra("broker")) ed.putString("broker", intent.getStringExtra("broker"));
                if (intent.hasExtra("user")) ed.putString("user", intent.getStringExtra("user"));
                if (intent.hasExtra("pass")) ed.putString("pass", intent.getStringExtra("pass"));
                if (intent.hasExtra("topic")) ed.putString("topic", intent.getStringExtra("topic"));
                ed.apply();
                loadConfigAndRegisterReceiver();
                initMqtt();
            }
        }, filter, Context.RECEIVER_EXPORTED);
    }

    private void initMqtt() {
        new Thread(() -> {
            try {
                if (client != null && client.isConnected()) {
                    client.disconnect();
                }
                client = new MqttClient(brokerUri, "PanelKnob_" + System.currentTimeMillis(), new MemoryPersistence());
                MqttConnectOptions opts = new MqttConnectOptions();
                opts.setCleanSession(true);
                opts.setAutomaticReconnect(true);
                if (!mqttUser.isEmpty()) {
                    opts.setUserName(mqttUser);
                    opts.setPassword(mqttPass.toCharArray());
                }

                client.setCallback(new MqttCallbackExtended() {
                    @Override
                    public void connectComplete(boolean reconnect, String serverURI) {
                        Log.i(TAG, "MQTT Connected to " + serverURI);
                        subscribeTopics();
                        publishHomeAssistantDiscovery();
                    }

                    @Override
                    public void connectionLost(Throwable cause) {
                        Log.w(TAG, "MQTT Connection lost", cause);
                    }

                    @Override
                    public void messageArrived(String topic, MqttMessage message) {
                        handleMessage(topic, new String(message.getPayload()).trim());
                    }

                    @Override
                    public void deliveryComplete(IMqttDeliveryToken token) {}
                });

                client.connect(opts);
            } catch (Exception e) {
                Log.e(TAG, "MQTT connection error: " + e.getMessage());
            }
        }).start();
    }

    private void subscribeTopics() {
        try {
            client.subscribe(baseTopic + "/volume/set/+");
            client.subscribe(baseTopic + "/display/#");
            Log.i(TAG, "Subscribed topics on " + baseTopic);
        } catch (Exception e) {
            Log.e(TAG, "Subscribe error", e);
        }
    }

    // Wszystkie encje w DOKŁADNIE JEDNYM urządzeniu "Gałka Panel"
    private void publishHomeAssistantDiscovery() {
        try {
            JSONObject device = new JSONObject();
            device.put("identifiers", new JSONArray().put("panel_knob_unified"));
            device.put("name", "Gałka Panel");
            device.put("model", "Allwinner Knob 240x240");
            device.put("manufacturer", "Custom Smart Home");

            // 1. Głośność Strefa 1
            JSONObject v1 = new JSONObject();
            v1.put("name", "Głośność Strefa 1");
            v1.put("unique_id", "knob_unified_vol1");
            v1.put("command_topic", baseTopic + "/volume/set/1");
            v1.put("state_topic", baseTopic + "/volume/state/1");
            v1.put("min", 0);
            v1.put("max", 100);
            v1.put("mode", "slider");
            v1.put("icon", "mdi:volume-high");
            v1.put("device", device);
            publishRaw("homeassistant/number/panel_knob/vol1/config", v1.toString(), true);

            // 2. Głośność Strefa 2
            JSONObject v2 = new JSONObject();
            v2.put("name", "Głośność Strefa 2");
            v2.put("unique_id", "knob_unified_vol2");
            v2.put("command_topic", baseTopic + "/volume/set/2");
            v2.put("state_topic", baseTopic + "/volume/state/2");
            v2.put("min", 0);
            v2.put("max", 100);
            v2.put("mode", "slider");
            v2.put("icon", "mdi:volume-high");
            v2.put("device", device);
            publishRaw("homeassistant/number/panel_knob/vol2/config", v2.toString(), true);

            // 3. Przełącznik Zegara
            JSONObject clk = new JSONObject();
            clk.put("name", "Ekran Zegar");
            clk.put("unique_id", "knob_unified_clock");
            clk.put("command_topic", baseTopic + "/display/clock");
            clk.put("payload_on", "on");
            clk.put("payload_off", "off");
            clk.put("icon", "mdi:clock-digital");
            clk.put("device", device);
            publishRaw("homeassistant/switch/panel_knob/clock/config", clk.toString(), true);

            // 4. Pole Tekstowe (wpisujesz tekst w HA -> wyświetla na gałce)
            JSONObject txt = new JSONObject();
            txt.put("name", "Ekran Tekst");
            txt.put("unique_id", "knob_unified_text");
            txt.put("command_topic", baseTopic + "/display/text");
            txt.put("icon", "mdi:text-box-outline");
            txt.put("device", device);
            publishRaw("homeassistant/text/panel_knob/text/config", txt.toString(), true);

            // 5. Suwak Progresu / Rolety (z okrągłym łukiem na gałce)
            JSONObject prog = new JSONObject();
            prog.put("name", "Ekran Progres / Roleta");
            prog.put("unique_id", "knob_unified_progress");
            prog.put("command_topic", baseTopic + "/display/progress");
            prog.put("state_topic", baseTopic + "/display/progress_state");
            prog.put("min", 0);
            prog.put("max", 100);
            prog.put("mode", "slider");
            prog.put("icon", "mdi:circle-slice-5");
            prog.put("device", device);
            publishRaw("homeassistant/number/panel_knob/progress/config", prog.toString(), true);

            // 6. Sensor Akcji Gałki (rotate_left, rotate_right, click)
            JSONObject act = new JSONObject();
            act.put("name", "Akcja Gałki");
            act.put("unique_id", "knob_unified_action");
            act.put("state_topic", baseTopic + "/knob/action");
            act.put("icon", "mdi:rotate-right");
            act.put("device", device);
            publishRaw("homeassistant/sensor/panel_knob/action/config", act.toString(), true);

            Log.i(TAG, "All unified entities published to HA");
        } catch (Exception e) {
            Log.e(TAG, "Discovery publication error", e);
        }
    }

    private void publishVolumeState(int room, int vol) {
        publishRaw(baseTopic + "/volume/state/" + room, String.valueOf(vol), true);
    }

    private void publishRaw(String topic, String payload, boolean retained) {
        try {
            if (client != null && client.isConnected()) {
                MqttMessage msg = new MqttMessage(payload.getBytes());
                msg.setRetained(retained);
                client.publish(topic, msg);
            }
        } catch (Exception e) {
            Log.e(TAG, "Publish error: " + topic, e);
        }
    }

    private void handleMessage(String topic, String payload) {
        try {
            // Zmiana głośności stref
            if (topic.startsWith(baseTopic + "/volume/set/")) {
                int room = Integer.parseInt(topic.substring((baseTopic + "/volume/set/").length()));
                int vol = Integer.parseInt(payload);
                Intent intent = new Intent("com.naner.volume.userchange");
                intent.putExtra("room", room);
                intent.putExtra("volume", vol);
                sendBroadcast(intent);

                publishVolumeState(room, vol);
                showNotification("🔊 STREFA " + room, vol + "%", "Głośność", 2500, "#00E5FF", "#FFFFFF");
                return;
            }

            // Wyświetlenie prostego tekstu z encji HA
            if (topic.equals(baseTopic + "/display/text")) {
                showNotification("WIADOMOŚĆ", payload, "", 4000, "#FFA726", "#FFFFFF");
                return;
            }

            // Wyświetlenie okrągłego łuku progresu (roleta, bateria, itp.)
            if (topic.equals(baseTopic + "/display/progress")) {
                int val = Integer.parseInt(payload);
                publishRaw(baseTopic + "/display/progress_state", String.valueOf(val), true);
                drawProgressArc(val, "ROLETA");
                return;
            }

            // Własny JSON z kolorami
            if (topic.equals(baseTopic + "/display/custom")) {
                JSONObject json = new JSONObject(payload);
                showNotification(
                        json.optString("title", ""),
                        json.optString("text", ""),
                        json.optString("sub", ""),
                        json.optInt("duration", 4000),
                        json.optString("title_color", "#4FC3F7"),
                        json.optString("text_color", "#FFFFFF")
                );
                return;
            }

            // Surowy obraz Base64
            if (topic.equals(baseTopic + "/display/image")) {
                byte[] decoded = Base64.decode(payload, Base64.DEFAULT);
                Bitmap bmp = BitmapFactory.decodeByteArray(decoded, 0, decoded.length);
                if (bmp != null) {
                    isCustomScreenActive = true;
                    Bitmap scaled = Bitmap.createScaledBitmap(bmp, 240, 240, true);
                    renderToFramebuffer(scaled);
                    handler.removeCallbacks(resetClockTask);
                    handler.postDelayed(resetClockTask, 5000);
                }
                return;
            }

            // Włącznik zegara
            if (topic.equals(baseTopic + "/display/clock")) {
                clockEnabled = !"off".equalsIgnoreCase(payload) && !"false".equalsIgnoreCase(payload);
                if (!clockEnabled) {
                    clearScreen();
                } else {
                    isCustomScreenActive = false;
                    drawClock();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Message handling error", e);
        }
    }

    // Nowoczesny okrągły łuk 240x240 z procentem na środku
    private synchronized void drawProgressArc(int percent, String label) {
        isCustomScreenActive = true;
        Bitmap bmp = Bitmap.createBitmap(240, 240, Bitmap.Config.RGB_565);
        Canvas canvas = new Canvas(bmp);
        canvas.drawColor(Color.BLACK);

        // Szare tło łuku
        Paint arcBg = new Paint();
        arcBg.setAntiAlias(true);
        arcBg.setStyle(Paint.Style.STROKE);
        arcBg.setStrokeWidth(16);
        arcBg.setColor(Color.DKGRAY);
        RectF rect = new RectF(28, 28, 212, 212);
        canvas.drawArc(rect, 135, 270, false, arcBg);

        // Aktywny łuk (morski błękit)
        Paint arcFg = new Paint();
        arcFg.setAntiAlias(true);
        arcFg.setStyle(Paint.Style.STROKE);
        arcFg.setStrokeWidth(16);
        arcFg.setStrokeCap(Paint.Cap.ROUND);
        arcFg.setColor(Color.parseColor("#00E5FF"));
        float sweep = (270f * Math.max(0, Math.min(100, percent))) / 100f;
        canvas.drawArc(rect, 135, sweep, false, arcFg);

        // Wartość procentowa na środku
        Paint textPaint = new Paint();
        textPaint.setAntiAlias(true);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(54);
        textPaint.setTypeface(Typeface.DEFAULT_BOLD);
        canvas.drawText(percent + "%", 120, 132, textPaint);

        // Etykieta pod spodem
        Paint labelPaint = new Paint();
        labelPaint.setAntiAlias(true);
        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setColor(Color.LTGRAY);
        labelPaint.setTextSize(22);
        canvas.drawText(label, 120, 175, labelPaint);

        renderToFramebuffer(bmp);
        handler.removeCallbacks(resetClockTask);
        handler.postDelayed(resetClockTask, 3500);
    }

    private synchronized void showNotification(String title, String mainText, String sub, int durationMs, String titleColorHex, String textColorHex) {
        isCustomScreenActive = true;
        Bitmap bmp = Bitmap.createBitmap(240, 240, Bitmap.Config.RGB_565);
        Canvas canvas = new Canvas(bmp);
        canvas.drawColor(Color.BLACK);

        Paint paint = new Paint();
        paint.setAntiAlias(true);
        paint.setTextAlign(Paint.Align.CENTER);

        paint.setColor(Color.parseColor(titleColorHex));
        paint.setTextSize(26);
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        canvas.drawText(title, 120, 55, paint);

        paint.setColor(Color.parseColor(textColorHex));
        paint.setTextSize(mainText.length() > 6 ? 32 : 54);
        canvas.drawText(mainText, 120, 135, paint);

        if (!sub.isEmpty()) {
            paint.setColor(Color.GRAY);
            paint.setTextSize(22);
            canvas.drawText(sub, 120, 195, paint);
        }

        renderToFramebuffer(bmp);
        handler.removeCallbacks(resetClockTask);
        if (durationMs > 0) {
            handler.postDelayed(resetClockTask, durationMs);
        }
    }

    private final Runnable resetClockTask = () -> {
        isCustomScreenActive = false;
        if (clockEnabled) {
            drawClock();
        }
    };

    private synchronized void drawClock() {
        Bitmap bmp = Bitmap.createBitmap(240, 240, Bitmap.Config.RGB_565);
        Canvas canvas = new Canvas(bmp);
        canvas.drawColor(Color.BLACK);

        Paint paint = new Paint();
        paint.setAntiAlias(true);
        paint.setTextAlign(Paint.Align.CENTER);

        Date now = new Date();
        paint.setColor(Color.WHITE);
        paint.setTextSize(72);
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        canvas.drawText(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(now), 120, 125, paint);

        paint.setColor(Color.LTGRAY);
        paint.setTextSize(22);
        paint.setTypeface(Typeface.DEFAULT);
        canvas.drawText(new SimpleDateFormat("EEE, d MMM", new Locale("pl", "PL")).format(now), 120, 165, paint);

        renderToFramebuffer(bmp);
    }

    private synchronized void clearScreen() {
        Bitmap bmp = Bitmap.createBitmap(240, 240, Bitmap.Config.RGB_565);
        Canvas canvas = new Canvas(bmp);
        canvas.drawColor(Color.BLACK);
        renderToFramebuffer(bmp);
    }

    private synchronized void renderToFramebuffer(Bitmap bmp) {
        try {
            byte[] raw = new byte[240 * 240 * 2];
            int idx = 0;
            for (int y = 0; y < 240; y++) {
                for (int x = 239; x >= 0; x--) {
                    int p = bmp.getPixel(x, y);
                    int r = (p >> 16) & 0xFF;
                    int g = (p >> 8) & 0xFF;
                    int b = p & 0xFF;
                    int rgb565 = ((r >> 3) << 11) | ((g >> 2) << 5) | (b >> 3);

                    raw[idx++] = (byte) (rgb565 & 0xFF);
                    raw[idx++] = (byte) ((rgb565 >> 8) & 0xFF);
                }
            }

            File fb = new File("/dev/graphics/fb1");
            if (fb.canWrite()) {
                FileOutputStream fos = new FileOutputStream(fb);
                fos.write(raw);
                fos.flush();
                fos.close();
            } else {
                Process process = Runtime.getRuntime().exec("sh");
                OutputStream os = process.getOutputStream();
                os.write(("cat > /dev/graphics/fb1\n").getBytes());
                os.flush();
                os.write(raw);
                os.flush();
                os.close();
                process.waitFor();
            }
        } catch (Exception e) {
            Log.e(TAG, "Framebuffer write error", e);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        running = false;
        try {
            unregisterReceiver(timeAndScreenReceiver);
            unregisterReceiver(hardwareVolumeReceiver);
        } catch (Exception ignored) {}
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}