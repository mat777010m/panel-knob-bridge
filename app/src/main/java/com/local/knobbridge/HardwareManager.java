package com.local.knobbridge;

import android.content.Context;
import android.media.AudioManager;
import android.util.Log;
import java.io.*;

public class HardwareManager {
    private static final String TAG = "KnobHardware";
    private final Context context;
    private boolean isScreenPowered = true;
    private boolean isLedRingOn = false;
    private boolean isMicMuted = false;
    private boolean running = true;
    private InputCallback inputCallback;

    private OutputStream fbOutputStream;
    private OutputStream uartOutputStream;

    public synchronized void writeUart(String payload) {
        runRoot("echo -e -n '" + payload + "' > /dev/ttyAS4");
    }

    public interface InputCallback {
        void onRotate(int delta);
        void onClick();
    }

    public HardwareManager(Context context) {
        this.context = context;
        initFramebufferPipe();
        initUartPort();
    }

    // Inicjalizacja UART 9600 baud pod DSP Yamaha YDA174
    private void initUartPort() {
        try {
            // Ustawiamy port na 9600 8N1 raw przez roota
            runRoot("chmod 666 /dev/ttyAS4 && stty -F /dev/ttyAS4 9600 cs8 -cstopb -parenb raw -echo");

            File uartFile = new File("/dev/ttyAS4");
            if (uartFile.canWrite()) {
                uartOutputStream = new BufferedOutputStream(new FileOutputStream(uartFile), 64);
                Log.i(TAG, "Port DSP /dev/ttyAS4 zainicjalizowany na 9600 baud");
            }
        } catch (Exception e) {
            Log.e(TAG, "Błąd otwierania /dev/ttyAS4", e);
        }
    }

    // Wysyłanie 6-bajtowej ramki binarnej do DSP
    public synchronized void sendDspCommand(int cmd, int val) {
        val = Math.max(0, Math.min(16, val)); // Bezpieczny zakres 0-16
        int cs = (0xFA + 0x00 + cmd) & 0xFF;  // Suma kontrolna wg specyfikacji

        byte[] frame = new byte[] {
            (byte) 0xFA,
            (byte) 0x00,
            (byte) (cmd & 0xFF),
            (byte) (val & 0xFF),
            (byte) (cs & 0xFF),
            (byte) 0xFE
        };

        try {
            if (uartOutputStream == null) initUartPort();
            if (uartOutputStream != null) {
                uartOutputStream.write(frame);
                uartOutputStream.flush();
            }
        } catch (Exception e) {
            Log.e(TAG, "Błąd wysyłania ramki do DSP", e);
            uartOutputStream = null;
        }
    }

    // Szybkie metody pomocnicze dla stref
    public void setZone1Volume(int vol) { sendDspCommand(0x55, vol); }
    public void setZone2Volume(int vol) { sendDspCommand(0x56, vol); }
    public void setZone1Bass(int val)   { sendDspCommand(0x4A, val); }
    public void setZone1Treble(int val) { sendDspCommand(0x49, val); }
    public void setZone2Bass(int val)   { sendDspCommand(0x4C, val); }
    public void setZone2Treble(int val) { sendDspCommand(0x4B, val); }

    private void initFramebufferPipe() {
        try {
            File fb = new File("/dev/graphics/fb1");
            if (fb.canWrite()) {
                fbOutputStream = new BufferedOutputStream(new FileOutputStream(fb), 240 * 240 * 2);
            } else {
                Process p = Runtime.getRuntime().exec(new String[]{"su", "0"});
                OutputStream os = p.getOutputStream();
                os.write("chmod 666 /dev/graphics/fb1\ncat > /dev/graphics/fb1\n".getBytes());
                os.flush();
                fbOutputStream = new BufferedOutputStream(os, 240 * 240 * 2);
            }
        } catch (Exception e) {
            Log.e(TAG, "Błąd otwierania fb1", e);
        }
    }

    public synchronized void writeRawFramebuffer(byte[] raw) {
        if (!isScreenPowered) return;
        try {
            if (fbOutputStream == null) initFramebufferPipe();
            if (fbOutputStream != null) {
                fbOutputStream.write(raw);
                fbOutputStream.flush();
            }
        } catch (Exception e) {
            fbOutputStream = null;
        }
    }

    public void startInputListening(InputCallback cb) {
        this.inputCallback = cb;
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
                            if (line.contains("KEY_F1") && inputCallback != null) inputCallback.onRotate(-1);
                            else if (line.contains("KEY_F2") && inputCallback != null) inputCallback.onRotate(1);
                            else if (line.contains("KEY_F3") && inputCallback != null) inputCallback.onClick();
                        }
                    }
                } catch (Exception e) {
                    try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
                }
            }
        }).start();
    }

    public synchronized void setScreenPower(boolean on) {
        this.isScreenPowered = on;
        runRoot(on ? "echo on > /proc/vendor/lcd_ctrl && echo 0 > /sys/class/graphics/fb1/blank"
                   : "echo off > /proc/vendor/lcd_ctrl && echo 4 > /sys/class/graphics/fb1/blank");
    }

    public synchronized void setLedRing(boolean on) {
        this.isLedRingOn = on;
        runRoot(on ? "echo on > /proc/vendor/led_ctrl" : "echo off > /proc/vendor/led_ctrl");
    }

    public synchronized void setMicMute(boolean mute) {
        this.isMicMuted = mute;
        try {
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) am.setMicrophoneMute(mute);
        } catch (Exception ignored) {}
    }

    private void runRoot(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "0"});
            OutputStream os = p.getOutputStream();
            os.write((cmd + "\nexit\n").getBytes());
            os.flush();
            p.waitFor();
        } catch (Exception ignored) {}
    }

    public boolean isScreenPowered() { return isScreenPowered; }
    public boolean isLedRingOn() { return isLedRingOn; }
    public boolean isMicMuted() { return isMicMuted; }

    public void stop() {
        this.running = false;
        try { if (fbOutputStream != null) fbOutputStream.close(); } catch (Exception ignored) {}
        try { if (uartOutputStream != null) uartOutputStream.close(); } catch (Exception ignored) {}
    }
}