package com.local.knobbridge;

import android.content.Context;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;

public class ConfigManager {
    private static final String TAG = "KnobConfig";
    private final File configFile;
    private JSONObject config;

    public ConfigManager(Context context) {
        this.configFile = new File(context.getFilesDir(), "config.json");
        load();
    }

    public synchronized void load() {
        if (!configFile.exists()) {
            config = createDefaultConfig();
            save();
            return;
        }
        try (BufferedReader br = new BufferedReader(new FileReader(configFile))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            config = new JSONObject(sb.toString());
        } catch (Exception e) {
            Log.e(TAG, "Błąd odczytu config.json, przywracam domyślny", e);
            config = createDefaultConfig();
        }
    }

    public synchronized boolean save(String jsonStr) {
        try {
            JSONObject parsed = new JSONObject(jsonStr);
            this.config = parsed;
            return save();
        } catch (Exception e) {
            Log.e(TAG, "Niepoprawny format JSON", e);
            return false;
        }
    }

    public synchronized boolean save() {
        try (FileWriter fw = new FileWriter(configFile)) {
            fw.write(config.toString(2));
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Błąd zapisu config.json", e);
            return false;
        }
    }

    public synchronized JSONObject getConfig() {
        return config;
    }

    private JSONObject createDefaultConfig() {
        try {
            JSONObject root = new JSONObject();

            JSONObject auth = new JSONObject();
            auth.put("user", "admin");
            auth.put("pass", "admin123");
            root.put("auth", auth);

            JSONObject mqtt = new JSONObject();
            mqtt.put("broker", "tcp://192.168.0.7:1883");
            mqtt.put("user", "admin");
            mqtt.put("pass", "admin");
            mqtt.put("base_topic", "panel");
            root.put("mqtt", mqtt);

            JSONArray rules = new JSONArray();

            JSONObject r1 = new JSONObject();
            r1.put("trigger", "mqtt");
            r1.put("topic", "kuchnia/ruch");
            r1.put("payload", "on");
            r1.put("action", "screen_power_timer");
            r1.put("seconds", 300);
            rules.put(r1);

            JSONObject r2 = new JSONObject();
            r2.put("trigger", "mqtt");
            r2.put("topic", "panel/hardware/uart");
            r2.put("action", "uart_write");
            rules.put(r2);

            root.put("rules", rules);

            JSONArray discovery = new JSONArray();
            JSONObject d1 = new JSONObject();
            d1.put("type", "switch");
            d1.put("id", "mic_mute");
            d1.put("name", "Wyciszenie Mikrofonu");
            d1.put("cmd_topic", "panel/hardware/mic/mute");
            d1.put("state_topic", "panel/hardware/mic/state");
            d1.put("icon", "mdi:microphone-off");
            discovery.put(d1);

            root.put("discovery", discovery);
            return root;
        } catch (Exception e) {
            return new JSONObject();
        }
    }
}