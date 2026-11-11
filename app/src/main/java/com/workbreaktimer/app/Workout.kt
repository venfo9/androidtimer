package com.workbreaktimer.app

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.UUID

/** One part of a repetition, e.g. "Вниз" for 3 seconds. */
data class WorkoutPhase(val name: String, val seconds: Int)

/** One planned set: how many repetitions, with how many kilograms (0 means body weight). */
data class SetPlan(val reps: Int, val weightKg: Double = 0.0)

/** A saved exercise: the tempo of one repetition and the sets to do. */
data class WorkoutTemplate(
    val id: String,
    val name: String,
    val phases: List<WorkoutPhase>,
    /** Sets in order, each with its own reps and weight. Never empty. */
    val setPlans: List<SetPlan>,
    val restSeconds: Int,
    /** Rest runs without a timer and the next set waits for the user's button. */
    val manualNextSet: Boolean = false
) {
    /** "3-1-1", the way tempo is written in training plans. */
    val tempoLabel: String get() = phases.joinToString("-") { it.seconds.toString() }

    val sets: Int get() = setPlans.size

    fun repsForSet(index: Int): Int = setPlans.getOrElse(index) { setPlans.last() }.reps

    fun weightForSet(index: Int): Double = setPlans.getOrElse(index) { setPlans.last() }.weightKg

    companion object {
        const val MAX_PHASES = 5
        const val MAX_PHASE_SECONDS = 30
        const val MAX_REPS = 100
        const val MAX_SETS = 20
        const val MAX_REST_SECONDS = 600
        const val MAX_WEIGHT_KG = 500.0
        const val WEIGHT_STEP_KG = 2.5
    }
}

/** "12,5 кг", without a trailing ",0" for whole numbers. */
fun formatWeight(kg: Double): String = formatNumber(kg) + " кг"

