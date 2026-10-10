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
import android.media.AudioManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.net.wifi.WifiManager;
import android.os.PowerManager;

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

    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    private String brokerUri = "tcp://192.168.0.7:1883";
    private String mqttUser = "admin";
    private String mqttPass = "admin";
    private String baseTopic = "panel";

    private MqttClient client;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean running = true;

    // Stany UI
    private enum ScreenMode { CLOCK, GAUGE, MEDIA, ALERT }
    private ScreenMode currentMode = ScreenMode.CLOCK;

    // Dane trybu pokrętła (Gauge/Dial)
    private String gaugeTitle = "Regulacja";
    private int gaugeValue = 50;
    private int gaugeMin = 0;
    private int gaugeMax = 100;
    private int gaugeStep = 5;
    private String gaugeUnit = "%";
    private int gaugeColor = Color.parseColor("#00E5FF");

    private Process inputProcess;
    private boolean isScreenHardwarePowered = true;

    // Odbiornik stanu ekranu głównego (usypianie sprzętowe matrycy gałki)
    private final BroadcastReceiver screenStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                Log.i(TAG, "Główny ekran wygaszony -> Wyłączam sprzętowo ekranik gałki");
                setHardwarePower(false);
            } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                Log.i(TAG, "Główny ekran wybudzony -> Włączam sprzętowo ekranik gałki");
                setHardwarePower(true);
                redrawCurrentScreen();
            } else if (Intent.ACTION_TIME_TICK.equals(action)) {
                if (currentMode == ScreenMode.CLOCK && isScreenHardwarePowered) {
                    drawClock();
                }
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        startForegroundNoti();
        acquireLocks();
        loadConfigAndRegisterReceiver();

        // Pełne zasilenie ekranu na start
        setHardwarePower(true);

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_TIME_TICK);
        registerReceiver(screenStateReceiver, filter);

        startRootInputMonitoring();
        initMqtt();
        drawClock();
    }

    private void acquireLocks() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "KnobBridge::CpuLock");
                wakeLock.acquire();
            }

            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "KnobBridge::WifiLock");
                wifiLock.acquire();
            }
            Log.i(TAG, "WakeLock & WifiLock acquired successfully");
        } catch (Exception e) {
            Log.e(TAG, "Błąd podczas pobierania blokad zasilania", e);
        }
    }

    private void startForegroundNoti() {
        NotificationChannel chan = new NotificationChannel("knob", "Knob Bridge", NotificationManager.IMPORTANCE_LOW);
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(chan);
        Notification noti = new Notification.Builder(this, "knob")
                .setContentTitle("Knob Engine Active")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .build();
        startForeground(101, noti);
    }

    // Fizyczne zasilanie matrycy i podświetlenia (zero wypalania matrycy)
    private synchronized void setHardwarePower(boolean enable) {
        isScreenHardwarePowered = enable;
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "0"});
            OutputStream os = p.getOutputStream();
            if (enable) {
                os.write("echo on > /proc/vendor/lcd_ctrl\n".getBytes());
                os.write("echo on > /proc/vendor/led_ctrl\n".getBytes());
                os.write("echo 0 > /sys/class/graphics/fb1/blank\n".getBytes());
                os.write("chmod 666 /dev/graphics/fb1\n".getBytes());
            } else {
                os.write("echo off > /proc/vendor/lcd_ctrl\n".getBytes());
                os.write("echo off > /proc/vendor/led_ctrl\n".getBytes());
                os.write("echo 4 > /sys/class/graphics/fb1/blank\n".getBytes());
            }
            os.write("exit\n".getBytes());
            os.flush();
            p.waitFor();
        } catch (Exception e) {
            Log.e(TAG, "Błąd zasilania ekranu", e);
        }
    }

    // Odczyt enkodera przez proces ROOT (omija ograniczenia SELinux)
    private void startRootInputMonitoring() {
        new Thread(() -> {
            while (running) {
                try {
                    inputProcess = Runtime.getRuntime().exec(new String[]{"su", "0"});
                    OutputStream os = inputProcess.getOutputStream();
                    os.write("getevent -l /dev/input/event0\n".getBytes());
                    os.flush();

                    BufferedReader reader = new BufferedReader(new InputStreamReader(inputProcess.getInputStream()));
                    String line;
                    while (running && (line = reader.readLine()) != null) {
                        if (line.contains("DOWN") || line.contains(" 00000001")) {
                            if (line.contains("KEY_F1")) {
                                handleKnobTurn(-1);
                            } else if (line.contains("KEY_F2")) {
                                handleKnobTurn(1);
                            } else if (line.contains("KEY_F3")) {
                                handleKnobClick();
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Input stream restart...", e);
                    try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
                }
            }
        }).start();
    }

    // Logika obrotu gałką
    private void handleKnobTurn(int delta) {
        if (!isScreenHardwarePowered) {
            // Jeśli ekran wygaszony, pierwszy obrót może go obudzić
            setHardwarePower(true);
            redrawCurrentScreen();
            return;
        }

        if (currentMode == ScreenMode.GAUGE) {
            // W trybie pokrętła zmieniamy wartość lokalnie bez żadnego laga!
            gaugeValue = Math.max(gaugeMin, Math.min(gaugeMax, gaugeValue + (delta * gaugeStep)));
            drawGaugeScreen();
            publishRaw(baseTopic + "/knob/gauge_value", String.valueOf(gaugeValue), false);
            publishRaw(baseTopic + "/knob/action", delta > 0 ? "rotate_right" : "rotate_left", false);
            resetAutoClockTimeout(5000);
        } else {
            // Tryb spoczynku: przesyłamy akcję do HA (np. do głośności muzyki)
            publishRaw(baseTopic + "/knob/action", delta > 0 ? "rotate_right" : "rotate_left", false);
        }
    }

    // Logika kliknięcia
    private void handleKnobClick() {
        if (!isScreenHardwarePowered) {
            setHardwarePower(true);
            redrawCurrentScreen();
            return;
        }

        if (currentMode == ScreenMode.GAUGE) {
            // Zatwierdzenie wartości z pokrętła w HA i powrót do zegarka
            publishRaw(baseTopic + "/knob/confirm", String.valueOf(gaugeValue), false);
            returnToClock();
        } else {
            publishRaw(baseTopic + "/knob/action", "click", false);
        }
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
                        handleIncomingMqtt(topic, new String(message.getPayload()).trim());
                    }

                    @Override
                    public void deliveryComplete(IMqttDeliveryToken token) {}
                });

                client.connect(opts);
            } catch (Exception e) {
                Log.e(TAG, "MQTT Error: " + e.getMessage());
            }
        }).start();
    }

    private void subscribeTopics() {
        try {
            client.subscribe(baseTopic + "/display/#");
            client.subscribe(baseTopic + "/volume/#");
        } catch (Exception e) {}
    }

    // Auto-Discovery: jedno spójne urządzenie w Home Assistancie
    private void publishHomeAssistantDiscovery() {
        try {
            JSONObject device = new JSONObject();
            device.put("identifiers", new JSONArray().put("panel_knob_engine"));
            device.put("name", "Gałka Panel");
            device.put("model", "Allwinner Knob 240x240 Engine");
            device.put("manufacturer", "Custom Smart Home");

            // Sensor akcji gałki (rotate_left, rotate_right, click)
            JSONObject act = new JSONObject();
            act.put("name", "Akcja Gałki");
            act.put("unique_id", "knob_engine_action");
            act.put("state_topic", baseTopic + "/knob/action");
            act.put("icon", "mdi:rotate-right");
            act.put("device", device);
            publishRaw("homeassistant/sensor/panel_knob/action/config", act.toString(), true);

            // Sensor wartości pokrętła
            JSONObject gVal = new JSONObject();
            gVal.put("name", "Wartość Pokrętła");
            gVal.put("unique_id", "knob_engine_gauge_val");
            gVal.put("state_topic", baseTopic + "/knob/gauge_value");
            gVal.put("icon", "mdi:gauge");
            gVal.put("device", device);
            publishRaw("homeassistant/sensor/panel_knob/gauge_val/config", gVal.toString(), true);

            // Włącznik zasilania ekranika
            JSONObject pwr = new JSONObject();
            pwr.put("name", "Zasilanie Ekranu");
            pwr.put("unique_id", "knob_engine_power");
            pwr.put("command_topic", baseTopic + "/display/power");
            pwr.put("payload_on", "on");
            pwr.put("payload_off", "off");
            pwr.put("icon", "mdi:power");
            pwr.put("device", device);
            publishRaw("homeassistant/switch/panel_knob/power/config", pwr.toString(), true);

            // Proste pole tekstowe do wysyłania alertu
            JSONObject txt = new JSONObject();
            txt.put("name", "Wyślij Wiadomość");
            txt.put("unique_id", "knob_engine_send_text");
            txt.put("command_topic", baseTopic + "/display/text");
            txt.put("icon", "mdi:message-text-outline");
            txt.put("device", device);
            publishRaw("homeassistant/text/panel_knob/send_text/config", txt.toString(), true);

        } catch (Exception e) {
            Log.e(TAG, "Discovery error", e);
        }
    }

    private void handleIncomingMqtt(String topic, String payload) {
        try {
            // 1. Ręczne wygaszanie/budzenie zasilania
            if (topic.equals(baseTopic + "/display/power")) {
                setHardwarePower("on".equalsIgnoreCase(payload));
                if (isScreenHardwarePowered) redrawCurrentScreen();
                return;
            }

            // 2. Tryb GAUGE (Pokrętło z HA: roleta, światło, temp)
            if (topic.equals(baseTopic + "/display/gauge")) {
                JSONObject json = new JSONObject(payload);
                gaugeTitle = json.optString("title", "Regulacja");
                gaugeValue = json.optInt("value", 50);
                gaugeMin = json.optInt("min", 0);
                gaugeMax = json.optInt("max", 100);
                gaugeStep = json.optInt("step", 5);
                gaugeUnit = json.optString("unit", "%");
                gaugeColor = Color.parseColor(json.optString("color", "#00E5FF"));

                currentMode = ScreenMode.GAUGE;
                drawGaugeScreen();
                resetAutoClockTimeout(json.optInt("timeout", 6000));
                return;
            }

            // 3. Tryb MEDIA (Radio / Odtwarzacz)
            if (topic.equals(baseTopic + "/display/media")) {
                JSONObject json = new JSONObject(payload);
                String title = json.optString("title", "Odtwarzacz");
                String subtitle = json.optString("subtitle", "");
                String base64Img = json.optString("image_base64", "");

                currentMode = ScreenMode.MEDIA;
                drawMediaScreen(title, subtitle, base64Img);
                resetAutoClockTimeout(json.optInt("timeout", 10000));
                return;
            }

            // 4. Tryb ALERT (Dzwonek, powiadomienie)
            if (topic.equals(baseTopic + "/display/alert") || topic.equals(baseTopic + "/display/text")) {
                String title = "POWIADOMIENIE";
                String msg = payload;
                String color = "#FF5722";
                int duration = 5000;

                if (payload.startsWith("{")) {
                    JSONObject json = new JSONObject(payload);
                    title = json.optString("title", title);
                    msg = json.optString("text", "");
                    color = json.optString("color", color);
                    duration = json.optInt("duration", duration);
                }

                currentMode = ScreenMode.ALERT;
                drawAlertScreen(title, msg, color);
                resetAutoClockTimeout(duration);
                return;
            }

            // 5. Powrót do zegara
            if (topic.equals(baseTopic + "/display/clock")) {
                returnToClock();
                return;
            }

            // 6. Głośność multimediów Androida (gdybyś chciał regulować tablet z HA)
            if (topic.equals(baseTopic + "/volume/media/set")) {
                int volPercent = Integer.parseInt(payload);
                AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
                int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                int target = (max * volPercent) / 100;
                am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0);
            }
        } catch (Exception e) {
            Log.e(TAG, "Błąd obsługi MQTT", e);
        }
    }

    private void redrawCurrentScreen() {
        if (!isScreenHardwarePowered) return;
        switch (currentMode) {
            case CLOCK: drawClock(); break;
            case GAUGE: drawGaugeScreen(); break;
            default: drawClock(); break;
        }
    }

    private final Runnable autoClockTask = this::returnToClock;

    private void resetAutoClockTimeout(int ms) {
        handler.removeCallbacks(autoClockTask);
        if (ms > 0) {
            handler.postDelayed(autoClockTask, ms);
        }
    }

    private void returnToClock() {
        currentMode = ScreenMode.CLOCK;
        handler.removeCallbacks(autoClockTask);
        if (isScreenHardwarePowered) {
            drawClock();
        }
    }

    // EKRAN 1: Zegar
    private synchronized void drawClock() {
        Bitmap bmp = Bitmap.createBitmap(240, 240, Bitmap.Config.RGB_565);
        Canvas canvas = new Canvas(bmp);
        canvas.drawColor(Color.BLACK);

        Paint paint = new Paint();
        paint.setAntiAlias(true);
        paint.setTextAlign(Paint.Align.CENTER);

        Date now = new Date();
        paint.setColor(Color.WHITE);
        paint.setTextSize(70);
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        canvas.drawText(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(now), 120, 125, paint);

        paint.setColor(Color.LTGRAY);
        paint.setTextSize(22);
        paint.setTypeface(Typeface.DEFAULT);
        canvas.drawText(new SimpleDateFormat("EEE, d MMM", new Locale("pl", "PL")).format(now), 120, 165, paint);

        renderToFramebuffer(bmp);
    }

    // EKRAN 2: Pokrętło (Gauge) z łukiem i procentem
    private synchronized void drawGaugeScreen() {
        Bitmap bmp = Bitmap.createBitmap(240, 240, Bitmap.Config.RGB_565);
        Canvas canvas = new Canvas(bmp);
        canvas.drawColor(Color.BLACK);

        RectF arcRect = new RectF(28, 28, 212, 212);

        // Tło łuku
        Paint bgPaint = new Paint();
        bgPaint.setAntiAlias(true);
        bgPaint.setStyle(Paint.Style.STROKE);
        bgPaint.setStrokeWidth(16);
        bgPaint.setColor(Color.DKGRAY);
        canvas.drawArc(arcRect, 135, 270, false, bgPaint);

        // Aktywny łuk
        Paint fgPaint = new Paint();
        fgPaint.setAntiAlias(true);
        fgPaint.setStyle(Paint.Style.STROKE);
        fgPaint.setStrokeWidth(16);
        fgPaint.setStrokeCap(Paint.Cap.ROUND);
        fgPaint.setColor(gaugeColor);

        float range = Math.max(1, gaugeMax - gaugeMin);
        float pct = ((float)(gaugeValue - gaugeMin)) / range;
        float sweep = 270f * Math.max(0f, Math.min(1f, pct));
        canvas.drawArc(arcRect, 135, sweep, false, fgPaint);

        // Tytuł na górze
        Paint textPaint = new Paint();
        textPaint.setAntiAlias(true);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setColor(Color.LTGRAY);
        textPaint.setTextSize(22);
        canvas.drawText(gaugeTitle, 120, 75, textPaint);

        // Wartość na środku
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(54);
        textPaint.setTypeface(Typeface.DEFAULT_BOLD);
        canvas.drawText(gaugeValue + gaugeUnit, 120, 135, textPaint);

        // Podpowiedź zatwierdzenia
        textPaint.setColor(Color.GRAY);
        textPaint.setTextSize(18);
        textPaint.setTypeface(Typeface.DEFAULT);
        canvas.drawText("Kliknij = OK", 120, 185, textPaint);

        renderToFramebuffer(bmp);
    }

    // EKRAN 3: Media / Radio
    private synchronized void drawMediaScreen(String title, String subtitle, String base64Img) {
        Bitmap bmp = Bitmap.createBitmap(240, 240, Bitmap.Config.RGB_565);
        Canvas canvas = new Canvas(bmp);
        canvas.drawColor(Color.BLACK);

        if (!base64Img.isEmpty()) {
            try {
                byte[] raw = Base64.decode(base64Img, Base64.DEFAULT);
                Bitmap art = BitmapFactory.decodeByteArray(raw, 0, raw.length);
                if (art != null) {
                    Bitmap scaled = Bitmap.createScaledBitmap(art, 240, 240, true);
                    canvas.drawBitmap(scaled, 0, 0, null);
                    renderToFramebuffer(bmp);
                    return;
                }
            } catch (Exception ignored) {}
        }

        Paint paint = new Paint();
        paint.setAntiAlias(true);
        paint.setTextAlign(Paint.Align.CENTER);

        paint.setColor(Color.parseColor("#FFA726"));
        paint.setTextSize(24);
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        canvas.drawText("📻 RADIO / AUDIO", 120, 60, paint);

        paint.setColor(Color.WHITE);
        paint.setTextSize(title.length() > 10 ? 28 : 36);
        canvas.drawText(title, 120, 125, paint);

        if (!subtitle.isEmpty()) {
            paint.setColor(Color.LTGRAY);
            paint.setTextSize(20);
            canvas.drawText(subtitle, 120, 175, paint);
        }

        renderToFramebuffer(bmp);
    }

    // EKRAN 4: Alert
    private synchronized void drawAlertScreen(String title, String msg, String colorHex) {
        Bitmap bmp = Bitmap.createBitmap(240, 240, Bitmap.Config.RGB_565);
        Canvas canvas = new Canvas(bmp);
        canvas.drawColor(Color.BLACK);

        Paint paint = new Paint();
        paint.setAntiAlias(true);
        paint.setTextAlign(Paint.Align.CENTER);

        paint.setColor(Color.parseColor(colorHex));
        paint.setTextSize(28);
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        canvas.drawText(title, 120, 65, paint);

        paint.setColor(Color.WHITE);
        paint.setTextSize(msg.length() > 8 ? 30 : 48);
        canvas.drawText(msg, 120, 140, paint);

        renderToFramebuffer(bmp);
    }

    private void publishRaw(String topic, String payload, boolean retained) {
        try {
            if (client != null && client.isConnected()) {
                MqttMessage msg = new MqttMessage(payload.getBytes());
                msg.setRetained(retained);
                client.publish(topic, msg);
            }
        } catch (Exception e) {}
    }

    // Bezpośredni zapis do fb1 z lustrzanym odbiciem
    private synchronized void renderToFramebuffer(Bitmap bmp) {
        if (!isScreenHardwarePowered) return;
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
                Process process = Runtime.getRuntime().exec(new String[]{"su", "0"});
                OutputStream os = process.getOutputStream();
                os.write(("cat > /dev/graphics/fb1\n").getBytes());
                os.flush();
                os.write(raw);
                os.flush();
                os.close();
                process.waitFor();
            }
        } catch (Exception e) {
            Log.e(TAG, "Framebuffer error", e);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        running = false;
        try { unregisterReceiver(screenStateReceiver); } catch (Exception ignored) {}
        // Zwalniamy blokady przy zatrzymaniu usługi
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
            if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        } catch (Exception ignored) {}
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}