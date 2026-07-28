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
 * registered for the configured idle threshold, the phone is assumed to be sitting on a desk
 * and the work timer starts by itself; any step restarts the countdown from the top.
 *
 * This has to be a foreground service: apps in the background receive no sensor events at all
 * since Android 9. The deadline is kept on an exact alarm rather than a Handler delay, which
 * is measured in uptime and stalls while the device sleeps.
 */
class StepTrackingService : Service(), SensorEventListener {

    private var sensorManager: SensorManager? = null

    /** Cumulative TYPE_STEP_COUNTER reading; -1 until the first event arrives. */
    private var lastStepCount = -1f

    /** Reading captured when the current countdown began, used to catch late batches. */
    private var countdownBaselineSteps = -1f
    private var deadlineMillis = 0L
    private var tracking = false

    companion object {
        const val CHANNEL_ID = "step_tracking_channel"
        const val NOTIFICATION_ID = 43

        const val ACTION_IDLE_DEADLINE = "com.workbreaktimer.app.action.IDLE_DEADLINE"
        const val ACTION_DISABLE_AUTO_START = "com.workbreaktimer.app.action.DISABLE_AUTO_START"

        private const val DEADLINE_REQUEST_CODE = 2001
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
            ACTION_IDLE_DEADLINE -> {
                ensureForeground()
                onDeadlineReached()
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
            deadlineMillis = System.currentTimeMillis() +
                TimerManager.state.value.settings.idleThresholdMillis
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
        if (count > previous) restartCountdown()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun restartCountdown() {
        countdownBaselineSteps = lastStepCount
        deadlineMillis = System.currentTimeMillis() +
            TimerManager.state.value.settings.idleThresholdMillis
        armDeadlineAlarm()
        notify(NOTIFICATION_ID, buildTrackingNotification())
    }

    private fun onDeadlineReached() {
        // A step batch delivered on the same wake-up as the alarm can arrive in either order,
        // so re-check the counter before committing rather than trusting the alarm alone.
        if (countdownBaselineSteps >= 0f && lastStepCount > countdownBaselineSteps) {
            restartCountdown()
            return
        }
        cancelDeadlineAlarm()
        notify(AUTOSTART_NOTIFICATION_ID, buildStartedNotification())
        AlarmChime.play(this, START_CHIME_MILLIS, AlarmChime.PATTERN_AUTO_START)
        TimerManager.autoStartWork(this)
    }

    // region alarms

    private fun armDeadlineAlarm() {
        val am = getSystemService(AlarmManager::class.java)
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, deadlineMillis, deadlinePendingIntent())
    }

    private fun cancelDeadlineAlarm() {
        getSystemService(AlarmManager::class.java).cancel(deadlinePendingIntent())
    }

    private fun deadlinePendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        this, DEADLINE_REQUEST_CODE,
        Intent(this, StepIdleDeadlineReceiver::class.java).setAction(ACTION_IDLE_DEADLINE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    // endregion

    // region notifications

    private fun buildTrackingNotification(): Notification {
        createChannel(CHANNEL_ID, "Ожидание начала работы", NotificationManager.IMPORTANCE_LOW)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentTitle("Ожидание начала работы")
            .setContentText("Таймер запустится сам, если не будет шагов")
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
            .withAccentColor(this)
            .build()
    }

    private fun buildStartedNotification(): Notification {
        // Sound and vibration are played by hand on the alarm stream, so the channel stays mute.
        createChannel(
            AUTOSTART_CHANNEL_ID, "Автозапуск таймера",
            NotificationManager.IMPORTANCE_DEFAULT, silent = true
        )
        val minutes = TimerManager.state.value.settings.workMillis / 60_000
        return NotificationCompat.Builder(this, AUTOSTART_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentTitle("Таймер работы запущен автоматически")
            .setContentText("Обнаружен сидячий режим — перерыв через $minutes мин")
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openAppPendingIntent())
            .withAccentColor(this)
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
        cancelDeadlineAlarm()
        super.onDestroy()
    }
}
