package com.example.floatingclock;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 悬浮时钟自绘视图。所有尺寸按视图比例计算，缩放时不会破坏布局。
 */
public class FloatingClockView extends View {

    private static final long FRAME_DELAY_MILLIS = 40L;
    private static final float COUNTDOWN_START_SECOND = 57.0F;
    private static final float COUNTDOWN_DURATION_SECONDS = 3.0F;
    private static final int COLOR_PRIMARY_TEXT = Color.rgb(247, 243, 235);
    private static final int COLOR_SECONDARY_TEXT = Color.rgb(186, 181, 172);
    private static final int COLOR_ACCENT = Color.rgb(235, 102, 87);
    private static final int COLOR_TRACK = Color.argb(100, 247, 243, 235);
    private static final Map<String, String> REGION_NAMES = createRegionNames();

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF panelBounds = new RectF();
    private final Path resizePath = new Path();
    private final Runnable frameRunnable = new Runnable() {
        @Override
        public void run() {
            invalidate();
            postDelayed(this, FRAME_DELAY_MILLIS);
        }
    };

    private OnWindowActionListener actionListener;
    private GestureMode gestureMode = GestureMode.NONE;
    private float gestureStartRawX;
    private float gestureStartRawY;
    private boolean closePressed;
    private int panelOpacityPercent = 70;
    private int panelColor = FloatingClockPreferences.DEFAULT_BACKGROUND_COLOR;

    public void setPanelColor(int color) {
        panelColor = color;
        invalidate();
    }

    public FloatingClockView(Context context) {
        super(context);
        paint.setStrokeCap(Paint.Cap.ROUND);
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        setContentDescription("可拖动、缩放的悬浮时钟");
    }

    public void setOnWindowActionListener(OnWindowActionListener actionListener) {
        this.actionListener = actionListener;
    }

    public void setPanelOpacityPercent(int panelOpacityPercent) {
        this.panelOpacityPercent = Math.max(20, Math.min(panelOpacityPercent, 100));
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        removeCallbacks(frameRunnable);
        post(frameRunnable);
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(frameRunnable);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }

        float cornerRadius = height * 0.11F;
        panelBounds.set(1.0F, 1.0F, width - 1.0F, height - 1.0F);

        paint.setStyle(Paint.Style.FILL);
        int panelAlpha = Math.round(255.0F * panelOpacityPercent / 100.0F);
        paint.setColor(Color.argb(panelAlpha, Color.red(panelColor),
                Color.green(panelColor), Color.blue(panelColor)));
        paint.setShadowLayer(height * 0.06F, 0.0F, height * 0.025F, Color.argb(80, 0, 0, 0));
        canvas.drawRoundRect(panelBounds, cornerRadius, cornerRadius, paint);
        paint.clearShadowLayer();

        ZonedDateTime currentTime = ZonedDateTime.now(ZoneId.systemDefault());
        drawCloseButton(canvas, width, height);
        drawTimezone(canvas, width, height, currentTime);
        drawTime(canvas, width, height, currentTime);

