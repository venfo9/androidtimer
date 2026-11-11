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

/** A group of exercises on the workout tab, e.g. "День ног". */
data class WorkoutFolder(val id: String, val name: String)

/** One planned set: how many repetitions, with how many kilograms (0 means body weight). */
data class SetPlan(val reps: Int, val weightKg: Double = 0.0)

/** One exercise of a superset, or the whole content of a plain exercise. */
data class ExercisePart(val name: String, val phases: List<WorkoutPhase>, val setPlans: List<SetPlan>)

/**
 * A saved exercise: the tempo of one repetition and the sets to do. With [superset] set it is
 * a superset instead: its parts run one after another within each round, and [phases] and
 * [setPlans] mirror the first part.
 */
data class WorkoutTemplate(
    val id: String,
    val name: String,
    val phases: List<WorkoutPhase>,
    /** Sets in order, each with its own reps and weight. Never empty. */
    val setPlans: List<SetPlan>,
    val restSeconds: Int,
    /** Rest runs without a timer and the next set waits for the user's button. */
    val manualNextSet: Boolean = false,
    /** Folder on the workout tab; null keeps the exercise at the top level. */
    val folderId: String? = null,
    /** Two or three exercises done back to back each round; null for a plain exercise. */
    val superset: List<ExercisePart>? = null,
    /** Pause between the exercises of a superset within one round. */
    val transitionSeconds: Int = DEFAULT_TRANSITION_SECONDS,
    val manualTransition: Boolean = false
) {
    val isSuperset: Boolean get() = superset != null

    /** What the engine runs: the superset's exercises, or this exercise as the only part. */
    val parts: List<ExercisePart> get() = superset ?: listOf(ExercisePart(name, phases, setPlans))

    /** Rounds of a superset; for a plain exercise, its sets. */
    val rounds: Int get() = parts.minOf { it.setPlans.size }

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
        const val MIN_SUPERSET_PARTS = 2
        const val MAX_SUPERSET_PARTS = 3
        const val DEFAULT_TRANSITION_SECONDS = 15
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
    val completed: Boolean,
    /** Folder the exercise was in when the workout was saved, for filtering the history. */
    val folderId: String? = null,
    /** Name of the superset this exercise was done in; null for a plain exercise. */
    val supersetName: String? = null
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
    val folders: List<WorkoutFolder> = emptyList(),
    /** In display order; moving an exercise up or down reorders this list. */
    val templates: List<WorkoutTemplate> = emptyList(),
    val sessions: List<WorkoutSession> = emptyList()
) {
    fun template(id: String): WorkoutTemplate? = templates.firstOrNull { it.id == id }

    fun folder(id: String): WorkoutFolder? = folders.firstOrNull { it.id == id }

    fun templatesIn(folderId: String?): List<WorkoutTemplate> = templates.filter { it.folderId == folderId }
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

    fun newTemplateDraft(folderId: String?): WorkoutTemplate =
        defaultTemplate().copy(id = UUID.randomUUID().toString(), name = "", folderId = folderId)

    fun newSupersetDraft(folderId: String?): WorkoutTemplate {
        val base = defaultTemplate()
        val parts = List(2) { i -> ExercisePart("Упражнение ${i + 1}", base.phases, base.setPlans) }
        return base.copy(id = UUID.randomUUID().toString(), name = "", folderId = folderId, superset = parts)
    }

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

    /** A copy named "… (копия)", placed right under the original. */
    fun duplicateTemplate(context: Context, id: String) {
        init(context)
        val current = _state.value
        val index = current.templates.indexOfFirst { it.id == id }
        if (index < 0) return
        val original = current.templates[index]
        val copy = original.copy(id = UUID.randomUUID().toString(), name = "${original.name} (копия)")
        commit(current.copy(templates = current.templates.toMutableList().also { it.add(index + 1, copy) }))
    }

    /** Swaps an exercise with its neighbour in the same folder; [delta] is -1 for up, 1 for down. */
    fun moveTemplate(context: Context, id: String, delta: Int) {
        init(context)
        val current = _state.value
        val template = current.template(id) ?: return
        val siblings = current.templatesIn(template.folderId)
        val target = siblings.getOrNull(siblings.indexOf(template) + delta) ?: return
        val list = current.templates.toMutableList()
        val a = list.indexOf(template)
        val b = list.indexOf(target)
        list[a] = target
        list[b] = template
        commit(current.copy(templates = list))
    }

    /** Puts an exercise into a folder, or to the top level with null, at the end of the list. */
    fun setTemplateFolder(context: Context, id: String, folderId: String?) {
        init(context)
        val current = _state.value
        val template = current.template(id) ?: return
        commit(current.copy(templates = current.templates.filterNot { it.id == id } + template.copy(folderId = folderId)))
    }

    fun addFolder(context: Context, name: String) {
        init(context)
        val current = _state.value
        commit(current.copy(folders = current.folders + WorkoutFolder(UUID.randomUUID().toString(), name)))
    }

    fun renameFolder(context: Context, id: String, name: String) {
        init(context)
        val current = _state.value
        commit(current.copy(folders = current.folders.map { if (it.id == id) it.copy(name = name) else it }))
    }

    /** Exercises of a deleted folder move to the top level; its history stays under "Все". */
    fun deleteFolder(context: Context, id: String) {
        init(context)
        val current = _state.value
        commit(
            current.copy(
                folders = current.folders.filterNot { it.id == id },
                templates = current.templates.map { if (it.folderId == id) it.copy(folderId = null) else it },
                sessions = current.sessions.map { if (it.folderId == id) it.copy(folderId = null) else it }
            )
        )
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

    /** Totals per exercise name for [sessions] started at or after [sinceMillis]. */
    fun stats(sessions: List<WorkoutSession>, sinceMillis: Long): List<ExerciseStats> =
        sessions
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
                val phases = phasesJson(template.phases)
                val setPlans = setPlansJson(template.setPlans)
                templates.put(
                    JSONObject()
                        .put("id", template.id)
                        .put("name", template.name)
                        .put("phases", phases)
                        .put("set_plans", setPlans)
                        .put("rest", template.restSeconds)
                        .put("manual_next", template.manualNextSet)
                        .put("folder", template.folderId ?: "")
                        .put("transition", template.transitionSeconds)
                        .put("manual_transition", template.manualTransition)
                        .also { json ->
                            template.superset?.let { parts ->
                                json.put("superset", JSONArray().also { array -> parts.forEach { array.put(partJson(it)) } })
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
                        .put("folder", session.folderId ?: "")
                        .put("superset", session.supersetName ?: "")
                )
            }
            val folders = JSONArray()
            newState.folders.forEach { folders.put(JSONObject().put("id", it.id).put("name", it.name)) }
            target.writeText(
                JSONObject().put("folders", folders).put("templates", templates).put("sessions", sessions).toString()
            )
        } catch (e: Exception) {
            // Unwritable storage; the in-memory state still serves this session.
        }
    }

    private fun load(target: File): WorkoutData = try {
        val root = JSONObject(target.readText())
        val templateArray = root.optJSONArray("templates") ?: JSONArray()
        val templates = (0 until templateArray.length()).mapNotNull { i ->
            val item = templateArray.optJSONObject(i) ?: return@mapNotNull null
            val phases = readPhases(item)
            if (phases.isEmpty()) return@mapNotNull null
            WorkoutTemplate(
                id = item.optString("id"),
                name = item.optString("name"),
                phases = phases,
                setPlans = readSetPlans(item),
                restSeconds = item.optInt("rest", 90).coerceAtLeast(0),
                manualNextSet = item.optBoolean("manual_next", false),
                folderId = item.optString("folder").ifEmpty { null },
                superset = item.optJSONArray("superset")?.let { array ->
                    (0 until array.length()).mapNotNull { j -> array.optJSONObject(j)?.let(::readPart) }
                }?.takeIf { it.size >= WorkoutTemplate.MIN_SUPERSET_PARTS },
                transitionSeconds = item.optInt("transition", WorkoutTemplate.DEFAULT_TRANSITION_SECONDS).coerceAtLeast(0),
                manualTransition = item.optBoolean("manual_transition", false)
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
                completed = item.optBoolean("completed"),
                folderId = if (item.has("folder")) {
                    item.optString("folder").ifEmpty { null }
                } else {
                    // Saved before folders existed: file it where its exercise is now.
                    templates.firstOrNull { it.name == item.optString("name") }?.folderId
                },
                supersetName = item.optString("superset").ifEmpty { null }
            )
        }
        val folderArray = root.optJSONArray("folders") ?: JSONArray()
        val folders = (0 until folderArray.length()).mapNotNull { i ->
            val item = folderArray.optJSONObject(i) ?: return@mapNotNull null
            WorkoutFolder(item.optString("id"), item.optString("name"))
        }
        WorkoutData(folders, templates, sessions)
    } catch (e: Exception) {
        // A corrupt file must not take the app down.
        WorkoutData()
    }

    private fun phasesJson(phases: List<WorkoutPhase>): JSONArray = JSONArray().also { array ->
        phases.forEach { array.put(JSONObject().put("name", it.name).put("seconds", it.seconds)) }
    }

    private fun setPlansJson(plans: List<SetPlan>): JSONArray = JSONArray().also { array ->
        plans.forEach { array.put(JSONObject().put("reps", it.reps).put("weight", it.weightKg)) }
    }

    private fun partJson(part: ExercisePart): JSONObject = JSONObject()
        .put("name", part.name)
        .put("phases", phasesJson(part.phases))
        .put("set_plans", setPlansJson(part.setPlans))

    private fun readPhases(item: JSONObject): List<WorkoutPhase> {
        val array = item.optJSONArray("phases") ?: return emptyList()
        return (0 until array.length()).mapNotNull { j ->
            val phase = array.optJSONObject(j) ?: return@mapNotNull null
            WorkoutPhase(phase.optString("name"), phase.optInt("seconds", 1).coerceAtLeast(1))
        }
    }

    private fun readPart(item: JSONObject): ExercisePart? {
        val phases = readPhases(item)
        if (phases.isEmpty()) return null
        return ExercisePart(item.optString("name"), phases, readSetPlans(item))
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
