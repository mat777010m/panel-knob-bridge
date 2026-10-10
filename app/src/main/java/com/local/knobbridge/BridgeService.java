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
    private String mqttUser = "";
    private String mqttPass = "";
    private String baseTopic = "panel";

    private MqttClient client;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean isCustomScreenActive = false;
    private boolean clockEnabled = true;

    private final Runnable clockRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isCustomScreenActive && clockEnabled) {
                drawClock();
            }
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        startForegroundNoti();
        wakeDisplayHardware();
        loadConfigAndRegisterReceiver();
        initMqtt();
        handler.post(clockRunnable);
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
            // Sprawdzona sekwencja wybudzania ekranu i nadania uprawnień
            Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", 
                "echo on > /proc/vendor/lcd_ctrl && echo 1 > /proc/vendor/led_ctrl && chmod 666 /dev/graphics/fb1"});
            p.waitFor();
            Log.i(TAG, "Hardware display initialized (echo on > lcd_ctrl)");
        } catch (Exception e) {
            Log.e(TAG, "Failed to wake display", e);
        }
    }

    private void loadConfigAndRegisterReceiver() {
        SharedPreferences prefs = getSharedPreferences("knob_prefs", MODE_PRIVATE);
        this.brokerUri = prefs.getString("broker", this.brokerUri);
        this.mqttUser = prefs.getString("user", this.mqttUser);
        this.mqttPass = prefs.getString("pass", this.mqttPass);
        this.baseTopic = prefs.getString("topic", this.baseTopic);
        Log.i(TAG, "Config: broker=" + brokerUri + ", topic=" + baseTopic);

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
                Log.i(TAG, "Config updated via intent, reconnecting MQTT...");
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
                opts.setConnectionTimeout(10);
                if (!mqttUser.isEmpty()) {
                    opts.setUserName(mqttUser);
                    opts.setPassword(mqttPass.toCharArray());
                }

                client.setCallback(new MqttCallbackExtended() {
                    @Override
                    public void connectComplete(boolean reconnect, String serverURI) {
                        Log.i(TAG, "MQTT Connected to " + serverURI);
                        subscribeTopics();
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
                Log.e(TAG, "MQTT init failed: " + e.getMessage());
            }
        }).start();
    }

    private void subscribeTopics() {
        try {
            client.subscribe(baseTopic + "/volume/set/+");
            client.subscribe(baseTopic + "/display/#");
            Log.i(TAG, "Subscribed to " + baseTopic + "/#");
        } catch (Exception e) {
            Log.e(TAG, "Subscription error", e);
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

                showNotification("🔊 STREFA " + room, vol + "%", "Głośność", 3000, "#00E5FF", "#FFFFFF");
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
            Log.e(TAG, "Error handling message", e);
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
        paint.setTextSize(64);
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        canvas.drawText(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(now), 105, 130, paint);

        paint.setColor(Color.parseColor("#FFA726"));
        paint.setTextSize(26);
        canvas.drawText(new SimpleDateFormat("ss", Locale.getDefault()).format(now), 195, 130, paint);

        paint.setColor(Color.LTGRAY);
        paint.setTextSize(24);
        paint.setTypeface(Typeface.DEFAULT);
        canvas.drawText(new SimpleDateFormat("EEE, d MMM", new Locale("pl", "PL")).format(now), 120, 180, paint);

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

            // Zapisujemy bezpośrednio lub przez proces shella, jeśli SELinux rzuca błędem
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
        handler.removeCallbacks(clockRunnable);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}