package com.example.floatingclock

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun remainingSeconds(milliseconds: Long): Long = (milliseconds + SECOND_MS - 1) / SECOND_MS

internal fun elapsedSeconds(session: ShoppingSession, bootCount: Int): Long =
    (session.duration - session.remaining(bootCount)) / SECOND_MS

class ShoppingTimerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store by lazy { ShoppingStore.get(this) }
    private val windowManager by lazy { getSystemService(WindowManager::class.java) }
    private var overlay: LinearLayout? = null
    private lateinit var clock: TextView
    private lateinit var pause: ImageButton
    private var position: Pair<Int, Int>? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val active = store.state.value.session
        startForeground(ShoppingNotifications.ACTIVE_ID, ShoppingNotifications.running(this, active, store.bootCount))
        scope.launch {
            store.state.map { it.session }.distinctUntilChanged().collectLatest { session ->
                updateShoppingAlarm(this@ShoppingTimerService, session)
                if (session == null) {
                    stopSelf()
                    return@collectLatest
                }
                getSystemService(NotificationManager::class.java).notify(
                    ShoppingNotifications.ACTIVE_ID, ShoppingNotifications.running(this@ShoppingTimerService, session, store.bootCount)
                )
                while (true) {
                    val remaining = session.remaining(store.bootCount)
                    if (remaining == 0L) {
                        try {
                            finishShoppingTimer(this@ShoppingTimerService, session.id)
                            break
                        } catch (exception: IOException) {
                            android.util.Log.w(TAG, "Unable to record shopping time", exception)
                        }
                    }
                    if (Settings.canDrawOverlays(this@ShoppingTimerService)) {
                        showOverlay(session.pausedRemaining != null)
                        val seconds = remainingSeconds(remaining)
                        clock.text = String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)
                    } else {
                        removeOverlay()
                    }
                    delay(250)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra(ShoppingNotifications.EXTRA_SESSION)
        if (id != null && id == store.state.value.session?.id) {
            when (intent.action) {
                ShoppingNotifications.ACTION_PAUSE -> modifyTimer(pausing = true)
                ShoppingNotifications.ACTION_END -> modifyTimer(pausing = false)
            }
        }
        return START_STICKY
    }

    private fun showOverlay(paused: Boolean) {
        if (overlay != null) {
            setPauseIcon(paused)
            return
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            alpha = 0.5f
            x = position?.first ?: (resources.displayMetrics.widthPixels - dp(220)).coerceAtLeast(0)
            y = position?.second ?: dp(68)
        }
        val panel = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(getColor(R.color.tool_surface))
            }
        }
        clock = TextView(this).apply {
            setTextColor(getColor(R.color.ink))
            textSize = 23f
            typeface = android.graphics.Typeface.MONOSPACE
            minWidth = dp(74)
            minHeight = dp(48)
            gravity = Gravity.CENTER
            contentDescription = getString(R.string.shopping_drag_timer)
        }
        pause = overlayButton(R.drawable.ic_shopping_pause, R.string.shopping_pause) { modifyTimer(pausing = true) }
        setPauseIcon(paused)
        val stop = overlayButton(R.drawable.ic_shopping_done, R.string.shopping_end) { modifyTimer(pausing = false) }
        panel.addView(clock)
        panel.addView(pause)
        panel.addView(stop)
        installDrag(clock, panel, params)
        try {
            windowManager.addView(panel, params)
            overlay = panel
        } catch (exception: SecurityException) {
            android.util.Log.w(TAG, "Shopping overlay permission unavailable", exception)
        } catch (exception: WindowManager.BadTokenException) {
            android.util.Log.w(TAG, "Shopping overlay unavailable", exception)
        }
    }

    private fun setPauseIcon(paused: Boolean) {
        val label = getString(if (paused) R.string.shopping_resume else R.string.shopping_pause)
        if (pause.contentDescription == label) return
        pause.setImageResource(if (paused) R.drawable.ic_shopping_resume else R.drawable.ic_shopping_pause)
        pause.contentDescription = label
    }

    private fun overlayButton(icon: Int, label: Int, onClick: () -> Unit) = ImageButton(this).apply {
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        setImageResource(icon)
        setColorFilter(getColor(R.color.ink))
        contentDescription = getString(label)
        val ripple = TypedValue()
        if (theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true)) {
            setBackgroundResource(ripple.resourceId)
        }
        setOnClickListener { onClick() }
    }

    private fun installDrag(handle: View, panel: View, params: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f
        handle.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val metrics = resources.displayMetrics
                    params.x = (initialX + (event.rawX - touchX).toInt())
                        .coerceIn(0, (metrics.widthPixels - panel.width).coerceAtLeast(0))
                    params.y = (initialY + (event.rawY - touchY).toInt())
                        .coerceIn(0, (metrics.heightPixels - panel.height).coerceAtLeast(0))
                    windowManager.updateViewLayout(panel, params)
                    position = params.x to params.y
                    true
                }
                MotionEvent.ACTION_UP -> view.performClick().let { true }
                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
    }

    private fun modifyTimer(pausing: Boolean) {
        val id = store.state.value.session?.id ?: return
        scope.launch {
            try {
                store.change { state ->
                    val session = state.session
                    if (session?.id != id) return@change state
                    val remaining = session.remaining(store.bootCount)
                    when {
                        remaining == 0L -> state
                        !pausing -> state.settle(elapsedSeconds(session, store.bootCount))
                        session.pausedRemaining == null -> state.copy(session = session.copy(pausedRemaining = remaining))
                        else -> state.copy(session = session.copy(
                            deadline = SystemClock.elapsedRealtime() + remaining,
                            deadlineWall = System.currentTimeMillis() + remaining,
                            bootCount = store.bootCount, pausedRemaining = null,
                        ))
                    }
                }
            } catch (exception: IOException) {
                Toast.makeText(this@ShoppingTimerService, R.string.shopping_save_error, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun removeOverlay() {
        overlay?.let(windowManager::removeView)
        overlay = null
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        scope.cancel()
        removeOverlay()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private companion object {
        const val TAG = "ShoppingTimerService"
    }
}

internal fun updateShoppingAlarm(context: Context, session: ShoppingSession?) {
    val alarm = context.getSystemService(AlarmManager::class.java)
    val intent = Intent(context, ShoppingAlarmReceiver::class.java)
    val pending = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
    alarm.cancel(pending)
    if (session == null || session.pausedRemaining != null) return
    val remaining = session.remaining(ShoppingStore.get(context).bootCount)
    val trigger = SystemClock.elapsedRealtime() + remaining
    try {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarm.canScheduleExactAlarms()) {
            alarm.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pending)
            return
        }
    } catch (exception: SecurityException) {
        android.util.Log.w("ShoppingAlarm", "Exact alarm access changed", exception)
    }
    alarm.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pending)
}

internal suspend fun finishShoppingTimer(context: Context, id: String) = withContext(NonCancellable) {
    val store = ShoppingStore.get(context)
    val session = store.state.value.session?.takeIf { it.id == id } ?: return@withContext
    if (store.expire(id)) ShoppingNotifications.finished(context, session)
}

class ShoppingAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val session = ShoppingStore.get(context).state.value.session ?: return
        val result = goAsync()
        CoroutineScope(Dispatchers.Main).launch {
            try {
                finishShoppingTimer(context, session.id)
            } catch (exception: IOException) {
                android.util.Log.w("ShoppingAlarm", "Unable to record shopping time", exception)
            } finally {
                result.finish()
            }
        }
    }
}