fun formatNumber(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString() else "%.2f".format(Locale("ru"), value).trimEnd('0').trimEnd(',')

/** One finished or stopped workout in the history. */
data class WorkoutSession(
    val id: String,
    val name: String,
    val startedAtMillis: Long,
    val durationMillis: Long,
    /** Repetitions done in each set that was started. */
    val repsPerSet: List<Int>,
    /** Kilograms of each set in [repsPerSet]; empty for sessions saved before weights existed. */
    val weightsPerSet: List<Double>,
    /** Repetitions the template planned for each of its sets. */
    val plannedRepsPerSet: List<Int>,
    /** False when the user stopped before the last set ended. */
    val completed: Boolean
) {
    val totalReps: Int get() = repsPerSet.sum()

    val plannedLabel: String get() = plannedRepsPerSet.joinToString(" · ")

    fun weightOfSet(index: Int): Double = weightsPerSet.getOrElse(index) { 0.0 }

    /** Kilograms lifted in total, reps times weight summed over sets. */
    val volumeKg: Double get() = repsPerSet.indices.sumOf { repsPerSet[it] * weightOfSet(it) }

    /** "10 × 20 кг · 10 × 22,5 кг", or "10 · 10" when no set had a weight. */
    val setsLabel: String
        get() = if (repsPerSet.indices.all { weightOfSet(it) == 0.0 }) {
            repsPerSet.joinToString(" · ")
        } else {
            repsPerSet.indices.joinToString(" · ") { i ->
                val kg = weightOfSet(i)
                if (kg == 0.0) "${repsPerSet[i]}" else "${repsPerSet[i]} × ${formatWeight(kg)}"
            }
        }
}

data class ExerciseStats(val name: String, val workouts: Int, val sets: Int, val reps: Int, val volumeKg: Double)

data class WorkoutData(
    val templates: List<WorkoutTemplate> = emptyList(),
    val sessions: List<WorkoutSession> = emptyList()
) {
    fun template(id: String): WorkoutTemplate? = templates.firstOrNull { it.id == id }
}

/** Exercise templates and workout history, kept in one JSON file. */
object WorkoutStore {

    private const val FILE_NAME = "workouts.json"
    private const val MAX_SESSIONS = 1000

    private val _state = MutableStateFlow(WorkoutData())
    val state: StateFlow<WorkoutData> = _state.asStateFlow()

    private var file: File? = null
    private var initialized = false

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        val target = File(context.applicationContext.filesDir, FILE_NAME)
        file = target
        if (target.exists()) {
            _state.value = load(target)
        } else {
            // The exercise the feature was designed around, so the first visit is not empty.
            commit(WorkoutData(templates = listOf(defaultTemplate())))
        }
    }

    fun newTemplateDraft(): WorkoutTemplate = defaultTemplate().copy(id = UUID.randomUUID().toString(), name = "")

    fun saveTemplate(context: Context, template: WorkoutTemplate) {
        init(context)
        val current = _state.value
        val templates = if (current.template(template.id) == null) {
            current.templates + template
        } else {
            current.templates.map { if (it.id == template.id) template else it }
        }
        commit(current.copy(templates = templates))
    }

    fun deleteTemplate(context: Context, id: String) {
        init(context)
        val current = _state.value
        commit(current.copy(templates = current.templates.filterNot { it.id == id }))
    }

    fun addSession(context: Context, session: WorkoutSession) {
        init(context)
        val current = _state.value
        commit(current.copy(sessions = (listOf(session) + current.sessions).take(MAX_SESSIONS)))
    }

    fun deleteSession(context: Context, id: String) {
        init(context)
        val current = _state.value
        commit(current.copy(sessions = current.sessions.filterNot { it.id == id }))
    }

    /** Totals per exercise name for sessions started at or after [sinceMillis]. */
    fun stats(sinceMillis: Long): List<ExerciseStats> =
        _state.value.sessions
            .filter { it.startedAtMillis >= sinceMillis }
            .groupBy { it.name }
            .map { (name, sessions) ->
                ExerciseStats(
                    name = name,
                    workouts = sessions.size,
                    sets = sessions.sumOf { it.repsPerSet.size },
                    reps = sessions.sumOf { it.totalReps },
                    volumeKg = sessions.sumOf { it.volumeKg }
                )
            }
            .sortedByDescending { it.reps }

    private fun defaultTemplate() = WorkoutTemplate(
        id = UUID.randomUUID().toString(),
        name = "Брусья",
        phases = listOf(
            WorkoutPhase("Вниз", 3),
            WorkoutPhase("Пауза", 1),
            WorkoutPhase("Вверх", 1)
        ),
        setPlans = List(3) { SetPlan(reps = 10) },
        restSeconds = 90
    )

    // region storage

    @Synchronized
    private fun commit(newState: WorkoutData) {
        _state.value = newState
        val target = file ?: return
        try {
            val templates = JSONArray()
            for (template in newState.templates) {
                val phases = JSONArray()
                template.phases.forEach { phases.put(JSONObject().put("name", it.name).put("seconds", it.seconds)) }
                val setPlans = JSONArray()
                template.setPlans.forEach { setPlans.put(JSONObject().put("reps", it.reps).put("weight", it.weightKg)) }
                templates.put(
                    JSONObject()
                        .put("id", template.id)
                        .put("name", template.name)
                        .put("phases", phases)
                        .put("set_plans", setPlans)
                        .put("rest", template.restSeconds)
                        .put("manual_next", template.manualNextSet)
                )
            }
            val sessions = JSONArray()
            for (session in newState.sessions) {
                val reps = JSONArray()
                session.repsPerSet.forEach { reps.put(it) }
                val weights = JSONArray()
                session.weightsPerSet.forEach { weights.put(it) }
                val planned = JSONArray()
                session.plannedRepsPerSet.forEach { planned.put(it) }
                sessions.put(
                    JSONObject()
                        .put("id", session.id)
                        .put("name", session.name)
                        .put("started", session.startedAtMillis)
                        .put("duration", session.durationMillis)
                        .put("reps_per_set", reps)
                        .put("weights_per_set", weights)
                        .put("planned_reps_per_set", planned)
                        .put("completed", session.completed)
                )
            }
            target.writeText(JSONObject().put("templates", templates).put("sessions", sessions).toString())
        } catch (e: Exception) {
            // Unwritable storage; the in-memory state still serves this session.
        }
    }

    private fun load(target: File): WorkoutData = try {
        val root = JSONObject(target.readText())
        val templateArray = root.optJSONArray("templates") ?: JSONArray()
        val templates = (0 until templateArray.length()).mapNotNull { i ->
            val item = templateArray.optJSONObject(i) ?: return@mapNotNull null
            val phaseArray = item.optJSONArray("phases") ?: JSONArray()
            val phases = (0 until phaseArray.length()).mapNotNull { j ->
                val phase = phaseArray.optJSONObject(j) ?: return@mapNotNull null
                WorkoutPhase(phase.optString("name"), phase.optInt("seconds", 1).coerceAtLeast(1))
            }
            if (phases.isEmpty()) return@mapNotNull null
            WorkoutTemplate(
                id = item.optString("id"),
                name = item.optString("name"),
                phases = phases,
                setPlans = readSetPlans(item),
                restSeconds = item.optInt("rest", 90).coerceAtLeast(0),
                manualNextSet = item.optBoolean("manual_next", false)
            )
        }
        val sessionArray = root.optJSONArray("sessions") ?: JSONArray()
        val sessions = (0 until sessionArray.length()).mapNotNull { i ->
            val item = sessionArray.optJSONObject(i) ?: return@mapNotNull null
            val repsArray = item.optJSONArray("reps_per_set") ?: JSONArray()
            val weightsArray = item.optJSONArray("weights_per_set") ?: JSONArray()
            WorkoutSession(
                id = item.optString("id"),
                name = item.optString("name"),
                startedAtMillis = item.optLong("started"),
                durationMillis = item.optLong("duration"),
                repsPerSet = (0 until repsArray.length()).map { repsArray.optInt(it) },
                weightsPerSet = (0 until weightsArray.length()).map { weightsArray.optDouble(it, 0.0) },
                plannedRepsPerSet = item.optJSONArray("planned_reps_per_set")
                    ?.let { array -> (0 until array.length()).map { array.optInt(it) } }
                    // Saved before sets could differ: one count for a number of sets.
                    ?: List(item.optInt("planned_sets")) { item.optInt("planned_reps") },
                completed = item.optBoolean("completed")
            )
        }
        WorkoutData(templates, sessions)
    } catch (e: Exception) {
        // A corrupt file must not take the app down.
        WorkoutData()
    }

    /**
     * Sets of a stored template. Files written before sets could differ hold one rep count,
     * a set count, and either one weight or a weight per set.
     */
    private fun readSetPlans(item: JSONObject): List<SetPlan> {
        item.optJSONArray("set_plans")?.let { array ->
            val plans = (0 until array.length()).mapNotNull { i ->
                val plan = array.optJSONObject(i) ?: return@mapNotNull null
                SetPlan(plan.optInt("reps", 10).coerceAtLeast(1), plan.optDouble("weight", 0.0))
            }
            if (plans.isNotEmpty()) return plans
        }
        val reps = item.optInt("reps", 10).coerceAtLeast(1)
        val weight = item.optDouble("weight", 0.0).takeUnless { it.isNaN() } ?: 0.0
        val weights = item.optJSONArray("set_weights")
        return List(item.optInt("sets", 1).coerceAtLeast(1)) { i ->
            SetPlan(reps, weights?.optDouble(i, weight) ?: weight)
        }
    }

    // endregion
}
