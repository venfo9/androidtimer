package com.workbreaktimer.app

import android.content.Context
import android.content.SharedPreferences

/** How an alarm announces itself when it fires. */
enum class AlarmStyle { FULL_SCREEN, SHORT_SIGNAL, SCREEN_NO_SOUND }

val AlarmStyle.label: String
    get() = when (this) {
        AlarmStyle.FULL_SCREEN -> "Полный будильник"
        AlarmStyle.SHORT_SIGNAL -> "Короткий сигнал без экрана"
        AlarmStyle.SCREEN_NO_SOUND -> "Экран и вибро без звука"
    }

/** Whether this style wakes the screen and shows AlarmActivity over the lock screen. */
val AlarmStyle.wakesScreen: Boolean
    get() = this != AlarmStyle.SHORT_SIGNAL

/**
 * Everything the user configures, kept apart from the running timer state so that adding a
 * preference never touches the phase/status machinery.
 *
 * All durations are milliseconds because that is what AlarmManager and the countdown work in;
 * the UI converts to and from minutes and seconds at the edge.
 */
data class TimerSettings(
    val workMillis: Long = DEFAULT_WORK_MILLIS,
    val breakMillis: Long = DEFAULT_BREAK_MILLIS,
    val idleThresholdMillis: Long = DEFAULT_IDLE_MILLIS,
    val snoozeMillis: Long = DEFAULT_SNOOZE_MILLIS,
    val autoStartEnabled: Boolean = false,
    /** Restart a running work phase every [walkResetSteps] steps taken in one series. */
    val walkResetEnabled: Boolean = false,
    val walkResetSteps: Int = DEFAULT_WALK_RESET_STEPS,
    /** A pause without steps longer than this ends a series and clears its count. */
    val walkGapSeconds: Int = DEFAULT_WALK_GAP_SECONDS,
    /** Steps the "today" bar on the timer screen fills up to. */
    val dailyStepGoal: Int = DEFAULT_DAILY_STEP_GOAL,
    val timerAlarmStyle: AlarmStyle = AlarmStyle.FULL_SCREEN,
    val reminderAlarmStyle: AlarmStyle = AlarmStyle.FULL_SCREEN
) {
    companion object {
        const val DEFAULT_WORK_MILLIS = 30 * 60_000L
        const val DEFAULT_BREAK_MILLIS = 5 * 60_000L
        const val DEFAULT_IDLE_MILLIS = 5 * 60_000L
        const val DEFAULT_SNOOZE_MILLIS = 5 * 60_000L
        const val DEFAULT_WALK_RESET_STEPS = 100
        const val DEFAULT_WALK_GAP_SECONDS = 30
        const val DEFAULT_DAILY_STEP_GOAL = 10_000

        /** Below a few seconds the alarm round-trip dominates; above three hours is a typo. */
        const val MIN_DURATION_MILLIS = 5_000L
        const val MAX_DURATION_MILLIS = 180 * 60_000L

        fun clamp(millis: Long): Long = millis.coerceIn(MIN_DURATION_MILLIS, MAX_DURATION_MILLIS)

        fun toMillis(minutes: Int, seconds: Int): Long =
            clamp(minutes * 60_000L + seconds * 1_000L)
    }
}

/**
 * Reads and writes [TimerSettings]. Deliberately shares the preference file and key names the
 * app already shipped with, so installs from earlier versions keep their durations.
 */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun read(): TimerSettings = TimerSettings(
        workMillis = prefs.getLong(KEY_WORK, TimerSettings.DEFAULT_WORK_MILLIS),
        breakMillis = prefs.getLong(KEY_BREAK, TimerSettings.DEFAULT_BREAK_MILLIS),
        idleThresholdMillis = prefs.getLong(KEY_IDLE, TimerSettings.DEFAULT_IDLE_MILLIS),
        snoozeMillis = prefs.getLong(KEY_SNOOZE, TimerSettings.DEFAULT_SNOOZE_MILLIS),
        autoStartEnabled = prefs.getBoolean(KEY_AUTO_START, false),
        walkResetEnabled = prefs.getBoolean(KEY_WALK_RESET, false),
        walkResetSteps = prefs.getInt(KEY_WALK_RESET_STEPS, TimerSettings.DEFAULT_WALK_RESET_STEPS),
        walkGapSeconds = prefs.getInt(KEY_WALK_GAP, TimerSettings.DEFAULT_WALK_GAP_SECONDS),
        dailyStepGoal = prefs.getInt(KEY_STEP_GOAL, TimerSettings.DEFAULT_DAILY_STEP_GOAL),
        timerAlarmStyle = readAlarmStyle(KEY_TIMER_ALARM_STYLE),
        reminderAlarmStyle = readAlarmStyle(KEY_REMINDER_ALARM_STYLE)
    )

    private fun readAlarmStyle(key: String): AlarmStyle =
        runCatching { AlarmStyle.valueOf(prefs.getString(key, "")!!) }
            .getOrDefault(AlarmStyle.FULL_SCREEN)

    fun write(settings: TimerSettings) {
        prefs.edit()
            .putLong(KEY_WORK, settings.workMillis)
            .putLong(KEY_BREAK, settings.breakMillis)
            .putLong(KEY_IDLE, settings.idleThresholdMillis)
            .putLong(KEY_SNOOZE, settings.snoozeMillis)
            .putBoolean(KEY_AUTO_START, settings.autoStartEnabled)
            .putBoolean(KEY_WALK_RESET, settings.walkResetEnabled)
            .putInt(KEY_WALK_RESET_STEPS, settings.walkResetSteps)
            .putInt(KEY_WALK_GAP, settings.walkGapSeconds)
            .putInt(KEY_STEP_GOAL, settings.dailyStepGoal)
            .putString(KEY_TIMER_ALARM_STYLE, settings.timerAlarmStyle.name)
            .putString(KEY_REMINDER_ALARM_STYLE, settings.reminderAlarmStyle.name)
            .apply()
    }

    private companion object {
        const val PREFS = "timer_prefs"
        const val KEY_WORK = "work_duration"
        const val KEY_BREAK = "break_duration"
        const val KEY_IDLE = "idle_threshold"
        const val KEY_SNOOZE = "snooze_duration"
        const val KEY_AUTO_START = "auto_start_enabled"
        const val KEY_WALK_RESET = "walk_reset_enabled"
        const val KEY_WALK_RESET_STEPS = "walk_reset_steps"
        const val KEY_WALK_GAP = "walk_gap_seconds"
        const val KEY_STEP_GOAL = "daily_step_goal"
        const val KEY_TIMER_ALARM_STYLE = "timer_alarm_style"
        const val KEY_REMINDER_ALARM_STYLE = "reminder_alarm_style"
    }
}
