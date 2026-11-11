package com.workbreaktimer.app

import androidx.compose.foundation.background
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
import androidx.compose.material3.Card
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

@Composable
fun WorkoutScreen(onEditTemplate: (String?) -> Unit, onOpenHistory: () -> Unit) {
    val context = LocalContext.current
    // Loads text-to-speech while the user picks an exercise, so the countdown is not cut short.
    remember { WorkoutEngine.prepare(context) }
    val run by WorkoutEngine.state.collectAsState()
    val voice by WorkoutEngine.voiceStatus.collectAsState()
    val data by WorkoutStore.state.collectAsState()

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
            Text("Тренировка", fontSize = 22.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            TextButton(onClick = onOpenHistory) { Text("История") }
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

        if (data.templates.isEmpty()) {
            Text("Упражнений пока нет.")
        }
        for (template in data.templates) {
            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(template.name, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Темп ${template.tempoLabel} · ${templateSetsLabel(template)}",
                        fontSize = 13.sp
                    )
                    Text(
                        template.phases.joinToString(" → ") { "${it.name} ${it.seconds} с" } +
                            " · ${templateRestLabel(template)}",
                        fontSize = 13.sp
                    )
                    templateWeightLabel(template)?.let { Text(it, fontSize = 13.sp) }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            WorkoutEngine.dismissSummary()
                            WorkoutEngine.start(context, template)
                        }) { Text("Старт") }
                        OutlinedButton(onClick = { onEditTemplate(template.id) }) { Text("Изменить") }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Button(onClick = { onEditTemplate(null) }) { Text("Добавить упражнение") }

        Spacer(Modifier.height(24.dp))
        Text(
            "Перед стартом идёт отсчёт 5 секунд. После паузы повторение начинается заново, " +
                "с отсчёта 3 секунды.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun WorkoutSummaryCard(run: WorkoutRunState, onOpenHistory: () -> Unit) {
    val session = run.summary
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (session == null) {
                Text("Тренировка остановлена", fontWeight = FontWeight.Medium)
                Text("Ни одного повторения, в историю ничего не попало.", fontSize = 13.sp)
            } else {
                Text(
                    if (session.completed) "Готово: ${session.name}" else "Остановлено: ${session.name}",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(4.dp))
                Text("${setsLabel(session.repsPerSet.size)} · ${repsLabel(session.totalReps)} · ${formatClock(session.durationMillis)}")
                Text("По подходам: ${session.setsLabel}", fontSize = 13.sp)
                if (session.volumeKg > 0) Text("Объём ${formatWeight(session.volumeKg)}", fontSize = 13.sp)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { WorkoutEngine.dismissSummary() }) { Text("Закрыть") }
                if (session != null) TextButton(onClick = onOpenHistory) { Text("История") }
            }
        }
    }
}

