package com.local.knobbridge;

import android.animation.ValueAnimator;
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
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Base64;
import android.util.Log;
import android.view.animation.DecelerateInterpolator;

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
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class BridgeService extends Service {
    private static final String TAG = "KnobBridge";

    private String brokerUri = "tcp://192.168.0.7:1883";
    private String mqttUser = "admin";
    private String mqttPass = "admin";
    private String baseTopic = "panel";

    private MqttClient client;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean running = true;

    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    // Flagi sprzętowe
    private boolean isMainScreenOn = true;
    private boolean isScreenHardwarePowered = true;
    private boolean isLedRingOn = false;

    // Alerty i powiadomienia
    private boolean hasUnreadAlert = false;
    private String alertBadgeText = "";
    private boolean isAlertKeepingAwake = false;

    // Tryby pracy
    private enum ScreenMode { CLOCK, GAUGE, MENU, MEDIA, ALERT }
    private ScreenMode currentMode = ScreenMode.CLOCK;

    // Tryb GAUGE
    private String gaugeTitle = "Regulacja";
    private int gaugeValue = 50;
    private int gaugeMin = 0;
    private int gaugeMax = 100;
    private int gaugeStep = 5;
    private String gaugeUnit = "%";
    private int gaugeColor = Color.parseColor("#00E5FF");
    private float animatedSweep = 0f;
    private ValueAnimator gaugeAnimator;

    // Tryb MENU
    private String menuTitle = "Wybór";
    private final List<String> menuItems = new ArrayList<>();
    private int menuIndex = 0;

    // Tryb MEDIA
    private String mediaTitle = "";
    private String mediaSub = "";
    private int mediaProgress = -1;

    // Synchronizacja z głównym ekranem tabletu
    private final BroadcastReceiver screenStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                isMainScreenOn = false;
                if (!isAlertKeepingAwake) {
                    setHardwarePower(false);
                }
            } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                isMainScreenOn = true;
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
            Log.i(TAG, "WakeLock i WifiLock zapięte pomyślnie");
        } catch (Exception e) {
            Log.e(TAG, "Błąd blokad zasilania", e);
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

    // Zasilanie matrycy fb1
    private synchronized void setHardwarePower(boolean enable) {
        isScreenHardwarePowered = enable;
        runRootCmd(enable ? "echo on > /proc/vendor/lcd_ctrl && echo 0 > /sys/class/graphics/fb1/blank && chmod 666 /dev/graphics/fb1"
                          : "echo off > /proc/vendor/lcd_ctrl && echo 4 > /sys/class/graphics/fb1/blank");
        publishRaw(baseTopic + "/display/state", enable ? "on" : "off", true);
    }

    // Pierścień LED
    private synchronized void setLedRingHardware(boolean enable) {
        isLedRingOn = enable;
        runRootCmd(enable ? "echo on > /proc/vendor/led_ctrl" : "echo off > /proc/vendor/led_ctrl");
        publishRaw(baseTopic + "/led/state", enable ? "on" : "off", true);
    }

    private void runRootCmd(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "0"});
            OutputStream os = p.getOutputStream();
            os.write((cmd + "\nexit\n").getBytes());
            os.flush();
            p.waitFor();
        } catch (Exception e) {
            Log.e(TAG, "Root cmd error: " + cmd, e);
        }
    }

    // Czytanie /dev/input/event0 przez proces roota
    private void startRootInputMonitoring() {
        new Thread(() -> {
            while (running) {
                try {
                    Process proc = Runtime.getRuntime().exec(new String[]{"su", "0"});
                    OutputStream os = proc.getOutputStream();
                    os.write("getevent -l /dev/input/event0\n".getBytes());
                    os.flush();

                    BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()));
                    String line;
                    while (running && (line = reader.readLine()) != null) {
                        if (line.contains("DOWN") || line.contains(" 00000001")) {
                            if (line.contains("KEY_F1")) handleKnobEvent(-1);
                            else if (line.contains("KEY_F2")) handleKnobEvent(1);
                            else if (line.contains("KEY_F3")) handleKnobClick();
                        }
                    }
                } catch (Exception e) {
                    try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
                }
            }
        }).start();
    }

    private void handleKnobEvent(int delta) {
        if (!isScreenHardwarePowered) {
            setHardwarePower(true);
            redrawCurrentScreen();
            return;
        }

        publishAction(delta > 0 ? "rotate_right" : "rotate_left");

        switch (currentMode) {
            case GAUGE:
                gaugeValue = Math.max(gaugeMin, Math.min(gaugeMax, gaugeValue + (delta * gaugeStep)));
                animateGaugeToValue(gaugeValue);
                publishRaw(baseTopic + "/knob/gauge_value", String.valueOf(gaugeValue), false);
                resetTimeout(5000); // 5s bezruchu -> anulowanie
                break;

            case MENU:
                if (!menuItems.isEmpty()) {
                    menuIndex = (menuIndex + delta + menuItems.size()) % menuItems.size();
                    drawMenuScreen();
                    resetTimeout(5000);
                }
                break;

            default:
                break;
        }
    }

    private void handleKnobClick() {
        if (!isScreenHardwarePowered) {
            setHardwarePower(true);
            redrawCurrentScreen();
            return;
        }

        publishAction("click");

        if (currentMode == ScreenMode.ALERT) {
            isAlertKeepingAwake = false;
            hasUnreadAlert = false;
            publishRaw(baseTopic + "/knob/alert_dismiss", "true", false);
            returnToClock();
            if (!isMainScreenOn) setHardwarePower(false);
            return;
        }

        if (currentMode == ScreenMode.GAUGE) {
            publishRaw(baseTopic + "/knob/confirm", String.valueOf(gaugeValue), false);
            gaugeValue = 0;
            gaugeTitle = "";
            returnToClock();
            return;
        }

        if (currentMode == ScreenMode.MENU && !menuItems.isEmpty()) {
            try {
                JSONObject res = new JSONObject();
                res.put("index", menuIndex);
                res.put("item", menuItems.get(menuIndex));
                publishRaw(baseTopic + "/knob/menu_select", res.toString(), false);
            } catch (Exception ignored) {}
            menuItems.clear();
            returnToClock();
        }
    }

    private void publishAction(String action) {
        try {
            publishRaw(baseTopic + "/knob/action", action, false);
            JSONObject eventObj = new JSONObject();
            eventObj.put("event_type", action);
            eventObj.put("timestamp", System.currentTimeMillis());
            publishRaw(baseTopic + "/knob/event", eventObj.toString(), false);
        } catch (Exception ignored) {}
    }

    // 5s bezruchu -> anulowanie do HA i powrót do zegara
    private final Runnable timeoutCancelTask = () -> {
        if (currentMode == ScreenMode.GAUGE || currentMode == ScreenMode.MENU) {
            Log.i(TAG, "Timeout 5s -> Anulowanie edycji");
            publishRaw(baseTopic + "/knob/cancel", "{\"canceled_mode\":\"" + currentMode.name().toLowerCase() + "\"}", false);
            gaugeValue = 0;
            gaugeTitle = "";
            menuItems.clear();
        }
        returnToClock();
    };

    private void resetTimeout(int ms) {
        handler.removeCallbacks(timeoutCancelTask);
        if (ms > 0) handler.postDelayed(timeoutCancelTask, ms);
    }

    private void returnToClock() {
        handler.removeCallbacks(timeoutCancelTask);
        currentMode = ScreenMode.CLOCK;
        if (isScreenHardwarePowered) drawClock();
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
                if (client != null && client.isConnected()) client.disconnect();
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
                        subscribeTopics();
                        publishHomeAssistantDiscovery();
                    }

                    @Override
                    public void connectionLost(Throwable cause) {}

                    @Override
                    public void messageArrived(String topic, MqttMessage message) {
                        handleIncomingMqtt(topic, new String(message.getPayload()).trim());
                    }

                    @Override
                    public void deliveryComplete(IMqttDeliveryToken token) {}
                });

                client.connect(opts);
            } catch (Exception ignored) {}
        }).start();
    }

    private void subscribeTopics() {
        try {
            client.subscribe(baseTopic + "/display/#");
            client.subscribe(baseTopic + "/led/#");
        } catch (Exception ignored) {}
    }

    // Auto-Discovery w HA
    private void publishHomeAssistantDiscovery() {
        try {
            JSONObject device = new JSONObject();
            device.put("identifiers", new JSONArray().put("panel_knob_engine_v3"));
            device.put("name", "Gałka Panel");
            device.put("model", "Knob Controller 240x240 v3");
            device.put("manufacturer", "Smart Home Lab");

            // 1. Zdarzenie obrotu/kliku (Event entity)
            JSONObject ev = new JSONObject();
            ev.put("name", "Zdarzenie Gałki");
            ev.put("unique_id", "knob_event_trigger_v3");
            ev.put("state_topic", baseTopic + "/knob/event");
            ev.put("event_types", new JSONArray().put("rotate_left").put("rotate_right").put("click"));
            ev.put("device", device);
            publishRaw("homeassistant/event/panel_knob/action_event/config", ev.toString(), true);

            // 2. Sensor akcji
            JSONObject act = new JSONObject();
            act.put("name", "Akcja Gałki");
            act.put("unique_id", "knob_action_sensor_v3");
            act.put("state_topic", baseTopic + "/knob/action");
            act.put("force_update", true);
            act.put("icon", "mdi:rotate-right");
            act.put("device", device);
            publishRaw("homeassistant/sensor/panel_knob/action/config", act.toString(), true);

            // 3. Zatwierdzona wartość
            JSONObject conf = new JSONObject();
            conf.put("name", "Zatwierdzona Wartość");
            conf.put("unique_id", "knob_confirm_val_v3");
            conf.put("state_topic", baseTopic + "/knob/confirm");
            conf.put("icon", "mdi:check-circle-outline");
            conf.put("device", device);
            publishRaw("homeassistant/sensor/panel_knob/confirm/config", conf.toString(), true);

            // 4. Pierścień LED
            JSONObject led = new JSONObject();
            led.put("name", "Pierścień LED Gałki");
            led.put("unique_id", "knob_led_ring_v3");
            led.put("command_topic", baseTopic + "/led/set");
            led.put("state_topic", baseTopic + "/led/state");
            led.put("payload_on", "on");
            led.put("payload_off", "off");
            led.put("icon", "mdi:led-strip-variant");
            led.put("device", device);
            publishRaw("homeassistant/switch/panel_knob/led_ring/config", led.toString(), true);

            // 5. Zasilanie ekraniku
            JSONObject pwr = new JSONObject();
            pwr.put("name", "Zasilanie Ekraniku");
            pwr.put("unique_id", "knob_power_switch_v3");
            pwr.put("command_topic", baseTopic + "/display/power");
            pwr.put("state_topic", baseTopic + "/display/state");
            pwr.put("payload_on", "on");
            pwr.put("payload_off", "off");
            pwr.put("icon", "mdi:power");
            pwr.put("device", device);
            publishRaw("homeassistant/switch/panel_knob/power/config", pwr.toString(), true);

        } catch (Exception ignored) {}
    }

    private void handleIncomingMqtt(String topic, String payload) {
        try {
            // Zasilanie ekranu
            if (topic.equals(baseTopic + "/display/power")) {
                setHardwarePower("on".equalsIgnoreCase(payload));
                if (isScreenHardwarePowered) redrawCurrentScreen();
                return;
            }

            // Pierścień LED
            if (topic.equals(baseTopic + "/led/set")) {
                setLedRingHardware("on".equalsIgnoreCase(payload));
                return;
            }

            // Czyszczenie alertu z HA
            if (topic.equals(baseTopic + "/display/alert/clear")) {
                hasUnreadAlert = false;
                alertBadgeText = "";
                isAlertKeepingAwake = false;
                returnToClock();
                if (!isMainScreenOn) setHardwarePower(false);
                return;
            }

            // Tryb GAUGE
            if (topic.equals(baseTopic + "/display/gauge")) {
                JSONObject j = new JSONObject(payload);
                gaugeTitle = j.optString("title", "Regulacja");
                gaugeValue = j.optInt("value", 50);
                gaugeMin = j.optInt("min", 0);
                gaugeMax = j.optInt("max", 100);
                gaugeStep = j.optInt("step", 5);
                gaugeUnit = j.optString("unit", "%");
                gaugeColor = Color.parseColor(j.optString("color", "#00E5FF"));

                currentMode = ScreenMode.GAUGE;
                ensureScreenAwake();
                animateGaugeToValue(gaugeValue);
                resetTimeout(5000);
                return;
            }

            // Tryb MENU
            if (topic.equals(baseTopic + "/display/menu")) {
                JSONObject j = new JSONObject(payload);
                menuTitle = j.optString("title", "Wybierz");
                menuItems.clear();
                JSONArray arr = j.optJSONArray("items");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) menuItems.add(arr.getString(i));
                }
                menuIndex = Math.max(0, Math.min(menuItems.size() - 1, j.optInt("selected_index", 0)));

                currentMode = ScreenMode.MENU;
                ensureScreenAwake();
                drawMenuScreen();
                resetTimeout(5000);
                return;
            }

            // Tryb MEDIA
            if (topic.equals(baseTopic + "/display/media")) {
                JSONObject j = new JSONObject(payload);
                mediaTitle = j.optString("title", "Odtwarzacz");
                mediaSub = j.optString("artist", j.optString("station", ""));
                mediaProgress = j.optInt("progress", -1);

                currentMode = ScreenMode.MEDIA;
                ensureScreenAwake();
                drawMediaScreen(j.optString("image_base64", ""));
                resetTimeout(j.optInt("timeout", 10000));
                return;
            }

            // Tryb ALERT
            if (topic.equals(baseTopic + "/display/alert")) {
                JSONObject j = new JSONObject(payload);
                String title = j.optString("title", "ALARM");
                String msg = j.optString("text", "");
                String color = j.optString("color", "#FF3D00");
                boolean keepAwake = j.optBoolean("keep_awake", false);

                hasUnreadAlert = true;
                alertBadgeText = title;
                isAlertKeepingAwake = keepAwake;

                currentMode = ScreenMode.ALERT;
                setHardwarePower(true);
                drawAlertScreen(title, msg, color);

                if (!keepAwake) resetTimeout(6000);
                else handler.removeCallbacks(timeoutCancelTask);
                return;
            }

            // Powrót do zegara
            if (topic.equals(baseTopic + "/display/clock")) {
                returnToClock();
            }

        } catch (Exception e) {
            Log.e(TAG, "MQTT parse error", e);
        }
    }

    private void ensureScreenAwake() {
        if (!isScreenHardwarePowered) setHardwarePower(true);
    }

    private void redrawCurrentScreen() {
        if (!isScreenHardwarePowered) return;
        if (currentMode == ScreenMode.GAUGE) drawGaugeScreen();
        else if (currentMode == ScreenMode.MENU) drawMenuScreen();
        else if (currentMode == ScreenMode.MEDIA) drawMediaScreen("");
        else drawClock();
    }

    private void animateGaugeToValue(int targetVal) {
        float range = Math.max(1, gaugeMax - gaugeMin);
        float pct = ((float)(targetVal - gaugeMin)) / range;
        float targetSweep = 270f * Math.max(0f, Math.min(1f, pct));

        if (gaugeAnimator != null && gaugeAnimator.isRunning()) {
            gaugeAnimator.cancel();
        }

        gaugeAnimator = ValueAnimator.ofFloat(animatedSweep, targetSweep);
        gaugeAnimator.setDuration(120);
        gaugeAnimator.setInterpolator(new DecelerateInterpolator());
        gaugeAnimator.addUpdateListener(anim -> {
            animatedSweep = (float) anim.getAnimatedValue();
            drawGaugeScreen();
        });
        gaugeAnimator.start();
    }

    // 1. ZEGAR
    private synchronized void drawClock() {
        Bitmap bmp = Bitmap.createBitmap(240, 240, Bitmap.Config.RGB_565);
        Canvas canvas = new Canvas(bmp);
        canvas.drawColor(Color.BLACK);

        Paint p = new Paint();
        p.setAntiAlias(true);
        p.setTextAlign(Paint.Align.CENTER);

        if (hasUnreadAlert) {
            p.setColor(Color.parseColor("#FF1744"));
            p.setTextSize(18);
            p.setTypeface(Typeface.DEFAULT_BOLD);
            canvas.drawText("● " + alertBadgeText, 120, 48, p);
        }

        Date now = new Date();
        p.setColor(Color.WHITE);
        p.setTextSize(70);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        canvas.drawText(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(now), 120, 130, p);

        p.setColor(Color.LTGRAY);
        p.setTextSize(22);
        p.setTypeface(Typeface.DEFAULT);
        canvas.drawText(new SimpleDateFormat("EEE, d MMM", new Locale("pl", "PL")).format(now), 120, 170, p);

        renderToFramebuffer(bmp);
    }

    // 2. GAUGE
    private synchronized void drawGaugeScreen() {
        Bitmap bmp = Bitmap.createBitmap(240, 240, Bitmap.Config.RGB_565);
        Canvas canvas = new Canvas(bmp);
        canvas.drawColor(Color.BLACK);

        // Kreseczki podziałki
        Paint tickPaint = new Paint();
        tickPaint.setAntiAlias(true);
        tickPaint.setColor(Color.parseColor("#333333"));
        tickPaint.setStrokeWidth(2.5f);

        for (int angle = 135; angle <= 405; angle += 15) {
            double rad = Math.toRadians(angle);
            float startX = (float) (120 + 96 * Math.cos(rad));
            float startY = (float) (120 + 96 * Math.sin(rad));
            float stopX = (float) (120 + 104 * Math.cos(rad));
            float stopY = (float) (120 + 104 * Math.sin(rad));
            canvas.drawLine(startX, startY, stopX, stopY, tickPaint);
        }

        RectF arcRect = new RectF(28, 28, 212, 212);

        Paint bgPaint = new Paint();
        bgPaint.setAntiAlias(true);
        bgPaint.setStyle(Paint.Style.STROKE);
        bgPaint.setStrokeWidth(14);
        bgPaint.setColor(Color.parseColor("#222222"));
        canvas.drawArc(arcRect, 135, 270, false, bgPaint);

        Paint fgPaint = new Paint();
        fgPaint.setAntiAlias(true);
        fgPaint.setStyle(Paint.Style.STROKE);
        fgPaint.setStrokeWidth(14);
        fgPaint.setStrokeCap(Paint.Cap.ROUND);
        fgPaint.setColor(gaugeColor);
        canvas.drawArc(arcRect, 135, animatedSweep, false, fgPaint);

        Paint tp = new Paint();
        tp.setAntiAlias(true);
        tp.setTextAlign(Paint.Align.CENTER);
        tp.setColor(Color.LTGRAY);
        tp.setTextSize(20);
        canvas.drawText(gaugeTitle, 120, 78, tp);

        tp.setColor(Color.WHITE);
        tp.setTextSize(52);
        tp.setTypeface(Typeface.DEFAULT_BOLD);
        canvas.drawText(gaugeValue + gaugeUnit, 120, 135, tp);

        tp.setColor(Color.GRAY);
        tp.setTextSize(16);
        tp.setTypeface(Typeface.DEFAULT);
        canvas.drawText("Klik = OK | 5s = Anuluj", 120, 185, tp);

        renderToFramebuffer(bmp);
    }

    // 3. MENU
    private synchronized void drawMenuScreen() {
        Bitmap bmp = Bitmap.createBitmap(240, 240, Bitmap.Config.RGB_565);
        Canvas canvas = new Canvas(bmp);
        canvas.drawColor(Color.BLACK);

        Paint p = new Paint();
        p.setAntiAlias(true);
        p.setTextAlign(Paint.Align.CENTER);

        p.setColor(Color.parseColor("#FFA726"));
        p.setTextSize(20);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        canvas.drawText(menuTitle.toUpperCase(), 120, 48, p);

        if (!menuItems.isEmpty()) {
            p.setColor(Color.GRAY);
            p.setTextSize(16);
            canvas.drawText((menuIndex + 1) + " / " + menuItems.size(), 120, 72, p);

            String currentItem = menuItems.get(menuIndex);
            p.setColor(Color.WHITE);
            p.setTextSize(currentItem.length() > 10 ? 26 : 32);
            p.setTypeface(Typeface.DEFAULT_BOLD);
            canvas.drawText(currentItem, 120, 135, p);

            p.setColor(Color.parseColor("#00E5FF"));
            p.setTextSize(24);
            canvas.drawText("◀          ▶", 120, 135, p);
        }

        p.setColor(Color.GRAY);
        p.setTextSize(16);
        p.setTypeface(Typeface.DEFAULT);
        canvas.drawText("Klik = OK | 5s = Anuluj", 120, 195, p);

        renderToFramebuffer(bmp);
    }

    // 4. MEDIA
    private synchronized void drawMediaScreen(String base64Img) {
        Bitmap bmp = Bitmap.createBitmap(240, 240, Bitmap.Config.RGB_565);
        Canvas canvas = new Canvas(bmp);
        canvas.drawColor(Color.BLACK);

        if (!base64Img.isEmpty()) {
            try {
                byte[] raw = Base64.decode(base64Img, Base64.DEFAULT);
                Bitmap art = BitmapFactory.decodeByteArray(raw, 0, raw.length);
                if (art != null) {
                    canvas.drawBitmap(Bitmap.createScaledBitmap(art, 240, 240, true), 0, 0, null);
                    renderToFramebuffer(bmp);
                    return;
                }
            } catch (Exception ignored) {}
        }

        Paint p = new Paint();
        p.setAntiAlias(true);
        p.setTextAlign(Paint.Align.CENTER);

        p.setColor(Color.parseColor("#E91E63"));
        p.setTextSize(22);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        canvas.drawText("♪ NOW PLAYING", 120, 52, p);

        p.setColor(Color.WHITE);
        p.setTextSize(mediaTitle.length() > 10 ? 26 : 32);
        canvas.drawText(mediaTitle, 120, 115, p);

        if (!mediaSub.isEmpty()) {
            p.setColor(Color.LTGRAY);
            p.setTextSize(20);
            p.setTypeface(Typeface.DEFAULT);
            canvas.drawText(mediaSub, 120, 150, p);
        }

        if (mediaProgress >= 0) {
            Paint barBg = new Paint();
            barBg.setColor(Color.DKGRAY);
            canvas.drawRect(40, 185, 200, 191, barBg);

            Paint barFg = new Paint();
            barFg.setColor(Color.parseColor("#E91E63"));
            float w = (160f * Math.max(0, Math.min(100, mediaProgress))) / 100f;
            canvas.drawRect(40, 185, 40 + w, 191, barFg);
        }

        renderToFramebuffer(bmp);
    }

    // 5. ALERT
    private synchronized void drawAlertScreen(String title, String msg, String colorHex) {
        Bitmap bmp = Bitmap.createBitmap(240, 240, Bitmap.Config.RGB_565);
        Canvas canvas = new Canvas(bmp);
        canvas.drawColor(Color.BLACK);

        Paint p = new Paint();
        p.setAntiAlias(true);
        p.setTextAlign(Paint.Align.CENTER);

        p.setColor(Color.parseColor(colorHex));
        p.setTextSize(28);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        canvas.drawText(title, 120, 65, p);

        p.setColor(Color.WHITE);
        p.setTextSize(msg.length() > 8 ? 26 : 38);
        canvas.drawText(msg, 120, 135, p);

        p.setColor(Color.GRAY);
        p.setTextSize(18);
        p.setTypeface(Typeface.DEFAULT);
        canvas.drawText("Kliknij = Odczytano", 120, 190, p);

        renderToFramebuffer(bmp);
    }

    private void publishRaw(String topic, String payload, boolean retained) {
        try {
            if (client != null && client.isConnected()) {
                MqttMessage msg = new MqttMessage(payload.getBytes());
                msg.setRetained(retained);
                client.publish(topic, msg);
            }
        } catch (Exception ignored) {}
    }

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
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
            if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        } catch (Exception ignored) {}
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}