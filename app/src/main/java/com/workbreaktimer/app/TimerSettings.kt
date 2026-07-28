package com.workbreaktimer.app

import android.content.Context
import android.content.SharedPreferences

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
    val autoStartEnabled: Boolean = false
) {
    companion object {
        const val DEFAULT_WORK_MILLIS = 30 * 60_000L
        const val DEFAULT_BREAK_MILLIS = 5 * 60_000L
        const val DEFAULT_IDLE_MILLIS = 5 * 60_000L
        const val DEFAULT_SNOOZE_MILLIS = 5 * 60_000L

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
        autoStartEnabled = prefs.getBoolean(KEY_AUTO_START, false)
    )

    fun write(settings: TimerSettings) {
        prefs.edit()
            .putLong(KEY_WORK, settings.workMillis)
            .putLong(KEY_BREAK, settings.breakMillis)
            .putLong(KEY_IDLE, settings.idleThresholdMillis)
            .putLong(KEY_SNOOZE, settings.snoozeMillis)
            .putBoolean(KEY_AUTO_START, settings.autoStartEnabled)
            .apply()
    }

    private companion object {
        const val PREFS = "timer_prefs"
        const val KEY_WORK = "work_duration"
        const val KEY_BREAK = "break_duration"
        const val KEY_IDLE = "idle_threshold"
        const val KEY_SNOOZE = "snooze_duration"
        const val KEY_AUTO_START = "auto_start_enabled"
    }
}
