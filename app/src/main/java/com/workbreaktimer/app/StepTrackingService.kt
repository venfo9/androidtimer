package com.workbreaktimer.app

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat

/**
 * Watches the step sensor while the user is expected to begin a work session. If no step is
 * registered for the configured idle threshold, the phone is assumed to be sitting on a desk
 * and the work timer starts by itself; any step restarts the countdown from the top.
 *
 * This has to be a foreground service because apps in the background receive no sensor events
 * at all since Android 9. The deadline is kept on an exact alarm rather than a Handler because
 * Handler delays are measured in uptime and stall while the device sleeps.
 */
class StepTrackingService : Service(), SensorEventListener {

    private var sensorManager: SensorManager? = null
    private var stepSensor: Sensor? = null

    /** Cumulative TYPE_STEP_COUNTER reading; -1 until the first event arrives. */
    private var lastStepCount = -1f

    /** Reading captured when the current countdown began, used to detect late batches. */
    private var countdownBaselineSteps = -1f
    private var deadlineMillis = 0L
    private var tracking = false

    companion object {
        const val CHANNEL_ID = "step_tracking_channel"
        const val AUTOSTART_CHANNEL_ID = "autostart_channel"
        const val NOTIFICATION_ID = 43
        const val AUTOSTART_NOTIFICATION_ID = 44
        const val ACTION_IDLE_DEADLINE = "com.workbreaktimer.app.action.IDLE_DEADLINE"
        const val ACTION_DISABLE_AUTO_START = "com.workbreaktimer.app.action.DISABLE_AUTO_START"
        private const val DEADLINE_REQUEST_CODE = 2001
        private const val CHIME_MILLIS = 2500L

        /**
         * Held statically because auto-starting the timer immediately stops this service —
         * a player owned by the instance would be released mid-chime.
         */
        private var chimePlayer: MediaPlayer? = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        TimerManager.init(this)

        when (intent?.action) {
            ACTION_DISABLE_AUTO_START -> {
                // Also stops this service, via the tracking invariant in TimerManager.
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
     * startForeground must be called on every delivery, including redeliveries after the
     * process was killed, or the system kills the service for not posting a notification.
     */
    private fun ensureForeground() {
        if (deadlineMillis == 0L) {
            deadlineMillis = System.currentTimeMillis() + TimerManager.state.value.idleThresholdMillis
        }
        startForeground(NOTIFICATION_ID, buildTrackingNotification())
    }

    private fun registerStepSensor() {
        val sm = getSystemService(SensorManager::class.java) ?: return
        sensorManager = sm
        val sensor = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        if (sensor == null) {
            // No pedometer on this device: auto-start would fire on a fixed delay regardless
            // of movement, which is not what the user asked for, so switch the feature off.
            TimerManager.setAutoStartEnabled(this, false)
            return
        }
        stepSensor = sensor
        // maxReportLatencyUs = 0 disables batching so steps are seen as they happen.
        sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL, 0)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_STEP_COUNTER) return
        val count = event.values.firstOrNull() ?: return
        val previous = lastStepCount
        lastStepCount = count
        if (previous < 0f) {
            // First reading is only a baseline — the counter is cumulative since boot, so its
            // absolute value says nothing about whether the user just moved.
            countdownBaselineSteps = count
            return
        }
        if (count > previous) restartCountdown()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun restartCountdown() {
        countdownBaselineSteps = lastStepCount
        deadlineMillis = System.currentTimeMillis() + TimerManager.state.value.idleThresholdMillis
        armDeadlineAlarm()
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildTrackingNotification())
    }

    private fun onDeadlineReached() {
        // A step batch delivered on the same wake-up as the alarm can arrive in either order,
        // so re-check the counter before committing rather than trusting the alarm alone.
        if (countdownBaselineSteps >= 0f && lastStepCount > countdownBaselineSteps) {
            restartCountdown()
            return
        }
        cancelDeadlineAlarm()
        notifyAutoStarted()
        playAutoStartChime()
        TimerManager.autoStartWork(this)
    }

    /**
     * A short burst on the alarm stream rather than a notification sound: the phone is face
     * down or in a pocket when this fires, and USAGE_ALARM is audible on the alarm volume
     * even in silent mode. Cut off after a couple of seconds — alarm tones run for minutes,
     * and this is an announcement, not an alarm to be dismissed.
     */
    private fun playAutoStartChime() {
        releaseChime()
        val uri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        val player = MediaPlayer()
        chimePlayer = player
        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        player.setOnCompletionListener { releaseChime() }
        try {
            player.setDataSource(applicationContext, uri)
            player.prepare()
            player.start()
            Handler(Looper.getMainLooper()).postDelayed({ releaseChime() }, CHIME_MILLIS)
        } catch (e: Exception) {
            // Playback can fail on some devices; the vibration below still announces the start.
            releaseChime()
        }
        vibrateOnce()
    }

    private fun releaseChime() {
        chimePlayer?.let { player ->
            try { player.stop() } catch (_: Exception) {}
            player.release()
        }
        chimePlayer = null
    }

    private fun vibrateOnce() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 300, 150, 300), -1))
    }

    private fun armDeadlineAlarm() {
        val am = getSystemService(AlarmManager::class.java)
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, deadlineMillis, deadlinePendingIntent())
    }

    private fun cancelDeadlineAlarm() {
        getSystemService(AlarmManager::class.java).cancel(deadlinePendingIntent())
    }

    private fun deadlinePendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        this, DEADLINE_REQUEST_CODE,
        Intent(this, StepIdleDeadlineReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun buildTrackingNotification(): Notification {
        createChannel(
            CHANNEL_ID,
            "Ожидание начала работы",
            "Отсчёт до автозапуска рабочего таймера",
            NotificationManager.IMPORTANCE_LOW
        )

        val disableIntent = Intent(this, StepTrackingService::class.java).apply {
            action = ACTION_DISABLE_AUTO_START
        }
        val disablePendingIntent = PendingIntent.getService(
            this, 0, disableIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentTitle("Ожидание начала работы")
            .setContentText("Таймер запустится сам, если не будет шагов")
            // The system renders and ticks the countdown itself, so this notification only
            // needs re-posting when the deadline actually moves.
            .setWhen(deadlineMillis)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openAppPendingIntent())
            .addAction(0, "Отключить автозапуск", disablePendingIntent)
            .build()
    }

    private fun notifyAutoStarted() {
        createChannel(
            AUTOSTART_CHANNEL_ID,
            "Автозапуск таймера",
            "Сообщение о том, что таймер работы запустился сам",
            NotificationManager.IMPORTANCE_DEFAULT,
            silent = true
        )
        val minutes = TimerManager.state.value.workDurationMillis / 60000
        val notification = NotificationCompat.Builder(this, AUTOSTART_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentTitle("Таймер работы запущен автоматически")
            .setContentText("Обнаружен сидячий режим — перерыв через $minutes мин")
            .setAutoCancel(true)
            .setContentIntent(openAppPendingIntent())
            .build()
        getSystemService(NotificationManager::class.java)
            .notify(AUTOSTART_NOTIFICATION_ID, notification)
    }

    private fun openAppPendingIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun createChannel(
        id: String,
        name: String,
        description: String,
        importance: Int,
        silent: Boolean = false
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(id) != null) return
        nm.createNotificationChannel(
            NotificationChannel(id, name, importance).also {
                it.description = description
                if (silent) {
                    // The chime is played by hand on the alarm stream; letting the channel
                    // ring as well would double up.
                    it.setSound(null, null)
                    it.enableVibration(false)
                }
            }
        )
    }

    override fun onDestroy() {
        sensorManager?.unregisterListener(this)
        sensorManager = null
        stepSensor = null
        tracking = false
        cancelDeadlineAlarm()
        super.onDestroy()
    }
}
