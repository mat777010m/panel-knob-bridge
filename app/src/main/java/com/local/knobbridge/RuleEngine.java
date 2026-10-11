package com.local.knobbridge;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;

public class RuleEngine {
    private static final String TAG = "KnobRules";
    private final ConfigManager configMgr;
    private final HardwareManager hw;
    private final DisplayRenderer renderer;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private MqttManager mqtt;
    private Runnable screenOffTimer;

    public RuleEngine(ConfigManager cfg, HardwareManager hw, DisplayRenderer rend) {
        this.configMgr = cfg;
        this.hw = hw;
        this.renderer = rend;
    }

    public void setMqtt(MqttManager mqtt) {
        this.mqtt = mqtt;
    }

    // Wykonywanie reguł dla MQTT
    public void processMqtt(String topic, String payload) {
        try {
            JSONArray rules = configMgr.getConfig().optJSONArray("rules");
            if (rules != null) {
                for (int i = 0; i < rules.length(); i++) {
                    JSONObject r = rules.getJSONObject(i);
                    if ("mqtt".equals(r.optString("trigger")) && topic.equals(r.optString("topic"))) {
                        String reqPayload = r.optString("payload", "");
                        if (reqPayload.isEmpty() || reqPayload.equalsIgnoreCase(payload)) {
                            executeAction(r, payload);
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Błąd reguły MQTT", e);
        }
    }

    private void executeAction(JSONObject r, String triggerPayload) {
        String action = r.optString("action");
        switch (action) {
            case "screen_power_timer":
                int sec = r.optInt("seconds", 300);
                hw.setScreenPower(true);
                renderer.render();
                if (screenOffTimer != null) handler.removeCallbacks(screenOffTimer);
                screenOffTimer = () -> hw.setScreenPower(false);
                handler.postDelayed(screenOffTimer, (long) sec * 1000);
                break;

            case "uart_write":
                String tpl = r.optString("payload_template", triggerPayload);
                hw.writeUart(tpl.replace("{{value}}", triggerPayload));
                break;

            case "mic_mute":
                hw.setMicMute("true".equalsIgnoreCase(triggerPayload) || "1".equals(triggerPayload));
                renderer.render();
                break;
        }
    }

    // Obsługa gałki: zmiana wartości i 5s timeout
    private final Runnable timeoutCancelTask = () -> {
        String tpl = renderer.getCurrentTemplate();
        if ("dial".equals(tpl) || "menu".equals(tpl)) {
            if (mqtt != null) mqtt.publish("panel/knob/cancel", "{\"template\":\"" + tpl + "\"}", false);
        }
        renderer.setTemplate("clock", new JSONObject());
    };

    public void onKnobTurn(int delta) {
        if (!hw.isScreenPowered()) {
            hw.setScreenPower(true);
            renderer.render();
            return;
        }

        if (mqtt != null) mqtt.publish("panel/knob/action", delta > 0 ? "rotate_right" : "rotate_left", false);

        String tpl = renderer.getCurrentTemplate();
        JSONObject data = renderer.getCurrentData();

        if ("dial".equals(tpl)) {
            int val = data.optInt("value", 50);
            int min = data.optInt("min", 0);
            int max = data.optInt("max", 100);
            int step = data.optInt("step", 5);

            val = Math.max(min, Math.min(max, val + (delta * step)));
            try { data.put("value", val); } catch (Exception ignored) {}

            renderer.animateDial(val, min, max);
            if (mqtt != null) mqtt.publish("panel/knob/value", String.valueOf(val), false);
            resetTimeout(5000);
        } else if ("menu".equals(tpl)) {
            JSONArray arr = data.optJSONArray("items");
            if (arr != null && arr.length() > 0) {
                int idx = data.optInt("selected_index", 0);
                idx = (idx + delta + arr.length()) % arr.length();
                try { data.put("selected_index", idx); } catch (Exception ignored) {}
                renderer.render();
                resetTimeout(5000);
            }
        }
    }

    public void onKnobClick() {
        if (!hw.isScreenPowered()) {
            hw.setScreenPower(true);
            renderer.render();
            return;
        }

        if (mqtt != null) mqtt.publish("panel/knob/action", "click", false);

        String tpl = renderer.getCurrentTemplate();
        JSONObject data = renderer.getCurrentData();

        if ("dial".equals(tpl)) {
            if (mqtt != null) mqtt.publish("panel/knob/confirm", String.valueOf(data.optInt("value", 0)), false);
            renderer.setTemplate("clock", new JSONObject());
        } else if ("menu".equals(tpl)) {
            JSONArray arr = data.optJSONArray("items");
            int idx = data.optInt("selected_index", 0);
            if (arr != null && idx < arr.length()) {
                if (mqtt != null) mqtt.publish("panel/knob/menu_select", arr.optString(idx, ""), false);
            }
            renderer.setTemplate("clock", new JSONObject());
        } else if ("alert".equals(tpl)) {
            renderer.setAlert(false, "");
            renderer.setTemplate("clock", new JSONObject());
        }
    }

    private void resetTimeout(int ms) {
        handler.removeCallbacks(timeoutCancelTask);
        if (ms > 0) handler.postDelayed(timeoutCancelTask, ms);
    }
}