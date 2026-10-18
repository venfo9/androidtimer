package com.workbreaktimer.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

enum class WorkoutStage { IDLE, COUNTDOWN, WORK, REST, PAUSED, FINISHED }

data class WorkoutRunState(
    val stage: WorkoutStage = WorkoutStage.IDLE,
    val template: WorkoutTemplate? = null,
    /** Set in progress, from 0. During rest it already points at the set that comes next. */
    val setIndex: Int = 0,
    val phaseIndex: Int = 0,
    /** Second of the current phase that was just announced, from 1. */
    val secondInPhase: Int = 0,
    val countdown: Int = 0,
    val restLeft: Int = 0,
    val pausedFrom: WorkoutStage? = null,
    val repsPerSet: List<Int> = emptyList(),
    val startedAtMillis: Long = 0,
    /** What went into the history; set once the workout is FINISHED, null if nothing was done. */
    val summary: WorkoutSession? = null
) {
    val active: Boolean
        get() = stage == WorkoutStage.COUNTDOWN || stage == WorkoutStage.WORK ||
            stage == WorkoutStage.REST || stage == WorkoutStage.PAUSED

    val repsInCurrentSet: Int get() = repsPerSet.getOrElse(setIndex) { 0 }

    /** The stage the screen should describe; a pause keeps showing where it stopped. */
    val shownStage: WorkoutStage get() = if (stage == WorkoutStage.PAUSED) pausedFrom ?: stage else stage
}

/**
 * Runs a workout one beat per second: announces each second of each phase, a tone after each
 * repetition, rest between sets, and writes the result to [WorkoutStore].
 *
 * Beats are scheduled against absolute uptime so a late beat does not push all later ones
 * back. WorkoutService holds a wake lock for the whole workout, which keeps uptime running
 * and the handler firing while the screen is off.
 */
object WorkoutEngine {

    private const val START_COUNTDOWN = 5
    private const val RESUME_COUNTDOWN = 3
    private const val BEAT_MILLIS = 1000L

    /** Rest seconds announced aloud; the last five work as the countdown to the next set. */
    private const val REST_ANNOUNCE_AT = 10
    private const val REST_COUNTDOWN = 5

    private val _state = MutableStateFlow(WorkoutRunState())
    val state: StateFlow<WorkoutRunState> = _state.asStateFlow()

    private val handler = Handler(Looper.getMainLooper())
    private val beatRunnable = Runnable { beat() }
    private var nextBeatAt = 0L

    private var appContext: Context? = null
    private var voice: WorkoutVoice? = null

    private val idleVoiceStatus = MutableStateFlow(WorkoutVoice.Status.LOADING)

    val voiceStatus: StateFlow<WorkoutVoice.Status>
        get() = voice?.status ?: idleVoiceStatus

    /** Starts text-to-speech early, so the first number of the countdown is not lost to its loading. */
    fun prepare(context: Context) {
        if (voice != null) return
        appContext = context.applicationContext
        voice = WorkoutVoice(context.applicationContext)
    }

    fun start(context: Context, template: WorkoutTemplate) {
        prepare(context)
        if (_state.value.active) return
        publish(
            WorkoutRunState(
                stage = WorkoutStage.COUNTDOWN,
                template = template,
                countdown = START_COUNTDOWN,
                repsPerSet = List(template.sets) { 0 },
                startedAtMillis = System.currentTimeMillis()
            )
        )
        WorkoutService.start(context)
        voice?.acquireFocus()
        beatNow()
    }

    fun togglePause() {
        if (_state.value.stage == WorkoutStage.PAUSED) resume() else pause()
    }

    fun pause() {
        val current = _state.value
        if (!current.active || current.stage == WorkoutStage.PAUSED) return
        handler.removeCallbacks(beatRunnable)
        voice?.silence()
        voice?.releaseFocus()
        publish(current.copy(stage = WorkoutStage.PAUSED, pausedFrom = current.stage))
    }

    /**
     * Rest continues where it stopped. Anything else restarts the interrupted repetition after a
     * short countdown, since the user has usually left the starting position by then.
     */
    fun resume() {
        val current = _state.value
        if (current.stage != WorkoutStage.PAUSED) return
        if (current.pausedFrom == WorkoutStage.REST) {
            publish(current.copy(stage = WorkoutStage.REST, pausedFrom = null))
            nextBeatAt = SystemClock.uptimeMillis() + BEAT_MILLIS
            handler.postAtTime(beatRunnable, nextBeatAt)
        } else {
            publish(current.copy(stage = WorkoutStage.COUNTDOWN, countdown = RESUME_COUNTDOWN, pausedFrom = null))
            voice?.acquireFocus()
            beatNow()
        }
    }

    fun skipRest() {
        val current = _state.value
        val resting = current.stage == WorkoutStage.REST ||
            (current.stage == WorkoutStage.PAUSED && current.pausedFrom == WorkoutStage.REST)
        if (!resting) return
        handler.removeCallbacks(beatRunnable)
        voice?.silence()
        publish(current.copy(stage = WorkoutStage.COUNTDOWN, countdown = RESUME_COUNTDOWN, restLeft = 0, pausedFrom = null))
        voice?.acquireFocus()
        beatNow()
    }

