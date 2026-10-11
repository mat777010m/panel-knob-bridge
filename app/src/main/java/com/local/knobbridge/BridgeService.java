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
import android.media.AudioManager;
import android.media.AudioRecordingConfiguration;
import android.net.wifi.WifiManager;
import android.os.Build;
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
    private AudioManager audioManager;

    // Flagi sprzętowe
    private boolean isMainScreenOn = true;
    private boolean isScreenHardwarePowered = true;
    private boolean isMicActive = false;
    private boolean isMicMuted = false;

    // Alerty i powiadomienia
    private boolean hasUnreadAlert = false;
    private String alertBadgeText = "";
    private boolean isAlertKeepingAwake = false;

    // Tryby pracy
    private enum ScreenMode { CLOCK, GAUGE, MENU, MEDIA, ALERT }
    private ScreenMode currentMode = ScreenMode.CLOCK;

    // GAUGE
    private String gaugeTitle = "";
    private String gaugeFooter = "";
    private int gaugeValue = 50;
    private int gaugeMin = 0;
    private int gaugeMax = 100;
    private int gaugeStep = 5;
    private String gaugeUnit = "%";
    private int gaugeColor = Color.parseColor("#00E5FF");
    private float animatedSweep = 0f;
    private ValueAnimator gaugeAnimator;

    // MENU
    private String menuTitle = "";
    private String menuFooter = "";
    private final List<String> menuItems = new ArrayList<>();
    private int menuIndex = 0;

    // MEDIA
    private String mediaHeader = "";
    private String mediaTitle = "";
    private String mediaSub = "";
    private int mediaProgress = -1;

    // Odbiornik zdarzeń ekranu
    private final BroadcastReceiver screenStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                isMainScreenOn = false;
                if (!isAlertKeepingAwake) setHardwarePower(false);
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

        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        initUartHardware();
        initMicMonitoring();

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
        } catch (Exception ignored) {}
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

    // --- MAGISTRALA DSP AUDIO (/dev/ttyAS4) ---
    private void initUartHardware() {
        runRootCmd("stty -F /dev/ttyAS4 9600 cs8 -cstopb -parenb -echo raw && chmod 666 /dev/ttyAS4");
    }

    private synchronized void sendDspFrame(int cmd, int val, int room) {
        byte[] frame = new byte[6];
        frame[0] = (byte) 0xFA;
        frame[1] = (byte) 0x01;
        frame[2] = (byte) (cmd & 0xFF);
        frame[3] = (byte) (val & 0xFF);
        frame[4] = (byte) (room & 0xFF);
        frame[5] = (byte) ((0xFA + 0x01 + (cmd & 0xFF) + (val & 0xFF) + (room & 0xFF)) & 0xFF);

        try {
            File f = new File("/dev/ttyAS4");
            if (f.canWrite()) {
                FileOutputStream fos = new FileOutputStream(f);
                fos.write(frame);
                fos.flush();
                fos.close();
            } else {
                Process p = Runtime.getRuntime().exec(new String[]{"su", "0"});
                OutputStream os = p.getOutputStream();
                os.write("cat > /dev/ttyAS4\n".getBytes());
                os.flush();
                os.write(frame);
                os.flush();
                os.close();
                p.waitFor();
            }
        } catch (Exception e) {
            Log.e(TAG, "DSP write error", e);
        }
    }

    // --- MONITORING I KONTROLA MIKROFONU ---
    private void initMicMonitoring() {
        if (audioManager != null) {
            isMicMuted = audioManager.isMicrophoneMute();

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                audioManager.registerAudioRecordingCallback(new AudioManager.AudioRecordingCallback() {
                    @Override
                    public void onRecordingConfigChanged(List<AudioRecordingConfiguration> configs) {
                        boolean active = (configs != null && !configs.isEmpty());
                        if (isMicActive != active) {
                            isMicActive = active;
                            publishRaw(baseTopic + "/mic/active/state", active ? "ON" : "OFF", true);
                            if (currentMode == ScreenMode.CLOCK) {
                                handler.post(() -> drawClock());
                            }
                        }
                    }
                }, handler);
            }
        }
    }

    private void setMicrophoneMute(boolean mute) {
        if (audioManager != null) {
            audioManager.setMicrophoneMute(mute);
            isMicMuted = audioManager.isMicrophoneMute();
            publishRaw(baseTopic + "/mic/mute/state", isMicMuted ? "ON" : "OFF", true);
            if (currentMode == ScreenMode.CLOCK) {
                drawClock();
            }
        }
    }

    // --- ZASILANIE EKRANU I LED ---
    private synchronized void setHardwarePower(boolean enable) {
        isScreenHardwarePowered = enable;
        runRootCmd(enable ? "echo on > /proc/vendor/lcd_ctrl && echo 0 > /sys/class/graphics/fb1/blank && chmod 666 /dev/graphics/fb1"
                          : "echo off > /proc/vendor/lcd_ctrl && echo 4 > /sys/class/graphics/fb1/blank");
        publishRaw(baseTopic + "/display/state", enable ? "on" : "off", true);
    }

    private synchronized void setLedRingHardware(boolean enable) {
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

    // --- CZYTANIE ENKODERA ---
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
                resetTimeout(5000);
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

    private final Runnable timeoutCancelTask = () -> {
        if (currentMode == ScreenMode.GAUGE || currentMode == ScreenMode.MENU) {
            publishRaw(baseTopic + "/knob/cancel", "{\"canceled_mode\":\"" + currentMode.name().toLowerCase() + "\"}", false);
            gaugeValue = 0;
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
                opts.setKeepAliveInterval(20);
                opts.setConnectionTimeout(10);
                if (!mqttUser.isEmpty()) {
                    opts.setUserName(mqttUser);
                    opts.setPassword(mqttPass.toCharArray());
                }

                client.setCallback(new MqttCallbackExtended() {
                    @Override
                    public void connectComplete(boolean reconnect, String serverURI) {
                        subscribeTopics();
                        publishHomeAssistantDiscovery();
                        publishRaw(baseTopic + "/mic/mute/state", isMicMuted ? "ON" : "OFF", true);
                        publishRaw(baseTopic + "/mic/active/state", isMicActive ? "ON" : "OFF", true);
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
            client.subscribe(baseTopic + "/dsp/#");
            client.subscribe(baseTopic + "/mic/#");
        } catch (Exception ignored) {}
    }

    // --- REJESTRACJA W HA (Zintegrowane urządzenie) ---
    private void publishHomeAssistantDiscovery() {
        try {
            JSONObject device = new JSONObject();
            device.put("identifiers", new JSONArray().put("panel_knob_engine_v4"));
            device.put("name", "Gałka Panel");
            device.put("model", "Allwinner DSP Knob 240x240");
            device.put("manufacturer", "Smart Home Lab");

            // Event i sensory akcji
            publishDiscovery("event", "action_event", "Zdarzenie Gałki", "knob_event_trigger_v4",
                    new JSONObject().put("state_topic", baseTopic + "/knob/event")
                            .put("event_types", new JSONArray().put("rotate_left").put("rotate_right").put("click")), device);

            publishDiscovery("sensor", "confirm_val", "Zatwierdzona Wartość", "knob_confirm_val_v4",
                    new JSONObject().put("state_topic", baseTopic + "/knob/confirm").put("icon", "mdi:check-circle-outline"), device);

            publishDiscovery("switch", "power", "Zasilanie Ekraniku", "knob_power_switch_v4",
                    new JSONObject().put("command_topic", baseTopic + "/display/power").put("state_topic", baseTopic + "/display/state")
                            .put("payload_on", "on").put("payload_off", "off").put("icon", "mdi:power"), device);

            publishDiscovery("switch", "led_ring", "Pierścień LED", "knob_led_ring_v4",
                    new JSONObject().put("command_topic", baseTopic + "/led/set").put("state_topic", baseTopic + "/led/state")
                            .put("payload_on", "on").put("payload_off", "off").put("icon", "mdi:led-strip-variant"), device);

            // Mikrofon
            publishDiscovery("switch", "mic_mute", "Wyciszenie Mikrofonu", "knob_mic_mute",
                    new JSONObject().put("command_topic", baseTopic + "/mic/mute/set").put("state_topic", baseTopic + "/mic/mute/state")
                            .put("payload_on", "ON").put("payload_off", "OFF").put("icon", "mdi:microphone-off"), device);

            publishDiscovery("binary_sensor", "mic_active", "Mikrofon Używany", "knob_mic_active",
                    new JSONObject().put("state_topic", baseTopic + "/mic/active/state").put("payload_on", "ON").put("payload_off", "OFF")
                            .put("icon", "mdi:microphone"), device);

            // Strefa 1 (Master, L, P)
            registerNumberEntity("dsp_z1_master", "Strefa 1 Głośność Główna", baseTopic + "/dsp/z1/master", 0, 20, "mdi:volume-high", device);
            registerNumberEntity("dsp_z1_left", "Strefa 1 Głośnik Lewy", baseTopic + "/dsp/z1/left", 0, 20, "mdi:speaker", device);
            registerNumberEntity("dsp_z1_right", "Strefa 1 Głośnik Prawy", baseTopic + "/dsp/z1/right", 0, 20, "mdi:speaker", device);

            // Strefa 2 (Master, L, P)
            registerNumberEntity("dsp_z2_master", "Strefa 2 Głośność Główna", baseTopic + "/dsp/z2/master", 0, 20, "mdi:volume-high", device);
            registerNumberEntity("dsp_z2_left", "Strefa 2 Głośnik Lewy", baseTopic + "/dsp/z2/left", 0, 20, "mdi:speaker", device);
            registerNumberEntity("dsp_z2_right", "Strefa 2 Głośnik Prawy", baseTopic + "/dsp/z2/right", 0, 20, "mdi:speaker", device);

            // Equalizer EQ
            registerNumberEntity("dsp_eq_bass", "Korektor Basy", baseTopic + "/dsp/eq/bass", -10, 10, "mdi:equalizer", device);
            registerNumberEntity("dsp_eq_treble", "Korektor Soprany", baseTopic + "/dsp/eq/treble", -10, 10, "mdi:equalizer", device);

            // Profil EQ
            JSONObject eqProf = new JSONObject();
            eqProf.put("name", "Profil Dźwięku EQ");
            eqProf.put("unique_id", "knob_dsp_eq_mode");
            eqProf.put("command_topic", baseTopic + "/dsp/eq/mode/set");
            eqProf.put("state_topic", baseTopic + "/dsp/eq/mode/state");
            eqProf.put("options", new JSONArray().put("Standard").put("Pop").put("Rock").put("Classic").put("Jazz").put("Vocal"));
            eqProf.put("icon", "mdi:music-box-outline");
            eqProf.put("device", device);
            publishRaw("homeassistant/select/panel_knob/dsp_eq_mode/config", eqProf.toString(), true);

        } catch (Exception ignored) {}
    }

    private void registerNumberEntity(String id, String name, String topicPrefix, int min, int max, String icon, JSONObject device) throws Exception {
        JSONObject obj = new JSONObject();
        obj.put("name", name);
        obj.put("unique_id", "knob_" + id);
        obj.put("command_topic", topicPrefix + "/set");
        obj.put("state_topic", topicPrefix + "/state");
        obj.put("min", min);
        obj.put("max", max);
        obj.put("mode", "slider");
        obj.put("icon", icon);
        obj.put("device", device);
        publishRaw("homeassistant/number/panel_knob/" + id + "/config", obj.toString(), true);
    }

    private void publishDiscovery(String domain, String id, String name, String uid, JSONObject data, JSONObject device) throws Exception {
        data.put("name", name);
        data.put("unique_id", uid);
        data.put("device", device);
        publishRaw("homeassistant/" + domain + "/panel_knob/" + id + "/config", data.toString(), true);
    }

    // --- OBSŁUGA ROZKAZÓW MQTT ---
    private void handleIncomingMqtt(String topic, String payload) {
        try {
            // Kontrola mikrofonu
            if (topic.equals(baseTopic + "/mic/mute/set")) {
                setMicrophoneMute("ON".equalsIgnoreCase(payload) || "true".equalsIgnoreCase(payload));
                return;
            }

            // DSP Strefa 1
            if (topic.equals(baseTopic + "/dsp/z1/master/set")) {
                int val = Integer.parseInt(payload);
                sendDspFrame(0xA1, val, 1);
                publishRaw(baseTopic + "/dsp/z1/master/state", String.valueOf(val), true);
                return;
            }
            if (topic.equals(baseTopic + "/dsp/z1/left/set")) {
                int val = Integer.parseInt(payload);
                sendDspFrame(0xAE, val, 1);
                publishRaw(baseTopic + "/dsp/z1/left/state", String.valueOf(val), true);
                return;
            }
            if (topic.equals(baseTopic + "/dsp/z1/right/set")) {
                int val = Integer.parseInt(payload);
                sendDspFrame(0xAF, val, 1);
                publishRaw(baseTopic + "/dsp/z1/right/state", String.valueOf(val), true);
                return;
            }

            // DSP Strefa 2
            if (topic.equals(baseTopic + "/dsp/z2/master/set")) {
                int val = Integer.parseInt(payload);
                sendDspFrame(0xA1, val, 2);
                publishRaw(baseTopic + "/dsp/z2/master/state", String.valueOf(val), true);
                return;
            }
            if (topic.equals(baseTopic + "/dsp/z2/left/set")) {
                int val = Integer.parseInt(payload);
                sendDspFrame(0xAE, val, 2);
                publishRaw(baseTopic + "/dsp/z2/left/state", String.valueOf(val), true);
                return;
            }
            if (topic.equals(baseTopic + "/dsp/z2/right/set")) {
                int val = Integer.parseInt(payload);
                sendDspFrame(0xAF, val, 2);
                publishRaw(baseTopic + "/dsp/z2/right/state", String.valueOf(val), true);
                return;
            }

            // DSP Equalizer
            if (topic.equals(baseTopic + "/dsp/eq/bass/set")) {
                int db = Integer.parseInt(payload); // -10 do +10
                int hex = 0xA0 + db; // 0x96 do 0xAA
                sendDspFrame(0xAB, hex, 1);
                sendDspFrame(0xAB, hex, 2);
                publishRaw(baseTopic + "/dsp/eq/bass/state", String.valueOf(db), true);
                return;
            }
            if (topic.equals(baseTopic + "/dsp/eq/treble/set")) {
                int db = Integer.parseInt(payload);
                int hex = 0xA0 + db;
                sendDspFrame(0xAC, hex, 1);
                sendDspFrame(0xAC, hex, 2);
                publishRaw(baseTopic + "/dsp/eq/treble/state", String.valueOf(db), true);
                return;
            }
            if (topic.equals(baseTopic + "/dsp/eq/mode/set")) {
                int modeVal = 1;
                switch (payload.toLowerCase()) {
                    case "pop": modeVal = 2; break;
                    case "rock": modeVal = 3; break;
                    case "classic": modeVal = 4; break;
                    case "jazz": modeVal = 5; break;
                    case "vocal": modeVal = 6; break;
                    default: modeVal = 1; break;
                }
                sendDspFrame(0xA2, modeVal, 1);
                sendDspFrame(0xA2, modeVal, 2);
                publishRaw(baseTopic + "/dsp/eq/mode/state", payload, true);
                return;
            }

            // Sprzęt ekran/LED
            if (topic.equals(baseTopic + "/display/power")) {
                setHardwarePower("on".equalsIgnoreCase(payload));
                if (isScreenHardwarePowered) redrawCurrentScreen();
                return;
            }
            if (topic.equals(baseTopic + "/led/set")) {
                setLedRingHardware("on".equalsIgnoreCase(payload));
                return;
            }

            // Czyszczenie alertu
            if (topic.equals(baseTopic + "/display/alert/clear")) {
                hasUnreadAlert = false;
                alertBadgeText = "";
                isAlertKeepingAwake = false;
                returnToClock();
                if (!isMainScreenOn) setHardwarePower(false);
                return;
            }

            // GAUGE
            if (topic.equals(baseTopic + "/display/gauge")) {
                JSONObject j = new JSONObject(payload);
                gaugeTitle = j.optString("title", "");
                gaugeFooter = j.optString("footer", "");
                gaugeValue = j.optInt("value", 50);
                gaugeMin = j.optInt("min", 0);
                gaugeMax = j.optInt("max", 100);
                gaugeStep = j.optInt("step", 5);
                gaugeUnit = j.optString("unit", "%");
                gaugeColor = Color.parseColor(j.optString("color", "#00E5FF"));

                currentMode = ScreenMode.GAUGE;
                ensureScreenAwake();
                animateGaugeToValue(gaugeValue);
                resetTimeout(j.optInt("timeout", 5000));
                return;
            }

            // MENU
            if (topic.equals(baseTopic + "/display/menu")) {
                JSONObject j = new JSONObject(payload);
                menuTitle = j.optString("title", "");
                menuFooter = j.optString("footer", "");
                menuItems.clear();
                JSONArray arr = j.optJSONArray("items");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) menuItems.add(arr.getString(i));
                }
                menuIndex = Math.max(0, Math.min(menuItems.size() - 1, j.optInt("selected_index", 0)));

                currentMode = ScreenMode.MENU;
                ensureScreenAwake();
                drawMenuScreen();
                resetTimeout(j.optInt("timeout", 5000));
                return;
            }

            // MEDIA (Brak sztywnego "NOW PLAYING")
            if (topic.equals(baseTopic + "/display/media")) {
                JSONObject j = new JSONObject(payload);
                mediaHeader = j.optString("header", "");
                mediaTitle = j.optString("title", "");
                mediaSub = j.optString("sub", j.optString("artist", ""));
                mediaProgress = j.optInt("progress", -1);

                currentMode = ScreenMode.MEDIA;
                ensureScreenAwake();
                drawMediaScreen(j.optString("image_base64", ""));
                resetTimeout(j.optInt("timeout", 10000));
                return;
            }

            // ALERT (Brak sztywnego "ALARM" i "Kliknij=Odczytano")
            if (topic.equals(baseTopic + "/display/alert")) {
                JSONObject j = new JSONObject(payload);
                String title = j.optString("title", "");
                String msg = j.optString("text", "");
                String footer = j.optString("footer", "");
                String color = j.optString("color", "#FF3D00");
                boolean keepAwake = j.optBoolean("keep_awake", false);
                boolean setBadge = j.optBoolean("badge", false);

                if (setBadge) {
                    hasUnreadAlert = true;
                    alertBadgeText = title.isEmpty() ? "ALERT" : title;
                }
                isAlertKeepingAwake = keepAwake;

                currentMode = ScreenMode.ALERT;
                setHardwarePower(true);
                drawAlertScreen(title, msg, footer, color);

                if (!keepAwake) resetTimeout(j.optInt("duration", 4000));
                else handler.removeCallbacks(timeoutCancelTask);
                return;
            }

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
        handler.post(() -> {
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
        });
    }

    // --- RYSOWANIE GRAFIKI (Czysty framework bez sztywnych napisów) ---

    // 1. ZEGAR (Z dynamicznym statusem mikrofonu i alertu)
    private synchronized void drawClock() {
        Bitmap bmp = Bitmap.createBitmap(240, 240, Bitmap.Config.RGB_565);
        Canvas canvas = new Canvas(bmp);
        canvas.drawColor(Color.BLACK);

        Paint p = new Paint();
        p.setAntiAlias(true);
        p.setTextAlign(Paint.Align.CENTER);

        // Pasek statusu u góry
        if (isMicMuted) {
            p.setColor(Color.parseColor("#FFA000")); // Bursztyn
            p.setTextSize(16);
            p.setTypeface(Typeface.DEFAULT_BOLD);
            canvas.drawText("MIC: WYCISZONY", 120, 38, p);
        } else if (isMicActive) {
            p.setColor(Color.parseColor("#FF1744")); // Czerwony
            p.setTextSize(16);
            p.setTypeface(Typeface.DEFAULT_BOLD);
            canvas.drawText("● MIC AKTYWNY", 120, 38, p);
        } else if (hasUnreadAlert) {
            p.setColor(Color.parseColor("#00E5FF"));
            p.setTextSize(16);
            p.setTypeface(Typeface.DEFAULT_BOLD);
            canvas.drawText("● " + alertBadgeText, 120, 38, p);
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

        if (!gaugeTitle.isEmpty()) {
            tp.setColor(Color.LTGRAY);
            tp.setTextSize(20);
            canvas.drawText(gaugeTitle, 120, 78, tp);
        }

        tp.setColor(Color.WHITE);
        tp.setTextSize(52);
        tp.setTypeface(Typeface.DEFAULT_BOLD);
        canvas.drawText(gaugeValue + gaugeUnit, 120, gaugeTitle.isEmpty() ? 125 : 135, tp);

        if (!gaugeFooter.isEmpty()) {
            tp.setColor(Color.GRAY);
            tp.setTextSize(16);
            tp.setTypeface(Typeface.DEFAULT);
            canvas.drawText(gaugeFooter, 120, 185, tp);
        }

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

        if (!menuTitle.isEmpty()) {
            p.setColor(Color.parseColor("#FFA726"));
            p.setTextSize(20);
            p.setTypeface(Typeface.DEFAULT_BOLD);
            canvas.drawText(menuTitle.toUpperCase(), 120, 48, p);
        }

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

        if (!menuFooter.isEmpty()) {
            p.setColor(Color.GRAY);
            p.setTextSize(16);
            p.setTypeface(Typeface.DEFAULT);
            canvas.drawText(menuFooter, 120, 195, p);
        }

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

        if (!mediaHeader.isEmpty()) {
            p.setColor(Color.parseColor("#E91E63"));
            p.setTextSize(20);
            p.setTypeface(Typeface.DEFAULT_BOLD);
            canvas.drawText(mediaHeader, 120, 50, p);
        }

        p.setColor(Color.WHITE);
        p.setTextSize(mediaTitle.length() > 10 ? 26 : 32);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        canvas.drawText(mediaTitle, 120, mediaHeader.isEmpty() ? 110 : 120, p);

        if (!mediaSub.isEmpty()) {
            p.setColor(Color.LTGRAY);
            p.setTextSize(20);
            p.setTypeface(Typeface.DEFAULT);
            canvas.drawText(mediaSub, 120, 155, p);
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
    private synchronized void drawAlertScreen(String title, String msg, String footer, String colorHex) {
        Bitmap bmp = Bitmap.createBitmap(240, 240, Bitmap.Config.RGB_565);
        Canvas canvas = new Canvas(bmp);
        canvas.drawColor(Color.BLACK);

        Paint p = new Paint();
        p.setAntiAlias(true);
        p.setTextAlign(Paint.Align.CENTER);

        if (!title.isEmpty()) {
            p.setColor(Color.parseColor(colorHex));
            p.setTextSize(26);
            p.setTypeface(Typeface.DEFAULT_BOLD);
            canvas.drawText(title, 120, 60, p);
        }

        p.setColor(Color.WHITE);
        p.setTextSize(msg.length() > 8 ? 26 : 36);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        canvas.drawText(msg, 120, title.isEmpty() ? 120 : 130, p);

        if (!footer.isEmpty()) {
            p.setColor(Color.GRAY);
            p.setTextSize(16);
            p.setTypeface(Typeface.DEFAULT);
            canvas.drawText(footer, 120, 190, p);
        }

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