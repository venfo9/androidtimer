package com.workbreaktimer.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Fires when a reminder comes due. Rings it like the timer alarm, except when the timer alarm
 * itself is already ringing — the end of a work phase is the more important of the two, so the
 * reminder steps aside and arrives as an ordinary notification instead.
 */
class ReminderAlarmReceiver : BroadcastReceiver() {

    companion object {
        const val EXTRA_REMINDER_ID = "reminder_id"
        const val QUIET_CHANNEL_ID = "reminder_quiet_channel"
        private const val QUIET_NOTIFICATION_BASE_ID = 5000
    }

    override fun onReceive(context: Context, intent: Intent) {
        val reminderId = intent.getStringExtra(EXTRA_REMINDER_ID) ?: return
        TimerManager.init(context)
        ReminderManager.init(context)

        val reminder = ReminderManager.state.value.reminder(reminderId) ?: return

        if (TimerManager.state.value.ringing) {
            postQuietNotification(context, reminder)
        } else {
            val service = Intent(context, AlarmRingService::class.java)
                .putExtra(AlarmRingService.EXTRA_REMINDER_ID, reminder.id)
                .putExtra(AlarmRingService.EXTRA_REMINDER_TITLE, reminder.title)
            try {
                ContextCompat.startForegroundService(context, service)
            } catch (e: Exception) {
                postQuietNotification(context, reminder)
            }
        }

        // Roll the schedule forward whether or not it could ring, so a blocked reminder does
        // not re-fire on the same instant forever.
        ReminderManager.onFired(context, reminder.id)
    }

    private fun postQuietNotification(context: Context, reminder: Reminder) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            nm.getNotificationChannel(QUIET_CHANNEL_ID) == null
        ) {
            nm.createNotificationChannel(
                NotificationChannel(
                    QUIET_CHANNEL_ID, "Напоминания без звука",
                    NotificationManager.IMPORTANCE_HIGH
                )
            )
        }
        val open = PendingIntent.getActivity(
            context, reminder.requestCode,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        nm.notify(
            QUIET_NOTIFICATION_BASE_ID + (reminder.requestCode % 1000),
            NotificationCompat.Builder(context, QUIET_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle(reminder.title)
                .setContentText("Напоминание")
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .setContentIntent(open)
                .build()
        )
    }
}