    fun addRest(seconds: Int) {
        val current = _state.value
        val resting = current.stage == WorkoutStage.REST ||
            (current.stage == WorkoutStage.PAUSED && current.pausedFrom == WorkoutStage.REST)
        if (!resting) return
        // Pushed out of the countdown range, the music comes back up until the countdown starts over.
        if (current.restLeft + seconds > REST_COUNTDOWN) voice?.releaseFocus()
        publish(current.copy(restLeft = current.restLeft + seconds))
    }

    /** Ends early and keeps what was done. */
    fun finish() {
        if (!_state.value.active) return
        conclude(_state.value, completed = false)
    }

    /** Throws the workout away without saving it. */
    fun reset() {
        stopSound()
        publish(WorkoutRunState())
        appContext?.let { WorkoutService.stop(it) }
    }

    /** Hides the summary of a finished workout. */
    fun dismissSummary() {
        if (_state.value.stage == WorkoutStage.FINISHED) publish(WorkoutRunState())
    }

    // region beats

    private fun beatNow() {
        handler.removeCallbacks(beatRunnable)
        nextBeatAt = SystemClock.uptimeMillis()
        beat()
    }

    private fun beat() {
        val current = _state.value
        val template = current.template ?: return
        when (current.stage) {
            WorkoutStage.COUNTDOWN -> {
                if (current.countdown > 0) {
                    voice?.say(current.countdown)
                    publish(current.copy(countdown = current.countdown - 1))
                } else {
                    startRep(current)
                }
            }
            WorkoutStage.WORK -> {
                var phase = current.phaseIndex
                var second = current.secondInPhase + 1
                if (second > template.phases[phase].seconds) {
                    phase++
                    second = 1
                }
                if (phase >= template.phases.size) {
                    completeRep(current, template)
                } else {
                    voice?.say(second)
                    publish(current.copy(phaseIndex = phase, secondInPhase = second))
                }
            }
            WorkoutStage.REST -> {
                val left = current.restLeft - 1
                if (left <= 0) {
                    startRep(current.copy(restLeft = 0))
                } else {
                    if (left == REST_COUNTDOWN) voice?.acquireFocus()
                    if (left <= REST_COUNTDOWN || left == REST_ANNOUNCE_AT) voice?.say(left)
                    publish(current.copy(restLeft = left))
                }
            }
            else -> return
        }
        val stage = _state.value.stage
        if (stage == WorkoutStage.COUNTDOWN || stage == WorkoutStage.WORK || stage == WorkoutStage.REST) {
            nextBeatAt += BEAT_MILLIS
            // After a long stall (a debugger, a frozen process) skip ahead rather than racing
            // through the missed beats.
            val now = SystemClock.uptimeMillis()
            if (nextBeatAt < now) nextBeatAt = now + BEAT_MILLIS
            handler.postAtTime(beatRunnable, nextBeatAt)
        }
    }

    /** A tone, then "1" for the first second of the first phase once the tone is over. */
    private fun startRep(current: WorkoutRunState) {
        voice?.acquireFocus()
        voice?.tone(WorkoutVoice.Tone.REP)
        voice?.say(1, delayMillis = WorkoutVoice.Tone.REP.millis + 50L)
        publish(current.copy(stage = WorkoutStage.WORK, phaseIndex = 0, secondInPhase = 1))
    }

    private fun completeRep(current: WorkoutRunState, template: WorkoutTemplate) {
        val reps = current.repsPerSet.toMutableList()
        reps[current.setIndex] = reps[current.setIndex] + 1
        val updated = current.copy(repsPerSet = reps)
        if (reps[current.setIndex] < template.reps) {
            startRep(updated)
            return
        }
        if (current.setIndex + 1 >= template.sets) {
            conclude(updated, completed = true)
            return
        }
        voice?.tone(WorkoutVoice.Tone.SET)
        val next = updated.copy(setIndex = current.setIndex + 1, phaseIndex = 0, secondInPhase = 0)
        if (template.restSeconds > 0) {
            voice?.releaseFocus()
            publish(next.copy(stage = WorkoutStage.REST, restLeft = template.restSeconds))
        } else {
            publish(next.copy(stage = WorkoutStage.COUNTDOWN, countdown = RESUME_COUNTDOWN))
        }
    }

    // endregion

    private fun conclude(current: WorkoutRunState, completed: Boolean) {
        val template = current.template ?: return
        stopSound()
        if (completed) voice?.tone(WorkoutVoice.Tone.DONE)
        // Sets that never started say nothing about the workout and only clutter the history.
        val repsPerSet = current.repsPerSet.filterIndexed { index, reps -> reps > 0 || index < current.setIndex }
        val session = if (repsPerSet.sum() > 0) {
            WorkoutSession(
                id = UUID.randomUUID().toString(),
                name = template.name,
                startedAtMillis = current.startedAtMillis,
                durationMillis = System.currentTimeMillis() - current.startedAtMillis,
                repsPerSet = repsPerSet,
                plannedReps = template.reps,
                plannedSets = template.sets,
                completed = completed
            )
        } else null
        val context = appContext
        if (session != null && context != null) WorkoutStore.addSession(context, session)
        publish(current.copy(stage = WorkoutStage.FINISHED, pausedFrom = null, summary = session))
        context?.let { WorkoutService.stop(it) }
    }

    private fun stopSound() {
        handler.removeCallbacks(beatRunnable)
        voice?.silence()
        voice?.releaseFocus()
    }

    private fun publish(newState: WorkoutRunState) {
        _state.value = newState
        appContext?.let { WorkoutService.updateNotification(it, newState) }
    }
}
