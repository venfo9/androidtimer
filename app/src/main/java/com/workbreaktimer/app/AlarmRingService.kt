package com.workbreaktimer.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat

/**
 * Short-lived foreground service that rings + vibrates + shows a full-screen
 * alarm notification when a timer phase completes. Started by
 * TimerAlarmReceiver, stopped once the user dismisses/advances from
 * AlarmActivity, the notification's "Stop" action, or MainActivity.
 */
class AlarmRingService : Service() {

    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null

    companion object {
        const val CHANNEL_ID = "alarm_channel"
        const val NOTIFICATION_ID = 42
        const val ACTION_STOP = "com.workbreaktimer.app.action.STOP_RING"
        const val ACTION_ADVANCE = "com.workbreaktimer.app.action.ADVANCE_PHASE"
        const val ACTION_SNOOZE = "com.workbreaktimer.app.action.SNOOZE"
        private const val WAKE_LOCK_TIMEOUT_MILLIS = 5 * 60 * 1000L
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            TimerManager.init(this)
            TimerManager.stopRingingOnly(this)
            stopSelf()
            return START_NOT_STICKY
        }
        // Lets the user advance straight from the lock-screen notification, which works
        // even when the full-screen intent was downgraded to an ordinary notification.
        if (intent?.action == ACTION_ADVANCE) {
            TimerManager.init(this)
            TimerManager.advancePhaseAndStart(this)
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_SNOOZE) {
            TimerManager.init(this)
            TimerManager.snooze(this)
            stopSelf()
            return START_NOT_STICKY
        }
        acquireScreenWakeLock()
        startForeground(NOTIFICATION_ID, buildNotification())
        launchAlarmActivityIfFullScreenIntentBlocked()
        startRinging()
        return START_STICKY
    }

    private fun alarmActivityIntent() = Intent(this, AlarmActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }

    /**
     * Without USE_FULL_SCREEN_INTENT (revoked by default since Android 14) the notification's
     * full-screen intent is downgraded to a heads-up banner and never opens AlarmActivity.
     * setAlarmClock puts us on a temporary background allowlist when it fires, so a direct
     * start may still succeed; if the platform blocks it the wake lock at least lights up the
     * screen so the notification is visible.
     */
    private fun launchAlarmActivityIfFullScreenIntentBlocked() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.canUseFullScreenIntent()) return
        try {
            startActivity(alarmActivityIntent())
        } catch (e: Exception) {
            // Background activity launch blocked; the notification remains the fallback.
        }
    }

    private fun acquireScreenWakeLock() {
        if (wakeLock != null) return
        val pm = getSystemService(PowerManager::class.java)
        @Suppress("DEPRECATION")
        wakeLock = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "WorkBreakTimer::alarm"
        ).apply { acquire(WAKE_LOCK_TIMEOUT_MILLIS) }
    }

    private fun buildNotification(): Notification {
        createChannel()
        val state = TimerManager.state.value
        val title = if (state.phase == TimerPhase.BREAK) "Перерыв окончен" else "Работа окончена"
        val text = if (state.phase == TimerPhase.BREAK) "Пора снова поработать" else "Пора сделать перерыв"

        val fullScreenPendingIntent = PendingIntent.getActivity(
            this, 0, alarmActivityIntent(),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, AlarmRingService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val advanceIntent = Intent(this, AlarmRingService::class.java).apply { action = ACTION_ADVANCE }
        val advancePendingIntent = PendingIntent.getService(
            this, 1, advanceIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val advanceLabel = if (state.phase == TimerPhase.BREAK) {
            "Начать работу (${formatDurationShort(state.settings.workMillis)})"
        } else {
            "Начать перерыв (${formatDurationShort(state.settings.breakMillis)})"
        }

        val snoozeIntent = Intent(this, AlarmRingService::class.java).apply { action = ACTION_SNOOZE }
        val snoozePendingIntent = PendingIntent.getService(
            this, 2, snoozeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val snoozeLabel = if (state.phase == TimerPhase.BREAK) {
            "Ещё отдохнуть (${formatDurationShort(state.settings.snoozeMillis)})"
        } else {
            "Ещё поработать (${formatDurationShort(state.settings.snoozeMillis)})"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setContentIntent(fullScreenPendingIntent)
            .setOngoing(true)
            .setAutoCancel(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(0, advanceLabel, advancePendingIntent)
            .addAction(0, snoozeLabel, snoozePendingIntent)
            .addAction(0, "Остановить", stopPendingIntent)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(CHANNEL_ID, "Сигнал таймера", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Уведомление об окончании таймера"
                    setSound(null, null)
                    enableVibration(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
                nm.createNotificationChannel(channel)
            }
        }
    }

    private fun startRinging() {
        val alarmUri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            isLooping = true
            try {
                setDataSource(this@AlarmRingService, alarmUri)
                prepare()
                start()
            } catch (e: Exception) {
                // Playback can fail on some devices/emulators; vibration still alerts the user.
            }
        }

        val pattern = longArrayOf(0, 800, 500)
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 1))
    }

    override fun onDestroy() {
        mediaPlayer?.let {
            try { it.stop() } catch (_: Exception) {}
            it.release()
        }
        mediaPlayer = null
        vibrator?.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }
}
