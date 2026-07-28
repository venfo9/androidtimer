package com.workbreaktimer.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * Live countdown shown in the shade whenever the timer is running or paused. A plain ongoing
 * notification needs no foreground service to stay undismissable or to keep ticking — the
 * underlying alarm already keeps time on its own, and setOngoing(true) alone blocks swipe
 * dismissal.
 */
object TimerCountdownNotifier {

    private const val CHANNEL_ID = "timer_countdown_channel"
    private const val NOTIFICATION_ID = 46

    /** Called from every TimerManager state change; decides show/update/hide from the status. */
    fun sync(context: Context, state: TimerUiState) {
        val nm = context.getSystemService(NotificationManager::class.java)
        when (state.status) {
            TimerStatus.RUNNING, TimerStatus.PAUSED -> {
                createChannel(nm)
                nm.notify(NOTIFICATION_ID, build(context, state))
            }
            else -> nm.cancel(NOTIFICATION_ID)
        }
    }

    private fun build(context: Context, state: TimerUiState): Notification {
        val phaseLabel = if (state.phase == TimerPhase.WORK) "Работа" else "Перерыв"
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(openAppPendingIntent(context))
            .addAction(0, "Сброс", actionPendingIntent(context, TimerActionReceiver.ACTION_RESET, 1))
            .withAccentColor(context)

        return if (state.status == TimerStatus.RUNNING) {
            builder
                .setContentTitle(phaseLabel)
                .setContentText("Идёт отсчёт")
                // The system renders and ticks this itself, so no per-second update is needed.
                .setWhen(state.endTimeMillis)
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
                .addAction(0, "Пауза", actionPendingIntent(context, TimerActionReceiver.ACTION_PAUSE, 0))
                .build()
        } else {
            builder
                .setContentTitle("$phaseLabel — пауза")
                .setContentText("Осталось ${formatClock(state.remainingMillis)}")
                .setUsesChronometer(false)
                .addAction(0, "Продолжить", actionPendingIntent(context, TimerActionReceiver.ACTION_RESUME, 2))
                .build()
        }
    }

    private fun actionPendingIntent(context: Context, action: String, requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context, requestCode,
            Intent(context, TimerActionReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun openAppPendingIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun createChannel(nm: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Отсчёт таймера", NotificationManager.IMPORTANCE_LOW).also {
                it.setSound(null, null)
                it.enableVibration(false)
            }
        )
    }
}
