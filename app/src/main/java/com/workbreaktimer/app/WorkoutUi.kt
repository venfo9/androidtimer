package com.workbreaktimer.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val PHASE_COLORS = listOf(
    Color(0xFF3B82F6), Color(0xFFF59E0B), Color(0xFF10B981), Color(0xFF8B5CF6), Color(0xFFEF4444)
)
private val REST_COLOR = Color(0xFF64748B)

private const val DAY_MILLIS = 24 * 60 * 60 * 1000L

/** History filter values besides a folder id; neither can collide with a UUID. */
private const val ALL_FOLDERS = "*"
private const val NO_FOLDER = ""

/** Russian noun form for [n]: 1 подход, 2 подхода, 5 подходов. */
private fun plural(n: Int, one: String, few: String, many: String): String {
    val mod100 = n % 100
    val mod10 = n % 10
    val word = when {
        mod100 in 11..14 -> many
        mod10 == 1 -> one
        mod10 in 2..4 -> few
        else -> many
    }
    return "$n $word"
}

private fun setsLabel(n: Int) = plural(n, "подход", "подхода", "подходов")
private fun repsLabel(n: Int) = plural(n, "повторение", "повторения", "повторений")
private fun workoutsLabel(n: Int) = plural(n, "тренировка", "тренировки", "тренировок")

private fun restLabel(seconds: Int) = if (seconds == 0) "без отдыха" else formatClock(seconds * 1000L)

private fun templateRestLabel(template: WorkoutTemplate) =
    if (template.manualNextSet) "следующий подход по кнопке" else "отдых ${restLabel(template.restSeconds)}"

/** "10 повторений × 3 подхода", or "3 подхода: 12 · 10 · 8" when the counts differ. */
private fun templateSetsLabel(template: WorkoutTemplate): String {
    val reps = template.setPlans.map { it.reps }
    return if (reps.distinct().size == 1) {
        "${repsLabel(reps.first())} × ${setsLabel(template.sets)}"
    } else {
        "${setsLabel(template.sets)}: ${reps.joinToString(" · ")}"
    }
}

private fun templateWeightLabel(template: WorkoutTemplate): String? {
    val weights = template.setPlans.map { it.weightKg }
    return when {
        weights.all { it == 0.0 } -> null
        weights.distinct().size == 1 -> "Вес ${formatWeight(weights.first())}"
        else -> "Вес по подходам: " + weights.joinToString(" / ") { formatNumber(it) } + " кг"
    }
}

/**
 * The exercise list: the top level with its folders, or the inside of one folder when
 * [folderId] is set. A running workout or its review takes over either one.
 */
