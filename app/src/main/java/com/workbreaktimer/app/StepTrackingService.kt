package com.workbreaktimer.app

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Watches the step sensor while the user is expected to begin a work session. If no step is
 * registered for the configured idle threshold, the phone is assumed to be sitting on a desk;
 * any step restarts the countdown from the top.
 *
 * Reaching the deadline does not start the timer outright — it opens a short confirmation
 * window first, because "no steps" cannot tell a user sitting down from a phone left behind
 * on the desk. Only when that window also expires does work begin.
 *
 * This has to be a foreground service: apps in the background receive no sensor events at all
 * since Android 9. Both deadlines are kept on exact alarms rather than Handler delays, which
 * are measured in uptime and stall while the device sleeps.
 */
class StepTrackingService : Service(), SensorEventListener {

    private var sensorManager: SensorManager? = null

    /** Cumulative TYPE_STEP_COUNTER reading; -1 until the first event arrives. */
    private var lastStepCount = -1f

    /** Reading captured when the current countdown began, used to catch late batches. */
    private var countdownBaselineSteps = -1f
    private var deadlineMillis = 0L
    private var awaitingConfirmation = false
    private var tracking = false

    companion object {
        const val CHANNEL_ID = "step_tracking_channel"
        const val CONFIRM_CHANNEL_ID = "autostart_confirm_channel"
        const val AUTOSTART_CHANNEL_ID = "autostart_channel"
        const val NOTIFICATION_ID = 43
        const val AUTOSTART_NOTIFICATION_ID = 44
        const val CONFIRM_NOTIFICATION_ID = 45

        const val ACTION_IDLE_DEADLINE = "com.workbreaktimer.app.action.IDLE_DEADLINE"
        const val ACTION_CONFIRM_DEADLINE = "com.workbreaktimer.app.action.CONFIRM_DEADLINE"
        const val ACTION_CANCEL_AUTO_START = "com.workbreaktimer.app.action.CANCEL_AUTO_START"
        const val ACTION_START_NOW = "com.workbreaktimer.app.action.START_NOW"
        const val ACTION_DISABLE_AUTO_START = "com.workbreaktimer.app.action.DISABLE_AUTO_START"

        private const val DEADLINE_REQUEST_CODE = 2001
        private const val CONFIRM_WINDOW_MILLIS = 30_000L
        private const val CONFIRM_CHIME_MILLIS = 1_200L
        private const val START_CHIME_MILLIS = 2_500L
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        TimerManager.init(this)

        when (intent?.action) {
            ACTION_DISABLE_AUTO_START -> {
                // Stops this service too, through the tracking invariant in TimerManager.
                TimerManager.setAutoStartEnabled(this, false)
                return START_NOT_STICKY
            }
            ACTION_CANCEL_AUTO_START -> {
                ensureForeground()
                awaitingConfirmation = false
                AlarmChime.stop()
                cancelNotification(CONFIRM_NOTIFICATION_ID)
                restartCountdown()
                return START_STICKY
            }
            ACTION_START_NOW -> {
                ensureForeground()
                commitAutoStart()
                return START_STICKY
            }
            ACTION_CONFIRM_DEADLINE -> {
                ensureForeground()
                commitAutoStart()
                return START_STICKY
            }
            ACTION_IDLE_DEADLINE -> {
                ensureForeground()
                onIdleDeadlineReached()
                return START_STICKY
            }
        }

        ensureForeground()
        if (!tracking) {
            tracking = true
            registerStepSensor()
            restartCountdown()
        }
        return START_STICKY
    }

    /**
     * startForeground must run on every delivery, including redeliveries after the process was
     * killed, or the system kills the service for not posting a notification.
     */
    private fun ensureForeground() {
        if (deadlineMillis == 0L) {
            deadlineMillis = System.currentTimeMillis() + TimerManager.state.value.settings.idleThresholdMillis
        }
        startForeground(NOTIFICATION_ID, buildTrackingNotification())
    }

