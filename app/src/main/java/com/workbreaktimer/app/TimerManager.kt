package com.workbreaktimer.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class TimerPhase { WORK, BREAK }
enum class TimerStatus { IDLE, RUNNING, PAUSED, FINISHED }

data class TimerUiState(
    val phase: TimerPhase = TimerPhase.WORK,
    val status: TimerStatus = TimerStatus.IDLE,
    val workDurationMillis: Long = DEFAULT_WORK_MILLIS,
    val breakDurationMillis: Long = DEFAULT_BREAK_MILLIS,
    val totalMillis: Long = DEFAULT_WORK_MILLIS,
    val remainingMillis: Long = DEFAULT_WORK_MILLIS,
    val endTimeMillis: Long = 0L,
    val ringing: Boolean = false,
    val autoStartEnabled: Boolean = false,
    val idleThresholdMillis: Long = DEFAULT_IDLE_MILLIS
) {
    companion object {
        const val DEFAULT_WORK_MILLIS = 30 * 60 * 1000L
        const val DEFAULT_BREAK_MILLIS = 5 * 60 * 1000L
        const val DEFAULT_IDLE_MILLIS = 5 * 60 * 1000L
    }

    /**
     * Step tracking only runs at the moments the user is expected to begin working:
     * a fresh or just-reset work phase, or right after a break whose alarm was
     * silenced without starting work by hand. Never while a timer runs, and never
     * after a work phase ends — there the user is meant to get up, not sit down.
     */
    val shouldTrackSteps: Boolean
        get() = autoStartEnabled && !ringing &&
            ((phase == TimerPhase.WORK && status == TimerStatus.IDLE) ||
                (phase == TimerPhase.BREAK && status == TimerStatus.FINISHED))
}

/**
 * Single source of truth for timer state. Shared in-process by MainActivity,
 * AlarmActivity, TimerAlarmReceiver and AlarmRingService. Persisted to
 * SharedPreferences so state survives process death; actual phase completion
 * is driven by an exact AlarmManager alarm rather than an in-app countdown,
 * so it fires even if the app has been swiped away.
 */
object TimerManager {

    private const val PREFS = "timer_prefs"
    private const val ALARM_REQUEST_CODE = 1001
    private const val SHOW_ALARM_REQUEST_CODE = 1002

    private const val KEY_PHASE = "phase"
    private const val KEY_STATUS = "status"
    private const val KEY_WORK_DURATION = "work_duration"
    private const val KEY_BREAK_DURATION = "break_duration"
    private const val KEY_TOTAL = "total"
    private const val KEY_REMAINING = "remaining"
    private const val KEY_END_TIME = "end_time"
    private const val KEY_RINGING = "ringing"
    private const val KEY_AUTO_START = "auto_start_enabled"
    private const val KEY_IDLE_THRESHOLD = "idle_threshold"

    private val _state = MutableStateFlow(TimerUiState())
    val state: StateFlow<TimerUiState> = _state.asStateFlow()

