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
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.json.JSONObject;

import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class BridgeService extends Service {
    private static final String TAG = "KnobBridge";

    // === KONFIGURACJA MQTT (Zmień na IP swojego HA) ===
    private static final String MQTT_BROKER = "tcp://192.168.1.50:1883";
    private static final String MQTT_USER = "";
    private static final String MQTT_PASS = "";

    private MqttClient client;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean isCustomScreenActive = false;

    // Zadanie odświeżające zegarek co 1 sekundę
    private final Runnable clockRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isCustomScreenActive) {
                drawClock();
            }
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        startForegroundNoti();
        connectMqtt();
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

    private void connectMqtt() {
        new Thread(() -> {
            try {
                client = new MqttClient(MQTT_BROKER, "PanelTablet_" + System.currentTimeMillis(), new MemoryPersistence());
                MqttConnectOptions opts = new MqttConnectOptions();
                opts.setCleanSession(true);
                opts.setAutomaticReconnect(true);
                if (MQTT_USER != null && !MQTT_USER.isEmpty()) {
                    opts.setUserName(MQTT_USER);
                    opts.setPassword(MQTT_PASS.toCharArray());
                }

                client.setCallback(new MqttCallback() {
                    @Override
                    public void connectionLost(Throwable cause) {}

                    @Override
                    public void messageArrived(String topic, MqttMessage message) {
                        String payload = new String(message.getPayload()).trim();
                        handleIncomingMqtt(topic, payload);
                    }

                    @Override
                    public void deliveryComplete(IMqttDeliveryToken token) {}
                });

                client.connect(opts);
                client.subscribe("panel/volume/set/+");
                client.subscribe("panel/display/custom");
                client.subscribe("panel/display/image");
                client.subscribe("panel/display/clock");
                Log.i(TAG, "Connected to MQTT broker");
            } catch (Exception e) {
                Log.e(TAG, "MQTT Connection error", e);
            }
        }).start();
    }

    private void handleIncomingMqtt(String topic, String payload) {
        try {
            if (topic.equals("panel/volume/set/1")) {
                int vol = Integer.parseInt(payload);
                setHardwareVolume(1, vol);
                showTemporaryMessage("🔊 STREFA 1", vol + "%", "Głośność", 3000);
            } else if (topic.equals("panel/volume/set/2")) {
                int vol = Integer.parseInt(payload);
                setHardwareVolume(2, vol);
                showTemporaryMessage("🔊 STREFA 2", vol + "%", "Głośność", 3000);
            } else if (topic.equals("panel/display/custom")) {
                // Format JSON: {"title":"🔔 Domofon","text":"Ktoś dzwoni!","sub":"Wejście","duration":5000}
                JSONObject json = new JSONObject(payload);
                String title = json.optString("title", "");
                String text = json.optString("text", "");
                String sub = json.optString("sub", "");
                int duration = json.optInt("duration", 4000);
                showTemporaryMessage(title, text, sub, duration);
            } else if (topic.equals("panel/display/image")) {
                // Base64 lub czyste PNG/JPEG
                byte[] decoded = Base64.decode(payload, Base64.DEFAULT);
                Bitmap bmp = BitmapFactory.decodeByteArray(decoded, 0, decoded.length);
                if (bmp != null) {
                    showTemporaryBitmap(bmp, 5000);
                }
            } else if (topic.equals("panel/display/clock")) {
                resetToClock();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error processing MQTT message: " + topic, e);
        }
    }

    private void setHardwareVolume(int room, int volume) {
        Intent intent = new Intent("com.naner.volume.userchange");
        intent.putExtra("room", room);
        intent.putExtra("volume", volume);
        sendBroadcast(intent);
    }

    private void showTemporaryMessage(String title, String mainText, String sub, int durationMs) {
        isCustomScreenActive = true;
        Bitmap bmp = Bitmap.createBitmap(240, 240, Bitmap.Config.RGB_565);
        Canvas canvas = new Canvas(bmp);
        canvas.drawColor(Color.BLACK);

        Paint paint = new Paint();
        paint.setAntiAlias(true);
        paint.setTextAlign(Paint.Align.CENTER);

        // Tytuł / Emotka u góry
        paint.setColor(Color.parseColor("#4FC3F7"));
        paint.setTextSize(26);
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        canvas.drawText(title, 120, 55, paint);

        // Główny tekst / liczba
        paint.setColor(Color.WHITE);
        paint.setTextSize(mainText.length() > 5 ? 36 : 60);
        canvas.drawText(mainText, 120, 135, paint);

        // Podtytuł na dole
        if (!sub.isEmpty()) {
            paint.setColor(Color.GRAY);
            paint.setTextSize(22);
            paint.setTypeface(Typeface.DEFAULT);
            canvas.drawText(sub, 120, 195, paint);
        }

        renderToFramebuffer(bmp);
        bmp.recycle();

        handler.removeCallbacks(resetClockTask);
        handler.postDelayed(resetClockTask, durationMs);
    }

    private void showTemporaryBitmap(Bitmap source, int durationMs) {
        isCustomScreenActive = true;
        Bitmap scaled = Bitmap.createScaledBitmap(source, 240, 240, true);
        renderToFramebuffer(scaled);
        scaled.recycle();
        source.recycle();

        handler.removeCallbacks(resetClockTask);
        handler.postDelayed(resetClockTask, durationMs);
    }

    private final Runnable resetClockTask = this::resetToClock;

    private void resetToClock() {
        isCustomScreenActive = false;
        drawClock();
    }

    private synchronized void drawClock() {
        Bitmap bmp = Bitmap.createBitmap(240, 240, Bitmap.Config.RGB_565);
        Canvas canvas = new Canvas(bmp);
        canvas.drawColor(Color.BLACK);

        Paint paint = new Paint();
        paint.setAntiAlias(true);
        paint.setTextAlign(Paint.Align.CENTER);

        SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());
        SimpleDateFormat secFormat = new SimpleDateFormat("ss", Locale.getDefault());
        SimpleDateFormat dateFormat = new SimpleDateFormat("EEE, d MMM", new Locale("pl", "PL"));
        Date now = new Date();

        // Duża godzina
        paint.setColor(Color.WHITE);
        paint.setTextSize(64);
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        canvas.drawText(timeFormat.format(now), 105, 130, paint);

        // Mniejsze sekundy obok
        paint.setColor(Color.parseColor("#FFA726"));
        paint.setTextSize(26);
        canvas.drawText(secFormat.format(now), 195, 130, paint);

        // Data na dole
        paint.setColor(Color.LTGRAY);
        paint.setTextSize(24);
        paint.setTypeface(Typeface.DEFAULT);
        canvas.drawText(dateFormat.format(now), 120, 180, paint);

        renderToFramebuffer(bmp);
        bmp.recycle();
    }

    private void renderToFramebuffer(Bitmap bmp) {
        new Thread(() -> {
            try {
                byte[] raw = new byte[240 * 240 * 2];
                int idx = 0;
                // Skalowanie/odbicie lustrzane w osi X zgodne z chińskim NRLcd
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
                FileOutputStream fos = new FileOutputStream("/dev/graphics/fb1");
                fos.write(raw);
                fos.flush();
                fos.close();
            } catch (Exception e) {
                Log.e(TAG, "Framebuffer write error", e);
            }
        }).start();
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