@Composable
fun WorkoutScreen(
    folderId: String?,
    onEditTemplate: (templateId: String?, folderId: String?) -> Unit,
    onOpenFolder: (String) -> Unit,
    onOpenHistory: () -> Unit,
    onBack: () -> Unit = {}
) {
    val context = LocalContext.current
    // Loads text-to-speech while the user picks an exercise, so the countdown is not cut short.
    remember { WorkoutEngine.prepare(context) }
    val run by WorkoutEngine.state.collectAsState()
    val voice by WorkoutEngine.voiceStatus.collectAsState()
    val data by WorkoutStore.state.collectAsState()
    val folder = folderId?.let { data.folder(it) }
    var newFolder by remember { mutableStateOf(false) }
    var renameFolder by remember { mutableStateOf(false) }
    var confirmDeleteFolder by remember { mutableStateOf(false) }

    // The screen stays on during a workout so the numbers stay readable on the bars. The power
    // button still turns it off, and the workout carries on in the background.
    val view = LocalView.current
    DisposableEffect(run.active) {
        view.keepScreenOn = run.active
        onDispose { view.keepScreenOn = false }
    }

    if (run.active) {
        ActiveWorkout(run)
        return
    }
    if (run.stage == WorkoutStage.REVIEW) {
        WorkoutReview(run)
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                folder?.name ?: "Тренировка",
                fontSize = 22.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onOpenHistory) { Text("История") }
        }
        if (folder != null) {
            Row {
                TextButton(onClick = onBack) { Text("← Назад") }
                TextButton(onClick = { renameFolder = true }) { Text("Переименовать") }
                TextButton(onClick = { confirmDeleteFolder = true }) { Text("Удалить") }
            }
        }
        Spacer(Modifier.height(16.dp))

        if (run.stage == WorkoutStage.FINISHED) {
            WorkoutSummaryCard(run, onOpenHistory)
            Spacer(Modifier.height(16.dp))
        }

        if (voice == WorkoutVoice.Status.UNAVAILABLE) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Синтез речи на телефоне не работает, поэтому вместо чисел прозвучат короткие " +
                        "сигналы. Установите или включите «Синтезатор речи Google».",
                    modifier = Modifier.padding(16.dp),
                    fontSize = 13.sp
                )
            }
            Spacer(Modifier.height(16.dp))
        }

        if (folderId == null) {
            for (item in data.folders) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                        .clickable { onOpenFolder(item.id) }
                ) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("📁  ${item.name}", fontSize = 18.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                        Text(exercisesLabel(data.templatesIn(item.id).size), fontSize = 13.sp)
                    }
                }
            }
        }

        val templates = data.templatesIn(folderId)
        if (templates.isEmpty()) {
            Text(if (folderId == null && data.folders.isNotEmpty()) "Вне папок упражнений нет." else "Упражнений пока нет.")
        }
        templates.forEachIndexed { index, template ->
            TemplateCard(
                template = template,
                folders = data.folders,
                isFirst = index == 0,
                isLast = index == templates.lastIndex,
                onStart = {
                    WorkoutEngine.dismissSummary()
                    WorkoutEngine.start(context, template)
                },
                onEdit = { onEditTemplate(template.id, folderId) }
            )
        }
        Spacer(Modifier.height(16.dp))
        Button(onClick = { onEditTemplate(null, folderId) }) { Text("Добавить упражнение") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onEditTemplate(NEW_SUPERSET_ID, folderId) }) { Text("Добавить суперсет") }
            if (folderId == null) {
                OutlinedButton(onClick = { newFolder = true }) { Text("Новая папка") }
            }
        }

        Spacer(Modifier.height(24.dp))
        Text(
            "Перед стартом идёт отсчёт 5 секунд. После паузы повторение начинается заново, " +
                "с отсчёта 3 секунды.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    if (newFolder) {
        FolderNameDialog(title = "Новая папка", initial = "", onDismiss = { newFolder = false }) { name ->
            WorkoutStore.addFolder(context, name)
            newFolder = false
        }
    }
    if (renameFolder && folder != null) {
        FolderNameDialog(title = "Название папки", initial = folder.name, onDismiss = { renameFolder = false }) { name ->
            WorkoutStore.renameFolder(context, folder.id, name)
            renameFolder = false
        }
    }
    if (confirmDeleteFolder && folder != null) {
        AlertDialog(
            onDismissRequest = { confirmDeleteFolder = false },
            title = { Text("Удалить папку «${folder.name}»?") },
            text = { Text("Упражнения из неё переедут в общий список, история останется.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteFolder = false
                    WorkoutStore.deleteFolder(context, folder.id)
                    onBack()
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteFolder = false }) { Text("Отмена") } }
        )
    }
}

private fun exercisesLabel(n: Int) = plural(n, "упражнение", "упражнения", "упражнений")

@Composable
private fun FolderNameDialog(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(40) },
                placeholder = { Text("Например, День ног") },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(name.trim().ifEmpty { "Папка" }) }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
