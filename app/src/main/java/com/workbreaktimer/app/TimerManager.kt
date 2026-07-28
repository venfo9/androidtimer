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

/**
 * The running timer. Configuration lives in [settings]; everything else here describes the
 * phase currently in flight.
 */
data class TimerUiState(
    val phase: TimerPhase = TimerPhase.WORK,
    val status: TimerStatus = TimerStatus.IDLE,
    val totalMillis: Long = TimerSettings.DEFAULT_WORK_MILLIS,
    val remainingMillis: Long = TimerSettings.DEFAULT_WORK_MILLIS,
    val endTimeMillis: Long = 0L,
    val ringing: Boolean = false,
    val settings: TimerSettings = TimerSettings()
) {
    /** Duration configured for whichever phase is current. */
    val phaseDurationMillis: Long
        get() = if (phase == TimerPhase.WORK) settings.workMillis else settings.breakMillis

    /**
     * Step tracking only runs at the moments the user is expected to begin working: an idle
     * or just-reset work phase, or a finished break whose alarm was silenced without starting
     * work by hand. Never while a timer runs, and never after a work phase ends — there the
     * user is meant to get up, not sit down.
     */
    val shouldTrackSteps: Boolean
        get() = settings.autoStartEnabled && !ringing &&
            ((phase == TimerPhase.WORK && status == TimerStatus.IDLE) ||
                (phase == TimerPhase.BREAK && status == TimerStatus.FINISHED))
}

/**
 * Single source of truth for timer state, shared in-process by the activities, receivers and
 * services. Persisted so state survives process death; phase completion is driven by an exact
 * AlarmManager alarm rather than an in-app countdown, so it fires even if the app was swiped
 * away.
 */
object TimerManager {

    private const val PREFS = "timer_prefs"
    private const val ALARM_REQUEST_CODE = 1001
    private const val SHOW_ALARM_REQUEST_CODE = 1002

    private const val KEY_PHASE = "phase"
    private const val KEY_STATUS = "status"
    private const val KEY_TOTAL = "total"
    private const val KEY_REMAINING = "remaining"
    private const val KEY_END_TIME = "end_time"
    private const val KEY_RINGING = "ringing"

    private val _state = MutableStateFlow(TimerUiState())
    val state: StateFlow<TimerUiState> = _state.asStateFlow()

    private var initialized = false
    private var settingsStore: SettingsStore? = null
    private var history: SessionHistoryRepository? = null

