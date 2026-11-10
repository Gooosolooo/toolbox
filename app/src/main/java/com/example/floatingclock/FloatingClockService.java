package com.example.floatingclock;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.WindowManager;

/**
 * 前台服务：管理系统级悬浮窗口及其生命周期。
 */
public class FloatingClockService extends Service
        implements FloatingClockView.OnWindowActionListener {

    public static final String ACTION_START = "com.example.floatingclock.action.START";
    public static final String ACTION_STOP = "com.example.floatingclock.action.STOP";
    public static final String ACTION_STATE_CHANGED =
            "com.example.floatingclock.action.STATE_CHANGED";
    private static final String ACTION_UPDATE_BACKGROUND_OPACITY =
            "com.example.floatingclock.action.UPDATE_BACKGROUND_OPACITY";
    private static final String EXTRA_BACKGROUND_OPACITY = "background_opacity";

    private static final String NOTIFICATION_CHANNEL_ID = "floating_clock_channel";
    private static final int NOTIFICATION_ID = 4102;
    private static final float WINDOW_ASPECT_RATIO = 1.85F;
    private static final int DEFAULT_WIDTH_DP = 320;
    private static final int MIN_WIDTH_DP = 120;
    private static final int SCREEN_EDGE_MARGIN_DP = 8;
    private static final String TAG = "FloatingClockService";

    private WindowManager windowManager;
    private WindowManager.LayoutParams windowLayoutParams;
    private FloatingClockView clockView;
    private int gestureStartX;
    private int gestureStartY;
    private int gestureStartWidth;
    private boolean opacityReceiverRegistered;

    private final BroadcastReceiver opacityReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (clockView == null) {
                return;
            }
            int opacity = intent.getIntExtra(
                    EXTRA_BACKGROUND_OPACITY,
                    FloatingClockPreferences.readBackgroundOpacity(context)
            );
            clockView.setPanelOpacityPercent(opacity);
            clockView.setPanelColor(FloatingClockPreferences.readBackgroundColor(context));
        }
    };

    public static void updateBackgroundOpacity(Context context, int opacity) {
        Intent intent = new Intent(ACTION_UPDATE_BACKGROUND_OPACITY);
        intent.setPackage(context.getPackageName());
        intent.putExtra(EXTRA_BACKGROUND_OPACITY, opacity);
        context.sendBroadcast(intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        registerOpacityReceiver();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopFloatingClock();
            return START_NOT_STICKY;
        }

        startForeground(NOTIFICATION_ID, createNotification());
        if (!Settings.canDrawOverlays(this)) {
            stopFloatingClock();
            return START_NOT_STICKY;
        }

        showFloatingClockIfNeeded();
        FloatingClockPreferences.setServiceActive(this, true);
        broadcastStateChanged();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        removeFloatingClock();
        unregisterOpacityReceiver();
        FloatingClockPreferences.setServiceActive(this, false);
        broadcastStateChanged();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (windowLayoutParams == null || clockView == null) {
            return;
        }
        int safeWidth = clamp(
                windowLayoutParams.width,
                dpToPx(MIN_WIDTH_DP),
                getMaximumWidth()
        );
        windowLayoutParams.width = safeWidth;
        windowLayoutParams.height = Math.round(safeWidth / WINDOW_ASPECT_RATIO);
        windowLayoutParams.x = clamp(windowLayoutParams.x, 0, getMaximumX(safeWidth));
        windowLayoutParams.y = clamp(
                windowLayoutParams.y,
                0,
                getMaximumY(windowLayoutParams.height)
        );
        updateWindowLayout();
        saveCurrentWindowState();
    }

    private void showFloatingClockIfNeeded() {
        if (clockView != null) {
            return;
        }

        int screenWidth = Resources.getSystem().getDisplayMetrics().widthPixels;
        int defaultWidth = Math.min(dpToPx(DEFAULT_WIDTH_DP), screenWidth - dpToPx(24));
        int defaultHeight = Math.round(defaultWidth / WINDOW_ASPECT_RATIO);
        FloatingClockPreferences.WindowState savedState =
                FloatingClockPreferences.readWindowState(
                        this,
                        defaultWidth,
                        defaultHeight,
                        dpToPx(12),
                        dpToPx(72)
                );

        int safeWidth = clamp(savedState.getWidth(), dpToPx(MIN_WIDTH_DP), getMaximumWidth());
        int safeHeight = Math.round(safeWidth / WINDOW_ASPECT_RATIO);
        windowLayoutParams = new WindowManager.LayoutParams(
                safeWidth,
                safeHeight,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
        );
        windowLayoutParams.gravity = Gravity.TOP | Gravity.START;
        windowLayoutParams.x = clamp(savedState.getX(), 0, getMaximumX(safeWidth));
        windowLayoutParams.y = clamp(savedState.getY(), 0, getMaximumY(safeHeight));

        clockView = new FloatingClockView(this);
        clockView.setPanelColor(FloatingClockPreferences.readBackgroundColor(this));
        clockView.setOnWindowActionListener(this);
        clockView.setPanelOpacityPercent(
                FloatingClockPreferences.readBackgroundOpacity(this)
        );
        windowManager.addView(clockView, windowLayoutParams);
    }

    @Override
    public void onGestureStart() {
        if (windowLayoutParams == null) {
            return;
        }
        gestureStartX = windowLayoutParams.x;
        gestureStartY = windowLayoutParams.y;
        gestureStartWidth = windowLayoutParams.width;
    }

    @Override
    public void onDrag(float offsetX, float offsetY) {
        if (clockView == null || windowLayoutParams == null) {
            return;
        }
        windowLayoutParams.x = clamp(
                gestureStartX + Math.round(offsetX),
                0,
                getMaximumX(windowLayoutParams.width)
        );
        windowLayoutParams.y = clamp(
                gestureStartY + Math.round(offsetY),
                0,
                getMaximumY(windowLayoutParams.height)
        );
        updateWindowLayout();
    }

    @Override
    public void onResize(float offsetX, float offsetY) {
        if (clockView == null || windowLayoutParams == null) {
            return;
        }

        // 对角线方向向量为 (1, 1 / 宽高比)。将手指累计位移投影到该方向，
        // 再取投影的横向分量作为宽度增量，避免拖动过程中切换计算依据。
        float inverseRatio = 1.0F / WINDOW_ASPECT_RATIO;
        float widthDelta = (offsetX + offsetY * inverseRatio)
                / (1.0F + inverseRatio * inverseRatio);
        int requestedWidth = gestureStartWidth + Math.round(widthDelta);
        int newWidth = clamp(requestedWidth, dpToPx(MIN_WIDTH_DP), getMaximumWidth());
        windowLayoutParams.width = newWidth;
        windowLayoutParams.height = Math.round(newWidth / WINDOW_ASPECT_RATIO);
        windowLayoutParams.x = clamp(windowLayoutParams.x, 0, getMaximumX(windowLayoutParams.width));
        windowLayoutParams.y = clamp(windowLayoutParams.y, 0, getMaximumY(windowLayoutParams.height));
        updateWindowLayout();
    }

    @Override
    public void onGestureEnd() {
        saveCurrentWindowState();
    }

    @Override
    public void onClose() {
        stopFloatingClock();
    }

    private void updateWindowLayout() {
        try {
            windowManager.updateViewLayout(clockView, windowLayoutParams);
        } catch (IllegalArgumentException exception) {
            Log.w(TAG, "Unable to update floating window layout", exception);
        }
    }

    private void saveCurrentWindowState() {
        if (windowLayoutParams == null) {
            return;
        }
        FloatingClockPreferences.saveWindowState(
                this,
                new FloatingClockPreferences.WindowState(
                        windowLayoutParams.width,
                        windowLayoutParams.height,
                        windowLayoutParams.x,
                        windowLayoutParams.y
                )
        );
    }

    private void stopFloatingClock() {
        saveCurrentWindowState();
        removeFloatingClock();
        FloatingClockPreferences.setServiceActive(this, false);
        broadcastStateChanged();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void removeFloatingClock() {
        if (clockView == null) {
            return;
        }
        try {
            windowManager.removeView(clockView);
        } catch (IllegalArgumentException exception) {
            Log.w(TAG, "Floating window has already been removed", exception);
        }
        clockView = null;
        windowLayoutParams = null;
    }

    private Notification createNotification() {
        Intent activityIntent = new Intent(this, ClockActivity.class);
        PendingIntent activityPendingIntent = android.app.TaskStackBuilder.create(this)
                .addNextIntentWithParentStack(activityIntent)
                .getPendingIntent(0, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stopIntent = new Intent(this, FloatingClockService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(
                this,
                1,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        return new Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(getString(R.string.notification_body))
                .setContentIntent(activityPendingIntent)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(0, getString(R.string.action_stop), stopPendingIntent)
                .build();
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.notification_channel),
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setShowBadge(false);
        NotificationManager manager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        manager.createNotificationChannel(channel);
    }

    private void broadcastStateChanged() {
        Intent intent = new Intent(ACTION_STATE_CHANGED);
        intent.setPackage(getPackageName());
        sendBroadcast(intent);
    }

    private void registerOpacityReceiver() {
        IntentFilter filter = new IntentFilter(ACTION_UPDATE_BACKGROUND_OPACITY);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(opacityReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(opacityReceiver, filter);
        }
        opacityReceiverRegistered = true;
    }

    private void unregisterOpacityReceiver() {
        if (!opacityReceiverRegistered) {
            return;
        }
        unregisterReceiver(opacityReceiver);
        opacityReceiverRegistered = false;
    }

    private int getMaximumWidth() {
        int screenWidth = Resources.getSystem().getDisplayMetrics().widthPixels;
        return Math.max(dpToPx(MIN_WIDTH_DP), screenWidth - dpToPx(SCREEN_EDGE_MARGIN_DP));
    }

    private int getMaximumX(int windowWidth) {
        int screenWidth = Resources.getSystem().getDisplayMetrics().widthPixels;
        return Math.max(0, screenWidth - windowWidth);
    }

    private int getMaximumY(int windowHeight) {
        int screenHeight = Resources.getSystem().getDisplayMetrics().heightPixels;
        return Math.max(0, screenHeight - windowHeight);
    }

    private int dpToPx(int dp) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(value, maximum));
    }
}