internal object ShoppingNotifications {
    const val ACTIVE_ID = 4201
    const val EXTRA_SESSION = "com.example.floatingclock.SHOPPING_SESSION"
    const val ACTION_PAUSE = "com.example.floatingclock.SHOPPING_PAUSE"
    const val ACTION_END = "com.example.floatingclock.SHOPPING_END"
    private const val FINISHED_ID = 4202
    private const val ACTIVE_CHANNEL = "shopping_active"
    private const val FINISHED_CHANNEL = "shopping_finished"

    private fun manager(context: Context): NotificationManager {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(ACTIVE_CHANNEL,
            context.getString(R.string.shopping_focus), NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel(FINISHED_CHANNEL,
            context.getString(R.string.shopping_time_finished), NotificationManager.IMPORTANCE_HIGH))
        return manager
    }

    private fun builder(context: Context, channel: String, session: ShoppingSession?, id: Int): Notification.Builder {
        manager(context)
        val intent = Intent(context, ShoppingActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(ShoppingActivity.EXTRA_ITEM, session?.itemId)
        val contentIntent = PendingIntent.getActivity(context, id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(contentIntent)
            .setContentText(session?.name)
    }

    fun running(context: Context, session: ShoppingSession?, bootCount: Int): Notification {
        val builder = builder(context, ACTIVE_CHANNEL, session, ACTIVE_ID)
            .setContentTitle(context.getString(R.string.shopping_focus))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (session != null && session.pausedRemaining == null) {
            builder.setWhen(System.currentTimeMillis() + session.remaining(bootCount))
                .setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
        } else if (session != null) {
            builder.setContentTitle(context.getString(R.string.shopping_focus_paused))
                .setContentText(context.getString(R.string.shopping_paused_remaining, session.name,
                    remainingSeconds(session.pausedRemaining ?: 0)))
                .setShowWhen(false)
        }
        if (session != null) {
            val toggle = if (session.pausedRemaining == null) R.string.shopping_pause else R.string.shopping_resume
            builder.addAction(action(context, session, ACTION_PAUSE, toggle))
            builder.addAction(action(context, session, ACTION_END, R.string.shopping_end))
        }
        return builder.build()
    }

    private fun action(context: Context, session: ShoppingSession, action: String, label: Int): Notification.Action {
        val intent = Intent(context, ShoppingTimerService::class.java).setAction(action)
            .putExtra(EXTRA_SESSION, session.id)
        val pending = PendingIntent.getService(context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Action.Builder(
            android.graphics.drawable.Icon.createWithResource(context, R.drawable.ic_notification),
            context.getString(label), pending,
        ).build()
    }

    fun finished(context: Context, session: ShoppingSession) {
        manager(context).notify(FINISHED_ID, builder(context, FINISHED_CHANNEL, session, FINISHED_ID)
            .setContentTitle(context.getString(R.string.shopping_time_finished)).setAutoCancel(true).build())
    }
}
