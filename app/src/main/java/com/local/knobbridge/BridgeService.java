package com.local.knobbridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Environment;
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
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class BridgeService extends Service {
    private static final String TAG = "KnobBridge";
    private static final File CONFIG_FILE = new File(Environment.getExternalStorageDirectory(), "knob_config.json");

    private String brokerUri = "tcp://192.168.0.100:1883";
    private String mqttUser = "";
    private String mqttPass = "";
    private String baseTopic = "panel";

    private MqttClient client;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean isCustomScreenActive = false;
    private boolean clockEnabled = true;

    private int clockBgColor = Color.BLACK;
    private int clockTimeColor = Color.WHITE;
    private int clockSecColor = Color.parseColor("#FFA726");
    private int clockDateColor = Color.LTGRAY;

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
        loadOrCreateConfig();
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

    private void loadOrCreateConfig() {
        try {
            if (!CONFIG_FILE.exists()) {
                JSONObject defaultCfg = new JSONObject();
                defaultCfg.put("broker", "tcp://192.168.0.100:1883");
                defaultCfg.put("user", "");
                defaultCfg.put("password", "");
                defaultCfg.put("base_topic", "panel");
                FileOutputStream fos = new FileOutputStream(CONFIG_FILE);
                fos.write(defaultCfg.toString(2).getBytes());
                fos.close();
                Log.i(TAG, "Created default config at " + CONFIG_FILE.getAbsolutePath());
            }

            FileInputStream fis = new FileInputStream(CONFIG_FILE);
            byte[] buf = new byte[(int) CONFIG_FILE.length()];
            fis.read(buf);
            fis.close();
            JSONObject cfg = new JSONObject(new String(buf));

            this.brokerUri = cfg.optString("broker", this.brokerUri);
            this.mqttUser = cfg.optString("user", this.mqttUser);
            this.mqttPass = cfg.optString("password", this.mqttPass);
            this.baseTopic = cfg.optString("base_topic", this.baseTopic);
            Log.i(TAG, "Loaded config: broker=" + brokerUri + ", topic=" + baseTopic);
        } catch (Exception e) {
            Log.e(TAG, "Config load error, using defaults", e);
        }
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
            client.subscribe(baseTopic + "/config/#");
            Log.i(TAG, "Subscribed to " + baseTopic + "/#");
        } catch (Exception e) {
            Log.e(TAG, "Subscription error", e);
        }
    }

    private void handleMessage(String topic, String payload) {
        try {
            // 1. Zmiana głośności stref
            if (topic.startsWith(baseTopic + "/volume/set/")) {
                int room = Integer.parseInt(topic.substring((baseTopic + "/volume/set/").length()));
                int vol = Integer.parseInt(payload);
                setHardwareVolume(room, vol);
                showNotification("🔊 STREFA " + room, vol + "%", "Głośność", 3000, "#00E5FF", "#FFFFFF");
                return;
            }

            // 2. Własny dynamiczny ekran (JSON)
            if (topic.equals(baseTopic + "/display/custom")) {
                JSONObject json = new JSONObject(payload);
                String title = json.optString("title", "");
                String text = json.optString("text", "");
                String sub = json.optString("sub", "");
                int duration = json.optInt("duration", 4000);
                String titleColor = json.optString("title_color", "#4FC3F7");
                String textColor = json.optString("text_color", "#FFFFFF");
                showNotification(title, text, sub, duration, titleColor, textColor);
                return;
            }

            // 3. Surowy Base64 obrazek
            if (topic.equals(baseTopic + "/display/image")) {
                byte[] decoded = Base64.decode(payload, Base64.DEFAULT);
                Bitmap bmp = BitmapFactory.decodeByteArray(decoded, 0, decoded.length);
                if (bmp != null) {
                    showTemporaryBitmap(bmp, 5000);
                }
                return;
            }

            // 4. Konfiguracja zegarka w locie
            if (topic.equals(baseTopic + "/display/clock")) {
                if ("off".equalsIgnoreCase(payload) || "false".equalsIgnoreCase(payload)) {
                    clockEnabled = false;
                    clearScreen();
                } else {
                    clockEnabled = true;
                    isCustomScreenActive = false;
                    drawClock();
                }
                return;
            }

            // 5. Przeładowanie pliku konfiguracyjnego w locie bez restartu apki
            if (topic.equals(baseTopic + "/config/reload")) {
                loadOrCreateConfig();
                initMqtt();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error handling topic: " + topic, e);
        }
    }

    private void setHardwareVolume(int room, int volume) {
        Intent intent = new Intent("com.naner.volume.userchange");
        intent.putExtra("room", room);
        intent.putExtra("volume", volume);
        sendBroadcast(intent);
    }

    private synchronized void showNotification(String title, String mainText, String sub, int durationMs, String titleColorHex, String textColorHex) {
        isCustomScreenActive = true;
        Bitmap bmp = Bitmap.createBitmap(240, 240, Bitmap.Config.RGB_565);
        Canvas canvas = new Canvas(bmp);
        canvas.drawColor(Color.BLACK);

        Paint paint = new Paint();
        paint.setAntiAlias(true);
        paint.setTextAlign(Paint.Align.CENTER);

        // Tytuł
        paint.setColor(Color.parseColor(titleColorHex));
        paint.setTextSize(26);
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        canvas.drawText(title, 120, 55, paint);

        // Treść główna
        paint.setColor(Color.parseColor(textColorHex));
        paint.setTextSize(mainText.length() > 6 ? 34 : 58);
        canvas.drawText(mainText, 120, 135, paint);

        // Podtytuł
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

    private synchronized void showTemporaryBitmap(Bitmap source, int durationMs) {
        isCustomScreenActive = true;
        Bitmap scaled = Bitmap.createScaledBitmap(source, 240, 240, true);
        renderToFramebuffer(scaled);
        scaled.recycle();

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
        canvas.drawColor(clockBgColor);

        Paint paint = new Paint();
        paint.setAntiAlias(true);
        paint.setTextAlign(Paint.Align.CENTER);

        SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());
        SimpleDateFormat secFormat = new SimpleDateFormat("ss", Locale.getDefault());
        SimpleDateFormat dateFormat = new SimpleDateFormat("EEE, d MMM", new Locale("pl", "PL"));
        Date now = new Date();

        // Godzina
        paint.setColor(clockTimeColor);
        paint.setTextSize(64);
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        canvas.drawText(timeFormat.format(now), 105, 130, paint);

        // Sekundy
        paint.setColor(clockSecColor);
        paint.setTextSize(26);
        canvas.drawText(secFormat.format(now), 195, 130, paint);

        // Data
        paint.setColor(clockDateColor);
        paint.setTextSize(24);
        paint.setTypeface(Typeface.DEFAULT);
        canvas.drawText(dateFormat.format(now), 120, 180, paint);

        renderToFramebuffer(bmp);
    }

    private synchronized void clearScreen() {
        Bitmap bmp = Bitmap.createBitmap(240, 240, Bitmap.Config.RGB_565);
        Canvas canvas = new Canvas(bmp);
        canvas.drawColor(Color.BLACK);
        renderToFramebuffer(bmp);
    }

    // Bezpieczny, synchroniczny zapis do framebuffera (brak race condition z recycle)
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
            bmp.recycle();

            FileOutputStream fos = new FileOutputStream("/dev/graphics/fb1");
            fos.write(raw);
            fos.flush();
            fos.close();
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