private fun TemplateCard(
    template: WorkoutTemplate,
    folders: List<WorkoutFolder>,
    isFirst: Boolean,
    isLast: Boolean,
    onStart: () -> Unit,
    onEdit: () -> Unit
) {
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    var pickFolder by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(template.name, fontSize = 18.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                Box {
                    TextButton(onClick = { menuOpen = true }) { Text("⋮", fontSize = 20.sp) }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Дублировать") },
                            onClick = {
                                menuOpen = false
                                WorkoutStore.duplicateTemplate(context, template.id)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Выше") },
                            enabled = !isFirst,
                            onClick = {
                                menuOpen = false
                                WorkoutStore.moveTemplate(context, template.id, -1)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Ниже") },
                            enabled = !isLast,
                            onClick = {
                                menuOpen = false
                                WorkoutStore.moveTemplate(context, template.id, 1)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Переместить в папку…") },
                            enabled = folders.isNotEmpty(),
                            onClick = {
                                menuOpen = false
                                pickFolder = true
                            }
                        )
                    }
                }
            }
            if (template.isSuperset) {
                Text("Суперсет · ${roundsLabel(template.rounds)}", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                for (part in template.parts) {
                    val weights = part.setPlans.map { it.weightKg }.filter { it > 0 }.distinct()
                    Text(
                        "${part.name} · ${part.phases.joinToString("-") { it.seconds.toString() }} · " +
                            part.setPlans.joinToString(" · ") { it.reps.toString() } +
                            if (weights.isEmpty()) "" else " · " + weights.joinToString(" / ") { formatNumber(it) } + " кг",
                        fontSize = 13.sp
                    )
                }
                Text(
                    "переход " + (if (template.manualTransition) "по кнопке" else restLabel(template.transitionSeconds)) +
                        " · " + if (template.manualNextSet) "следующий круг по кнопке" else "отдых ${restLabel(template.restSeconds)}",
                    fontSize = 13.sp
                )
            } else {
                Text("Темп ${template.tempoLabel} · ${templateSetsLabel(template)}", fontSize = 13.sp)
                Text(
                    template.phases.joinToString(" → ") { "${it.name} ${it.seconds} с" } +
                        " · ${templateRestLabel(template)}",
                    fontSize = 13.sp
                )
                templateWeightLabel(template)?.let { Text(it, fontSize = 13.sp) }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onStart) { Text("Старт") }
                OutlinedButton(onClick = onEdit) { Text("Изменить") }
            }
        }
    }

    if (pickFolder) {
        AlertDialog(
            onDismissRequest = { pickFolder = false },
            title = { Text("Переместить «${template.name}»") },
            text = {
                Column {
                    val choices = listOf<WorkoutFolder?>(null) + folders
                    for (choice in choices) {
                        val current = choice?.id == template.folderId
                        TextButton(
                            onClick = {
                                pickFolder = false
                                if (!current) WorkoutStore.setTemplateFolder(context, template.id, choice?.id)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                (choice?.name ?: "Без папки") + if (current) "  ✓" else "",
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pickFolder = false }) { Text("Отмена") } }
        )
    }
}

@Composable
private fun WorkoutSummaryCard(run: WorkoutRunState, onOpenHistory: () -> Unit) {
    val sessions = run.summary
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (sessions.isEmpty()) {
                Text("Тренировка остановлена", fontWeight = FontWeight.Medium)
                Text("Ни одного повторения, в историю ничего не попало.", fontSize = 13.sp)
            } else {
                val title = run.template?.name ?: sessions.first().name
                Text(
                    if (sessions.first().completed) "Готово: $title" else "Остановлено: $title",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(formatClock(sessions.first().durationMillis), fontSize = 13.sp)
                for (session in sessions) {
                    Spacer(Modifier.height(6.dp))
                    if (sessions.size > 1) Text(session.name, fontWeight = FontWeight.Medium)
                    Text("${setsLabel(session.repsPerSet.size)} · ${repsLabel(session.totalReps)}")
                    Text("По подходам: ${session.setsLabel}", fontSize = 13.sp)
                    if (session.volumeKg > 0) Text("Объём ${formatWeight(session.volumeKg)}", fontSize = 13.sp)
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { WorkoutEngine.dismissSummary() }) { Text("Закрыть") }
                if (sessions.isNotEmpty()) TextButton(onClick = onOpenHistory) { Text("История") }
            }
        }
    }
}

private fun roundsLabel(n: Int) = plural(n, "круг", "круга", "кругов")

