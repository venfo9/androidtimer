package com.workbreaktimer.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

enum class WorkoutStage { IDLE, COUNTDOWN, WORK, REST, PAUSED, REVIEW, FINISHED }

/** What a REST is for: the pause between the exercises of a superset, or the rest after a round. */
enum class RestKind { ROUND, TRANSITION }

data class WorkoutRunState(
    val stage: WorkoutStage = WorkoutStage.IDLE,
    val template: WorkoutTemplate? = null,
    /** Set (round) in progress, from 0. During rest after a round it already points at the next one. */
    val setIndex: Int = 0,
    /** Exercise of the superset in progress; always 0 for a plain exercise. */
    val partIndex: Int = 0,
    val phaseIndex: Int = 0,
    /** Second of the current phase that was just announced, from 1. */
    val secondInPhase: Int = 0,
    val countdown: Int = 0,
    val restLeft: Int = 0,
    /** Seconds of rest so far, shown when the next set waits for the user's button. */
    val restElapsed: Int = 0,
    val restKind: RestKind = RestKind.ROUND,
    val pausedFrom: WorkoutStage? = null,
    /** Repetitions done, by exercise and then by set. */
    val reps: List<List<Int>> = emptyList(),
    /** Kilograms, by exercise and then by set; start from the template and can change on the go. */
    val weights: List<List<Double>> = emptyList(),
    /** The set finished last, which rest offers to correct; -1 before the first. */
    val lastDonePart: Int = -1,
    val lastDoneSet: Int = -1,
    val startedAtMillis: Long = 0,
    /** When the last set ended; fixed at REVIEW so time spent checking the numbers is not counted. */
    val endedAtMillis: Long = 0,
    /** At REVIEW: all planned sets were done, rather than stopped early. */
    val completed: Boolean = false,
    /** What went into the history once the workout is FINISHED; empty if nothing was saved. */
    val summary: List<WorkoutSession> = emptyList()
) {
    val active: Boolean
        get() = stage == WorkoutStage.COUNTDOWN || stage == WorkoutStage.WORK ||
            stage == WorkoutStage.REST || stage == WorkoutStage.PAUSED

    val part: ExercisePart? get() = template?.parts?.getOrNull(partIndex)

    val repsInCurrentSet: Int get() = reps.getOrNull(partIndex)?.getOrNull(setIndex) ?: 0

    val weightOfCurrentSet: Double get() = weights.getOrNull(partIndex)?.getOrNull(setIndex) ?: 0.0

    /** The rest has no timer and waits for the user's button. */
    val manualRest: Boolean
        get() = if (restKind == RestKind.TRANSITION) template?.manualTransition == true else template?.manualNextSet == true

    /** The stage the screen should describe; a pause keeps showing where it stopped. */
    val shownStage: WorkoutStage get() = if (stage == WorkoutStage.PAUSED) pausedFrom ?: stage else stage
}