        float secondWithFraction = currentTime.getSecond() + currentTime.getNano() / 1_000_000_000.0F;
        if (secondWithFraction >= COUNTDOWN_START_SECOND) {
            drawCountdownProgress(canvas, width, height, secondWithFraction);
        } else {
            drawNormalFooter(canvas, width, height);
        }
        drawResizeHandle(canvas, width, height);
    }

    private void drawCloseButton(Canvas canvas, int width, int height) {
        float centerX = width * 0.075F;
        float centerY = height * 0.18F;
        float radius = height * 0.085F * 2.0F / 3.0F;

        if (closePressed) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb(70, 255, 255, 255));
            canvas.drawCircle(centerX, centerY, radius * 1.35F, paint);
        }

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1.0F, height * 0.012F));
        paint.setColor(COLOR_PRIMARY_TEXT);
        canvas.drawLine(centerX - radius, centerY - radius, centerX + radius, centerY + radius, paint);
        canvas.drawLine(centerX + radius, centerY - radius, centerX - radius, centerY + radius, paint);
    }

    private void drawTimezone(Canvas canvas, int width, int height, ZonedDateTime currentTime) {
        paint.setStyle(Paint.Style.FILL);
        paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        paint.setTextAlign(Paint.Align.RIGHT);
        paint.setTextSize(height * 0.085F);
        paint.setColor(COLOR_SECONDARY_TEXT);
        canvas.drawText(buildTimezoneLabel(currentTime), width * 0.93F, height * 0.21F, paint);
    }

    private void drawTime(Canvas canvas, int width, int height, ZonedDateTime currentTime) {
        String mainTime = String.format(
                Locale.ROOT,
                "%02d:%02d:%02d.",
                currentTime.getHour(),
                currentTime.getMinute(),
                currentTime.getSecond()
        );
        String tenth = String.valueOf(currentTime.getNano() / 100_000_000);

        paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(width * 0.15F);
        paint.setLetterSpacing(0.0F);
        // 固定字符格宽度仅取决于窗口宽度，不随当前数字变化。
        float digitCellWidth = width * 0.09F;
        float separatorCellWidth = width * 0.045F;
        float totalWidth = digitCellWidth * 7.0F + separatorCellWidth * 3.0F;
        float cursorX = (width - totalWidth) / 2.0F;
        float baseline = height * 0.61F;
        String timeText = mainTime + tenth;
        for (int index = 0; index < timeText.length(); index++) {
            char character = timeText.charAt(index);
            float cellWidth = character == ':' || character == '.'
                    ? separatorCellWidth : digitCellWidth;
            paint.setColor(index == timeText.length() - 1 ? COLOR_ACCENT : COLOR_PRIMARY_TEXT);
            canvas.drawText(timeText, index, index + 1,
                    cursorX + cellWidth / 2.0F, baseline, paint);
            cursorX += cellWidth;
        }
    }

    private void drawCountdownProgress(Canvas canvas, int width, int height, float currentSecond) {
        float left = width * 0.075F;
        float right = width * 0.925F;
        float lineY = height * 0.88F;
        float progress = Math.min(
                1.0F,
                Math.max(0.0F, (currentSecond - COUNTDOWN_START_SECOND) / COUNTDOWN_DURATION_SECONDS)
        );

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(2.0F, height * 0.018F));
        paint.setColor(COLOR_TRACK);
        canvas.drawLine(left, lineY, right, lineY, paint);

        paint.setColor(COLOR_ACCENT);
        canvas.drawLine(left, lineY, left + (right - left) * progress, lineY, paint);

        paint.setStyle(Paint.Style.FILL);
        paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(height * 0.065F);
        for (int index = 1; index <= 5; index++) {
            float markerProgress = index / 6.0F;
            float markerX = left + (right - left) * markerProgress;
            boolean passed = progress >= markerProgress;
            paint.setColor(passed ? COLOR_ACCENT : COLOR_SECONDARY_TEXT);
            canvas.drawCircle(markerX, lineY, height * 0.017F, paint);
            String label = formatCountdownLabel(3.0F - index * 0.5F);
            canvas.drawText(label, markerX, height * 0.81F, paint);
        }

        float indicatorX = left + (right - left) * progress;
        paint.setColor(COLOR_PRIMARY_TEXT);
        canvas.drawCircle(indicatorX, lineY, height * 0.031F, paint);
        paint.setColor(COLOR_ACCENT);
        canvas.drawCircle(indicatorX, lineY, height * 0.018F, paint);
    }

    private void drawNormalFooter(Canvas canvas, int width, int height) {
        paint.setStyle(Paint.Style.FILL);
        paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        paint.setTextAlign(Paint.Align.LEFT);
        paint.setTextSize(height * 0.06F);
        paint.setColor(COLOR_SECONDARY_TEXT);
        canvas.drawText("SYSTEM TIME  /  100 MS", width * 0.075F, height * 0.88F, paint);

        paint.setColor(COLOR_ACCENT);
        canvas.drawRect(
                width * 0.075F,
                height * 0.915F,
                width * 0.36F,
                height * 0.932F,
                paint
        );
    }

    private void drawResizeHandle(Canvas canvas, int width, int height) {
        float inset = Math.max(8.0F, height * 0.07F);
        float step = Math.max(5.0F, height * 0.045F);
        resizePath.reset();
        resizePath.moveTo(width - inset - step * 2.0F, height - inset);
        resizePath.lineTo(width - inset, height - inset);
        resizePath.lineTo(width - inset, height - inset - step * 2.0F);
        resizePath.moveTo(width - inset - step, height - inset);
        resizePath.lineTo(width - inset, height - inset);
        resizePath.lineTo(width - inset, height - inset - step);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1.5F, height * 0.012F));
        paint.setColor(COLOR_PRIMARY_TEXT);
        canvas.drawPath(resizePath, paint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (actionListener == null) {
            return false;
        }

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                gestureStartRawX = event.getRawX();
                gestureStartRawY = event.getRawY();
                gestureMode = resolveGestureMode(event.getX(), event.getY());
                closePressed = gestureMode == GestureMode.CLOSE;
                actionListener.onGestureStart();
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                float offsetX = event.getRawX() - gestureStartRawX;
                float offsetY = event.getRawY() - gestureStartRawY;
                if (gestureMode == GestureMode.DRAG) {
                    actionListener.onDrag(offsetX, offsetY);
                } else if (gestureMode == GestureMode.RESIZE) {
                    actionListener.onResize(offsetX, offsetY);
                } else if (gestureMode == GestureMode.CLOSE) {
                    closePressed = isInCloseRegion(event.getX(), event.getY());
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
                boolean shouldClose = gestureMode == GestureMode.CLOSE
                        && isInCloseRegion(event.getX(), event.getY());
                resetGesture();
                if (shouldClose) {
                    actionListener.onClose();
                } else {
                    actionListener.onGestureEnd();
                }
                performClick();
                return true;
            case MotionEvent.ACTION_CANCEL:
                resetGesture();
                actionListener.onGestureEnd();
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    private GestureMode resolveGestureMode(float x, float y) {
        if (isInCloseRegion(x, y)) {
            return GestureMode.CLOSE;
        }
        float touchSize = 32.0F * getResources().getDisplayMetrics().density;
        if (x >= Math.min(getWidth() * 0.78F, getWidth() - touchSize)
                && y >= Math.min(getHeight() * 0.68F, getHeight() - touchSize)) {
            return GestureMode.RESIZE;
        }
        return GestureMode.DRAG;
    }

    private boolean isInCloseRegion(float x, float y) {
        float touchSize = 32.0F * getResources().getDisplayMetrics().density;
        return x >= 0.0F && y >= 0.0F
                && x <= Math.max(getWidth() * 0.18F, touchSize)
                && y <= Math.max(getHeight() * 0.38F, touchSize);
    }

    private void resetGesture() {
        closePressed = false;
        gestureMode = GestureMode.NONE;
        invalidate();
    }

    private static String buildTimezoneLabel(ZonedDateTime dateTime) {
        ZoneOffset offset = dateTime.getOffset();
        int totalMinutes = offset.getTotalSeconds() / 60;
        int absoluteMinutes = Math.abs(totalMinutes);
        int hours = absoluteMinutes / 60;
        int minutes = absoluteMinutes % 60;
        String offsetText;
        if (minutes == 0) {
            offsetText = String.format(Locale.ROOT, "UTC%s%d", totalMinutes >= 0 ? "+" : "-", hours);
        } else {
            offsetText = String.format(
                    Locale.ROOT,
                    "UTC%s%d:%02d",
                    totalMinutes >= 0 ? "+" : "-",
                    hours,
                    minutes
            );
        }

        String zoneId = dateTime.getZone().getId();
        String region = REGION_NAMES.get(zoneId);
        if (region == null) {
            region = extractFallbackRegion(zoneId);
        }
        return offsetText + "｜" + region;
    }

    private static String extractFallbackRegion(String zoneId) {
        if (zoneId.startsWith("GMT") || zoneId.startsWith("UTC") || zoneId.startsWith("Etc/")) {
            return "本地";
        }
        int separatorIndex = zoneId.lastIndexOf('/');
        String region = separatorIndex >= 0 ? zoneId.substring(separatorIndex + 1) : zoneId;
        return region.replace('_', ' ');
    }

    private static String formatCountdownLabel(float value) {
        if (value == (int) value) {
            return String.valueOf((int) value);
        }
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static Map<String, String> createRegionNames() {
        Map<String, String> names = new HashMap<>();
        names.put("Asia/Shanghai", "北京");
        names.put("Asia/Chongqing", "重庆");
        names.put("Asia/Hong_Kong", "香港");
        names.put("Asia/Macau", "澳门");
        names.put("Asia/Taipei", "台北");
        names.put("Asia/Tokyo", "东京");
        names.put("Asia/Seoul", "首尔");
        names.put("Asia/Singapore", "新加坡");
        names.put("Asia/Bangkok", "曼谷");
        names.put("Asia/Dubai", "迪拜");
        names.put("Europe/London", "伦敦");
        names.put("Europe/Paris", "巴黎");
        names.put("Europe/Berlin", "柏林");
        names.put("Europe/Moscow", "莫斯科");
        names.put("America/New_York", "纽约");
        names.put("America/Chicago", "芝加哥");
        names.put("America/Denver", "丹佛");
        names.put("America/Los_Angeles", "洛杉矶");
        names.put("America/Toronto", "多伦多");
        names.put("Australia/Sydney", "悉尼");
        names.put("Pacific/Auckland", "奥克兰");
        return Collections.unmodifiableMap(names);
    }

    private enum GestureMode {
        NONE,
        CLOSE,
        DRAG,
        RESIZE
    }

    public interface OnWindowActionListener {
        void onGestureStart();

        void onDrag(float offsetX, float offsetY);

        void onResize(float offsetX, float offsetY);

        void onGestureEnd();

        void onClose();
    }
}