@Composable
private fun ActiveWorkout(run: WorkoutRunState) {
    val template = run.template ?: return
    val part = run.part ?: return
    val stage = run.shownStage
    val paused = run.stage == WorkoutStage.PAUSED
    val superset = template.isSuperset
    val setWord = if (superset) "Круг" else "Подход"
    var confirmReset by remember { mutableStateOf(false) }
    var editWeight by remember { mutableStateOf(false) }

    val resting = stage == WorkoutStage.REST
    val transition = resting && run.restKind == RestKind.TRANSITION
    val color = when (stage) {
        WorkoutStage.WORK -> PHASE_COLORS[run.phaseIndex % PHASE_COLORS.size]
        WorkoutStage.REST -> REST_COLOR
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val title = when (stage) {
        WorkoutStage.COUNTDOWN -> "Приготовьтесь"
        WorkoutStage.WORK -> part.phases[run.phaseIndex].name
        WorkoutStage.REST -> if (transition) "Переход" else "Отдых"
        else -> ""
    }
    val bigNumber = when (stage) {
        // The engine has already counted the spoken number down, so show the one just heard.
        WorkoutStage.COUNTDOWN -> (run.countdown + 1).toString()
        WorkoutStage.WORK -> run.secondInPhase.toString()
        WorkoutStage.REST -> formatClock((if (run.manualRest) run.restElapsed else run.restLeft) * 1000L)
        else -> ""
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(color.copy(alpha = 0.12f))
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(template.name, fontSize = 18.sp, fontWeight = FontWeight.Medium)
        if (superset) {
            Text(
                (if (transition) "Дальше: " else "") +
                    "${part.name} (${run.partIndex + 1} из ${template.parts.size})",
                fontSize = 16.sp
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            (if (resting && !transition) "Дальше ${setWord.lowercase()} " else "$setWord ") +
                "${run.setIndex + 1} из ${template.rounds}",
            fontSize = 16.sp
        )
        Spacer(Modifier.height(24.dp))

        Text(
            if (paused) "Пауза" else title,
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
            color = if (paused) MaterialTheme.colorScheme.onSurface else color
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text(bigNumber, fontSize = 112.sp, fontWeight = FontWeight.Bold, color = color)
            if (stage == WorkoutStage.WORK) {
                Text(
                    " / ${part.phases[run.phaseIndex].seconds}",
                    fontSize = 32.sp,
                    modifier = Modifier.padding(bottom = 24.dp)
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            part.phases.forEachIndexed { index, phase ->
                val current = stage == WorkoutStage.WORK && index == run.phaseIndex
                val phaseColor = PHASE_COLORS[index % PHASE_COLORS.size]
                Box(
                    modifier = Modifier
                        .background(
                            if (current) phaseColor else phaseColor.copy(alpha = 0.18f),
                            RoundedCornerShape(8.dp)
                        )
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        "${phase.name} ${phase.seconds}",
                        fontSize = 13.sp,
                        color = if (current) Color.White else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        if (!resting) {
            Text(
                "Повторение ${run.repsInCurrentSet + 1} из ${part.setPlans[run.setIndex].reps}",
                fontSize = 22.sp,
                fontWeight = FontWeight.Medium
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            val kg = run.weightOfCurrentSet
            Text(
                (if (resting) "Вес дальше: " else "Вес: ") + if (kg == 0.0) "без веса" else formatWeight(kg),
                fontSize = 18.sp
            )
            TextButton(onClick = { editWeight = true }) { Text("Изменить") }
        }

        // During rest the set just done can still be corrected.
        val donePart = run.lastDonePart
        val doneSet = run.lastDoneSet
        if (resting && doneSet >= 0) {
            Spacer(Modifier.height(16.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        if (superset) {
                            "${template.parts[donePart].name}, круг ${doneSet + 1}: сколько сделали"
                        } else {
                            "Подход ${doneSet + 1}: сколько сделали"
                        },
                        fontWeight = FontWeight.Medium
                    )
                    SetCorrection(
                        reps = run.reps[donePart][doneSet],
                        weightKg = run.weights[donePart][doneSet],
                        onChange = { reps, kg -> WorkoutEngine.correctSet(donePart, doneSet, reps, kg) }
                    )
                }
            }
        }

        Spacer(Modifier.height(32.dp))
        if (resting && run.manualRest && !paused) {
            Button(
                onClick = { WorkoutEngine.skipRest() },
                modifier = Modifier.fillMaxWidth().height(64.dp)
            ) {
                Text(
                    when {
                        transition -> "Следующее упражнение"
                        superset -> "Следующий круг"
                        else -> "Следующий подход"
                    },
                    fontSize = 20.sp
                )
            }
        } else {
            Button(
                onClick = { WorkoutEngine.togglePause() },
                modifier = Modifier.fillMaxWidth().height(64.dp)
            ) {
                Text(if (paused) "Продолжить" else "Пауза", fontSize = 22.sp)
            }
        }

        if (resting && !run.manualRest) {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { WorkoutEngine.skipRest() }) {
                    Text(if (transition) "Пропустить переход" else "Пропустить отдых")
                }
                OutlinedButton(onClick = { WorkoutEngine.addRest(30) }) { Text("+30 сек") }
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { WorkoutEngine.finish() }) { Text("Завершить") }
            TextButton(onClick = { confirmReset = true }) { Text("Сброс") }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            "«Завершить» покажет итог, его можно поправить перед сохранением. «Сброс» удалит тренировку.",
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    if (editWeight) {
        var draft by remember { mutableStateOf(run.weightOfCurrentSet) }
        AlertDialog(
            onDismissRequest = { editWeight = false },
            title = { Text("Вес, кг") },
            text = {
                Column {
                    WeightField(draft) { draft = it }
                    Spacer(Modifier.height(8.dp))
                    Text("Следующие подходы этого упражнения с тем же весом получат новый вес.", fontSize = 13.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    WorkoutEngine.setWeight(draft)
                    editWeight = false
                }) { Text("Готово") }
            },
            dismissButton = { TextButton(onClick = { editWeight = false }) { Text("Отмена") } }
        )
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Сбросить тренировку?") },
            text = { Text("Сделанные повторения не попадут в историю.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    WorkoutEngine.reset()
                }) { Text("Сбросить") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Отмена") } }
        )
    }
}

/** Reps and weight of one set, both typed or nudged with -/+. */
@Composable
private fun SetCorrection(reps: Int, weightKg: Double, onChange: (reps: Int, weightKg: Double) -> Unit) {
    LabeledRow("Повторений") {
        IntField(reps, 0..WorkoutTemplate.MAX_REPS) { onChange(it, weightKg) }
    }
    LabeledRow("Вес, кг") {
        WeightField(weightKg) { onChange(reps, it) }
    }
}

/** The finished workout, set by set, to correct before it goes into the history. */
@Composable
private fun WorkoutReview(run: WorkoutRunState) {
    val template = run.template ?: return
    var confirmDiscard by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            if (run.completed) "Готово: ${template.name}" else "Остановлено: ${template.name}",
            fontSize = 22.sp,
            fontWeight = FontWeight.Medium
        )
        Text(
            "Проверьте подходы и поправьте, если сделали меньше или с другим весом. " +
                "Подход с нулём повторений не сохранится.",
            fontSize = 13.sp
        )
        template.parts.forEachIndexed { p, part ->
            if (template.isSuperset) {
                Spacer(Modifier.height(16.dp))
                Text(part.name, fontSize = 18.sp, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(8.dp))
            run.reps.getOrNull(p)?.forEachIndexed { set, reps ->
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            "${if (template.isSuperset) "Круг" else "Подход"} ${set + 1} · план ${part.setPlans[set].reps}",
                            fontWeight = FontWeight.Medium
                        )
                        SetCorrection(
                            reps = reps,
                            weightKg = run.weights[p][set],
                            onChange = { r, kg -> WorkoutEngine.correctSet(p, set, r, kg) }
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { WorkoutEngine.saveReview() }) { Text("Сохранить") }
            OutlinedButton(onClick = { confirmDiscard = true }) { Text("Не сохранять") }
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Не сохранять тренировку?") },
            text = { Text("Подходы не попадут в историю.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    WorkoutEngine.discardReview()
                }) { Text("Не сохранять") }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Отмена") } }
        )
    }
}

/** Passed as the template id to open the editor on a new superset. */
const val NEW_SUPERSET_ID = "new_superset"

/** [plans] cut or extended to [count] sets; new sets repeat the last one. */
private fun resizePlans(plans: List<SetPlan>, count: Int): List<SetPlan> =
    List(count) { plans.getOrElse(it) { plans.last() } }

@Composable
fun WorkoutTemplateEditorScreen(templateId: String?, folderId: String?, onDone: () -> Unit) {
    val context = LocalContext.current
    val data by WorkoutStore.state.collectAsState()
    val newSuperset = templateId == NEW_SUPERSET_ID
    val existing = templateId?.takeIf { !newSuperset }?.let { data.template(it) }
    // Seeded once; the editor owns its draft until the user saves.
    val initial = remember {
        existing ?: if (newSuperset) WorkoutStore.newSupersetDraft(folderId) else WorkoutStore.newTemplateDraft(folderId)
    }
    val isSuperset = initial.isSuperset

    var name by remember { mutableStateOf(initial.name) }
    var parts by remember { mutableStateOf(initial.parts) }
    var rounds by remember { mutableStateOf(initial.rounds) }
    var rest by remember { mutableStateOf(initial.restSeconds) }
    var manualNextSet by remember { mutableStateOf(initial.manualNextSet) }
    var transition by remember { mutableStateOf(initial.transitionSeconds) }
    var manualTransition by remember { mutableStateOf(initial.manualTransition) }
    var confirmDelete by remember { mutableStateOf(false) }

    fun updatePart(index: Int, transform: (ExercisePart) -> ExercisePart) {
        parts = parts.toMutableList().also { it[index] = transform(it[index]) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        Text(
            when {
                isSuperset && existing == null -> "Новый суперсет"
                isSuperset -> "Суперсет"
                existing == null -> "Новое упражнение"
                else -> "Упражнение"
            },
            fontSize = 22.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(40) },
            label = { Text("Название") },
            placeholder = { Text(if (isSuperset) "Например, Грудь и спина" else "Например, Брусья") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        if (!isSuperset) {
            Spacer(Modifier.height(20.dp))
            PhasesEditor(parts[0].phases) { phases -> updatePart(0) { it.copy(phases = phases) } }

            Spacer(Modifier.height(20.dp))
            Text("Подходы", fontWeight = FontWeight.Medium)
            Text("Повторения и вес для каждого подхода. Вес 0 означает без веса, только своё тело.", fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            SetPlansEditor(parts[0].setPlans, fixedCount = false) { plans -> updatePart(0) { it.copy(setPlans = plans) } }

            Spacer(Modifier.height(20.dp))
            PauseEditor(
                title = "Отдых между подходами",
                manualLabel = "Следующий подход по кнопке",
                manual = manualNextSet,
                seconds = rest,
                onManual = { manualNextSet = it },
                onSeconds = { rest = it }
            )
        } else {
            Spacer(Modifier.height(16.dp))
            Text(
                "Упражнения идут одно за другим в каждом круге: первое, переход, второе и так далее, " +
                    "потом отдых и следующий круг.",
                fontSize = 13.sp
            )
            LabeledRow("Кругов") {
                IntField(rounds, 1..WorkoutTemplate.MAX_SETS) { count ->
                    rounds = count
                    parts = parts.map { it.copy(setPlans = resizePlans(it.setPlans, count)) }
                }
            }
            parts.forEachIndexed { index, part ->
                Spacer(Modifier.height(12.dp))
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Упражнение ${index + 1}", fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                            if (parts.size > WorkoutTemplate.MIN_SUPERSET_PARTS) {
                                TextButton(onClick = { parts = parts.toMutableList().also { it.removeAt(index) } }) {
                                    Text("Убрать")
                                }
                            }
                        }
                        OutlinedTextField(
                            value = part.name,
                            onValueChange = { value -> updatePart(index) { it.copy(name = value.take(40)) } },
                            label = { Text("Название упражнения") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(12.dp))
                        PhasesEditor(part.phases) { phases -> updatePart(index) { it.copy(phases = phases) } }
                        Spacer(Modifier.height(12.dp))
                        Text("Повторения и вес по кругам", fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(6.dp))
                        SetPlansEditor(part.setPlans, fixedCount = true) { plans -> updatePart(index) { it.copy(setPlans = plans) } }
                    }
                }
            }
            if (parts.size < WorkoutTemplate.MAX_SUPERSET_PARTS) {
                TextButton(onClick = {
                    val last = parts.last()
                    parts = parts + ExercisePart("Упражнение ${parts.size + 1}", last.phases, last.setPlans)
                }) { Text("+ Добавить упражнение") }
            }

            Spacer(Modifier.height(20.dp))
            PauseEditor(
                title = "Переход между упражнениями",
                manualLabel = "Следующее упражнение по кнопке",
                manual = manualTransition,
                seconds = transition,
                onManual = { manualTransition = it },
                onSeconds = { transition = it }
            )
            Spacer(Modifier.height(20.dp))
            PauseEditor(
                title = "Отдых после круга",
                manualLabel = "Следующий круг по кнопке",
                manual = manualNextSet,
                seconds = rest,
                onManual = { manualNextSet = it },
                onSeconds = { rest = it }
            )
        }

        Spacer(Modifier.height(32.dp))
        Row {
            Button(onClick = {
                val cleanParts = parts.mapIndexed { i, part ->
                    part.copy(
                        name = part.name.trim().ifEmpty { "Упражнение ${i + 1}" },
                        phases = part.phases.mapIndexed { j, phase -> phase.copy(name = phase.name.trim().ifEmpty { "Фаза ${j + 1}" }) }
                    )
                }
                WorkoutStore.saveTemplate(
                    context,
                    initial.copy(
                        name = name.trim().ifEmpty { if (isSuperset) "Суперсет" else "Упражнение" },
                        phases = cleanParts[0].phases,
                        setPlans = cleanParts[0].setPlans,
                        restSeconds = rest,
                        manualNextSet = manualNextSet,
                        superset = if (isSuperset) cleanParts else null,
                        transitionSeconds = transition,
                        manualTransition = manualTransition
                    )
                )
                onDone()
            }) { Text("Сохранить") }
            Spacer(Modifier.width(12.dp))
            OutlinedButton(onClick = onDone) { Text("Отмена") }
        }
        if (existing != null) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { confirmDelete = true }) { Text(if (isSuperset) "Удалить суперсет" else "Удалить упражнение") }
        }
    }

    if (confirmDelete && existing != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Удалить «${existing.name}»?") },
            text = { Text("История тренировок останется.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    WorkoutStore.deleteTemplate(context, existing.id)
                    onDone()
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } }
        )
    }
}

@Composable
private fun PhasesEditor(phases: List<WorkoutPhase>, onChange: (List<WorkoutPhase>) -> Unit) {
    Text("Фазы одного повторения", fontWeight = FontWeight.Medium)
    Text("Справа длительность фазы в секундах. Голос считает секунды каждой фазы с единицы.", fontSize = 13.sp)
    Spacer(Modifier.height(8.dp))
    phases.forEachIndexed { index, phase ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = phase.name,
                onValueChange = { value ->
                    onChange(phases.toMutableList().also { it[index] = phase.copy(name = value.take(20)) })
                },
                label = { Text("Фаза ${index + 1}") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            IntField(phase.seconds, 1..WorkoutTemplate.MAX_PHASE_SECONDS) { seconds ->
                onChange(phases.toMutableList().also { it[index] = phase.copy(seconds = seconds) })
            }
            if (phases.size > 1) {
                TextButton(
                    onClick = { onChange(phases.toMutableList().also { it.removeAt(index) }) },
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.size(40.dp)
                ) { Text("✕") }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
    if (phases.size < WorkoutTemplate.MAX_PHASES) {
        TextButton(onClick = { onChange(phases + WorkoutPhase("", 1)) }) { Text("+ Добавить фазу") }
    }
}

/**
 * Reps and weight per set as a table. With [fixedCount] the number of rows belongs to someone
 * else (the superset's rounds), so rows cannot be added or removed here.
 */
@Composable
private fun SetPlansEditor(plans: List<SetPlan>, fixedCount: Boolean, onChange: (List<SetPlan>) -> Unit) {
    plans.forEachIndexed { index, plan ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${index + 1}.", modifier = Modifier.width(28.dp), fontWeight = FontWeight.Medium)
            CompactNumberField(
                value = plan.reps.toDouble(),
                label = "повт.",
                max = WorkoutTemplate.MAX_REPS.toDouble(),
                decimal = false,
                width = 80.dp
            ) { reps ->
                onChange(plans.toMutableList().also { it[index] = plan.copy(reps = reps.toInt().coerceAtLeast(1)) })
            }
            Spacer(Modifier.width(8.dp))
            CompactNumberField(
                value = plan.weightKg,
                label = "кг",
                max = WorkoutTemplate.MAX_WEIGHT_KG,
                decimal = true,
                width = 96.dp
            ) { kg ->
                onChange(plans.toMutableList().also { it[index] = plan.copy(weightKg = kg) })
            }
            if (!fixedCount && plans.size > 1) {
                TextButton(
                    onClick = { onChange(plans.toMutableList().also { it.removeAt(index) }) },
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.size(40.dp)
                ) { Text("✕") }
            }
        }
        Spacer(Modifier.height(6.dp))
    }
    Row {
        if (!fixedCount && plans.size < WorkoutTemplate.MAX_SETS) {
            // A new set repeats the last one, so a uniform plan needs only taps on "+".
            TextButton(onClick = { onChange(plans + plans.last()) }) { Text("+ Добавить подход") }
        }
        if (plans.size > 1) {
            TextButton(onClick = { onChange(List(plans.size) { plans.first() }) }) { Text("Всем как в 1-м") }
        }
    }
}

/** A pause that either runs on a timer of [seconds] or waits for the user's button. */
@Composable
private fun PauseEditor(
    title: String,
    manualLabel: String,
    manual: Boolean,
    seconds: Int,
    onManual: (Boolean) -> Unit,
    onSeconds: (Int) -> Unit
) {
    Text(title, fontWeight = FontWeight.Medium)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = manual, onCheckedChange = onManual)
        Spacer(Modifier.width(12.dp))
        Text(manualLabel)
    }
    if (manual) {
        Text(
            "Пауза идёт без таймера, на экране видно, сколько прошло. Дальше по кнопке и отсчёту 3 секунды.",
            fontSize = 13.sp
        )
    } else {
        LabeledRow("Секунд (${restLabel(seconds)})") {
            IntField(seconds, 0..WorkoutTemplate.MAX_REST_SECONDS, step = 15) { onSeconds(it) }
        }
    }
}

@Composable
private fun LabeledRow(title: String, content: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, modifier = Modifier.weight(1f))
        content()
    }
}

@Composable
private fun IntField(value: Int, range: IntRange, step: Int = 1, onChange: (Int) -> Unit) {
    NumberField(
        value = value.toDouble(),
        min = range.first.toDouble(),
        max = range.last.toDouble(),
        step = step.toDouble(),
        decimal = false
    ) { onChange(it.toInt()) }
}

@Composable
private fun WeightField(kg: Double, onChange: (Double) -> Unit) {
    NumberField(
        value = kg,
        min = 0.0,
        max = WorkoutTemplate.MAX_WEIGHT_KG,
        step = WorkoutTemplate.WEIGHT_STEP_KG,
        decimal = true,
        fieldWidth = 80.dp,
        onChange = onChange
    )
}

/** A number typed on the keyboard, or nudged by a step with the buttons on either side. */
@Composable
private fun NumberField(
    value: Double,
    min: Double,
    max: Double,
    step: Double,
    decimal: Boolean,
    fieldWidth: Dp = 64.dp,
    onChange: (Double) -> Unit
) {
    // The text belongs to the user while typing: "12," is on its way to "12,5" and must not be
    // reformatted away. It follows the value only when the value changes some other way.
    var text by remember { mutableStateOf(formatNumber(value)) }
    LaunchedEffect(value) {
        if (text.replace(',', '.').toDoubleOrNull() != value) text = formatNumber(value)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(
            onClick = { onChange((value - step).coerceIn(min, max)) },
            enabled = value > min,
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier.size(40.dp)
        ) { Text("−", fontSize = 18.sp) }
        Spacer(Modifier.width(4.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { raw ->
                val cleaned = cleanNumber(raw, decimal)
                text = cleaned
                cleaned.replace(',', '.').toDoubleOrNull()?.let { onChange(it.coerceIn(min, max)) }
            },
            singleLine = true,
            textStyle = LocalTextStyle.current.copy(textAlign = TextAlign.Center, fontSize = 16.sp),
            keyboardOptions = KeyboardOptions(
                keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number
            ),
            modifier = Modifier.width(fieldWidth)
        )
        Spacer(Modifier.width(4.dp))
        OutlinedButton(
            onClick = { onChange((value + step).coerceIn(min, max)) },
            enabled = value < max,
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier.size(40.dp)
        ) { Text("+", fontSize = 18.sp) }
    }
}

/** A typed number without -/+ buttons, narrow enough for two in a table row. */
@Composable
private fun CompactNumberField(
    value: Double,
    label: String,
    max: Double,
    decimal: Boolean,
    width: Dp,
    onChange: (Double) -> Unit
) {
    // Same ownership rule as NumberField: the text follows the value only when it changes elsewhere.
    var text by remember { mutableStateOf(formatNumber(value)) }
    LaunchedEffect(value) {
        if (text.replace(',', '.').toDoubleOrNull() != value) text = formatNumber(value)
    }
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            val cleaned = cleanNumber(raw, decimal)
            text = cleaned
            cleaned.replace(',', '.').toDoubleOrNull()?.let { onChange(it.coerceIn(0.0, max)) }
        },
        label = { Text(label) },
        singleLine = true,
        textStyle = LocalTextStyle.current.copy(fontSize = 16.sp),
        keyboardOptions = KeyboardOptions(
            keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number
        ),
        modifier = Modifier.width(width)
    )
}

