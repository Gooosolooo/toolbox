package com.example.floatingclock;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 集中管理悬浮窗持久化数据，避免业务类散落魔法字符串。
 */
public final class FloatingClockPreferences {
    public static final int DEFAULT_BACKGROUND_COLOR = 0xFF475164;
    private static final String KEY_BACKGROUND_COLOR = "background_color";

    public static int readBackgroundColor(Context context) {
        return getPreferences(context).getInt(KEY_BACKGROUND_COLOR, DEFAULT_BACKGROUND_COLOR);
    }

    public static void saveBackgroundColor(Context context, int color) {
        getPreferences(context).edit().putInt(KEY_BACKGROUND_COLOR, color | 0xFF000000).apply();
    }

    private static final String PREFERENCE_NAME = "floating_clock_preferences";
    private static final String KEY_WINDOW_WIDTH = "window_width";
    private static final String KEY_WINDOW_HEIGHT = "window_height";
    private static final String KEY_WINDOW_X = "window_x";
    private static final String KEY_WINDOW_Y = "window_y";
    private static final String KEY_SERVICE_ACTIVE = "service_active";
    private static final String KEY_BACKGROUND_OPACITY = "background_opacity";
    private static final int DEFAULT_BACKGROUND_OPACITY = 70;
    private static final int MINIMUM_BACKGROUND_OPACITY = 20;
    private static final int MAXIMUM_BACKGROUND_OPACITY = 100;

    private FloatingClockPreferences() {
        throw new IllegalStateException("Utility class");
    }

    public static WindowState readWindowState(
            Context context,
            int defaultWidth,
            int defaultHeight,
            int defaultX,
            int defaultY
    ) {
        SharedPreferences preferences = getPreferences(context);
        return new WindowState(
                preferences.getInt(KEY_WINDOW_WIDTH, defaultWidth),
                preferences.getInt(KEY_WINDOW_HEIGHT, defaultHeight),
                preferences.getInt(KEY_WINDOW_X, defaultX),
                preferences.getInt(KEY_WINDOW_Y, defaultY)
        );
    }

    public static void saveWindowState(Context context, WindowState windowState) {
        getPreferences(context).edit()
                .putInt(KEY_WINDOW_WIDTH, windowState.getWidth())
                .putInt(KEY_WINDOW_HEIGHT, windowState.getHeight())
                .putInt(KEY_WINDOW_X, windowState.getX())
                .putInt(KEY_WINDOW_Y, windowState.getY())
                .apply();
    }

    public static boolean isServiceActive(Context context) {
        return getPreferences(context).getBoolean(KEY_SERVICE_ACTIVE, false);
    }

    public static void setServiceActive(Context context, boolean active) {
        getPreferences(context).edit().putBoolean(KEY_SERVICE_ACTIVE, active).apply();
    }

    public static int readBackgroundOpacity(Context context) {
        int opacity = getPreferences(context).getInt(
                KEY_BACKGROUND_OPACITY,
                DEFAULT_BACKGROUND_OPACITY
        );
        return Math.max(
                MINIMUM_BACKGROUND_OPACITY,
                Math.min(opacity, MAXIMUM_BACKGROUND_OPACITY)
        );
    }

    public static void saveBackgroundOpacity(Context context, int opacity) {
        int safeOpacity = Math.max(
                MINIMUM_BACKGROUND_OPACITY,
                Math.min(opacity, MAXIMUM_BACKGROUND_OPACITY)
        );
        getPreferences(context).edit()
                .putInt(KEY_BACKGROUND_OPACITY, safeOpacity)
                .apply();
    }

    private static SharedPreferences getPreferences(Context context) {
        return context.getSharedPreferences(PREFERENCE_NAME, Context.MODE_PRIVATE);
    }

    public static final class WindowState {
        private final int width;
        private final int height;
        private final int x;
        private final int y;

        public WindowState(int width, int height, int x, int y) {
            this.width = width;
            this.height = height;
            this.x = x;
            this.y = y;
        }

        public int getWidth() {
            return width;
        }

        public int getHeight() {
            return height;
        }

        public int getX() {
            return x;
        }

        public int getY() {
            return y;
        }
    }
}
