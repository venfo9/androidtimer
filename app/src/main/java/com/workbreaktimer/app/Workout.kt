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

/** A saved exercise: the tempo of one repetition and how many of them to do. */
data class WorkoutTemplate(
    val id: String,
    val name: String,
    val phases: List<WorkoutPhase>,
    val reps: Int,
    val sets: Int,
    val restSeconds: Int,
    /** Kilograms for every set; 0 means body weight only. */
    val weightKg: Double = 0.0,
    /** A weight per set when they differ, e.g. a pyramid; null when [weightKg] applies to all. */
    val setWeights: List<Double>? = null
) {
    /** "3-1-1", the way tempo is written in training plans. */
    val tempoLabel: String get() = phases.joinToString("-") { it.seconds.toString() }

    fun weightForSet(index: Int): Double = setWeights?.getOrNull(index) ?: weightKg

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
    val plannedReps: Int,
    val plannedSets: Int,
    /** False when the user stopped before the last set ended. */
    val completed: Boolean
) {
    val totalReps: Int get() = repsPerSet.sum()

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
        reps = 10,
        sets = 3,
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
                templates.put(
                    JSONObject()
                        .put("id", template.id)
                        .put("name", template.name)
                        .put("phases", phases)
                        .put("reps", template.reps)
                        .put("sets", template.sets)
                        .put("rest", template.restSeconds)
                        .put("weight", template.weightKg)
                        .also { json ->
                            template.setWeights?.let { weights ->
                                json.put("set_weights", JSONArray().also { array -> weights.forEach { array.put(it) } })
                            }
                        }
                )
            }
            val sessions = JSONArray()
            for (session in newState.sessions) {
                val reps = JSONArray()
                session.repsPerSet.forEach { reps.put(it) }
                val weights = JSONArray()
                session.weightsPerSet.forEach { weights.put(it) }
                sessions.put(
                    JSONObject()
                        .put("id", session.id)
                        .put("name", session.name)
                        .put("started", session.startedAtMillis)
                        .put("duration", session.durationMillis)
                        .put("reps_per_set", reps)
                        .put("weights_per_set", weights)
                        .put("planned_reps", session.plannedReps)
                        .put("planned_sets", session.plannedSets)
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
                reps = item.optInt("reps", 10).coerceAtLeast(1),
                sets = item.optInt("sets", 1).coerceAtLeast(1),
                restSeconds = item.optInt("rest", 90).coerceAtLeast(0),
                weightKg = item.optDouble("weight", 0.0).takeUnless { it.isNaN() } ?: 0.0,
                setWeights = item.optJSONArray("set_weights")?.let { array ->
                    (0 until array.length()).map { array.optDouble(it, 0.0) }
                }
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
                plannedReps = item.optInt("planned_reps"),
                plannedSets = item.optInt("planned_sets"),
                completed = item.optBoolean("completed")
            )
        }
        WorkoutData(templates, sessions)
    } catch (e: Exception) {
        // A corrupt file must not take the app down.
        WorkoutData()
    }

    // endregion
}
