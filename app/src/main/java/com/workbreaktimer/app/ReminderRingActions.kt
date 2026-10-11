package com.workbreaktimer.app

import android.content.Context
import android.content.Intent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/**
 * What the user can do with a ringing reminder. AlarmActivity and the in-app banner both call
 * these, so the two places cannot drift apart.
 */
object ReminderRingActions {

    fun done(context: Context, reminderId: String) {
        ReminderManager.setCompleted(context, reminderId, true)
        stopRinging(context)
    }

    fun snooze(context: Context, reminderId: String) {
        ReminderManager.snooze(context, reminderId)
        stopRinging(context)
    }

    /** Silences the alarm, leaves the task unchecked and turns off all future repeats. */
    fun disable(context: Context, reminderId: String) {
        ReminderManager.setEnabled(context, reminderId, false)
        stopRinging(context)
    }

    private fun stopRinging(context: Context) {
        context.stopService(Intent(context, AlarmRingService::class.java))
    }
}

@Composable
fun DisableReminderConfirmDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Отключить повторы?") },
        text = {
            Text(
                "Сигнал выключится, задача не будет отмечена выполненной, и это напоминание " +
                    "больше не сработает. Включить обратно можно в списке напоминаний."
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Отключить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}
