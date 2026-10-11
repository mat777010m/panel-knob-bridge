package com.local.knobbridge;

import android.animation.ValueAnimator;
import android.graphics.*;
import android.os.Handler;
import android.os.Looper;
import android.view.animation.DecelerateInterpolator;
import org.json.JSONArray;
import org.json.JSONObject;

public class DisplayRenderer {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final HardwareManager hw;

    // Stałe bufory pamięci - ZERO alokacji podczas animacji/rysowania
    private final Bitmap canvasBmp = Bitmap.createBitmap(240, 240, Bitmap.Config.RGB_565);
    private final Canvas canvas = new Canvas(canvasBmp);
    private final int[] pixelsBuf = new int[240 * 240];
    private final byte[] rawFbBuf = new byte[240 * 240 * 2];

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arcRect = new RectF();

    private String currentLayout = "stack";
    private JSONObject currentData = new JSONObject();

    // Animacja dial
    private float currentSweep = 0f;
    private ValueAnimator dialAnimator;

    public interface RenderListener {
        void onFrameReady(Bitmap bmp);
    }
    private RenderListener listener;

    public DisplayRenderer(HardwareManager hw) {
        this.hw = hw;
        arcPaint.setStyle(Paint.Style.STROKE);
    }

    public void setRenderListener(RenderListener l) {
        this.listener = l;
    }

    public synchronized void setLayout(String layout, JSONObject data) {
        this.currentLayout = layout != null ? layout : "stack";
        this.currentData = data != null ? data : new JSONObject();
        render();
    }

    public void animateDialSweep(float targetSweep) {
        handler.post(() -> {
            if (dialAnimator != null && dialAnimator.isRunning()) dialAnimator.cancel();
            dialAnimator = ValueAnimator.ofFloat(currentSweep, targetSweep);
            dialAnimator.setDuration(90); // 90ms = idealna responsywność bez smużenia
            dialAnimator.setInterpolator(new DecelerateInterpolator());
            dialAnimator.addUpdateListener(anim -> {
                currentSweep = (float) anim.getAnimatedValue();
                render();
            });
            dialAnimator.start();
        });
    }

    public synchronized void render() {
        if (!hw.isScreenPowered()) return;

        // Czyść ekran do koloru tła z JSON (domyślnie czarny)
        int bgColor = parseHex(currentData.optString("bg_color", "#000000"), Color.BLACK);
        canvas.drawColor(bgColor);

        // Opcjonalny pasek statusu w rogu (np. MIC OFF, ikona alarmu)
        drawCustomStatusBar();

        switch (currentLayout) {
            case "dial": drawDialSkeleton(); break;
            case "carousel": drawCarouselSkeleton(); break;
            case "progress": drawProgressSkeleton(); break;
            case "stack":
            default: drawStackSkeleton(); break;
        }

        pushToHardware();
    }

