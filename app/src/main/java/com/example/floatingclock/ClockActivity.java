package com.example.floatingclock;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;

/**
 * 应用入口：负责悬浮窗授权以及服务的启动、停止。
 */
public class ClockActivity extends Activity {

    private static final int NOTIFICATION_PERMISSION_REQUEST_CODE = 1001;
    private static final int MINIMUM_BACKGROUND_OPACITY = 20;

    private TextView statusText;
    private Button primaryButton;
    private Button stopButton;
    private TextView opacityLabel;
    private SeekBar opacitySeekBar;
    private boolean receiverRegistered;

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refreshState();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        findViewById(R.id.clockBackButton).setOnClickListener(view -> finish());
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(
                findViewById(R.id.clockPage), (view, insets) -> {
                    androidx.core.graphics.Insets bars = insets.getInsets(
                            androidx.core.view.WindowInsetsCompat.Type.systemBars()
                                    | androidx.core.view.WindowInsetsCompat.Type.displayCutout());
                    view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                    return insets;
                });
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        statusText = findViewById(R.id.statusText);
        primaryButton = findViewById(R.id.primaryButton);
        stopButton = findViewById(R.id.stopButton);
        opacityLabel = findViewById(R.id.opacityLabel);
        opacitySeekBar = findViewById(R.id.opacitySeekBar);

        primaryButton.setOnClickListener(view -> handlePrimaryAction());
        stopButton.setOnClickListener(view -> stopFloatingClock());
        configureOpacityControl();
        android.widget.LinearLayout appearancePanel = (android.widget.LinearLayout)
                opacitySeekBar.getParent();
        appearancePanel.addView(new BackgroundColorControl(this));
    }

    @Override
    protected void onStart() {
        super.onStart();
        registerStateReceiver();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshState();
    }

    @Override
    protected void onStop() {
        if (receiverRegistered) {
            unregisterReceiver(stateReceiver);
            receiverRegistered = false;
        }
        super.onStop();
    }

    private void handlePrimaryAction() {
        if (!Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())
            );
            startActivity(intent);
            return;
        }

        requestNotificationPermissionIfNeeded();
        Intent serviceIntent = new Intent(this, FloatingClockService.class);
        serviceIntent.setAction(FloatingClockService.ACTION_START);
        startForegroundService(serviceIntent);
        refreshState();
    }

    private void stopFloatingClock() {
        Intent serviceIntent = new Intent(this, FloatingClockService.class);
        serviceIntent.setAction(FloatingClockService.ACTION_STOP);
        startService(serviceIntent);
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    NOTIFICATION_PERMISSION_REQUEST_CODE
            );
        }
    }

    private void refreshState() {
        boolean hasOverlayPermission = Settings.canDrawOverlays(this);
        boolean isRunning = FloatingClockPreferences.isServiceActive(this);

        if (!hasOverlayPermission) {
            statusText.setText(R.string.status_permission_missing);
            primaryButton.setText(R.string.action_grant_permission);
        } else if (isRunning) {
            statusText.setText(R.string.status_running);
            primaryButton.setText(R.string.action_start);
        } else {
            statusText.setText(R.string.status_permission_ready);
            primaryButton.setText(R.string.action_start);
        }

        stopButton.setVisibility(isRunning ? View.VISIBLE : View.GONE);
    }

    private void configureOpacityControl() {
        int savedOpacity = FloatingClockPreferences.readBackgroundOpacity(this);
        opacitySeekBar.setProgress(savedOpacity - MINIMUM_BACKGROUND_OPACITY);
        updateOpacityLabel(savedOpacity);
        opacitySeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int opacity = progress + MINIMUM_BACKGROUND_OPACITY;
                updateOpacityLabel(opacity);
                if (!fromUser) {
                    return;
                }
                FloatingClockPreferences.saveBackgroundOpacity(ClockActivity.this, opacity);
                FloatingClockService.updateBackgroundOpacity(ClockActivity.this, opacity);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                // 无需额外处理。
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                // onProgressChanged 已实时保存。
            }
        });
    }

    private void updateOpacityLabel(int opacity) {
        opacityLabel.setText(getString(R.string.opacity_label, opacity));
    }

    private void registerStateReceiver() {
        IntentFilter filter = new IntentFilter(FloatingClockService.ACTION_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(stateReceiver, filter);
        }
        receiverRegistered = true;
    }
}