    private var initialized = false

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        _state.value = readState(context)
    }

    /** Re-applies the tracking invariant after a cold start or a permission grant. */
    fun syncStepTracking(context: Context) {
        reconcileStepTracking(context, _state.value)
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun readState(context: Context): TimerUiState {
        val p = prefs(context)
        val phase = TimerPhase.valueOf(p.getString(KEY_PHASE, TimerPhase.WORK.name)!!)
        val status = TimerStatus.valueOf(p.getString(KEY_STATUS, TimerStatus.IDLE.name)!!)
        val workDuration = p.getLong(KEY_WORK_DURATION, TimerUiState.DEFAULT_WORK_MILLIS)
        val breakDuration = p.getLong(KEY_BREAK_DURATION, TimerUiState.DEFAULT_BREAK_MILLIS)
        return TimerUiState(
            phase = phase,
            status = status,
            workDurationMillis = workDuration,
            breakDurationMillis = breakDuration,
            totalMillis = p.getLong(KEY_TOTAL, workDuration),
            remainingMillis = p.getLong(KEY_REMAINING, workDuration),
            endTimeMillis = p.getLong(KEY_END_TIME, 0L),
            ringing = p.getBoolean(KEY_RINGING, false),
            autoStartEnabled = p.getBoolean(KEY_AUTO_START, false),
            idleThresholdMillis = p.getLong(KEY_IDLE_THRESHOLD, TimerUiState.DEFAULT_IDLE_MILLIS)
        )
    }

    private fun persist(state: TimerUiState, context: Context) {
        prefs(context).edit()
            .putString(KEY_PHASE, state.phase.name)
            .putString(KEY_STATUS, state.status.name)
            .putLong(KEY_WORK_DURATION, state.workDurationMillis)
            .putLong(KEY_BREAK_DURATION, state.breakDurationMillis)
            .putLong(KEY_TOTAL, state.totalMillis)
            .putLong(KEY_REMAINING, state.remainingMillis)
            .putLong(KEY_END_TIME, state.endTimeMillis)
            .putBoolean(KEY_RINGING, state.ringing)
            .putBoolean(KEY_AUTO_START, state.autoStartEnabled)
            .putLong(KEY_IDLE_THRESHOLD, state.idleThresholdMillis)
            .apply()
    }

    private fun update(context: Context, transform: (TimerUiState) -> TimerUiState) {
        val newState = transform(_state.value)
        _state.value = newState
        persist(newState, context)
        reconcileStepTracking(context, newState)
    }

    /** Keeps StepTrackingService running exactly while [TimerUiState.shouldTrackSteps] holds. */
    private fun reconcileStepTracking(context: Context, state: TimerUiState) {
        val intent = Intent(context, StepTrackingService::class.java)
        if (state.shouldTrackSteps && hasActivityRecognitionPermission(context)) {
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                // Foreground start not allowed from the current context; tracking will be
                // retried on the next state change that happens while the app is visible.
            }
        } else {
            context.stopService(intent)
        }
    }

    fun hasActivityRecognitionPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) ==
            PackageManager.PERMISSION_GRANTED

    fun setWorkMinutes(context: Context, minutes: Int) {
        val millis = minutes.coerceIn(1, 180) * 60 * 1000L
        update(context) { s ->
            val syncDisplay = s.phase == TimerPhase.WORK && s.status == TimerStatus.IDLE
            s.copy(
                workDurationMillis = millis,
                totalMillis = if (syncDisplay) millis else s.totalMillis,
                remainingMillis = if (syncDisplay) millis else s.remainingMillis
            )
        }
    }

    fun setBreakMinutes(context: Context, minutes: Int) {
        val millis = minutes.coerceIn(1, 180) * 60 * 1000L
        update(context) { s ->
            val syncDisplay = s.phase == TimerPhase.BREAK && s.status == TimerStatus.IDLE
            s.copy(
                breakDurationMillis = millis,
                totalMillis = if (syncDisplay) millis else s.totalMillis,
                remainingMillis = if (syncDisplay) millis else s.remainingMillis
            )
        }
    }

    fun setAutoStartEnabled(context: Context, enabled: Boolean) {
        update(context) { s -> s.copy(autoStartEnabled = enabled) }
    }

    fun setIdleMinutes(context: Context, minutes: Int) {
        val millis = minutes.coerceIn(1, 180) * 60 * 1000L
        update(context) { s -> s.copy(idleThresholdMillis = millis) }
    }

    fun start(context: Context) {
        update(context) { s ->
            val total = if (s.phase == TimerPhase.WORK) s.workDurationMillis else s.breakDurationMillis
            val end = System.currentTimeMillis() + total
            scheduleAlarm(context, end)
            s.copy(
                status = TimerStatus.RUNNING, ringing = false,
                totalMillis = total, remainingMillis = total, endTimeMillis = end
            )
        }
    }

    /**
     * Entry point for StepTrackingService: the phone has been still long enough to call it
     * a sitting session, so begin work regardless of whether the previous phase was a
     * finished break or an idle work phase.
     */
    fun autoStartWork(context: Context) {
        update(context) { s ->
            val total = s.workDurationMillis
            val end = System.currentTimeMillis() + total
            scheduleAlarm(context, end)
            s.copy(
                phase = TimerPhase.WORK, status = TimerStatus.RUNNING, ringing = false,
                totalMillis = total, remainingMillis = total, endTimeMillis = end
            )
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

    /** Always returns to the WORK phase, ready to start — regardless of which phase was active. */
    fun reset(context: Context) {
        cancelAlarm(context)
        context.stopService(Intent(context, AlarmRingService::class.java))
        update(context) { s ->
            s.copy(
                phase = TimerPhase.WORK,
                status = TimerStatus.IDLE,
                ringing = false,
                totalMillis = s.workDurationMillis,
                remainingMillis = s.workDurationMillis,
                endTimeMillis = 0L
            )
        }
    }

    fun onAlarmFired(context: Context) {
        update(context) { s -> s.copy(status = TimerStatus.FINISHED, ringing = true, remainingMillis = 0L) }
    }

    fun advancePhaseAndStart(context: Context) {
        context.stopService(Intent(context, AlarmRingService::class.java))
        update(context) { s ->
            val nextPhase = if (s.phase == TimerPhase.WORK) TimerPhase.BREAK else TimerPhase.WORK
            val total = if (nextPhase == TimerPhase.WORK) s.workDurationMillis else s.breakDurationMillis
            val end = System.currentTimeMillis() + total
            scheduleAlarm(context, end)
            s.copy(
                phase = nextPhase, status = TimerStatus.RUNNING, ringing = false,
                totalMillis = total, remainingMillis = total, endTimeMillis = end
            )
        }
    }

    /**
     * Silences the alarm without advancing. Clearing [TimerUiState.ringing] is what makes a
     * finished break start step tracking — the user turned the sound off but did not begin
     * working by hand.
     */
    fun stopRingingOnly(context: Context) {
        context.stopService(Intent(context, AlarmRingService::class.java))
        update(context) { s -> s.copy(ringing = false) }
    }

    /**
     * setAlarmClock (rather than setExactAndAllowWhileIdle) marks this as a user-facing
     * alarm: it shows the system alarm icon, is exempt from Doze deferral, and puts the
     * app on a temporary background allowlist when it fires.
     */
    @SuppressLint("MissingPermission")
    private fun scheduleAlarm(context: Context, triggerAtMillis: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val info = AlarmManager.AlarmClockInfo(triggerAtMillis, showAlarmPendingIntent(context))
        am.setAlarmClock(info, alarmPendingIntent(context))
    }

    private fun showAlarmPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
        return PendingIntent.getActivity(
            context, SHOW_ALARM_REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
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