/**
 * Runs a workout one beat per second: announces each second of each phase, a tone after each
 * repetition, pauses between the exercises of a superset, rest between rounds, and hands the
 * result to the user for review before it goes to [WorkoutStore].
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
        val rounds = template.rounds
        publish(
            WorkoutRunState(
                stage = WorkoutStage.COUNTDOWN,
                template = template,
                countdown = START_COUNTDOWN,
                reps = template.parts.map { List(rounds) { 0 } },
                weights = template.parts.map { part -> List(rounds) { part.setPlans[it].weightKg } },
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

    /** Ends rest or a transition now; the next set starts after a short countdown. */
    fun skipRest() {
        val current = _state.value
        if (!current.resting) return
        handler.removeCallbacks(beatRunnable)
        voice?.silence()
        publish(current.copy(stage = WorkoutStage.COUNTDOWN, countdown = RESUME_COUNTDOWN, restLeft = 0, pausedFrom = null))
        voice?.acquireFocus()
        beatNow()
    }

    fun addRest(seconds: Int) {
        val current = _state.value
        if (!current.resting) return
        // Pushed out of the countdown range, the music comes back up until the countdown starts over.
        if (current.restLeft + seconds > REST_COUNTDOWN) voice?.releaseFocus()
        publish(current.copy(restLeft = current.restLeft + seconds))
    }

    private val WorkoutRunState.resting: Boolean
        get() = stage == WorkoutStage.REST || (stage == WorkoutStage.PAUSED && pausedFrom == WorkoutStage.REST)

    /**
     * Changes the weight of the current set (during rest, of the next one) of the current
     * exercise. Its later sets planned with the same weight follow, so with one weight for all
     * sets a correction sticks, while a pyramid keeps its other steps.
     */
    fun setWeight(kg: Double) {
        val current = _state.value
        if (!current.active) return
        val old = current.weightOfCurrentSet
        val partWeights = current.weights[current.partIndex].mapIndexed { index, weight ->
            when {
                index == current.setIndex -> kg
                index > current.setIndex && weight == old -> kg
                else -> weight
            }
        }
        publish(current.copy(weights = current.weights.toMutableList().also { it[current.partIndex] = partWeights }))
    }

    /**
     * Fixes the numbers of one set after the fact: during rest for the set just done, and at
     * REVIEW for any set. The engine counts repetitions of the tempo, not what the user lifted.
     */
    fun correctSet(part: Int, set: Int, reps: Int, weightKg: Double) {
        val current = _state.value
        if (!current.active && current.stage != WorkoutStage.REVIEW) return
        val partReps = current.reps.getOrNull(part) ?: return
        if (set !in partReps.indices) return
        publish(
            current.copy(
                reps = current.reps.toMutableList().also { list ->
                    list[part] = partReps.toMutableList().also { it[set] = reps.coerceAtLeast(0) }
                },
                weights = current.weights.toMutableList().also { list ->
                    list[part] = current.weights[part].toMutableList().also { it[set] = weightKg }
                }
            )
        )
    }

    /** Ends early; what was done goes to REVIEW like a finished workout. */
    fun finish() {
        if (!_state.value.active) return
        conclude(_state.value, completed = false)
    }

    /**
     * Writes the reviewed sets to the history, one entry per exercise, so a superset counts
     * toward each exercise's own statistics. Sets left at zero repetitions are dropped.
     */
    fun saveReview() {
        val current = _state.value
        val template = current.template ?: return
        if (current.stage != WorkoutStage.REVIEW) return
        val sessions = template.parts.mapIndexedNotNull { p, part ->
            val partReps = current.reps.getOrNull(p) ?: return@mapIndexedNotNull null
            val kept = partReps.indices.filter { partReps[it] > 0 }
            if (kept.isEmpty()) return@mapIndexedNotNull null
            WorkoutSession(
                id = UUID.randomUUID().toString(),
                name = part.name,
                startedAtMillis = current.startedAtMillis,
                durationMillis = current.endedAtMillis - current.startedAtMillis,
                repsPerSet = kept.map { partReps[it] },
                weightsPerSet = kept.map { current.weights[p].getOrElse(it) { 0.0 } },
                plannedRepsPerSet = part.setPlans.map { it.reps },
                completed = current.completed,
                folderId = template.folderId,
                supersetName = if (template.isSuperset) template.name else null
            )
        }
        val context = appContext
        // The newest entry goes on top of the history, so add in reverse to keep exercise order.
        if (context != null) sessions.asReversed().forEach { WorkoutStore.addSession(context, it) }
        publish(current.copy(stage = WorkoutStage.FINISHED, summary = sessions))
    }

    fun discardReview() {
        if (_state.value.stage == WorkoutStage.REVIEW) publish(WorkoutRunState())
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
        val part = current.part ?: return
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
                if (second > part.phases[phase].seconds) {
                    phase++
                    second = 1
                }
                if (phase >= part.phases.size) {
                    completeRep(current, part)
                } else {
                    voice?.say(second)
                    publish(current.copy(phaseIndex = phase, secondInPhase = second))
                }
            }
            WorkoutStage.REST -> if (current.manualRest) {
                publish(current.copy(restElapsed = current.restElapsed + 1))
            } else {
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

    private fun completeRep(current: WorkoutRunState, part: ExercisePart) {
        val template = current.template ?: return
        val partReps = current.reps[current.partIndex].toMutableList()
        partReps[current.setIndex] = partReps[current.setIndex] + 1
        val updated = current.copy(reps = current.reps.toMutableList().also { it[current.partIndex] = partReps })
        if (partReps[current.setIndex] < part.setPlans[current.setIndex].reps) {
            startRep(updated)
            return
        }
        val done = updated.copy(lastDonePart = current.partIndex, lastDoneSet = current.setIndex, phaseIndex = 0, secondInPhase = 0)
        when {
            // A superset moves on to its next exercise within the same round.
            current.partIndex < template.parts.lastIndex -> pauseBetweenSets(
                done.copy(partIndex = current.partIndex + 1, restKind = RestKind.TRANSITION),
                manual = template.manualTransition,
                seconds = template.transitionSeconds
            )
            current.setIndex + 1 >= template.rounds -> conclude(done, completed = true)
            else -> pauseBetweenSets(
                done.copy(partIndex = 0, setIndex = current.setIndex + 1, restKind = RestKind.ROUND),
                manual = template.manualNextSet,
                seconds = template.restSeconds
            )
        }
    }

    private fun pauseBetweenSets(next: WorkoutRunState, manual: Boolean, seconds: Int) {
        voice?.tone(WorkoutVoice.Tone.SET)
        when {
            manual -> {
                voice?.releaseFocus()
                publish(next.copy(stage = WorkoutStage.REST, restLeft = 0, restElapsed = 0))
            }
            seconds > 0 -> {
                voice?.releaseFocus()
                publish(next.copy(stage = WorkoutStage.REST, restLeft = seconds, restElapsed = 0))
            }
            else -> publish(next.copy(stage = WorkoutStage.COUNTDOWN, countdown = RESUME_COUNTDOWN))
        }
    }

    // endregion

    /**
     * Stops the beats and hands the sets over for review. Rounds that never started say
     * nothing about the workout, so they are left out of it.
     */
    private fun conclude(current: WorkoutRunState, completed: Boolean) {
        stopSound()
        if (completed) voice?.tone(WorkoutVoice.Tone.DONE)
        val rounds = current.reps.firstOrNull()?.size ?: 0
        val lastStarted = (0 until rounds).lastOrNull { round ->
            round < current.setIndex || current.reps.any { it[round] > 0 }
        }
        val keep = (lastStarted ?: -1) + 1
        publish(
            if (current.reps.sumOf { it.sum() } == 0) {
                current.copy(stage = WorkoutStage.FINISHED, pausedFrom = null, summary = emptyList())
            } else {
                current.copy(
                    stage = WorkoutStage.REVIEW,
                    pausedFrom = null,
                    reps = current.reps.map { it.take(keep) },
                    weights = current.weights.map { it.take(keep) },
                    endedAtMillis = System.currentTimeMillis(),
                    completed = completed
                )
            }
        )
        appContext?.let { WorkoutService.stop(it) }
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
