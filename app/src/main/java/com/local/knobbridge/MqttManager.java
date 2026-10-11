package com.local.knobbridge;

import android.util.Log;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.json.JSONArray;
import org.json.JSONObject;

public class MqttManager {
    private static final String TAG = "KnobMqtt";

    private final ConfigManager cfg;
    private final RuleEngine rules;
    private final DisplayRenderer renderer;
    private final HardwareManager hw;

    private MqttClient client;
    private boolean isConnected = false;

    public MqttManager(ConfigManager cfg, RuleEngine rules, DisplayRenderer rend, HardwareManager hw) {
        this.cfg = cfg;
        this.rules = rules;
        this.renderer = rend;
        this.hw = hw;
    }

    public void start() {
        new Thread(() -> {
            try {
                JSONObject mCfg = cfg.getConfig().optJSONObject("mqtt");
                if (mCfg == null) return;

                String broker = mCfg.optString("broker", "tcp://192.168.0.7:1883");
                String user = mCfg.optString("user", "");
                String pass = mCfg.optString("pass", "");

                if (client != null && client.isConnected()) {
                    client.disconnect();
                }
                client = new MqttClient(broker, "KnobModular_" + System.currentTimeMillis(), new MemoryPersistence());

                MqttConnectOptions opts = new MqttConnectOptions();
                opts.setCleanSession(true);
                opts.setAutomaticReconnect(true);
                opts.setKeepAliveInterval(20);
                if (!user.isEmpty()) {
                    opts.setUserName(user);
                    opts.setPassword(pass.toCharArray());
                }

                client.setCallback(new MqttCallbackExtended() {
                    @Override
                    public void connectComplete(boolean reconnect, String serverURI) {
                        isConnected = true;
                        subscribeTopics();
                        publishDynamicDiscovery();
                    }
                    @Override
                    public void connectionLost(Throwable cause) {
                        isConnected = false;
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
                Log.e(TAG, "Błąd startu MQTT", e);
            }
        }).start();
    }

    private void subscribeTopics() {
        try {
            client.subscribe("panel/#");
            client.subscribe("kuchnia/#");
        } catch (Exception ignored) {}
    }

    private void handleMessage(String topic, String payload) {
        try {
            if ("panel/ui/set".equals(topic)) {
                JSONObject j = new JSONObject(payload);
                renderer.setLayout(j.optString("layout", j.optString("template", "clock")), j);
                return;
            }

            if ("panel/display/power".equals(topic)) {
                hw.setScreenPower("on".equalsIgnoreCase(payload));
                renderer.render();
                return;
            }
            if ("panel/led/set".equals(topic)) {
                hw.setLedRing("on".equalsIgnoreCase(payload));
                return;
            }
            if ("panel/hardware/mic/mute".equals(topic)) {
                boolean mute = "true".equalsIgnoreCase(payload) || "1".equals(payload);
                hw.setMicMute(mute);
                publish("panel/hardware/mic/state", mute ? "true" : "false", true);
                renderer.render();
                return;
            }

            if ("panel/audio/zone1/volume".equals(topic)) {
                int vol = Integer.parseInt(payload);
                hw.setZone1Volume(vol);
                publish("panel/audio/zone1/volume/state", String.valueOf(vol), true);
                return;
            }
            if ("panel/audio/zone2/volume".equals(topic)) {
                int vol = Integer.parseInt(payload);
                hw.setZone2Volume(vol);
                publish("panel/audio/zone2/volume/state", String.valueOf(vol), true);
                return;
            }
            if ("panel/audio/zone1/bass".equals(topic)) {
                hw.setZone1Bass(Integer.parseInt(payload));
                return;
            }
            if ("panel/audio/zone1/treble".equals(topic)) {
                hw.setZone1Treble(Integer.parseInt(payload));
                return;
            }

            rules.processMqtt(topic, payload);
        } catch (Exception e) {
            Log.e(TAG, "Błąd obsługi MQTT", e);
        }
    }

    private void publishDynamicDiscovery() {
        try {
            JSONObject dev = new JSONObject();
            dev.put("identifiers", new JSONArray().put("knob_panel_engine_v4"));
            dev.put("name", "Gałka Panel");
            dev.put("manufacturer", "Smart Home Lab");

            JSONObject ev = new JSONObject();
            ev.put("name", "Zdarzenie Gałki");
            ev.put("unique_id", "knob_event_trigger");
            ev.put("state_topic", "panel/knob/event");
            ev.put("event_types", new JSONArray().put("rotate_left").put("rotate_right").put("click"));
            ev.put("device", dev);
            publish("homeassistant/event/panel_knob/action_event/config", ev.toString(), true);

            JSONObject z1 = new JSONObject();
            z1.put("name", "Głośność Strefa 1");
            z1.put("unique_id", "knob_dsp_zone1_vol");
            z1.put("command_topic", "panel/audio/zone1/volume");
            z1.put("state_topic", "panel/audio/zone1/volume/state");
            z1.put("min", 0);
            z1.put("max", 16);
            z1.put("step", 1);
            z1.put("icon", "mdi:volume-high");
            z1.put("device", dev);
            publish("homeassistant/number/panel_knob/zone1_volume/config", z1.toString(), true);

            JSONObject z2 = new JSONObject();
            z2.put("name", "Głośność Strefa 2");
            z2.put("unique_id", "knob_dsp_zone2_vol");
            z2.put("command_topic", "panel/audio/zone2/volume");
            z2.put("state_topic", "panel/audio/zone2/volume/state");
            z2.put("min", 0);
            z2.put("max", 16);
            z2.put("step", 1);
            z2.put("icon", "mdi:volume-high");
            z2.put("device", dev);
            publish("homeassistant/number/panel_knob/zone2_volume/config", z2.toString(), true);

            JSONArray list = cfg.getConfig().optJSONArray("discovery");
            if (list != null) {
                for (int i = 0; i < list.length(); i++) {
                    JSONObject item = list.getJSONObject(i);
                    String type = item.optString("type", "switch");
                    String id = item.optString("id");

                    JSONObject disc = new JSONObject();
                    disc.put("name", item.optString("name"));
                    disc.put("unique_id", "knob_" + id);
                    disc.put("command_topic", item.optString("cmd_topic"));
                    disc.put("state_topic", item.optString("state_topic"));
                    disc.put("icon", item.optString("icon", "mdi:toggle-switch"));
                    disc.put("device", dev);

                    publish("homeassistant/" + type + "/panel_knob/" + id + "/config", disc.toString(), true);
                }
            }
        } catch (Exception ignored) {}
    }

    public void publish(String topic, String payload, boolean retained) {
        try {
            if (client != null && client.isConnected()) {
                MqttMessage msg = new MqttMessage(payload.getBytes());
                msg.setRetained(retained);
                client.publish(topic, msg);
            }
        } catch (Exception ignored) {}
    }

    public boolean isConnected() { return isConnected; }

    public void stop() {
        try {
            if (client != null) client.disconnect();
        } catch (Exception ignored) {}
    }
}