    private fun registerStepSensor() {
        val sm = getSystemService(SensorManager::class.java) ?: return
        sensorManager = sm
        val sensor = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        if (sensor == null) {
            // No pedometer here: auto-start would fire on a fixed delay regardless of movement,
            // which is not the feature the user asked for, so switch it off.
            TimerManager.setAutoStartEnabled(this, false)
            return
        }
        // maxReportLatencyUs = 0 disables batching so steps are seen as they happen.
        sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL, 0)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_STEP_COUNTER) return
        val count = event.values.firstOrNull() ?: return
        val previous = lastStepCount
        lastStepCount = count
        if (previous < 0f) {
            // The counter is cumulative since boot, so the first reading is only a baseline.
            countdownBaselineSteps = count
            return
        }
        if (count <= previous) return
        // A step during the confirmation window answers the question: the user is up and about.
        if (awaitingConfirmation) {
            awaitingConfirmation = false
            AlarmChime.stop()
            cancelNotification(CONFIRM_NOTIFICATION_ID)
        }
        restartCountdown()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun restartCountdown() {
        awaitingConfirmation = false
        countdownBaselineSteps = lastStepCount
        deadlineMillis = System.currentTimeMillis() + TimerManager.state.value.settings.idleThresholdMillis
        armAlarm(ACTION_IDLE_DEADLINE, deadlineMillis)
        notify(NOTIFICATION_ID, buildTrackingNotification())
    }

    private fun onIdleDeadlineReached() {
        // A step batch delivered on the same wake-up as the alarm can arrive in either order,
        // so re-check the counter before committing rather than trusting the alarm alone.
        if (countdownBaselineSteps >= 0f && lastStepCount > countdownBaselineSteps) {
            restartCountdown()
            return
        }
        awaitingConfirmation = true
        deadlineMillis = System.currentTimeMillis() + CONFIRM_WINDOW_MILLIS
        armAlarm(ACTION_CONFIRM_DEADLINE, deadlineMillis)
        notify(CONFIRM_NOTIFICATION_ID, buildConfirmNotification())
        notify(NOTIFICATION_ID, buildTrackingNotification())
        AlarmChime.play(this, CONFIRM_CHIME_MILLIS, AlarmChime.PATTERN_CONFIRM)
    }

    private fun commitAutoStart() {
        awaitingConfirmation = false
        cancelAlarm()
        cancelNotification(CONFIRM_NOTIFICATION_ID)
        notify(AUTOSTART_NOTIFICATION_ID, buildStartedNotification())
        AlarmChime.play(this, START_CHIME_MILLIS, AlarmChime.PATTERN_AUTO_START)
        TimerManager.autoStartWork(this)
    }

    // region alarms

    private fun armAlarm(action: String, triggerAtMillis: Long) {
        val am = getSystemService(AlarmManager::class.java)
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, alarmPendingIntent(action))
    }

    private fun cancelAlarm() {
        val am = getSystemService(AlarmManager::class.java)
        am.cancel(alarmPendingIntent(ACTION_IDLE_DEADLINE))
    }

    /**
     * One request code for both deadlines: only ever one is armed, and FLAG_UPDATE_CURRENT
     * makes arming the second replace the first.
     */
    private fun alarmPendingIntent(action: String): PendingIntent = PendingIntent.getBroadcast(
        this, DEADLINE_REQUEST_CODE,
        Intent(this, StepIdleDeadlineReceiver::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    // endregion

    // region notifications

    private fun buildTrackingNotification(): Notification {
        createChannel(CHANNEL_ID, "Ожидание начала работы", NotificationManager.IMPORTANCE_LOW)
        val title = if (awaitingConfirmation) "Подтвердите начало работы" else "Ожидание начала работы"
        val text = if (awaitingConfirmation) {
            "Скоро запущу таймер работы"
        } else {
            "Таймер запустится сам, если не будет шагов"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentTitle(title)
            .setContentText(text)
            // The system renders and ticks the countdown, so this only needs re-posting when
            // the deadline actually moves.
            .setWhen(deadlineMillis)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openAppPendingIntent())
            .addAction(0, "Отключить автозапуск", servicePendingIntent(ACTION_DISABLE_AUTO_START, 0))
            .build()
    }

    private fun buildConfirmNotification(): Notification {
        // Sound and vibration are played by hand on the alarm stream, so the channel stays mute.
        createChannel(CONFIRM_CHANNEL_ID, "Подтверждение автозапуска", NotificationManager.IMPORTANCE_HIGH, silent = true)
        return NotificationCompat.Builder(this, CONFIRM_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentTitle("Начинаю работу через 30 сек")
            .setContentText("Похоже, вы сели работать. Отмените, если это не так.")
            .setWhen(deadlineMillis)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setContentIntent(openAppPendingIntent())
            .addAction(0, "Отменить", servicePendingIntent(ACTION_CANCEL_AUTO_START, 1))
            .addAction(0, "Начать сейчас", servicePendingIntent(ACTION_START_NOW, 2))
            .build()
    }

    private fun buildStartedNotification(): Notification {
        createChannel(AUTOSTART_CHANNEL_ID, "Автозапуск таймера", NotificationManager.IMPORTANCE_DEFAULT, silent = true)
        val minutes = TimerManager.state.value.settings.workMillis / 60_000
        return NotificationCompat.Builder(this, AUTOSTART_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentTitle("Таймер работы запущен автоматически")
            .setContentText("Обнаружен сидячий режим — перерыв через $minutes мин")
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openAppPendingIntent())
            .build()
    }

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this, requestCode,
            Intent(this, StepTrackingService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun openAppPendingIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun notify(id: Int, notification: Notification) =
        getSystemService(NotificationManager::class.java).notify(id, notification)

    private fun cancelNotification(id: Int) =
        getSystemService(NotificationManager::class.java).cancel(id)

    private fun createChannel(id: String, name: String, importance: Int, silent: Boolean = false) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(id) != null) return
        nm.createNotificationChannel(
            NotificationChannel(id, name, importance).also {
                if (silent) {
                    it.setSound(null, null)
                    it.enableVibration(false)
                }
            }
        )
    }

    // endregion

    override fun onDestroy() {
        sensorManager?.unregisterListener(this)
        sensorManager = null
        tracking = false
        awaitingConfirmation = false
        cancelAlarm()
        // Only the pending question goes away with the service; the "timer started" notice is
        // posted moments before this runs and has to survive it.
        cancelNotification(CONFIRM_NOTIFICATION_ID)
        super.onDestroy()
    }
}
