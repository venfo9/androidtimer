package com.workbreaktimer.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat

/**
 * Switches airplane mode and tells the user when it could not. Every call here blocks for a
 * few seconds while Shizuku answers, so callers run it off the main thread.
 */
object AirplaneSwitcher {

    sealed interface Result {
        /** The mode already matched, so nothing ran. */
        data object AlreadyInState : Result
        data object Switched : Result
        data class Failed(val reason: String) : Result
    }

    private const val CHANNEL_ID = "airplane_schedule_channel"
    private const val FAILURE_NOTIFICATION_ID = 60
    private const val PRECHECK_NOTIFICATION_ID = 61
    private const val CONFIRM_ATTEMPTS = 10
    private const val CONFIRM_STEP_MILLIS = 200L

    fun isAirplaneOn(context: Context): Boolean =
        Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1

    fun switchTo(context: Context, turnOn: Boolean): Result {
        if (isAirplaneOn(context) == turnOn) return Result.AlreadyInState
        ShizukuBridge.setAirplaneModeBlocking(context, turnOn)?.let { return Result.Failed(it) }
        // A zero exit code only means the command was accepted. Reading the setting back is
        // what proves the radios actually changed.
        repeat(CONFIRM_ATTEMPTS) {
            if (isAirplaneOn(context) == turnOn) return Result.Switched
            Thread.sleep(CONFIRM_STEP_MILLIS)
        }
        return Result.Failed("команда прошла, но режим полёта не переключился")
    }

    /** What a rule does when its time comes. */
    fun runScheduled(context: Context, turnOn: Boolean) {
        val result = switchTo(context, turnOn)
        val (ok, message) = when (result) {
            Result.AlreadyInState -> true to "уже был в нужном состоянии"
            Result.Switched -> true to "успешно"
            is Result.Failed -> false to result.reason
        }
        AirplaneScheduleManager.recordRun(
            context, AirplaneRun(System.currentTimeMillis(), turnOn, ok, message)
        )
        if (result is Result.Failed) {
            notify(
                context,
                FAILURE_NOTIFICATION_ID,
                title = if (turnOn) "Режим полёта не включён" else "Режим полёта не выключен",
                text = "${result.reason.replaceFirstChar { it.uppercase() }}. Нажмите, чтобы переключить вручную.",
                tapIntent = Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
            )
        }
    }

    /** Fifteen minutes ahead of a rule: warns while there is still time to start Shizuku. */
    fun precheck(context: Context, rule: AirplaneRule) {
        val status = ShizukuBridge.awaitStatus(context)
        if (status == ShizukuBridge.Status.READY) return
        val action = if (rule.turnOn) "включить" else "выключить"
        notify(
            context,
            PRECHECK_NOTIFICATION_ID,
            title = "Расписание не сможет $action режим полёта",
            text = "${status.label.replaceFirstChar { it.uppercase() }}. " +
                "Правило на ${"%02d:%02d".format(rule.hour, rule.minute)} не сработает. " +
                "Нажмите, чтобы открыть Shizuku.",
            tapIntent = ShizukuBridge.openIntent(context)
        )
    }

    private fun notify(context: Context, id: Int, title: String, text: String, tapIntent: Intent) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID, "Расписание режима полёта", NotificationManager.IMPORTANCE_HIGH
                ).apply { description = "Сообщает, когда расписание не смогло переключить режим полёта" }
            )
        }
        val pending = PendingIntent.getActivity(
            context, id,
            tapIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        nm.notify(
            id,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pending)
                .withAccentColor(context)
                .build()
        )
    }
}
