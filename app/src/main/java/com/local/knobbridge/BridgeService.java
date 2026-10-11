package com.local.knobbridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.WifiManager;
import android.os.IBinder;
import android.os.PowerManager;

public class BridgeService extends Service {
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    private ConfigManager configMgr;
    private HardwareManager hardwareMgr;
    private DisplayRenderer renderer;
    private RuleEngine ruleEngine;
    private MqttManager mqttMgr;
    private WebServer webServer;

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String act = intent.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(act)) {
                hardwareMgr.setScreenPower(false);
            } else if (Intent.ACTION_SCREEN_ON.equals(act)) {
                hardwareMgr.setScreenPower(true);
                renderer.render();
            } else if (Intent.ACTION_TIME_TICK.equals(act)) {
                if ("clock".equals(renderer.getCurrentLayout()) && hardwareMgr.isScreenPowered()) {
                    renderer.render();
                }
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        startForeground();
        acquireLocks();

        configMgr = new ConfigManager(this);
        hardwareMgr = new HardwareManager(this);
        renderer = new DisplayRenderer(hardwareMgr);
        ruleEngine = new RuleEngine(configMgr, hardwareMgr, renderer);
        mqttMgr = new MqttManager(configMgr, ruleEngine, renderer, hardwareMgr);
        ruleEngine.setMqtt(mqttMgr);

        webServer = new WebServer(configMgr, renderer, hardwareMgr, mqttMgr);

        hardwareMgr.setScreenPower(true);
        renderer.render();

        hardwareMgr.startInputListening(new HardwareManager.InputCallback() {
            @Override
            public void onRotate(int delta) { ruleEngine.onKnobTurn(delta); }
            @Override
            public void onClick() { ruleEngine.onKnobClick(); }
        });

        mqttMgr.start();
        webServer.start();

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_TIME_TICK);
        registerReceiver(screenReceiver, filter);
    }

    private void acquireLocks() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "KnobBridge::Cpu");
                wakeLock.acquire();
            }
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "KnobBridge::Wifi");
                wifiLock.acquire();
            }
        } catch (Exception ignored) {}
    }

    private void startForeground() {
        NotificationChannel c = new NotificationChannel("knob", "Knob Service", NotificationManager.IMPORTANCE_LOW);
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(c);
        startForeground(101, new Notification.Builder(this, "knob")
                .setContentTitle("Knob OS Active (Port 6767)")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .build());
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(screenReceiver); } catch (Exception ignored) {}
        if (webServer != null) webServer.stop();
        if (mqttMgr != null) mqttMgr.stop();
        if (hardwareMgr != null) hardwareMgr.stop();
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
            if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        } catch (Exception ignored) {}
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}