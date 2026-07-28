package com.workbreaktimer.app

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

data class ReminderState(
    val folders: List<ReminderFolder> = emptyList(),
    val reminders: List<Reminder> = emptyList()
) {
    fun remindersIn(folderId: String): List<Reminder> =
        reminders.filter { it.folderId == folderId }.sortedBy { it.triggerAtMillis }

    fun folder(folderId: String): ReminderFolder? = folders.firstOrNull { it.id == folderId }

    fun reminder(reminderId: String): Reminder? = reminders.firstOrNull { it.id == reminderId }
}

/**
 * Owns the reminder list and keeps exactly one exact alarm armed per enabled reminder.
 *
 * Every mutation re-arms that reminder's alarm, so the alarm table is derived state rather
 * than something callers have to remember to keep in step.
 */
object ReminderManager {

    private val _state = MutableStateFlow(ReminderState())
    val state: StateFlow<ReminderState> = _state.asStateFlow()

    private var store: ReminderStore? = null
    private var initialized = false

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        val fileStore = FileReminderStore(context)
        store = fileStore
        _state.value = ReminderState(fileStore.readFolders(), fileStore.readReminders())
    }

    // region folders

    fun addFolder(context: Context, name: String) {
        init(context)
        val folder = ReminderFolder(id = UUID.randomUUID().toString(), name = name.trim())
        if (folder.name.isEmpty()) return
        commit(context, _state.value.copy(folders = _state.value.folders + folder))
    }

    fun renameFolder(context: Context, folderId: String, name: String) {
        init(context)
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        commit(
            context,
            _state.value.copy(
                folders = _state.value.folders.map {
                    if (it.id == folderId) it.copy(name = trimmed) else it
                }
            )
        )
    }

    /** Deleting a folder takes its reminders with it, cancelling each pending alarm. */
    fun deleteFolder(context: Context, folderId: String) {
        init(context)
        val current = _state.value
        current.remindersIn(folderId).forEach { cancelAlarm(context, it) }
        commit(
            context,
            ReminderState(
                folders = current.folders.filterNot { it.id == folderId },
                reminders = current.reminders.filterNot { it.folderId == folderId }
            )
        )
    }

    // endregion

    // region reminders

    fun saveReminder(
        context: Context,
        existingId: String?,
        folderId: String,
        title: String,
        triggerAtMillis: Long,
        repeat: RepeatRule,
        intervalMillis: Long = Reminder.DEFAULT_INTERVAL_MILLIS
    ) {
        init(context)
        val trimmed = title.trim().ifEmpty { "Без названия" }
        val interval = intervalMillis.coerceAtLeast(Reminder.MIN_INTERVAL_MILLIS)
        val current = _state.value
        val existing = existingId?.let { current.reminder(it) }
        val reminder = existing?.copy(
            title = trimmed, triggerAtMillis = triggerAtMillis, repeat = repeat,
            intervalMillis = interval, folderId = folderId
        ) ?: Reminder(
            id = UUID.randomUUID().toString(),
            folderId = folderId,
            title = trimmed,
            triggerAtMillis = triggerAtMillis,
            repeat = repeat,
            intervalMillis = interval,
            requestCode = store!!.nextRequestCode()
        )
        val reminders = if (existing == null) {
            current.reminders + reminder
        } else {
            current.reminders.map { if (it.id == reminder.id) reminder else it }
        }
        commit(context, current.copy(reminders = reminders))
        rearm(context, reminder)
    }

    fun setEnabled(context: Context, reminderId: String, enabled: Boolean) {
        init(context)
        val current = _state.value
        val reminder = current.reminder(reminderId) ?: return
        val updated = reminder.copy(enabled = enabled)
        commit(
            context,
            current.copy(reminders = current.reminders.map { if (it.id == updated.id) updated else it })
        )
        rearm(context, updated)
    }

    fun deleteReminder(context: Context, reminderId: String) {
        init(context)
        val current = _state.value
        val reminder = current.reminder(reminderId) ?: return
        cancelAlarm(context, reminder)
        commit(context, current.copy(reminders = current.reminders.filterNot { it.id == reminder.id }))
    }

    /** Toggles the done checkbox — a marker for the current occurrence, independent of enabled. */
    fun setCompleted(context: Context, reminderId: String, completed: Boolean) {
        init(context)
        val current = _state.value
        val reminder = current.reminder(reminderId) ?: return
        val updated = reminder.copy(completed = completed)
        commit(
            context,
            current.copy(reminders = current.reminders.map { if (it.id == updated.id) updated else it })
        )
    }

    /**
     * Called once a reminder has rung. Repeating reminders roll forward to their next
     * occurrence; one-shot reminders switch themselves off but stay in the list so the user
     * can see what fired and reuse it. Either way this is a fresh occurrence, so any earlier
     * "done" mark is cleared — the alarm firing again is what makes it fresh, not the "Выполнено"
     * button, which runs later and sets it for the occurrence that just rang.
     */
    fun onFired(context: Context, reminderId: String) {
        init(context)
        val current = _state.value
        val reminder = current.reminder(reminderId) ?: return
        val next = reminder.nextOccurrence()
        val updated = if (next == null) {
            reminder.copy(enabled = false, completed = false)
        } else {
            reminder.copy(triggerAtMillis = next, completed = false)
        }
        commit(
            context,
            current.copy(reminders = current.reminders.map { if (it.id == updated.id) updated else it })
        )
        rearm(context, updated)
    }

    /** Pushes a ringing reminder out by the configured snooze without touching its schedule. */
    fun snooze(context: Context, reminderId: String) {
        init(context)
        TimerManager.init(context)
        val reminder = _state.value.reminder(reminderId) ?: return
        val at = System.currentTimeMillis() + TimerManager.state.value.settings.snoozeMillis
        scheduleAt(context, reminder, at)
    }

    // endregion

    // region scheduling

    /** Re-arms every enabled reminder; used after a reboot clears the alarm table. */
    fun rescheduleAll(context: Context) {
        init(context)
        _state.value.reminders.forEach { rearm(context, it) }
    }

    private fun rearm(context: Context, reminder: Reminder) {
        cancelAlarm(context, reminder)
        if (!reminder.enabled) return
        val at = if (reminder.triggerAtMillis > System.currentTimeMillis()) {
            reminder.triggerAtMillis
        } else {
            // Missed while the device was off: roll forward, or drop it if it was one-shot.
            reminder.nextOccurrence() ?: return
        }
        scheduleAt(context, reminder, at)
    }

    @SuppressLint("MissingPermission")
    private fun scheduleAt(context: Context, reminder: Reminder, triggerAtMillis: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        // setAlarmClock, like the timer: user-facing, exempt from Doze deferral, and grants a
        // temporary background allowlist when it fires.
        val info = AlarmManager.AlarmClockInfo(triggerAtMillis, showIntent(context))
        am.setAlarmClock(info, firePendingIntent(context, reminder))
    }

    private fun cancelAlarm(context: Context, reminder: Reminder) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(firePendingIntent(context, reminder))
    }

    private fun firePendingIntent(context: Context, reminder: Reminder): PendingIntent =
        PendingIntent.getBroadcast(
            context, reminder.requestCode,
            Intent(context, ReminderAlarmReceiver::class.java)
                .putExtra(ReminderAlarmReceiver.EXTRA_REMINDER_ID, reminder.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun showIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    // endregion

    private fun commit(context: Context, newState: ReminderState) {
        _state.value = newState
        store?.writeAll(newState.folders, newState.reminders)
    }
}
