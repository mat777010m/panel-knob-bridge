package com.local.knobbridge;

import android.graphics.Bitmap;
import android.util.Base64;
import android.util.Log;
import org.json.JSONObject;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class WebServer {
    private static final String TAG = "KnobWeb";
    private final int port = 6767;
    private final ConfigManager cfg;
    private final DisplayRenderer renderer;
    private final HardwareManager hw;
    private final MqttManager mqtt;

    private ServerSocket serverSocket;
    private boolean running = true;
    private String lastFrameBase64 = "";

    public WebServer(ConfigManager cfg, DisplayRenderer rend, HardwareManager hw, MqttManager mqtt) {
        this.cfg = cfg;
        this.renderer = rend;
        this.hw = hw;
        this.mqtt = mqtt;

        // Podgląd na żywo aktualizowany przy każdej klatce
        renderer.setRenderListener(bmp -> {
            try {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                bmp.compress(Bitmap.CompressFormat.PNG, 90, baos);
                lastFrameBase64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);
            } catch (Exception ignored) {}
        });
    }

    public void start() {
        new Thread(() -> {
            try {
                serverSocket = new ServerSocket(port);
                Log.i(TAG, "Panel Web wystartował na porcie " + port);
                while (running) {
                    Socket sock = serverSocket.accept();
                    handleClient(sock);
                }
            } catch (Exception e) {
                if (running) Log.e(TAG, "Błąd serwera HTTP", e);
            }
        }).start();
    }

    private void handleClient(Socket sock) {
        new Thread(() -> {
            try {
                BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream()));
                OutputStream out = sock.getOutputStream();

                String line = in.readLine();
                if (line == null) return;

                String[] parts = line.split(" ");
                String method = parts[0];
                String path = parts[1];

                // Czytanie nagłówków
                String authHeader = "";
                int contentLength = 0;
                while ((line = in.readLine()) != null && !line.isEmpty()) {
                    if (line.toLowerCase().startsWith("authorization:")) {
                        authHeader = line.substring(14).trim();
                    } else if (line.toLowerCase().startsWith("content-length:")) {
                        contentLength = Integer.parseInt(line.substring(15).trim());
                    }
                }

                // Autoryzacja Basic Auth
                if (!checkAuth(authHeader)) {
                    send401(out);
                    sock.close();
                    return;
                }

                // API
                if (path.equals("/api/frame")) {
                    sendJson(out, "{\"image\":\"data:image/png;base64," + lastFrameBase64 + "\"}");
                } else if (path.equals("/api/config") && method.equals("GET")) {
                    sendJson(out, cfg.getConfig().toString());
                } else if (path.equals("/api/config") && method.equals("POST")) {
                    char[] body = new char[contentLength];
                    in.read(body, 0, contentLength);
                    boolean ok = cfg.save(new String(body));
                    if (ok) mqtt.start(); // Przeładowanie reguł i discovery
                    sendJson(out, "{\"success\":" + ok + "}");
                } else if (path.equals("/api/test_ui") && method.equals("POST")) {
                    char[] body = new char[contentLength];
                    in.read(body, 0, contentLength);
                    JSONObject j = new JSONObject(new String(body));
                    renderer.setTemplate(j.optString("template", "clock"), j);
                    sendJson(out, "{\"status\":\"ok\"}");
                } else {
                    // Strona główna z edytorem i okrągłym symulatorem
                    sendHtml(out, getWebUiHtml());
                }
                sock.close();
            } catch (Exception ignored) {}
        }).start();
    }

    private boolean checkAuth(String header) {
        if (!header.startsWith("Basic ")) return false;
        try {
            String b64 = header.substring(6);
            String creds = new String(Base64.decode(b64, Base64.DEFAULT), StandardCharsets.UTF_8);
            String[] p = creds.split(":", 2);

            JSONObject auth = cfg.getConfig().optJSONObject("auth");
            String u = auth != null ? auth.optString("user", "admin") : "admin";
            String pass = auth != null ? auth.optString("pass", "admin123") : "admin123";

            return u.equals(p[0]) && pass.equals(p[1]);
        } catch (Exception e) {
            return false;
        }
    }

    private void send401(OutputStream out) throws IOException {
        String res = "HTTP/1.1 401 Unauthorized\r\nWWW-Authenticate: Basic realm=\"KnobPanel\"\r\nContent-Length: 0\r\n\r\n";
        out.write(res.getBytes());
    }

    private void sendJson(OutputStream out, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: " + bytes.length + "\r\n\r\n";
        out.write(head.getBytes());
        out.write(bytes);
    }

    private void sendHtml(OutputStream out, String html) throws IOException {
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: " + bytes.length + "\r\n\r\n";
        out.write(head.getBytes());
        out.write(bytes);
    }

    // Wbudowany interfejs webowy (Ciemny motyw, symulator z ramką, edytor JSON)
    private String getWebUiHtml() {
        return "<!DOCTYPE html><html lang='pl'><head><meta charset='utf-8'>"
            + "<title>Knob Controller OS</title>"
            + "<style>"
            + "body{margin:0;background:#121212;color:#eee;font-family:sans-serif;display:flex;height:100vh;}"
            + ".left{width:360px;background:#1a1a1a;padding:20px;display:flex;flex-direction:column;align-items:center;border-right:1px solid #333;}"
            + ".right{flex:1;padding:20px;display:flex;flex-direction:column;}"
            + ".bezel{width:280px;height:280px;background:#2b2b2b;border-radius:50%;display:flex;align-items:center;justify-content:center;box-shadow:inset 0 0 15px #000, 0 10px 30px rgba(0,0,0,0.8);border:4px solid #444;position:relative;margin:20px 0;}"
            + ".screen-window{width:240px;height:240px;border-radius:50%;overflow:hidden;background:#000;display:flex;align-items:center;justify-content:center;box-shadow:inset 0 0 10px #000;}"
            + "#previewImg{width:240px;height:240px;display:block;}"
            + "textarea{width:100%;flex:1;background:#1e1e1e;color:#00e5ff;border:1px solid #333;border-radius:6px;font-family:monospace;padding:12px;font-size:14px;box-sizing:border-box;}"
            + "button{background:#00e5ff;color:#000;border:none;padding:12px 20px;font-weight:bold;cursor:pointer;border-radius:4px;margin-top:10px;}"
            + "button:hover{background:#00b4cc;}"
            + ".tabs{display:flex;gap:10px;margin-bottom:10px;}"
            + ".tab-btn{background:#333;color:#fff;padding:8px 16px;border-radius:4px;cursor:pointer;}"
            + ".tab-btn.active{background:#00e5ff;color:#000;}"
            + "</style></head><body>"
            + "<div class='left'>"
            + "<h2>Podgląd Gałki</h2>"
            + "<div class='bezel'><div class='screen-window'><img id='previewImg' src='' alt='Ekran'></div></div>"
            + "<p style='color:#888;font-size:13px;text-align:center;'>Czarna obwódka symuluje fizyczną ramkę gałki tnącą ekran 240x240</p>"
            + "</div>"
            + "<div class='right'>"
            + "<h2>Konfiguracja & Reguły (config.json)</h2>"
            + "<textarea id='jsonEditor'></textarea>"
            + "<div style='display:flex;gap:10px;'>"
            + "<button onclick='saveConfig()'>💾 Zapisz i Zastosuj</button>"
            + "<button style='background:#444;color:#fff;' onclick='loadConfig()'>🔄 Odśwież</button>"
            + "</div>"
            + "</div>"
            + "<script>"
            + "function refreshFrame(){fetch('/api/frame').then(r=>r.json()).then(d=>{if(d.image)document.getElementById('previewImg').src=d.image;});}"
            + "setInterval(refreshFrame, 800);"
            + "function loadConfig(){fetch('/api/config').then(r=>r.json()).then(d=>{document.getElementById('jsonEditor').value=JSON.stringify(d,null,2);});}"
            + "function saveConfig(){fetch('/api/config',{method:'POST',body:document.getElementById('jsonEditor').value}).then(r=>r.json()).then(d=>{alert(d.success?'Zapisano pomyślnie!':'Błąd formatu JSON');});}"
            + "loadConfig();"
            + "</script></body></html>";
    }

    public void stop() {
        this.running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
    }
}