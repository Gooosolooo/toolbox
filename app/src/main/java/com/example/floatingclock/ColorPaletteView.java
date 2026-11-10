package com.example.floatingclock;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;

/** 色相条与饱和度、明度面板；选中标记跟随当前颜色。 */
public final class ColorPaletteView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float[] hsv = {0.0F, 0.0F, 1.0F};
    private OnColorSelected listener;
    private boolean adjustingHue;

    public ColorPaletteView(Context context) {
        super(context);
        setContentDescription("上方拖动选择饱和度和明度，下方拖动选择色相");
    }

    public void setOnColorSelected(OnColorSelected listener) {
        this.listener = listener;
    }

    public void setColor(int color) {
        float previousHue = hsv[0];
        Color.colorToHSV(color, hsv);
        if (hsv[1] == 0.0F) {
            hsv[0] = previousHue;
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth();
        float panelHeight = getHeight() * 0.76F;
        if (width <= 0 || panelHeight <= 0) {
            return;
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new LinearGradient(0, 0, width, 0, Color.WHITE,
                Color.HSVToColor(new float[]{hsv[0], 1, 1}), Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, width, panelHeight, paint);
        paint.setShader(new LinearGradient(0, 0, 0, panelHeight,
                Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, width, panelHeight, paint);
        paint.setShader(new LinearGradient(0, 0, width, 0,
                new int[]{Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN,
                        Color.BLUE, Color.MAGENTA, Color.RED}, null, Shader.TileMode.CLAMP));
        canvas.drawRect(0, getHeight() * 0.86F, width, getHeight(), paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(3 * getResources().getDisplayMetrics().density);
        paint.setColor(Color.WHITE);
        canvas.drawCircle(hsv[1] * width, (1 - hsv[2]) * panelHeight,
                6 * getResources().getDisplayMetrics().density, paint);
        float hueX = hsv[0] / 360.0F * width;
        canvas.drawLine(hueX, getHeight() * 0.86F, hueX, getHeight(), paint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                adjustingHue = event.getY() > getHeight() * 0.8F;
                getParent().requestDisallowInterceptTouchEvent(true);
                updateSelection(event);
                return true;
            case MotionEvent.ACTION_MOVE:
                updateSelection(event);
                return true;
            case MotionEvent.ACTION_UP:
                updateSelection(event);
                performClick();
                getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            case MotionEvent.ACTION_CANCEL:
                getParent().requestDisallowInterceptTouchEvent(false);
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

    private void updateSelection(MotionEvent event) {
        if (getWidth() <= 0 || getHeight() <= 0) {
            return;
        }
        float horizontal = Math.max(0, Math.min(1, event.getX() / getWidth()));
        if (adjustingHue) {
            hsv[0] = horizontal * 359.99F;
        } else {
            hsv[1] = horizontal;
            hsv[2] = 1 - Math.max(0, Math.min(1,
                    event.getY() / (getHeight() * 0.76F)));
        }
        invalidate();
        if (listener != null) {
            listener.onColorSelected(Color.HSVToColor(hsv));
        }
    }

    public interface OnColorSelected {
        void onColorSelected(int color);
    }
}
