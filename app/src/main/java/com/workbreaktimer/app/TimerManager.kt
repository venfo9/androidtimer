package com.workbreaktimer.app

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class TimerPhase { WORK, BREAK }
enum class TimerStatus { IDLE, RUNNING, PAUSED, FINISHED }

data class TimerUiState(
    val phase: TimerPhase = TimerPhase.WORK,
    val status: TimerStatus = TimerStatus.IDLE,
    val workDurationMillis: Long = DEFAULT_WORK_MILLIS,
    val totalMillis: Long = DEFAULT_WORK_MILLIS,
    val remainingMillis: Long = DEFAULT_WORK_MILLIS,
    val endTimeMillis: Long = 0L
) {
    companion object {
        const val DEFAULT_WORK_MILLIS = 30 * 60 * 1000L
    }
}

/**
 * Single source of truth for timer state. Shared in-process by MainActivity,
 * AlarmActivity, TimerAlarmReceiver and AlarmRingService. Persisted to
 * SharedPreferences so state survives process death; actual phase completion
 * is driven by an exact AlarmManager alarm rather than an in-app countdown,
 * so it fires even if the app has been swiped away.
 */
object TimerManager {

    const val BREAK_MILLIS = 5 * 60 * 1000L
    private const val PREFS = "timer_prefs"
    private const val ALARM_REQUEST_CODE = 1001

    private const val KEY_PHASE = "phase"
    private const val KEY_STATUS = "status"
    private const val KEY_WORK_DURATION = "work_duration"
    private const val KEY_TOTAL = "total"
    private const val KEY_REMAINING = "remaining"
    private const val KEY_END_TIME = "end_time"

    private val _state = MutableStateFlow(TimerUiState())
    val state: StateFlow<TimerUiState> = _state.asStateFlow()

    private var initialized = false

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        _state.value = readState(context)
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun readState(context: Context): TimerUiState {
        val p = prefs(context)
        val phase = TimerPhase.valueOf(p.getString(KEY_PHASE, TimerPhase.WORK.name)!!)
        val status = TimerStatus.valueOf(p.getString(KEY_STATUS, TimerStatus.IDLE.name)!!)
        val workDuration = p.getLong(KEY_WORK_DURATION, TimerUiState.DEFAULT_WORK_MILLIS)
        val total = p.getLong(KEY_TOTAL, workDuration)
        val remaining = p.getLong(KEY_REMAINING, workDuration)
        val endTime = p.getLong(KEY_END_TIME, 0L)
        return TimerUiState(phase, status, workDuration, total, remaining, endTime)
    }

    private fun persist(state: TimerUiState, context: Context) {
        prefs(context).edit()
            .putString(KEY_PHASE, state.phase.name)
            .putString(KEY_STATUS, state.status.name)
            .putLong(KEY_WORK_DURATION, state.workDurationMillis)
            .putLong(KEY_TOTAL, state.totalMillis)
            .putLong(KEY_REMAINING, state.remainingMillis)
            .putLong(KEY_END_TIME, state.endTimeMillis)
            .apply()
    }

    private fun update(context: Context, transform: (TimerUiState) -> TimerUiState) {
        val newState = transform(_state.value)
        _state.value = newState
        persist(newState, context)
    }

    fun setWorkMinutes(context: Context, minutes: Int) {
        val millis = minutes.coerceIn(1, 180) * 60 * 1000L
        update(context) { s ->
            if (s.phase == TimerPhase.WORK && s.status == TimerStatus.IDLE) {
                s.copy(workDurationMillis = millis, totalMillis = millis, remainingMillis = millis)
            } else s
        }
    }

    fun start(context: Context) {
        update(context) { s ->
            val total = if (s.phase == TimerPhase.WORK) s.workDurationMillis else BREAK_MILLIS
            val end = System.currentTimeMillis() + total
            scheduleAlarm(context, end)
            s.copy(status = TimerStatus.RUNNING, totalMillis = total, remainingMillis = total, endTimeMillis = end)
        }
    }

    fun pause(context: Context) {
        cancelAlarm(context)
        update(context) { s ->
            if (s.status != TimerStatus.RUNNING) return@update s
            val remaining = (s.endTimeMillis - System.currentTimeMillis()).coerceAtLeast(0)
            s.copy(status = TimerStatus.PAUSED, remainingMillis = remaining)
        }
    }

    fun resume(context: Context) {
        update(context) { s ->
            if (s.status != TimerStatus.PAUSED) return@update s
            val end = System.currentTimeMillis() + s.remainingMillis
            scheduleAlarm(context, end)
            s.copy(status = TimerStatus.RUNNING, endTimeMillis = end)
        }
    }

    fun reset(context: Context) {
        cancelAlarm(context)
        context.stopService(Intent(context, AlarmRingService::class.java))
        update(context) { s ->
            val total = if (s.phase == TimerPhase.WORK) s.workDurationMillis else BREAK_MILLIS
            s.copy(status = TimerStatus.IDLE, totalMillis = total, remainingMillis = total, endTimeMillis = 0L)
        }
    }

    fun onAlarmFired(context: Context) {
        update(context) { s -> s.copy(status = TimerStatus.FINISHED, remainingMillis = 0L) }
    }

    fun advancePhaseAndStart(context: Context) {
        context.stopService(Intent(context, AlarmRingService::class.java))
        update(context) { s ->
            val nextPhase = if (s.phase == TimerPhase.WORK) TimerPhase.BREAK else TimerPhase.WORK
            val total = if (nextPhase == TimerPhase.WORK) s.workDurationMillis else BREAK_MILLIS
            val end = System.currentTimeMillis() + total
            scheduleAlarm(context, end)
            s.copy(phase = nextPhase, status = TimerStatus.RUNNING, totalMillis = total, remainingMillis = total, endTimeMillis = end)
        }
    }

    fun stopRingingOnly(context: Context) {
        context.stopService(Intent(context, AlarmRingService::class.java))
    }

    @SuppressLint("MissingPermission")
    private fun scheduleAlarm(context: Context, triggerAtMillis: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, alarmPendingIntent(context))
    }

    private fun cancelAlarm(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(alarmPendingIntent(context))
    }

    private fun alarmPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, TimerAlarmReceiver::class.java)
        return PendingIntent.getBroadcast(
            context, ALARM_REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