/** Digits only, plus one decimal comma when [decimal]; a typed dot becomes a comma. */
private fun cleanNumber(raw: String, decimal: Boolean): String {
    val out = StringBuilder()
    var hasSeparator = false
    for (c in raw) {
        if (c.isDigit()) {
            out.append(c)
        } else if (decimal && (c == '.' || c == ',') && !hasSeparator) {
            hasSeparator = true
            out.append(',')
        }
    }
    return out.toString().take(6)
}

@Composable
fun WorkoutHistoryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val data by WorkoutStore.state.collectAsState()
    val now = remember { System.currentTimeMillis() }
    // ALL_FOLDERS, NO_FOLDER, or a folder id.
    var filter by remember { mutableStateOf(ALL_FOLDERS) }
    val sessions = remember(data, filter) {
        when (filter) {
            ALL_FOLDERS -> data.sessions
            NO_FOLDER -> data.sessions.filter { it.folderId == null }
            else -> data.sessions.filter { it.folderId == filter }
        }
    }
    val week = remember(sessions) { WorkoutStore.stats(sessions, now - 7 * DAY_MILLIS) }
    val month = remember(sessions) { WorkoutStore.stats(sessions, now - 30 * DAY_MILLIS) }
    val timeFormat = remember { SimpleDateFormat("d MMM, HH:mm", Locale("ru")) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        Text("История тренировок", fontSize = 22.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(16.dp))

        if (data.folders.isNotEmpty()) {
            val options = listOf(ALL_FOLDERS to "Все") + data.folders.map { it.id to it.name } +
                (NO_FOLDER to "Без папки")
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                for ((key, label) in options) {
                    OutlinedButton(
                        onClick = { filter = key },
                        border = if (key == filter) {
                            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                        } else {
                            ButtonDefaults.outlinedButtonBorder
                        }
                    ) { Text(label, fontSize = 13.sp) }
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        StatsCard("За 7 дней", week)
        Spacer(Modifier.height(12.dp))
        StatsCard("За 30 дней", month)

        Spacer(Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))

        if (sessions.isEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("Пока пусто. Завершите первую тренировку, и она появится здесь.")
        }
        for (session in sessions.take(200)) {
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(session.name, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    Text(timeFormat.format(Date(session.startedAtMillis)), fontSize = 13.sp)
                }
                Text(
                    "${setsLabel(session.repsPerSet.size)} · ${repsLabel(session.totalReps)} · ${formatClock(session.durationMillis)}" +
                        if (session.volumeKg > 0) " · объём ${formatWeight(session.volumeKg)}" else "",
                    fontSize = 14.sp
                )
                val marks = buildList {
                    add("по подходам: ${session.setsLabel}")
                    session.supersetName?.let { add("суперсет «$it»") }
                    if (!session.completed) add("остановлено, план ${session.plannedLabel}")
                }
                Text(marks.joinToString(" · "), fontSize = 13.sp)
                TextButton(
                    onClick = { WorkoutStore.deleteSession(context, session.id) },
                    contentPadding = PaddingValues(0.dp)
                ) { Text("Удалить", fontSize = 13.sp) }
            }
            HorizontalDivider()
        }

        Spacer(Modifier.height(24.dp))
        Button(onClick = onBack) { Text("Назад") }
    }
}

@Composable
private fun StatsCard(title: String, stats: List<ExerciseStats>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            if (stats.isEmpty()) {
                Text("Тренировок не было.", fontSize = 13.sp)
            }
            for (item in stats) {
                Text(item.name, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 4.dp))
                Text(
                    "${workoutsLabel(item.workouts)} · ${setsLabel(item.sets)} · ${repsLabel(item.reps)}" +
                        if (item.volumeKg > 0) " · объём ${formatWeight(item.volumeKg)}" else "",
                    fontSize = 13.sp
                )
            }
        }
    }
}
