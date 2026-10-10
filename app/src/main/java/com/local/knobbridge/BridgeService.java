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
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
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

    // Nasłuchiwanie sprzętowych zmian głośności z fabrycznego modułu wzmacniacza
    private final BroadcastReceiver hardwareVolumeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int room = intent.getIntExtra("room", 1);
            int volume = intent.getIntExtra("volume", -1);
            if (volume != -1) {
                Log.i(TAG, "Hardware volume change detected: Room " + room + " = " + volume);
                publishVolumeState(room, volume);
                showNotification("🔊 STREFA " + room, volume + "%", "Głośność", 2500, "#00E5FF", "#FFFFFF");
            }
        }
    };

    // Zegar: aktualizacja co minutę i przy wybudzeniu ekranu
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

        // Odbiorniki zegara i fizycznej głośności
        IntentFilter timeFilter = new IntentFilter();
        timeFilter.addAction(Intent.ACTION_TIME_TICK);
        timeFilter.addAction(Intent.ACTION_SCREEN_ON);
        registerReceiver(timeAndScreenReceiver, timeFilter);

        IntentFilter volFilter = new IntentFilter();
        volFilter.addAction("com.naner.volume.changed");
        volFilter.addAction("com.naner.volume.userchange");
        registerReceiver(hardwareVolumeReceiver, volFilter);

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
                "echo on > /proc/vendor/lcd_ctrl && echo 1 > /proc/vendor/led_ctrl && chmod 666 /dev/graphics/fb1"});
            p.waitFor();
        } catch (Exception e) {
            Log.e(TAG, "Display wake failed", e);
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
                        publishHomeAssistantDiscovery(); // Automatyczna rejestracja w HA
                    }

                    @Override
                    public void connectionLost(Throwable cause) {
                        Log.w(TAG, "MQTT Connection lost", cause);
                    }

                    @Override
                    public void messageArrived(String topic, MqttMessage message) {
                        String payload = new String(message.getPayload()).trim();
                        Log.i(TAG, "MQTT Received: " + topic + " -> " + payload);
                        handleMessage(topic, payload);
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

    // Automatyczna publikacja Auto-Discovery do HA (tworzy urządzenie w kafelku MQTT)
    private void publishHomeAssistantDiscovery() {
        try {
            JSONObject device = new JSONObject();
            device.put("identifiers", new org.json.JSONArray().put("allwinner_knob_panel"));
            device.put("name", "Gałka Panel");
            device.put("model", "Knob Display 240x240");
            device.put("manufacturer", "Allwinner OEM");

            // Suwak dla Strefy 1 z tematem stanu (synchronizacja!)
            JSONObject discVol1 = new JSONObject();
            discVol1.put("name", "Głośność Strefa 1");
            discVol1.put("unique_id", "knob_panel_zone1_volume");
            discVol1.put("command_topic", baseTopic + "/volume/set/1");
            discVol1.put("state_topic", baseTopic + "/volume/state/1");
            discVol1.put("min", 0);
            discVol1.put("max", 100);
            discVol1.put("mode", "slider");
            discVol1.put("icon", "mdi:volume-high");
            discVol1.put("device", device);

            // Przełącznik zegarka
            JSONObject discClock = new JSONObject();
            discClock.put("name", "Ekran Zegar");
            discClock.put("unique_id", "knob_panel_clock_toggle");
            discClock.put("command_topic", baseTopic + "/display/clock");
            discClock.put("payload_on", "on");
            discClock.put("payload_off", "off");
            discClock.put("icon", "mdi:clock-digital");
            discClock.put("device", device);

            publishRaw("homeassistant/number/knob_panel/vol1/config", discVol1.toString(), true);
            publishRaw("homeassistant/switch/knob_panel/clock/config", discClock.toString(), true);
            Log.i(TAG, "Home Assistant MQTT Discovery published successfully");
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
            Log.e(TAG, "Publish error to " + topic, e);
        }
    }

    private void handleMessage(String topic, String payload) {
        try {
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
        paint.setTextSize(mainText.length() > 6 ? 34 : 58);
        canvas.drawText(mainText, 120, 135, paint);

        if (!sub.isEmpty()) {
            paint.setColor(Color.GRAY);
            paint.setTextSize(22);
            paint.setTypeface(Typeface.DEFAULT);
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