    /** Open session being timed, closed into history when the phase ends. */
    private var sessionStartedAt = 0L
    private var sessionAutoStarted = false
    private var sessionSnoozedMillis = 0L

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        settingsStore = SettingsStore(context)
        history = FileSessionHistoryRepository(context)
        _state.value = readState(context)
    }

    fun history(context: Context): SessionHistoryRepository {
        init(context)
        return history!!
    }

    /** Re-applies the tracking invariant after a cold start or a permission grant. */
    fun syncStepTracking(context: Context) = reconcileStepTracking(context, _state.value)

    // region persistence

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun readState(context: Context): TimerUiState {
        val p = prefs(context)
        val settings = SettingsStore(context).read()
        val phase = runCatching { TimerPhase.valueOf(p.getString(KEY_PHASE, "")!!) }
            .getOrDefault(TimerPhase.WORK)
        val status = runCatching { TimerStatus.valueOf(p.getString(KEY_STATUS, "")!!) }
            .getOrDefault(TimerStatus.IDLE)
        return TimerUiState(
            phase = phase,
            status = status,
            totalMillis = p.getLong(KEY_TOTAL, settings.workMillis),
            remainingMillis = p.getLong(KEY_REMAINING, settings.workMillis),
            endTimeMillis = p.getLong(KEY_END_TIME, 0L),
            ringing = p.getBoolean(KEY_RINGING, false),
            settings = settings
        )
    }

    private fun persist(state: TimerUiState, context: Context) {
        prefs(context).edit()
            .putString(KEY_PHASE, state.phase.name)
            .putString(KEY_STATUS, state.status.name)
            .putLong(KEY_TOTAL, state.totalMillis)
            .putLong(KEY_REMAINING, state.remainingMillis)
            .putLong(KEY_END_TIME, state.endTimeMillis)
            .putBoolean(KEY_RINGING, state.ringing)
            .apply()
        settingsStore?.write(state.settings)
    }

    private fun update(context: Context, transform: (TimerUiState) -> TimerUiState) {
        val newState = transform(_state.value)
        _state.value = newState
        persist(newState, context)
        reconcileStepTracking(context, newState)
    }

    // endregion

    // region settings

    fun updateSettings(context: Context, transform: (TimerSettings) -> TimerSettings) {
        update(context) { s ->
            val settings = transform(s.settings)
            // Keep the idle display in step with a duration the user just edited, but only
            // when nothing is counting down against it.
            val syncDisplay = s.status == TimerStatus.IDLE
            val phaseDuration =
                if (s.phase == TimerPhase.WORK) settings.workMillis else settings.breakMillis
            s.copy(
                settings = settings,
                totalMillis = if (syncDisplay) phaseDuration else s.totalMillis,
                remainingMillis = if (syncDisplay) phaseDuration else s.remainingMillis
            )
        }
    }

    fun setAutoStartEnabled(context: Context, enabled: Boolean) =
        updateSettings(context) { it.copy(autoStartEnabled = enabled) }

    // endregion

    // region transitions

    fun start(context: Context) = beginPhase(context, _state.value.phase, autoStarted = false)

    /**
     * Entry point for StepTrackingService: the phone has been still long enough to call it a
     * sitting session, so begin work whichever phase the user was left in.
     */
    fun autoStartWork(context: Context) = beginPhase(context, TimerPhase.WORK, autoStarted = true)

    fun advancePhaseAndStart(context: Context) {
        val next = if (_state.value.phase == TimerPhase.WORK) TimerPhase.BREAK else TimerPhase.WORK
        beginPhase(context, next, autoStarted = false)
    }

    private fun beginPhase(context: Context, phase: TimerPhase, autoStarted: Boolean) {
        context.stopService(Intent(context, AlarmRingService::class.java))
        closeOpenSession(completed = _state.value.status == TimerStatus.FINISHED)
        update(context) { s ->
            val total = if (phase == TimerPhase.WORK) s.settings.workMillis else s.settings.breakMillis
            val end = System.currentTimeMillis() + total
            scheduleAlarm(context, end)
            s.copy(
                phase = phase, status = TimerStatus.RUNNING, ringing = false,
                totalMillis = total, remainingMillis = total, endTimeMillis = end
            )
        }
        openSession(autoStarted)
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

    /** Always returns to the WORK phase, ready to start, whichever phase was active. */
    fun reset(context: Context) {
        cancelAlarm(context)
        context.stopService(Intent(context, AlarmRingService::class.java))
        closeOpenSession(completed = false)
        update(context) { s ->
            s.copy(
                phase = TimerPhase.WORK,
                status = TimerStatus.IDLE,
                ringing = false,
                totalMillis = s.settings.workMillis,
                remainingMillis = s.settings.workMillis,
                endTimeMillis = 0L
            )
        }
    }

    /**
     * Extends the phase that just ended instead of moving on: more working time when the
     * break alarm is unwelcome, more rest when it is the work alarm. The session stays open
     * so the extra time lands in the same history row.
     */
    fun snooze(context: Context) {
        context.stopService(Intent(context, AlarmRingService::class.java))
        update(context) { s ->
            if (s.status != TimerStatus.FINISHED) return@update s
            val extra = s.settings.snoozeMillis
            val end = System.currentTimeMillis() + extra
            scheduleAlarm(context, end)
            sessionSnoozedMillis += extra
            s.copy(
                status = TimerStatus.RUNNING, ringing = false,
                totalMillis = extra, remainingMillis = extra, endTimeMillis = end
            )
        }
    }

    fun onAlarmFired(context: Context) {
        update(context) { s -> s.copy(status = TimerStatus.FINISHED, ringing = true, remainingMillis = 0L) }
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

    // endregion

    // region history

    private fun openSession(autoStarted: Boolean) {
        sessionStartedAt = System.currentTimeMillis()
        sessionAutoStarted = autoStarted
        sessionSnoozedMillis = 0L
    }

    private fun closeOpenSession(completed: Boolean) {
        val startedAt = sessionStartedAt
        if (startedAt == 0L) return
        val current = _state.value
        sessionStartedAt = 0L
        history?.append(
            SessionRecord(
                phase = current.phase,
                startedAtMillis = startedAt,
                endedAtMillis = System.currentTimeMillis(),
                plannedMillis = current.phaseDurationMillis,
                completed = completed,
                autoStarted = sessionAutoStarted,
                snoozedMillis = sessionSnoozedMillis
            )
        )
    }

    // endregion

    // region alarms and tracking

    /**
     * setAlarmClock rather than setExactAndAllowWhileIdle marks this as a user-facing alarm:
     * it shows the system alarm icon, is exempt from Doze deferral, and puts the app on a
     * temporary background allowlist when it fires.
     */
    @SuppressLint("MissingPermission")
    private fun scheduleAlarm(context: Context, triggerAtMillis: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val info = AlarmManager.AlarmClockInfo(triggerAtMillis, showAlarmPendingIntent(context))
        am.setAlarmClock(info, alarmPendingIntent(context))
    }

    private fun cancelAlarm(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(alarmPendingIntent(context))
    }

    private fun alarmPendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, ALARM_REQUEST_CODE, Intent(context, TimerAlarmReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun showAlarmPendingIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context, SHOW_ALARM_REQUEST_CODE, Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /** Keeps StepTrackingService running exactly while [TimerUiState.shouldTrackSteps] holds. */
    private fun reconcileStepTracking(context: Context, state: TimerUiState) {
        val intent = Intent(context, StepTrackingService::class.java)
        if (state.shouldTrackSteps && hasActivityRecognitionPermission(context)) {
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                // Foreground start not allowed from this context; retried on the next state
                // change that happens while the app is visible.
            }
        } else {
            context.stopService(intent)
        }
    }

    fun hasActivityRecognitionPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) ==
            PackageManager.PERMISSION_GRANTED

    // endregion
}
