package com.weblink.sdk;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Renders a dynamic automotive dashboard interface directly onto a Canvas
 * for streaming to the WebLink car stereo display.
 */
public class WebLinkWaitingRenderer {

    private static final SimpleDateFormat TIME_FMT = new SimpleDateFormat("HH:mm:ss", Locale.US);

    public static class DashboardState {
        public String modelName = "Pioneer Headunit";
        public int colorTheme = 0; // 0 = Blue, 1 = Emerald, 2 = Violet, 3 = Amber
        public boolean isTouching = false;
        public float touchX = -1;
        public float touchY = -1;
        public int touchState = 0;
        public long lastTouchTime = 0;
        public int touchCount = 0;
    }

    public static void renderAutomotiveDashboard(Canvas canvas, int width, int height, long frameIndex, DashboardState state) {
        int w = width > 0 ? width : 800;
        int h = height > 0 ? height : 480;

        // Theme colors
        int primaryColor;
        int accentColor;
        int bgDark = Color.parseColor("#0B1120");

        if (state == null) state = new DashboardState();

        switch (state.colorTheme % 4) {
            case 1:
                primaryColor = Color.parseColor("#10B981"); // Emerald
                accentColor = Color.parseColor("#34D399");
                break;
            case 2:
                primaryColor = Color.parseColor("#8B5CF6"); // Violet
                accentColor = Color.parseColor("#A78BFA");
                break;
            case 3:
                primaryColor = Color.parseColor("#F59E0B"); // Amber
                accentColor = Color.parseColor("#FBBF24");
                break;
            default:
                primaryColor = Color.parseColor("#0284C7"); // Sky Blue
                accentColor = Color.parseColor("#38BDF8");
                break;
        }

        // 1. Dark Slate Background
        canvas.drawColor(bgDark);

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        // 2. Subtle Grid
        paint.setColor(Color.parseColor("#1E293B"));
        paint.setStrokeWidth(1.5f);
        for (int x = 0; x < w; x += 80) {
            canvas.drawLine(x, 0, x, h, paint);
        }
        for (int y = 0; y < h; y += 80) {
            canvas.drawLine(0, y, w, y, paint);
        }

        // 3. Top Status Bar
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.parseColor("#111827"));
        canvas.drawRect(0, 0, w, 56, paint);

        // Status bar bottom border
        paint.setColor(primaryColor);
        paint.setStrokeWidth(2f);
        canvas.drawLine(0, 56, w, 56, paint);

        // Brand / Title
        paint.setColor(Color.WHITE);
        paint.setTextSize(22);
        paint.setFakeBoldText(true);
        paint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText("⚡ WebLink Standalone PoC", 24, 36, paint);

        // Live Clock
        String timeStr = TIME_FMT.format(new Date());
        paint.setTextAlign(Paint.Align.RIGHT);
        paint.setColor(Color.parseColor("#E2E8F0"));
        canvas.drawText(timeStr, w - 24, 36, paint);

        // 4. Center Dashboard Card
        RectF card = new RectF(48, 80, w - 48, h - 88);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.parseColor("#1E293B"));
        canvas.drawRoundRect(card, 20f, 20f, paint);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2.5f);
        paint.setColor(primaryColor);
        canvas.drawRoundRect(card, 20f, 20f, paint);

        // Center Pulsing Indicator Ring
        float centerX = w * 0.5f;
        float centerY = h * 0.46f;
        float pulse = (float) (Math.sin(frameIndex * 0.1) * 8.0);
        float baseRadius = 50f + pulse;

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(3f);
        paint.setColor(accentColor);
        canvas.drawCircle(centerX, centerY, baseRadius, paint);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(primaryColor);
        canvas.drawCircle(centerX, centerY, 32f, paint);

        // Center status text
        paint.setColor(Color.WHITE);
        paint.setTextSize(28);
        paint.setFakeBoldText(true);
        paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText("STREAM ACTIVE", centerX, centerY + 75, paint);

        paint.setColor(Color.parseColor("#94A3B8"));
        paint.setTextSize(16);
        paint.setFakeBoldText(false);
        String details = state.modelName + " • " + w + "x" + h + " @ 30fps • Frame #" + frameIndex;
        canvas.drawText(details, centerX, centerY + 105, paint);

        // 5. Interactive Touch Indicators
        if (state.touchX >= 0 && state.touchY >= 0) {
            long now = System.currentTimeMillis();
            if (now - state.lastTouchTime < 3000) {
                // Touch ripple
                float touchRadius = state.isTouching ? 36f : 24f;
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(Color.parseColor("#34D399"));
                paint.setAlpha(180);
                canvas.drawCircle(state.touchX, state.touchY, touchRadius, paint);

                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(3f);
                paint.setColor(Color.WHITE);
                paint.setAlpha(255);
                canvas.drawCircle(state.touchX, state.touchY, touchRadius + 8f, paint);

                // Coordinates label near finger
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(Color.WHITE);
                paint.setTextSize(15);
                paint.setTextAlign(Paint.Align.LEFT);
                String posStr = "Touch: (" + (int) state.touchX + ", " + (int) state.touchY + ")";
                canvas.drawText(posStr, Math.min(w - 140, state.touchX + 25), Math.max(70, state.touchY - 15), paint);
            }
        }

        // 6. Bottom Navigation / Interactive Buttons on the car screen
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.parseColor("#0F172A"));
        canvas.drawRect(0, h - 72, w, h, paint);

        paint.setColor(Color.parseColor("#334155"));
        paint.setStrokeWidth(1.5f);
        canvas.drawLine(0, h - 72, w, h - 72, paint);

        // Interactive Button 1: Switch Color Theme
        RectF btnTheme = new RectF(32, h - 62, 240, h - 12);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.parseColor("#1E293B"));
        canvas.drawRoundRect(btnTheme, 10f, 10f, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(primaryColor);
        canvas.drawRoundRect(btnTheme, 10f, 10f, paint);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.WHITE);
        paint.setTextSize(16);
        paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText("🎨 Tap: Theme", btnTheme.centerX(), btnTheme.centerY() + 6, paint);

        // Interactive Button 2: Touch Counter
        RectF btnTouch = new RectF(260, h - 62, 480, h - 12);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.parseColor("#1E293B"));
        canvas.drawRoundRect(btnTouch, 10f, 10f, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(accentColor);
        canvas.drawRoundRect(btnTouch, 10f, 10f, paint);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.WHITE);
        paint.setTextSize(16);
        canvas.drawText("👆 Touches: " + state.touchCount, btnTouch.centerX(), btnTouch.centerY() + 6, paint);

        // Status pill
        RectF btnStatus = new RectF(500, h - 62, w - 32, h - 12);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.parseColor("#14532D")); // Dark Green
        canvas.drawRoundRect(btnStatus, 10f, 10f, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(Color.parseColor("#22C55E"));
        canvas.drawRoundRect(btnStatus, 10f, 10f, paint);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.parseColor("#86EFAC"));
        paint.setTextSize(16);
        canvas.drawText("✔ USB AOA OK", btnStatus.centerX(), btnStatus.centerY() + 6, paint);
    }
}
