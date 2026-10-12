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
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
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
import android.provider.Settings;
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
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class BridgeService extends Service {
    private static final String TAG = "KnobBridge";

    // Konfiguracja MQTT
    private String brokerUri = "tcp://192.168.0.7:1883";
    private String mqttUser = "admin";
    private String mqttPass = "admin";
    private String baseTopic = "panel";

    private MqttClient client;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile boolean running = true;
    private volatile boolean isConnecting = false;

    // Kolejki robocze
    private final ExecutorService mqttExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService hwExecutor = Executors.newSingleThreadExecutor();
    private Process inputMonitoringProc;

    // Blokady i multimedia
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private AudioManager audioManager;
    private AudioManager.AudioRecordingCallback audioRecordingCallback;

    // Deskryptory sprzętowe
    private FileOutputStream ttyOutStream;
    private RandomAccessFile fbAccessFile;

    // Flagi sprzętowe
    private boolean isMainScreenOn = true;
    private volatile boolean isScreenHardwarePowered = true;
    private boolean isMicActive = false;
    private boolean isMicMuted = false;
    private boolean isLedRingOn = false;

    // Wskaźnik interakcji z gałką na ekranie
    private String lastKnobFeedback = "";
    private final Runnable clearFeedbackTask = () -> {
        lastKnobFeedback = "";
        if (currentMode == ScreenMode.CLOCK && isScreenHardwarePowered) {
            postRenderTask(this::drawClockInternal);
        }
    };

    // Stany audio DSP i korektora
    private int dspZ1Master = 15;
    private int dspZ1Left = 12;
    private int dspZ1Right = 12;
    private int dspZ2Master = 15;
    private int dspZ2Left = 12;
    private int dspZ2Right = 12;
    private int dspEqBass = 0;
    private int dspEqTreble = 0;
    private String dspEqMode = "Standard";

    // Alerty
    private boolean hasUnreadAlert = false;
    private String alertBadgeText = "";
    private boolean isAlertKeepingAwake = false;
    private String alertTitle = "";
    private String alertMsg = "";
    private String alertFooter = "";
    private String alertColor = "#FF3D00";

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
    private final AtomicBoolean isDrawGaugePending = new AtomicBoolean(false);

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
    private Bitmap cachedMediaArt = null;

    // OSTRY RENDER ARGB_8888 (Brak rozmycia i brak szumów ditheringu)
    private final Bitmap renderBmp = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888);
    private final Canvas renderCanvas = new Canvas(renderBmp);
    private final int[] pixelArray = new int[240 * 240];
    private final byte[] rawFbBuffer = new byte[240 * 240 * 2];
    private final RectF arcRect = new RectF(28, 28, 212, 212);
    private final Rect fullScreenRect = new Rect(0, 0, 240, 240);

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private static final float[] TICK_COORDS = new float[19 * 4];
    static {
        int idx = 0;
        for (int angle = 135; angle <= 405; angle += 15) {
            double rad = Math.toRadians(angle);
            TICK_COORDS[idx++] = (float) (120 + 96 * Math.cos(rad));
            TICK_COORDS[idx++] = (float) (120 + 96 * Math.sin(rad));
            TICK_COORDS[idx++] = (float) (120 + 104 * Math.cos(rad));
            TICK_COORDS[idx++] = (float) (120 + 104 * Math.sin(rad));
        }
    }

    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm", Locale.getDefault());
    private final SimpleDateFormat dateFmt = new SimpleDateFormat("EEE, d MMM", new Locale("pl", "PL"));

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
                    postRenderTask(BridgeService.this::drawClockInternal);
                }
            }
        }
    };

    private final BroadcastReceiver configReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            SharedPreferences prefs = getSharedPreferences("knob_prefs", MODE_PRIVATE);
            SharedPreferences.Editor ed = prefs.edit();
            if (intent.hasExtra("broker")) ed.putString("broker", intent.getStringExtra("broker"));
            if (intent.hasExtra("user")) ed.putString("user", intent.getStringExtra("user"));
            if (intent.hasExtra("pass")) ed.putString("pass", intent.getStringExtra("pass"));
            if (intent.hasExtra("topic")) ed.putString("topic", intent.getStringExtra("topic"));
            ed.apply();
            loadConfig();
            scheduleMqttConnect();
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "Startowanie usługi BridgeService...");
        startForegroundNoti();
        acquireLocks();
        loadConfig();

        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        initMicMonitoring();

        IntentFilter screenFilter = new IntentFilter();
        screenFilter.addAction(Intent.ACTION_SCREEN_OFF);
        screenFilter.addAction(Intent.ACTION_SCREEN_ON);
        screenFilter.addAction(Intent.ACTION_TIME_TICK);
        registerReceiver(screenStateReceiver, screenFilter);

        IntentFilter cfgFilter = new IntentFilter("com.local.knobbridge.SET_CONFIG");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(configReceiver, cfgFilter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(configReceiver, cfgFilter);
        }

        initHardware();
        startRootInputMonitoring();
        scheduleMqttConnect();
    }

    private void acquireLocks() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "KnobBridge::CpuLock");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire();
            }
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "KnobBridge::WifiLock");
                wifiLock.setReferenceCounted(false);
                wifiLock.acquire();
            }
        } catch (Exception ignored) {}
    }

    private void startForegroundNoti() {
        NotificationChannel chan = new NotificationChannel("knob", "Knob Bridge", NotificationManager.IMPORTANCE_LOW);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.createNotificationChannel(chan);

        Notification noti = new Notification.Builder(this, "knob")
                .setContentTitle("Knob Engine Active")
                .setContentText("Mostek sprzętowy i MQTT działają poprawnie")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .build();

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(101, noti, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(101, noti);
        }
    }

    private void loadConfig() {
        SharedPreferences prefs = getSharedPreferences("knob_prefs", MODE_PRIVATE);
        this.brokerUri = prefs.getString("broker", this.brokerUri);
        this.mqttUser = prefs.getString("user", this.mqttUser);
        this.mqttPass = prefs.getString("pass", this.mqttPass);
        this.baseTopic = prefs.getString("topic", this.baseTopic);
    }

    // --- INICJALIZACJA SYSTEMU I AUDIO YAMAHA / ALLWINNER ---
    private void initHardware() {
        hwExecutor.execute(() -> {
            Log.i(TAG, "Inicjalizacja rejestrów sprzętowych i toru audio...");
            // 1. Ubicie chińskiego serwisu blokującego port
            // 2. Włączenie powielacza sygnału stereo na 4 kanały (dla Strefy 2)
            // 3. Odciszenie końcówki mocy i wybór źródła dźwięku z Androida
            // 4. Konfiguracja portu szeregowego 9600 8N1 i uprawnień
            String initCmds =
                    "pm disable-user com.sznaner.soundeffect\n" +
                    "echo on > /proc/vendor/sound_merger\n" +
                    "echo on > /proc/vendor/horn_mode\n" +
                    "echo local > /proc/vendor/aux_mode\n" +
                    "echo on > /proc/vendor/lcd_ctrl\n" +
                    "echo 0 > /sys/class/graphics/fb1/blank\n" +
                    "stty -F /dev/ttyAS4 9600 cs8 -cstopb -parenb -echo raw\n" +
                    "chmod 666 /dev/ttyAS4 /dev/graphics/fb1 /dev/input/event0 /proc/vendor/lcd_ctrl /proc/vendor/led_ctrl /proc/vendor/sound_merger /proc/vendor/horn_mode /proc/vendor/aux_mode /sys/class/graphics/fb1/blank\n";

            runRootCmdSync(initCmds);

            try {
                ttyOutStream = new FileOutputStream(new File("/dev/ttyAS4"));
            } catch (Exception e) {
                Log.w(TAG, "Brak bezpośredniego strumienia TTY: " + e.getMessage());
            }

            try {
                fbAccessFile = new RandomAccessFile("/dev/graphics/fb1", "rw");
            } catch (Exception e) {
                Log.w(TAG, "Brak bezpośredniego FB1: " + e.getMessage());
            }

            // Inicjalizacja nastaw DSP: Master, kanały lewe/prawe oraz EQ dla obu stref
            sendDspFrameSync(0xA1, dspZ1Master, 1);
            sendDspFrameSync(0xAE, dspZ1Left, 1);
            sendDspFrameSync(0xAF, dspZ1Right, 1);

            sendDspFrameSync(0xA1, dspZ2Master, 2);
            sendDspFrameSync(0xAE, dspZ2Left, 2);
            sendDspFrameSync(0xAF, dspZ2Right, 2);

            sendDspFrameSync(0xAB, 0xA0, 1);
            sendDspFrameSync(0xAB, 0xA0, 2);
            sendDspFrameSync(0xAC, 0xA0, 1);
            sendDspFrameSync(0xAC, 0xA0, 2);
            sendDspFrameSync(0xA2, 0x01, 1);
            sendDspFrameSync(0xA2, 0x01, 2);

            setHardwarePowerInternal(true);
            drawClockInternal();
        });
    }

    private void sendDspFrame(int cmd, int val, int room) {
        hwExecutor.execute(() -> sendDspFrameSync(cmd, val, room));
    }

    private void sendDspFrameSync(int cmd, int val, int room) {
        byte bCmd = (byte) (cmd & 0xFF);
        byte bVal = (byte) (val & 0xFF);
        byte bRoom = (byte) (room & 0xFF);
        byte cs = (byte) ((0xFA + 0x01 + (cmd & 0xFF) + (val & 0xFF) + (room & 0xFF)) & 0xFF);
        byte[] frame = new byte[]{(byte) 0xFA, 0x01, bCmd, bVal, bRoom, cs};

        if (ttyOutStream != null) {
            try {
                ttyOutStream.write(frame);
                ttyOutStream.flush();
                Thread.sleep(25); // Pauza zapobiegająca gubieniu ramek przez mikrokontroler przy 9600 baud
                return;
            } catch (Exception e) {
                try { ttyOutStream.close(); } catch (Exception ignored) {}
                ttyOutStream = null;
            }
        }

        String hexCmd = String.format(Locale.US, "printf '\\xFA\\x01\\x%02X\\x%02X\\x%02X\\x%02X' > /dev/ttyAS4",
                bCmd, bVal, bRoom, cs);
        runRootCmdSync(hexCmd);
        try { Thread.sleep(25); } catch (InterruptedException ignored) {}
    }

    private void writeSysFs(String path, String value) {
        try (FileOutputStream fos = new FileOutputStream(path)) {
            fos.write(value.getBytes(StandardCharsets.UTF_8));
            fos.flush();
        } catch (Exception e) {
            runRootCmdSync("echo " + value + " > " + path);
        }
    }

    private void setHardwarePower(boolean enable) {
        isScreenHardwarePowered = enable;
        hwExecutor.execute(() -> setHardwarePowerInternal(enable));
    }

    private void setHardwarePowerInternal(boolean enable) {
        isScreenHardwarePowered = enable;
        writeSysFs("/proc/vendor/lcd_ctrl", enable ? "on" : "off");
        writeSysFs("/sys/class/graphics/fb1/blank", enable ? "0" : "4");
        publishRaw(baseTopic + "/display/state", enable ? "on" : "off", true);
    }

    private void setLedRingHardware(boolean enable) {
        hwExecutor.execute(() -> {
            writeSysFs("/proc/vendor/led_ctrl", enable ? "on" : "off");
            publishRaw(baseTopic + "/led/state", enable ? "on" : "off", true);
        });
    }

    private void runRootCmdSync(String cmd) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"su", "0"});
            OutputStream os = p.getOutputStream();
            os.write((cmd + "\nexit\n").getBytes(StandardCharsets.UTF_8));
            os.flush();
            p.waitFor();
        } catch (Exception ignored) {
        } finally {
            if (p != null) p.destroy();
        }
    }

    // --- MIKROFON ---
    private void initMicMonitoring() {
        if (audioManager != null) {
            try { isMicMuted = audioManager.isMicrophoneMute(); } catch (Exception ignored) {}
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                audioRecordingCallback = new AudioManager.AudioRecordingCallback() {
                    @Override
                    public void onRecordingConfigChanged(List<AudioRecordingConfiguration> configs) {
                        boolean active = (configs != null && !configs.isEmpty());
                        if (isMicActive != active) {
                            isMicActive = active;
                            publishRaw(baseTopic + "/mic/active/state", active ? "ON" : "OFF", true);
                            if (currentMode == ScreenMode.CLOCK && isScreenHardwarePowered) {
                                postRenderTask(BridgeService.this::drawClockInternal);
                            }
                        }
                    }
                };
                audioManager.registerAudioRecordingCallback(audioRecordingCallback, handler);
            }
        }
    }

    private void setMicrophoneMute(boolean mute) {
        if (audioManager != null) {
            try {
                audioManager.setMicrophoneMute(mute);
                isMicMuted = mute;
                Log.i(TAG, "Ustawiono stan mikrofonu mute=" + mute);
                publishRaw(baseTopic + "/mic/mute/state", isMicMuted ? "ON" : "OFF", true);
                if (currentMode == ScreenMode.CLOCK && isScreenHardwarePowered) {
                    postRenderTask(this::drawClockInternal);
                }
            } catch (Exception e) {
                Log.e(TAG, "Błąd przełączania mute: " + e.getMessage());
            }
        }
    }

    // --- ENKODER: NIEZAWODNE GETEVENT POPRZEZ INTERAKTYWNE SU 0 ---
    private void startRootInputMonitoring() {
        new Thread(() -> {
            while (running) {
                try {
                    inputMonitoringProc = Runtime.getRuntime().exec(new String[]{"su", "0"});
                    OutputStream os = inputMonitoringProc.getOutputStream();
                    os.write("getevent -l /dev/input/event0\n".getBytes(StandardCharsets.UTF_8));
                    os.flush();

                    BufferedReader reader = new BufferedReader(new InputStreamReader(inputMonitoringProc.getInputStream()));
                    String line;
                    Log.i(TAG, "Nasłuch zdarzeń enkodera /dev/input/event0 aktywny.");
                    while (running && (line = reader.readLine()) != null) {
                        if (line.contains("DOWN") || line.contains(" 00000001")) {
                            if (line.contains("KEY_F1")) handleKnobEvent(-1);
                            else if (line.contains("KEY_F2")) handleKnobEvent(1);
                            else if (line.contains("KEY_F3")) handleKnobClick();
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Błąd enkodera: " + e.getMessage());
                } finally {
                    if (inputMonitoringProc != null) {
                        inputMonitoringProc.destroy();
                        inputMonitoringProc = null;
                    }
                }
                if (running) {
                    try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
                }
            }
        }, "KnobInputThread").start();
    }

    private void handleKnobEvent(int delta) {
        if (!isScreenHardwarePowered) {
            setHardwarePower(true);
            redrawCurrentScreen();
            return;
        }

        String action = delta > 0 ? "rotate_right" : "rotate_left";
        publishAction(action);

        switch (currentMode) {
            case CLOCK:
                lastKnobFeedback = delta > 0 ? "OBRÓT W PRAWO ▶" : "◀ OBRÓT W LEWO";
                handler.removeCallbacks(clearFeedbackTask);
                handler.postDelayed(clearFeedbackTask, 1500);
                postRenderTask(this::drawClockInternal);
                break;
            case GAUGE:
                gaugeValue = Math.max(gaugeMin, Math.min(gaugeMax, gaugeValue + (delta * gaugeStep)));
                animateGaugeToValue(gaugeValue);
                publishRaw(baseTopic + "/knob/gauge_value", String.valueOf(gaugeValue), false);
                resetTimeout(5000);
                break;
            case MENU:
                if (!menuItems.isEmpty()) {
                    menuIndex = (menuIndex + delta + menuItems.size()) % menuItems.size();
                    postRenderTask(this::drawMenuScreenInternal);
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

        if (currentMode == ScreenMode.CLOCK) {
            lastKnobFeedback = "● KLIKNIĘCIE";
            handler.removeCallbacks(clearFeedbackTask);
            handler.postDelayed(clearFeedbackTask, 1500);
            postRenderTask(this::drawClockInternal);
            return;
        }

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
        publishRaw(baseTopic + "/knob/action", action, false);
        try {
            JSONObject eventObj = new JSONObject();
            eventObj.put("event_type", action);
            eventObj.put("timestamp", System.currentTimeMillis());
            publishRaw(baseTopic + "/knob/event", eventObj.toString(), false);
        } catch (Exception ignored) {}
    }

    private final Runnable timeoutCancelTask = () -> {
        if (currentMode == ScreenMode.GAUGE || currentMode == ScreenMode.MENU) {
            publishRaw(baseTopic + "/knob/cancel", "{\"canceled_mode\":\"" + currentMode.name().toLowerCase(Locale.ROOT) + "\"}", false);
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
        lastKnobFeedback = "";
        if (cachedMediaArt != null) {
            cachedMediaArt.recycle();
            cachedMediaArt = null;
        }
        if (isScreenHardwarePowered) postRenderTask(this::drawClockInternal);
    }

    // --- ASYNCHRONICZNE MQTT BEZ BLOKAD ---
    private void scheduleMqttConnect() {
        if (mqttExecutor.isShutdown()) return;
        mqttExecutor.execute(this::connectMqttInternal);
    }

    private void connectMqttInternal() {
        if (isConnecting || (client != null && client.isConnected())) return;
        isConnecting = true;

        try {
            if (client != null) {
                try { client.disconnectForcibly(500); client.close(); } catch (Exception ignored) {}
                client = null;
            }

            String androidId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
            if (androidId == null || androidId.isEmpty()) androidId = "a133_panel";
            String clientId = "PanelKnobEngine_" + androidId;

            client = new MqttClient(brokerUri, clientId, new MemoryPersistence());
            MqttConnectOptions opts = new MqttConnectOptions();
            opts.setCleanSession(true);
            opts.setAutomaticReconnect(true);
            opts.setKeepAliveInterval(60);
            opts.setConnectionTimeout(15);
            opts.setMaxInflight(100);
            if (!mqttUser.isEmpty()) {
                opts.setUserName(mqttUser);
                opts.setPassword(mqttPass.toCharArray());
            }

            client.setCallback(new MqttCallbackExtended() {
                @Override
                public void connectComplete(boolean reconnect, String serverURI) {
                    Log.i(TAG, "MQTT połączone (reconnect=" + reconnect + ")");
                    mqttExecutor.execute(() -> {
                        subscribeTopicsInternal();
                        publishHomeAssistantDiscoveryInternal();
                        syncAllStatesInternal();
                    });
                }

                @Override
                public void connectionLost(Throwable cause) {
                    Log.w(TAG, "MQTT utracone: " + (cause != null ? cause.getMessage() : "rozłączenie"));
                }

                @Override
                public void messageArrived(String topic, MqttMessage message) {
                    String payload = new String(message.getPayload(), StandardCharsets.UTF_8).trim();
                    handleIncomingMqtt(topic, payload);
                }

                @Override
                public void deliveryComplete(IMqttDeliveryToken token) {}
            });

            Log.i(TAG, "Łączenie z brokerem MQTT " + brokerUri);
            client.connect(opts);
        } catch (Exception e) {
            Log.e(TAG, "Błąd łączenia z MQTT: " + e.getMessage());
            handler.postDelayed(this::scheduleMqttConnect, 5000);
        } finally {
            isConnecting = false;
        }
    }

    private void subscribeTopicsInternal() {
        try {
            if (client != null && client.isConnected()) {
                client.subscribe(new String[]{
                        baseTopic + "/display/#",
                        baseTopic + "/led/#",
                        baseTopic + "/dsp/#",
                        baseTopic + "/mic/#"
                }, new int[]{0, 0, 0, 0});
            }
        } catch (Exception e) {
            Log.e(TAG, "Błąd subskrypcji MQTT: " + e.getMessage());
        }
    }

    private void publishRaw(String topic, String payload, boolean retained) {
        if (mqttExecutor.isShutdown()) return;
        mqttExecutor.execute(() -> publishRawInternal(topic, payload, retained));
    }

    private void publishRawInternal(String topic, String payload, boolean retained) {
        try {
            if (client != null && client.isConnected()) {
                MqttMessage msg = new MqttMessage(payload.getBytes(StandardCharsets.UTF_8));
                msg.setQos(0);
                msg.setRetained(retained);
                client.publish(topic, msg);
            }
        } catch (Exception e) {
            Log.e(TAG, "Błąd publish MQTT: " + e.getMessage());
        }
    }

    private void publishHomeAssistantDiscoveryInternal() {
        try {
            JSONObject device = new JSONObject();
            device.put("identifiers", new JSONArray().put("panel_knob_engine_v4"));
            device.put("name", "Gałka Panel");
            device.put("model", "Allwinner DSP Knob 240x240");
            device.put("manufacturer", "Smart Home Lab");

            // 1. Zdarzenie gałki
            JSONObject ev = new JSONObject();
            ev.put("name", "Zdarzenie Gałki");
            ev.put("unique_id", "knob_event_trigger_v4");
            ev.put("state_topic", baseTopic + "/knob/event");
            ev.put("event_types", new JSONArray().put("rotate_left").put("rotate_right").put("click"));
            ev.put("device", device);
            publishRawInternal("homeassistant/event/panel_knob/action_event/config", ev.toString(), true);

            // 2. Sensor tekstu akcji
            JSONObject act = new JSONObject();
            act.put("name", "Akcja Gałki");
            act.put("unique_id", "knob_action_sensor_v4");
            act.put("state_topic", baseTopic + "/knob/action");
            act.put("force_update", true);
            act.put("icon", "mdi:rotate-right");
            act.put("device", device);
            publishRawInternal("homeassistant/sensor/panel_knob/action/config", act.toString(), true);

            // 3. Sensor zatwierdzonej wartości
            JSONObject conf = new JSONObject();
            conf.put("name", "Zatwierdzona Wartość");
            conf.put("unique_id", "knob_confirm_val_v4");
            conf.put("state_topic", baseTopic + "/knob/confirm");
            conf.put("icon", "mdi:check-circle-outline");
            conf.put("device", device);
            publishRawInternal("homeassistant/sensor/panel_knob/confirm/config", conf.toString(), true);

            // 4. Zasilanie ekranu pomocniczego
            JSONObject pwr = new JSONObject();
            pwr.put("name", "Zasilanie Ekraniku");
            pwr.put("unique_id", "knob_power_switch_v4");
            pwr.put("command_topic", baseTopic + "/display/power");
            pwr.put("state_topic", baseTopic + "/display/state");
            pwr.put("payload_on", "on");
            pwr.put("payload_off", "off");
            pwr.put("icon", "mdi:power");
            pwr.put("device", device);
            publishRawInternal("homeassistant/switch/panel_knob/power/config", pwr.toString(), true);

            // 5. Pierścień LED
            JSONObject led = new JSONObject();
            led.put("name", "Pierścień LED");
            led.put("unique_id", "knob_led_ring_v4");
            led.put("command_topic", baseTopic + "/led/set");
            led.put("state_topic", baseTopic + "/led/state");
            led.put("payload_on", "on");
            led.put("payload_off", "off");
            led.put("icon", "mdi:led-strip-variant");
            led.put("device", device);
            publishRawInternal("homeassistant/switch/panel_knob/led_ring/config", led.toString(), true);

            // 6. Wyciszenie mikrofonu
            JSONObject micM = new JSONObject();
            micM.put("name", "Wyciszenie Mikrofonu");
            micM.put("unique_id", "knob_mic_mute_v4");
            micM.put("command_topic", baseTopic + "/mic/mute/set");
            micM.put("state_topic", baseTopic + "/mic/mute/state");
            micM.put("payload_on", "ON");
            micM.put("payload_off", "OFF");
            micM.put("icon", "mdi:microphone-off");
            micM.put("device", device);
            publishRawInternal("homeassistant/switch/panel_knob/mic_mute/config", micM.toString(), true);

            // 7. Mikrofon aktywny
            JSONObject micA = new JSONObject();
            micA.put("name", "Mikrofon Używany");
            micA.put("unique_id", "knob_mic_active_v4");
            micA.put("state_topic", baseTopic + "/mic/active/state");
            micA.put("payload_on", "ON");
            micA.put("payload_off", "OFF");
            micA.put("icon", "mdi:microphone");
            micA.put("device", device);
            publishRawInternal("homeassistant/binary_sensor/panel_knob/mic_active/config", micA.toString(), true);

            // 8. Strefa 1 (Głośność główna, lewy, prawy)
            registerNumberInternal("dsp_z1_master", "Strefa 1 Głośność Główna", baseTopic + "/dsp/z1/master", 0, 20, "mdi:volume-high", device);
            registerNumberInternal("dsp_z1_left", "Strefa 1 Głośnik Lewy", baseTopic + "/dsp/z1/left", 0, 20, "mdi:speaker", device);
            registerNumberInternal("dsp_z1_right", "Strefa 1 Głośnik Prawy", baseTopic + "/dsp/z1/right", 0, 20, "mdi:speaker", device);

            // 9. Strefa 2 (Głośność główna, lewy, prawy)
            registerNumberInternal("dsp_z2_master", "Strefa 2 Głośność Główna", baseTopic + "/dsp/z2/master", 0, 20, "mdi:volume-high", device);
            registerNumberInternal("dsp_z2_left", "Strefa 2 Głośnik Lewy", baseTopic + "/dsp/z2/left", 0, 20, "mdi:speaker", device);
            registerNumberInternal("dsp_z2_right", "Strefa 2 Głośnik Prawy", baseTopic + "/dsp/z2/right", 0, 20, "mdi:speaker", device);

            // 10. Korektor EQ (Basy, soprany, profil brzmienia)
            registerNumberInternal("dsp_eq_bass", "Korektor Basy", baseTopic + "/dsp/eq/bass", -10, 10, "mdi:equalizer", device);
            registerNumberInternal("dsp_eq_treble", "Korektor Soprany", baseTopic + "/dsp/eq/treble", -10, 10, "mdi:equalizer", device);

            JSONObject eqProf = new JSONObject();
            eqProf.put("name", "Profil Dźwięku EQ");
            eqProf.put("unique_id", "knob_dsp_eq_mode_v4");
            eqProf.put("command_topic", baseTopic + "/dsp/eq/mode/set");
            eqProf.put("state_topic", baseTopic + "/dsp/eq/mode/state");
            eqProf.put("options", new JSONArray().put("Standard").put("Pop").put("Rock").put("Classic").put("Jazz").put("Vocal"));
            eqProf.put("icon", "mdi:music-box-outline");
            eqProf.put("device", device);
            publishRawInternal("homeassistant/select/panel_knob/dsp_eq_mode/config", eqProf.toString(), true);

            Log.i(TAG, "Rejestracja Home Assistant Discovery zakończona pomyślnie.");
        } catch (Exception e) {
            Log.e(TAG, "Błąd discovery: " + e.getMessage());
        }
    }

    private void registerNumberInternal(String id, String name, String topicPrefix, int min, int max, String icon, JSONObject device) {
        try {
            JSONObject obj = new JSONObject();
            obj.put("name", name);
            obj.put("unique_id", "knob_" + id + "_v4");
            obj.put("command_topic", topicPrefix + "/set");
            obj.put("state_topic", topicPrefix + "/state");
            obj.put("min", min);
            obj.put("max", max);
            obj.put("step", 1);
            obj.put("mode", "slider");
            obj.put("icon", icon);
            obj.put("device", device);
            publishRawInternal("homeassistant/number/panel_knob/" + id + "/config", obj.toString(), true);
        } catch (Exception ignored) {}
    }

    private void syncAllStatesInternal() {
        publishRawInternal(baseTopic + "/dsp/z1/master/state", String.valueOf(dspZ1Master), true);
        publishRawInternal(baseTopic + "/dsp/z1/left/state", String.valueOf(dspZ1Left), true);
        publishRawInternal(baseTopic + "/dsp/z1/right/state", String.valueOf(dspZ1Right), true);
        publishRawInternal(baseTopic + "/dsp/z2/master/state", String.valueOf(dspZ2Master), true);
        publishRawInternal(baseTopic + "/dsp/z2/left/state", String.valueOf(dspZ2Left), true);
        publishRawInternal(baseTopic + "/dsp/z2/right/state", String.valueOf(dspZ2Right), true);
        publishRawInternal(baseTopic + "/dsp/eq/bass/state", String.valueOf(dspEqBass), true);
        publishRawInternal(baseTopic + "/dsp/eq/treble/state", String.valueOf(dspEqTreble), true);
        publishRawInternal(baseTopic + "/dsp/eq/mode/state", dspEqMode, true);
        publishRawInternal(baseTopic + "/display/state", isScreenHardwarePowered ? "on" : "off", true);
        publishRawInternal(baseTopic + "/led/state", isLedRingOn ? "on" : "off", true);
        publishRawInternal(baseTopic + "/mic/mute/state", isMicMuted ? "ON" : "OFF", true);
        publishRawInternal(baseTopic + "/mic/active/state", isMicActive ? "ON" : "OFF", true);
    }

    private int parseClampedInt(String payload, int min, int max, int defaultVal) {
        if (payload == null || payload.isEmpty()) return defaultVal;
        try {
            double d = Double.parseDouble(payload.trim());
            int val = (int) Math.round(d);
            return Math.max(min, Math.min(max, val));
        } catch (Exception e) {
            return defaultVal;
        }
    }

    private void handleIncomingMqtt(String topic, String payload) {
        try {
            if (topic.equals(baseTopic + "/mic/mute/set")) {
                setMicrophoneMute("ON".equalsIgnoreCase(payload) || "true".equalsIgnoreCase(payload));
                return;
            }

            // Strefa 1
            if (topic.equals(baseTopic + "/dsp/z1/master/set")) {
                dspZ1Master = parseClampedInt(payload, 0, 20, 15);
                sendDspFrame(0xA1, dspZ1Master, 1);
                publishRaw(baseTopic + "/dsp/z1/master/state", String.valueOf(dspZ1Master), true);
                return;
            }
            if (topic.equals(baseTopic + "/dsp/z1/left/set")) {
                dspZ1Left = parseClampedInt(payload, 0, 20, 12);
                sendDspFrame(0xAE, dspZ1Left, 1);
                publishRaw(baseTopic + "/dsp/z1/left/state", String.valueOf(dspZ1Left), true);
                return;
            }
            if (topic.equals(baseTopic + "/dsp/z1/right/set")) {
                dspZ1Right = parseClampedInt(payload, 0, 20, 12);
                sendDspFrame(0xAF, dspZ1Right, 1);
                publishRaw(baseTopic + "/dsp/z1/right/state", String.valueOf(dspZ1Right), true);
                return;
            }

            // Strefa 2
            if (topic.equals(baseTopic + "/dsp/z2/master/set")) {
                dspZ2Master = parseClampedInt(payload, 0, 20, 15);
                sendDspFrame(0xA1, dspZ2Master, 2);
                publishRaw(baseTopic + "/dsp/z2/master/state", String.valueOf(dspZ2Master), true);
                return;
            }
            if (topic.equals(baseTopic + "/dsp/z2/left/set")) {
                dspZ2Left = parseClampedInt(payload, 0, 20, 12);
                sendDspFrame(0xAE, dspZ2Left, 2);
                publishRaw(baseTopic + "/dsp/z2/left/state", String.valueOf(dspZ2Left), true);
                return;
            }
            if (topic.equals(baseTopic + "/dsp/z2/right/set")) {
                dspZ2Right = parseClampedInt(payload, 0, 20, 12);
                sendDspFrame(0xAF, dspZ2Right, 2);
                publishRaw(baseTopic + "/dsp/z2/right/state", String.valueOf(dspZ2Right), true);
                return;
            }

            // DSP EQ
            if (topic.equals(baseTopic + "/dsp/eq/bass/set")) {
                dspEqBass = parseClampedInt(payload, -10, 10, 0);
                int hex = 0xA0 + dspEqBass;
                sendDspFrame(0xAB, hex, 1);
                sendDspFrame(0xAB, hex, 2);
                publishRaw(baseTopic + "/dsp/eq/bass/state", String.valueOf(dspEqBass), true);
                return;
            }
            if (topic.equals(baseTopic + "/dsp/eq/treble/set")) {
                dspEqTreble = parseClampedInt(payload, -10, 10, 0);
                int hex = 0xA0 + dspEqTreble;
                sendDspFrame(0xAC, hex, 1);
                sendDspFrame(0xAC, hex, 2);
                publishRaw(baseTopic + "/dsp/eq/treble/state", String.valueOf(dspEqTreble), true);
                return;
            }
            if (topic.equals(baseTopic + "/dsp/eq/mode/set")) {
                int modeVal;
                switch (payload.trim().toLowerCase(Locale.ROOT)) {
                    case "pop": modeVal = 2; dspEqMode = "Pop"; break;
                    case "rock": modeVal = 3; dspEqMode = "Rock"; break;
                    case "classic": modeVal = 4; dspEqMode = "Classic"; break;
                    case "jazz": modeVal = 5; dspEqMode = "Jazz"; break;
                    case "vocal": modeVal = 6; dspEqMode = "Vocal"; break;
                    default: modeVal = 1; dspEqMode = "Standard"; break;
                }
                sendDspFrame(0xA2, modeVal, 1);
                sendDspFrame(0xA2, modeVal, 2);
                publishRaw(baseTopic + "/dsp/eq/mode/state", dspEqMode, true);
                return;
            }

            if (topic.equals(baseTopic + "/display/power")) {
                setHardwarePower("on".equalsIgnoreCase(payload));
                if (isScreenHardwarePowered) redrawCurrentScreen();
                return;
            }
            if (topic.equals(baseTopic + "/led/set")) {
                isLedRingOn = "on".equalsIgnoreCase(payload);
                setLedRingHardware(isLedRingOn);
                return;
            }

            if (topic.equals(baseTopic + "/display/alert/clear")) {
                hasUnreadAlert = false;
                alertBadgeText = "";
                isAlertKeepingAwake = false;
                returnToClock();
                if (!isMainScreenOn) setHardwarePower(false);
                return;
            }

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
                postRenderTask(this::drawMenuScreenInternal);
                resetTimeout(j.optInt("timeout", 5000));
                return;
            }

            if (topic.equals(baseTopic + "/display/media")) {
                JSONObject j = new JSONObject(payload);
                mediaHeader = j.optString("header", "");
                mediaTitle = j.optString("title", "");
                mediaSub = j.optString("sub", j.optString("artist", ""));
                mediaProgress = j.optInt("progress", -1);

                String b64 = j.optString("image_base64", "");
                if (!b64.isEmpty()) {
                    try {
                        byte[] raw = Base64.decode(b64, Base64.DEFAULT);
                        Bitmap decoded = BitmapFactory.decodeByteArray(raw, 0, raw.length);
                        if (decoded != null) {
                            if (cachedMediaArt != null) cachedMediaArt.recycle();
                            cachedMediaArt = decoded;
                        }
                    } catch (Exception ignored) {}
                }

                currentMode = ScreenMode.MEDIA;
                ensureScreenAwake();
                postRenderTask(this::drawMediaScreenInternal);
                resetTimeout(j.optInt("timeout", 10000));
                return;
            }

            if (topic.equals(baseTopic + "/display/alert")) {
                JSONObject j = new JSONObject(payload);
                alertTitle = j.optString("title", "");
                alertMsg = j.optString("text", "");
                alertFooter = j.optString("footer", "");
                alertColor = j.optString("color", "#FF3D00");
                boolean keepAwake = j.optBoolean("keep_awake", false);
                boolean setBadge = j.optBoolean("badge", false);

                if (setBadge) {
                    hasUnreadAlert = true;
                    alertBadgeText = alertTitle.isEmpty() ? "ALERT" : alertTitle;
                }
                isAlertKeepingAwake = keepAwake;

                currentMode = ScreenMode.ALERT;
                setHardwarePower(true);
                postRenderTask(this::drawAlertScreenInternal);

                if (!keepAwake) resetTimeout(j.optInt("duration", 4000));
                else handler.removeCallbacks(timeoutCancelTask);
                return;
            }

            if (topic.equals(baseTopic + "/display/clock")) {
                returnToClock();
            }

        } catch (Exception ignored) {}
    }

    private void ensureScreenAwake() {
        if (!isScreenHardwarePowered) setHardwarePower(true);
    }

    private void redrawCurrentScreen() {
        if (!isScreenHardwarePowered) return;
        switch (currentMode) {
            case GAUGE: postRenderTask(this::drawGaugeScreenInternal); break;
            case MENU: postRenderTask(this::drawMenuScreenInternal); break;
            case MEDIA: postRenderTask(this::drawMediaScreenInternal); break;
            case ALERT: postRenderTask(this::drawAlertScreenInternal); break;
            default: postRenderTask(this::drawClockInternal); break;
        }
    }

    private void postRenderTask(Runnable renderTask) {
        if (isScreenHardwarePowered && !hwExecutor.isShutdown()) {
            hwExecutor.execute(renderTask);
        }
    }

    private void animateGaugeToValue(int targetVal) {
        handler.post(() -> {
            float range = Math.max(1, gaugeMax - gaugeMin);
            float pct = ((float) (targetVal - gaugeMin)) / range;
            float targetSweep = 270f * Math.max(0f, Math.min(1f, pct));

            if (gaugeAnimator != null && gaugeAnimator.isRunning()) {
                gaugeAnimator.cancel();
            }

            gaugeAnimator = ValueAnimator.ofFloat(animatedSweep, targetSweep);
            gaugeAnimator.setDuration(120);
            gaugeAnimator.setInterpolator(new DecelerateInterpolator());
            gaugeAnimator.addUpdateListener(anim -> {
                animatedSweep = (float) anim.getAnimatedValue();
                if (isDrawGaugePending.compareAndSet(false, true)) {
                    postRenderTask(() -> {
                        isDrawGaugePending.set(false);
                        drawGaugeScreenInternal();
                    });
                }
            });
            gaugeAnimator.start();
        });
    }

    // --- RYSOWANIE GRAFIKI (Czysty, wygładzony i ostry obraz) ---
    private void drawClockInternal() {
        renderCanvas.drawColor(Color.BLACK);
        paint.reset();
        paint.setAntiAlias(true);
        paint.setSubpixelText(true);
        paint.setTextAlign(Paint.Align.CENTER);

        // Górny status mikrofonu / powiadomień
        if (isMicMuted) {
            paint.setColor(Color.parseColor("#FFA000"));
            paint.setTextSize(17);
            paint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
            renderCanvas.drawText("● MIC WYCISZONY", 120, 48, paint);
        } else if (isMicActive) {
            paint.setColor(Color.parseColor("#FF1744"));
            paint.setTextSize(17);
            paint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
            renderCanvas.drawText("● MIC AKTYWNY", 120, 48, paint);
        } else if (hasUnreadAlert) {
            paint.setColor(Color.parseColor("#00E5FF"));
            paint.setTextSize(17);
            paint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
            renderCanvas.drawText("● " + alertBadgeText, 120, 48, paint);
        }

        // Godzina
        Date now = new Date();
        paint.setColor(Color.WHITE);
        paint.setTextSize(64);
        paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        renderCanvas.drawText(timeFmt.format(now), 120, 128, paint);

        // Data
        paint.setColor(Color.parseColor("#B0BEC5"));
        paint.setTextSize(21);
        paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        renderCanvas.drawText(dateFmt.format(now), 120, 162, paint);

        // Dolny feedback ruchu gałką
        if (!lastKnobFeedback.isEmpty()) {
            paint.setColor(Color.parseColor("#00E5FF"));
            paint.setTextSize(16);
            paint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
            renderCanvas.drawText(lastKnobFeedback, 120, 198, paint);
        }

        renderToFramebufferInternal();
    }

    private void drawGaugeScreenInternal() {
        renderCanvas.drawColor(Color.BLACK);

        tickPaint.setColor(Color.parseColor("#333333"));
        tickPaint.setStrokeWidth(2.5f);
        renderCanvas.drawLines(TICK_COORDS, tickPaint);

        paint.reset();
        paint.setAntiAlias(true);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(14);
        paint.setColor(Color.parseColor("#222222"));
        renderCanvas.drawArc(arcRect, 135, 270, false, paint);

        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(gaugeColor);
        renderCanvas.drawArc(arcRect, 135, animatedSweep, false, paint);

        paint.reset();
        paint.setAntiAlias(true);
        paint.setSubpixelText(true);
        paint.setTextAlign(Paint.Align.CENTER);

        if (!gaugeTitle.isEmpty()) {
            paint.setColor(Color.LTGRAY);
            paint.setTextSize(20);
            renderCanvas.drawText(gaugeTitle, 120, 78, paint);
        }

        paint.setColor(Color.WHITE);
        paint.setTextSize(52);
        paint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        renderCanvas.drawText(gaugeValue + gaugeUnit, 120, gaugeTitle.isEmpty() ? 125 : 135, paint);

        if (!gaugeFooter.isEmpty()) {
            paint.setColor(Color.GRAY);
            paint.setTextSize(16);
            paint.setTypeface(Typeface.DEFAULT);
            renderCanvas.drawText(gaugeFooter, 120, 185, paint);
        }

        renderToFramebufferInternal();
    }

    private void drawMenuScreenInternal() {
        renderCanvas.drawColor(Color.BLACK);
        paint.reset();
        paint.setAntiAlias(true);
        paint.setSubpixelText(true);
        paint.setTextAlign(Paint.Align.CENTER);

        if (!menuTitle.isEmpty()) {
            paint.setColor(Color.parseColor("#FFA726"));
            paint.setTextSize(20);
            paint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
            renderCanvas.drawText(menuTitle.toUpperCase(Locale.ROOT), 120, 48, paint);
        }

        if (!menuItems.isEmpty()) {
            paint.setColor(Color.GRAY);
            paint.setTextSize(16);
            renderCanvas.drawText((menuIndex + 1) + " / " + menuItems.size(), 120, 72, paint);

            String currentItem = menuItems.get(menuIndex);
            paint.setColor(Color.WHITE);
            paint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));

            float fontSize = 32f;
            paint.setTextSize(fontSize);
            while (fontSize > 16f && paint.measureText(currentItem) > 160f) {
                fontSize -= 2f;
                paint.setTextSize(fontSize);
            }
            renderCanvas.drawText(currentItem, 120, 135, paint);

            paint.setColor(Color.parseColor("#00E5FF"));
            paint.setTextSize(24);
            renderCanvas.drawText("◀          ▶", 120, 135, paint);
        }

        if (!menuFooter.isEmpty()) {
            paint.setColor(Color.GRAY);
            paint.setTextSize(16);
            paint.setTypeface(Typeface.DEFAULT);
            renderCanvas.drawText(menuFooter, 120, 195, paint);
        }

        renderToFramebufferInternal();
    }

    private void drawMediaScreenInternal() {
        renderCanvas.drawColor(Color.BLACK);

        if (cachedMediaArt != null && !cachedMediaArt.isRecycled()) {
            renderCanvas.drawBitmap(cachedMediaArt, null, fullScreenRect, null);
            renderToFramebufferInternal();
            return;
        }

        paint.reset();
        paint.setAntiAlias(true);
        paint.setSubpixelText(true);
        paint.setTextAlign(Paint.Align.CENTER);

        if (!mediaHeader.isEmpty()) {
            paint.setColor(Color.parseColor("#E91E63"));
            paint.setTextSize(20);
            paint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
            renderCanvas.drawText(mediaHeader, 120, 50, paint);
        }

        paint.setColor(Color.WHITE);
        paint.setTextSize(mediaTitle.length() > 10 ? 26 : 32);
        paint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        renderCanvas.drawText(mediaTitle, 120, mediaHeader.isEmpty() ? 110 : 120, paint);

        if (!mediaSub.isEmpty()) {
            paint.setColor(Color.LTGRAY);
            paint.setTextSize(20);
            paint.setTypeface(Typeface.DEFAULT);
            renderCanvas.drawText(mediaSub, 120, 155, paint);
        }

        if (mediaProgress >= 0) {
            paint.setColor(Color.DKGRAY);
            renderCanvas.drawRect(40, 185, 200, 191, paint);

            paint.setColor(Color.parseColor("#E91E63"));
            float w = (160f * Math.max(0, Math.min(100, mediaProgress))) / 100f;
            renderCanvas.drawRect(40, 185, 40 + w, 191, paint);
        }

        renderToFramebufferInternal();
    }

    private void drawAlertScreenInternal() {
        renderCanvas.drawColor(Color.BLACK);
        paint.reset();
        paint.setAntiAlias(true);
        paint.setSubpixelText(true);
        paint.setTextAlign(Paint.Align.CENTER);

        if (!alertTitle.isEmpty()) {
            paint.setColor(Color.parseColor(alertColor));
            paint.setTextSize(26);
            paint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
            renderCanvas.drawText(alertTitle, 120, 60, paint);
        }

        paint.setColor(Color.WHITE);
        paint.setTextSize(alertMsg.length() > 8 ? 26 : 36);
        paint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        renderCanvas.drawText(alertMsg, 120, alertTitle.isEmpty() ? 120 : 130, paint);

        if (!alertFooter.isEmpty()) {
            paint.setColor(Color.GRAY);
            paint.setTextSize(16);
            paint.setTypeface(Typeface.DEFAULT);
            renderCanvas.drawText(alertFooter, 120, 190, paint);
        }

        renderToFramebufferInternal();
    }

    // Bezpośredni zapis bufora do FB1 (RGB565 i lustrzane odbicie X)
    private void renderToFramebufferInternal() {
        if (!isScreenHardwarePowered) return;
        try {
            renderBmp.getPixels(pixelArray, 0, 240, 0, 0, 240, 240);

            int idx = 0;
            for (int y = 0; y < 240; y++) {
                int rowOffset = y * 240;
                for (int x = 239; x >= 0; x--) {
                    int p = pixelArray[rowOffset + x];
                    int r = (p >> 19) & 0x1F;
                    int g = (p >> 10) & 0x3F;
                    int b = (p >> 3) & 0x1F;
                    int rgb565 = (r << 11) | (g << 5) | b;

                    rawFbBuffer[idx++] = (byte) (rgb565 & 0xFF);
                    rawFbBuffer[idx++] = (byte) ((rgb565 >> 8) & 0xFF);
                }
            }

            if (fbAccessFile != null) {
                try {
                    fbAccessFile.seek(0);
                    fbAccessFile.write(rawFbBuffer);
                    return;
                } catch (Exception e) {
                    try { fbAccessFile.close(); } catch (Exception ignored) {}
                    fbAccessFile = null;
                }
            }

            try (FileOutputStream fos = new FileOutputStream("/dev/graphics/fb1")) {
                fos.write(rawFbBuffer);
                fos.flush();
            }
        } catch (Exception ignored) {}
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        running = false;

        if (inputMonitoringProc != null) {
            inputMonitoringProc.destroy();
            inputMonitoringProc = null;
        }

        try { unregisterReceiver(screenStateReceiver); } catch (Exception ignored) {}
        try { unregisterReceiver(configReceiver); } catch (Exception ignored) {}

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && audioRecordingCallback != null && audioManager != null) {
            try { audioManager.unregisterAudioRecordingCallback(audioRecordingCallback); } catch (Exception ignored) {}
        }

        mqttExecutor.shutdownNow();
        if (client != null) {
            try {
                if (client.isConnected()) client.disconnectForcibly(500);
                client.close();
            } catch (Exception ignored) {}
            client = null;
        }

        if (ttyOutStream != null) {
            try { ttyOutStream.close(); } catch (Exception ignored) {}
            ttyOutStream = null;
        }

        if (fbAccessFile != null) {
            try { fbAccessFile.close(); } catch (Exception ignored) {}
            fbAccessFile = null;
        }

        if (cachedMediaArt != null) {
            cachedMediaArt.recycle();
            cachedMediaArt = null;
        }

        hwExecutor.shutdownNow();

        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
            if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        } catch (Exception ignored) {}
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}