@Composable
private fun ActiveWorkout(run: WorkoutRunState) {
    val template = run.template ?: return
    val stage = run.shownStage
    val paused = run.stage == WorkoutStage.PAUSED
    var confirmReset by remember { mutableStateOf(false) }
    var editWeight by remember { mutableStateOf(false) }

    val color = when (stage) {
        WorkoutStage.WORK -> PHASE_COLORS[run.phaseIndex % PHASE_COLORS.size]
        WorkoutStage.REST -> REST_COLOR
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val title = when (stage) {
        WorkoutStage.COUNTDOWN -> "Приготовьтесь"
        WorkoutStage.WORK -> template.phases[run.phaseIndex].name
        WorkoutStage.REST -> "Отдых"
        else -> ""
    }
    val bigNumber = when (stage) {
        // The engine has already counted the spoken number down, so show the one just heard.
        WorkoutStage.COUNTDOWN -> (run.countdown + 1).toString()
        WorkoutStage.WORK -> run.secondInPhase.toString()
        WorkoutStage.REST -> formatClock((if (run.manualRest) run.restElapsed else run.restLeft) * 1000L)
        else -> ""
    }
    val resting = stage == WorkoutStage.REST
    // During rest the set just done can still be corrected.
    val doneSet = run.setIndex - 1

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(color.copy(alpha = 0.12f))
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(template.name, fontSize = 18.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(4.dp))
        Text(
            (if (resting) "Дальше подход " else "Подход ") + "${run.setIndex + 1} из ${template.sets}",
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
                    " / ${template.phases[run.phaseIndex].seconds}",
                    fontSize = 32.sp,
                    modifier = Modifier.padding(bottom = 24.dp)
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            template.phases.forEachIndexed { index, phase ->
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
                "Повторение ${run.repsInCurrentSet + 1} из ${template.repsForSet(run.setIndex)}",
                fontSize = 22.sp,
                fontWeight = FontWeight.Medium
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            val kg = run.weightOfCurrentSet
            Text(
                (if (resting) "Вес следующего подхода: " else "Вес: ") +
                    if (kg == 0.0) "без веса" else formatWeight(kg),
                fontSize = 18.sp
            )
            TextButton(onClick = { editWeight = true }) { Text("Изменить") }
        }

        if (resting && doneSet >= 0) {
            Spacer(Modifier.height(16.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Подход ${doneSet + 1}: сколько сделали", fontWeight = FontWeight.Medium)
                    SetCorrection(
                        reps = run.repsPerSet[doneSet],
                        weightKg = run.weightsPerSet.getOrElse(doneSet) { 0.0 },
                        onChange = { reps, kg -> WorkoutEngine.correctSet(doneSet, reps, kg) }
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
                Text("Следующий подход", fontSize = 22.sp)
            }
        } else {
            Button(
                onClick = { WorkoutEngine.togglePause() },
                modifier = Modifier.fillMaxWidth().height(64.dp)
            ) {
                Text(if (paused) "Продолжить" else "Пауза", fontSize = 22.sp)
            }
        }

        if (stage == WorkoutStage.REST && !run.manualRest) {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { WorkoutEngine.skipRest() }) { Text("Пропустить отдых") }
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
            title = { Text("Вес подхода ${run.setIndex + 1}, кг") },
            text = {
                Column {
                    WeightField(draft) { draft = it }
                    Spacer(Modifier.height(8.dp))
                    Text("Следующие подходы с тем же весом получат новый вес.", fontSize = 13.sp)
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
        Spacer(Modifier.height(12.dp))
        run.repsPerSet.forEachIndexed { index, reps ->
            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        "Подход ${index + 1} · план ${template.repsForSet(index)}",
                        fontWeight = FontWeight.Medium
                    )
                    SetCorrection(
                        reps = reps,
                        weightKg = run.weightsPerSet.getOrElse(index) { 0.0 },
                        onChange = { r, kg -> WorkoutEngine.correctSet(index, r, kg) }
                    )
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

@Composable
fun WorkoutTemplateEditorScreen(templateId: String?, onDone: () -> Unit) {
    val context = LocalContext.current
    val data by WorkoutStore.state.collectAsState()
    val existing = templateId?.let { data.template(it) }
    // Seeded once; the editor owns its draft until the user saves.
    val initial = remember { existing ?: WorkoutStore.newTemplateDraft() }

    var name by remember { mutableStateOf(initial.name) }
    var phases by remember { mutableStateOf(initial.phases) }
    var setPlans by remember { mutableStateOf(initial.setPlans) }
    var rest by remember { mutableStateOf(initial.restSeconds) }
    var manualNextSet by remember { mutableStateOf(initial.manualNextSet) }
    var confirmDelete by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        Text(if (existing == null) "Новое упражнение" else "Упражнение", fontSize = 22.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(40) },
            label = { Text("Название") },
            placeholder = { Text("Например, Брусья") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(20.dp))
        Text("Фазы одного повторения", fontWeight = FontWeight.Medium)
        Text("Справа длительность фазы в секундах. Голос считает секунды каждой фазы с единицы.", fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))
        phases.forEachIndexed { index, phase ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = phase.name,
                    onValueChange = { value ->
                        phases = phases.toMutableList().also { it[index] = phase.copy(name = value.take(20)) }
                    },
                    label = { Text("Фаза ${index + 1}") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                IntField(phase.seconds, 1..WorkoutTemplate.MAX_PHASE_SECONDS) { seconds ->
                    phases = phases.toMutableList().also { it[index] = phase.copy(seconds = seconds) }
                }
                if (phases.size > 1) {
                    TextButton(
                        onClick = { phases = phases.toMutableList().also { it.removeAt(index) } },
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.size(40.dp)
                    ) { Text("✕") }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        if (phases.size < WorkoutTemplate.MAX_PHASES) {
            TextButton(onClick = { phases = phases + WorkoutPhase("", 1) }) { Text("+ Добавить фазу") }
        }

        Spacer(Modifier.height(20.dp))
        Text("Подходы", fontWeight = FontWeight.Medium)
        Text("Повторения и вес для каждого подхода. Вес 0 означает без веса, только своё тело.", fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))
        setPlans.forEachIndexed { index, plan ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${index + 1}.", modifier = Modifier.width(28.dp), fontWeight = FontWeight.Medium)
                CompactNumberField(
                    value = plan.reps.toDouble(),
                    label = "повт.",
                    max = WorkoutTemplate.MAX_REPS.toDouble(),
                    decimal = false,
                    width = 80.dp
                ) { reps ->
                    setPlans = setPlans.toMutableList().also { it[index] = plan.copy(reps = reps.toInt().coerceAtLeast(1)) }
                }
                Spacer(Modifier.width(8.dp))
                CompactNumberField(
                    value = plan.weightKg,
                    label = "кг",
                    max = WorkoutTemplate.MAX_WEIGHT_KG,
                    decimal = true,
                    width = 96.dp
                ) { kg ->
                    setPlans = setPlans.toMutableList().also { it[index] = plan.copy(weightKg = kg) }
                }
                if (setPlans.size > 1) {
                    TextButton(
                        onClick = { setPlans = setPlans.toMutableList().also { it.removeAt(index) } },
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.size(40.dp)
                    ) { Text("✕") }
                }
            }
            Spacer(Modifier.height(6.dp))
        }
        Row {
            if (setPlans.size < WorkoutTemplate.MAX_SETS) {
                // A new set repeats the last one, so a uniform plan needs only taps on "+".
                TextButton(onClick = { setPlans = setPlans + setPlans.last() }) { Text("+ Добавить подход") }
            }
            if (setPlans.size > 1) {
                TextButton(onClick = { setPlans = List(setPlans.size) { setPlans.first() } }) {
                    Text("Всем как в 1-м")
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        Text("Отдых между подходами", fontWeight = FontWeight.Medium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = manualNextSet, onCheckedChange = { manualNextSet = it })
            Spacer(Modifier.width(12.dp))
            Text("Следующий подход по кнопке")
        }
        if (manualNextSet) {
            Text(
                "Отдых идёт без таймера, на экране видно, сколько прошло. " +
                    "Подход начнётся после кнопки «Следующий подход» и отсчёта 3 секунды.",
                fontSize = 13.sp
            )
        } else {
            LabeledRow("Отдых, сек (${restLabel(rest)})") {
                IntField(rest, 0..WorkoutTemplate.MAX_REST_SECONDS, step = 15) { rest = it }
            }
        }

        Spacer(Modifier.height(32.dp))
        Row {
            Button(onClick = {
                WorkoutStore.saveTemplate(
                    context,
                    initial.copy(
                        name = name.trim().ifEmpty { "Упражнение" },
                        phases = phases.mapIndexed { i, phase -> phase.copy(name = phase.name.trim().ifEmpty { "Фаза ${i + 1}" }) },
                        setPlans = setPlans,
                        restSeconds = rest,
                        manualNextSet = manualNextSet
                    )
                )
                onDone()
            }) { Text("Сохранить") }
            Spacer(Modifier.width(12.dp))
            OutlinedButton(onClick = onDone) { Text("Отмена") }
        }
        if (existing != null) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { confirmDelete = true }) { Text("Удалить упражнение") }
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
    val week = remember(data) { WorkoutStore.stats(now - 7 * DAY_MILLIS) }
    val month = remember(data) { WorkoutStore.stats(now - 30 * DAY_MILLIS) }
    val timeFormat = remember { SimpleDateFormat("d MMM, HH:mm", Locale("ru")) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        Text("История тренировок", fontSize = 22.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(16.dp))

        StatsCard("За 7 дней", week)
        Spacer(Modifier.height(12.dp))
        StatsCard("За 30 дней", month)

        Spacer(Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))

        if (data.sessions.isEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("Пока пусто. Завершите первую тренировку, и она появится здесь.")
        }
        for (session in data.sessions.take(200)) {
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