    // 1. SZKIELET PIONOWY (STACK) - uniwersalny na zegary, sensory, alerty
    private void drawStackSkeleton() {
        paint.setTextAlign(Paint.Align.CENTER);

        // Od 1 do 4 w pełni konfigurowalnych linii
        for (int i = 1; i <= 4; i++) {
            String text = currentData.optString("line" + i, "");
            if (!text.isEmpty()) {
                paint.setColor(parseHex(currentData.optString("color" + i, "#FFFFFF"), Color.WHITE));
                paint.setTextSize(currentData.optInt("size" + i, 24));
                paint.setTypeface(currentData.optBoolean("bold" + i, false) ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
                int y = currentData.optInt("y" + i, 50 * i);
                canvas.drawText(text, 120, y, paint);
            }
        }
    }

    // 2. SZKIELET POKRĘTŁA (DIAL) - mechanika łukowa
    private void drawDialSkeleton() {
        // Podziałka kreskowa dookoła (opcjonalna)
        if (currentData.optBoolean("show_ticks", true)) {
            paint.setColor(parseHex(currentData.optString("ticks_color", "#333333"), Color.DKGRAY));
            paint.setStrokeWidth(currentData.optInt("ticks_width", 2));
            int step = currentData.optInt("ticks_step", 15);
            for (int a = 135; a <= 405; a += step) {
                double rad = Math.toRadians(a);
                canvas.drawLine(
                    (float)(120 + 96 * Math.cos(rad)), (float)(120 + 96 * Math.sin(rad)),
                    (float)(120 + 104 * Math.cos(rad)), (float)(120 + 104 * Math.sin(rad)),
                    paint
                );
            }
        }

        // Geometria łuku z JSON
        int arcInset = currentData.optInt("arc_inset", 28);
        arcRect.set(arcInset, arcInset, 240 - arcInset, 240 - arcInset);
        int strokeW = currentData.optInt("arc_width", 14);

        // Tło łuku
        arcPaint.setStrokeWidth(strokeW);
        arcPaint.setColor(parseHex(currentData.optString("track_color", "#222222"), Color.DKGRAY));
        arcPaint.setStrokeCap(Paint.Cap.BUTT);
        canvas.drawArc(arcRect, 135, 270, false, arcPaint);

        // Aktywny pasek animowany
        arcPaint.setColor(parseHex(currentData.optString("active_color", "#00E5FF"), Color.CYAN));
        arcPaint.setStrokeCap(currentData.optBoolean("round_caps", true) ? Paint.Cap.ROUND : Paint.Cap.BUTT);
        canvas.drawArc(arcRect, 135, currentSweep, false, arcPaint);

        // Czyste etykiety tekstowe (top, center, bottom)
        paint.setTextAlign(Paint.Align.CENTER);

        String top = currentData.optString("top_text", "");
        if (!top.isEmpty()) {
            paint.setColor(parseHex(currentData.optString("top_color", "#AAAAAA"), Color.LTGRAY));
            paint.setTextSize(currentData.optInt("top_size", 20));
            paint.setTypeface(Typeface.DEFAULT);
            canvas.drawText(top, 120, currentData.optInt("top_y", 78), paint);
        }

        String center = currentData.optString("center_text", "");
        if (!center.isEmpty()) {
            paint.setColor(parseHex(currentData.optString("center_color", "#FFFFFF"), Color.WHITE));
            paint.setTextSize(currentData.optInt("center_size", 52));
            paint.setTypeface(Typeface.DEFAULT_BOLD);
            canvas.drawText(center, 120, currentData.optInt("center_y", 135), paint);
        }

        String bottom = currentData.optString("bottom_text", "");
        if (!bottom.isEmpty()) {
            paint.setColor(parseHex(currentData.optString("bottom_color", "#888888"), Color.GRAY));
            paint.setTextSize(currentData.optInt("bottom_size", 16));
            paint.setTypeface(Typeface.DEFAULT);
            canvas.drawText(bottom, 120, currentData.optInt("bottom_y", 185), paint);
        }
    }

    // 3. SZKIELET KARUZELI (CAROUSEL)
    private void drawCarouselSkeleton() {
        paint.setTextAlign(Paint.Align.CENTER);

        String title = currentData.optString("title", "");
        if (!title.isEmpty()) {
            paint.setColor(parseHex(currentData.optString("title_color", "#FFA726"), Color.YELLOW));
            paint.setTextSize(currentData.optInt("title_size", 20));
            paint.setTypeface(Typeface.DEFAULT_BOLD);
            canvas.drawText(title, 120, currentData.optInt("title_y", 50), paint);
        }

        JSONArray items = currentData.optJSONArray("items");
        int idx = currentData.optInt("selected_index", 0);
        if (items != null && items.length() > 0) {
            if (currentData.optBoolean("show_counter", true)) {
                paint.setColor(parseHex(currentData.optString("counter_color", "#777777"), Color.GRAY));
                paint.setTextSize(16);
                paint.setTypeface(Typeface.DEFAULT);
                canvas.drawText((idx + 1) + " / " + items.length(), 120, 75, paint);
            }

            paint.setColor(parseHex(currentData.optString("item_color", "#FFFFFF"), Color.WHITE));
            paint.setTextSize(currentData.optInt("item_size", 30));
            paint.setTypeface(Typeface.DEFAULT_BOLD);
            canvas.drawText(items.optString(idx, ""), 120, 135, paint);

            String arrows = currentData.optString("arrows_text", "◀          ▶");
            if (!arrows.isEmpty()) {
                paint.setColor(parseHex(currentData.optString("arrows_color", "#00E5FF"), Color.CYAN));
                paint.setTextSize(24);
                paint.setTypeface(Typeface.DEFAULT);
                canvas.drawText(arrows, 120, 135, paint);
            }
        }
    }

    // 4. SZKIELET POSTĘPU (PROGRESS)
    private void drawProgressSkeleton() {
        paint.setTextAlign(Paint.Align.CENTER);

        String main = currentData.optString("main_text", "");
        if (!main.isEmpty()) {
            paint.setColor(parseHex(currentData.optString("main_color", "#FFFFFF"), Color.WHITE));
            paint.setTextSize(currentData.optInt("main_size", 30));
            paint.setTypeface(Typeface.DEFAULT_BOLD);
            canvas.drawText(main, 120, currentData.optInt("main_y", 105), paint);
        }

        String sub = currentData.optString("sub_text", "");
        if (!sub.isEmpty()) {
            paint.setColor(parseHex(currentData.optString("sub_color", "#AAAAAA"), Color.LTGRAY));
            paint.setTextSize(currentData.optInt("sub_size", 20));
            paint.setTypeface(Typeface.DEFAULT);
            canvas.drawText(sub, 120, currentData.optInt("sub_y", 145), paint);
        }

        int pct = currentData.optInt("progress_pct", -1);
        if (pct >= 0) {
            int bx = currentData.optInt("bar_x", 40);
            int by = currentData.optInt("bar_y", 180);
            int bw = currentData.optInt("bar_w", 160);
            int bh = currentData.optInt("bar_h", 6);

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(parseHex(currentData.optString("bar_bg_color", "#333333"), Color.DKGRAY));
            canvas.drawRect(bx, by, bx + bw, by + bh, paint);

            paint.setColor(parseHex(currentData.optString("bar_color", "#E91E63"), Color.MAGENTA));
            float fillW = (bw * Math.max(0, Math.min(100, pct))) / 100f;
            canvas.drawRect(bx, by, bx + fillW, by + bh, paint);
        }
    }

    private void drawCustomStatusBar() {
        JSONObject status = currentData.optJSONObject("status_bar");
        if (status != null) {
            String left = status.optString("left_text", "");
            if (!left.isEmpty()) {
                paint.setTextAlign(Paint.Align.LEFT);
                paint.setColor(parseHex(status.optString("left_color", "#FF1744"), Color.RED));
                paint.setTextSize(status.optInt("left_size", 16));
                paint.setTypeface(Typeface.DEFAULT_BOLD);
                canvas.drawText(left, 20, 32, paint);
            }

            String right = status.optString("right_text", "");
            if (!right.isEmpty()) {
                paint.setTextAlign(Paint.Align.RIGHT);
                paint.setColor(parseHex(status.optString("right_color", "#FF5252"), Color.RED));
                paint.setTextSize(status.optInt("right_size", 15));
                paint.setTypeface(Typeface.DEFAULT_BOLD);
                canvas.drawText(right, 220, 32, paint);
            }
        }
    }

    // Bezpośredni zrzut do fb1 bez alokacji
    private void pushToHardware() {
        canvasBmp.getPixels(pixelsBuf, 0, 240, 0, 0, 240, 240);
        int idx = 0;
        for (int y = 0; y < 240; y++) {
            int rowOffset = y * 240;
            for (int x = 239; x >= 0; x--) {
                int p = pixelsBuf[rowOffset + x];
                int rgb565 = (((p >> 16) & 0xF8) << 8) | (((p >> 8) & 0xFC) << 3) | ((p & 0xFF) >> 3);
                rawFbBuf[idx++] = (byte) (rgb565 & 0xFF);
                rawFbBuf[idx++] = (byte) ((rgb565 >> 8) & 0xFF);
            }
        }
        hw.writeRawFramebuffer(rawFbBuf);
        if (listener != null) listener.onFrameReady(canvasBmp);
    }

    private int parseHex(String hex, int fallback) {
        try {
            return Color.parseColor(hex);
        } catch (Exception e) {
            return fallback;
        }
    }

    public String getCurrentLayout() { return currentLayout; }
    public JSONObject getCurrentData() { return currentData; }
}