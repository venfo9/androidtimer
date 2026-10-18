package com.workbreaktimer.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Keeps a workout alive with the screen off or the app in the background: the foreground
 * service stops the system from freezing the process, and the wake lock keeps the CPU awake
 * so WorkoutEngine's beats keep coming on time.
 */
class WorkoutService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    companion object {
        private const val CHANNEL_ID = "workout_channel"
        private const val NOTIFICATION_ID = 47
        private const val ACTION_TOGGLE_PAUSE = "com.workbreaktimer.app.action.WORKOUT_TOGGLE_PAUSE"
        private const val ACTION_FINISH = "com.workbreaktimer.app.action.WORKOUT_FINISH"

        /** A forgotten workout must not keep the CPU awake forever. */
        private const val WAKE_LOCK_TIMEOUT_MILLIS = 3 * 60 * 60 * 1000L

        /** Text of the last posted notification; the engine publishes every second, the text changes less often. */
        private var lastNotificationKey: String? = null

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, WorkoutService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WorkoutService::class.java))
        }

        fun updateNotification(context: Context, state: WorkoutRunState) {
            if (!state.active) return
            val key = notificationText(state) + state.stage
            if (key == lastNotificationKey) return
            lastNotificationKey = key
            context.getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification(context, state))
        }

        private fun notificationText(state: WorkoutRunState): String {
            val template = state.template ?: return ""
            val set = "Подход ${state.setIndex + 1} из ${template.sets}"
            return when (state.shownStage) {
                WorkoutStage.REST -> "Отдых перед подходом ${state.setIndex + 1} из ${template.sets}"
                WorkoutStage.COUNTDOWN -> "$set · приготовьтесь"
                else -> "$set · повторение ${state.repsInCurrentSet + 1} из ${template.reps}"
            }
        }

        private fun buildNotification(context: Context, state: WorkoutRunState): Notification {
            createChannel(context)
            val paused = state.stage == WorkoutStage.PAUSED
            val title = (state.template?.name ?: "Тренировка") + if (paused) " · пауза" else ""
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(title)
                .setContentText(notificationText(state))
                .setOngoing(true)
                .setSilent(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(
                    PendingIntent.getActivity(
                        context, 0,
                        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )
                .addAction(0, if (paused) "Продолжить" else "Пауза", servicePendingIntent(context, ACTION_TOGGLE_PAUSE, 1))
                .addAction(0, "Завершить", servicePendingIntent(context, ACTION_FINISH, 2))
                .withAccentColor(context)
                .build()
        }

        private fun servicePendingIntent(context: Context, action: String, requestCode: Int): PendingIntent =
            PendingIntent.getService(
                context, requestCode,
                Intent(context, WorkoutService::class.java).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

        private fun createChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Тренировка", NotificationManager.IMPORTANCE_LOW).also {
                    it.setSound(null, null)
                    it.enableVibration(false)
                }
            )
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val state = WorkoutEngine.state.value
        if (!state.active) {
            // The process died mid-workout and the system restarted the service without its state.
            stopSelf()
            return START_NOT_STICKY
        }
        lastNotificationKey = null
        startForeground(NOTIFICATION_ID, buildNotification(this, state))
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WorkBreakTimer:workout")
                .also { it.acquire(WAKE_LOCK_TIMEOUT_MILLIS) }
        }
        when (intent?.action) {
            ACTION_TOGGLE_PAUSE -> WorkoutEngine.togglePause()
            ACTION_FINISH -> WorkoutEngine.finish()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        lastNotificationKey = null
        super.onDestroy()
    }
}
