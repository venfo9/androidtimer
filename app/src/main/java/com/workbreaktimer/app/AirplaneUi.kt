package com.workbreaktimer.app

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun timeLabel(hour: Int, minute: Int) = "%02d:%02d".format(hour, minute)

@Composable
fun AirplaneScreen(onEditRule: (String?) -> Unit) {
    val context = LocalContext.current
    val state by AirplaneScheduleManager.state.collectAsState()
    val shizuku by ShizukuBridge.status.collectAsState()
    val scope = rememberCoroutineScope()

    var airplaneOn by remember { mutableStateOf(AirplaneSwitcher.isAirplaneOn(context)) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testRunning by remember { mutableStateOf(false) }

    // The mode can change from quick settings while this screen is open; nothing pushes that
    // to the app, so it is read again every couple of seconds.
    LaunchedEffect(Unit) {
        while (true) {
            airplaneOn = AirplaneSwitcher.isAirplaneOn(context)
            delay(2_000)
        }
    }

    fun runTest(turnOn: Boolean) {
        testRunning = true
        testResult = null
        scope.launch {
            val result = withContext(Dispatchers.IO) { AirplaneSwitcher.switchTo(context, turnOn) }
            testResult = when (result) {
                AirplaneSwitcher.Result.AlreadyInState -> "Режим уже был в нужном состоянии"
                AirplaneSwitcher.Result.Switched -> "Получилось, Shizuku работает"
                is AirplaneSwitcher.Result.Failed -> "Не получилось: ${result.reason}"
            }
            airplaneOn = AirplaneSwitcher.isAirplaneOn(context)
            testRunning = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        Text("Режим полёта", fontSize = 22.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(16.dp))

        ShizukuStatusCard(context, shizuku)
        Spacer(Modifier.height(16.dp))

        Text("Сейчас режим полёта ${if (airplaneOn) "включён" else "выключен"}")
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { runTest(true) }, enabled = !testRunning) { Text("Включить") }
            OutlinedButton(onClick = { runTest(false) }, enabled = !testRunning) { Text("Выключить") }
        }
        if (testRunning) Text("Проверяю…", fontSize = 13.sp)
        testResult?.let { Text(it, fontSize = 13.sp) }

        state.lastRun?.let { run ->
            Spacer(Modifier.height(12.dp))
            val at = SimpleDateFormat("d MMM, HH:mm", Locale("ru")).format(Date(run.atMillis))
            val what = if (run.turnOn) "включение" else "выключение"
            Text(
                "Последний запуск по расписанию: $at, $what, ${if (run.ok) "" else "ошибка: "}${run.message}",
                fontSize = 13.sp
            )
        }

        Spacer(Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        Text("Расписание", fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(8.dp))
        if (state.rules.isEmpty()) {
            Text("Правил пока нет.")
        }
        for (rule in state.rules.sortedWith(compareBy({ it.hour }, { it.minute }))) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).clickable { onEditRule(rule.id) }) {
                    Text(
                        "${if (rule.turnOn) "Включить" else "Выключить"} в ${timeLabel(rule.hour, rule.minute)}",
                        fontWeight = FontWeight.Medium
                    )
                    Text(AirplaneRule.daysLabel(rule.daysMask), fontSize = 13.sp)
                }
                Switch(
                    checked = rule.enabled,
                    onCheckedChange = { AirplaneScheduleManager.setEnabled(context, rule.id, it) }
                )
            }
            Row {
                TextButton(onClick = { onEditRule(rule.id) }) { Text("Изменить") }
                TextButton(onClick = { AirplaneScheduleManager.deleteRule(context, rule.id) }) { Text("Удалить") }
            }
            HorizontalDivider()
        }
        Spacer(Modifier.height(16.dp))
        Button(onClick = { onEditRule(null) }) { Text("Добавить правило") }
    }
}

@Composable
private fun ShizukuStatusCard(context: Context, status: ShizukuBridge.Status) {
    val (title, hint) = when (status) {
        ShizukuBridge.Status.READY ->
            "Shizuku работает" to "Расписание переключит режим полёта само."
        ShizukuBridge.Status.NOT_INSTALLED ->
            "Shizuku не установлен" to "Без него приложение не может переключать режим полёта."
        ShizukuBridge.Status.NOT_RUNNING ->
            "Shizuku не запущен" to "Откройте Shizuku и нажмите «Запустить». После перезагрузки телефона это нужно повторять."
        ShizukuBridge.Status.OUTDATED ->
            "Shizuku устарел" to "Обновите Shizuku до последней версии."
        ShizukuBridge.Status.NO_PERMISSION ->
            "Нет разрешения Shizuku" to "Разрешите приложению пользоваться Shizuku."
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            Text(hint, fontSize = 13.sp)
            when (status) {
                ShizukuBridge.Status.READY -> Unit
                ShizukuBridge.Status.NO_PERMISSION -> {
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { ShizukuBridge.requestPermission() }) { Text("Разрешить") }
                }
                ShizukuBridge.Status.NOT_INSTALLED -> {
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { context.startActivity(ShizukuBridge.openIntent(context)) }) {
                        Text("Установить")
                    }
                }
                else -> {
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { context.startActivity(ShizukuBridge.openIntent(context)) }) {
                        Text("Открыть Shizuku")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AirplaneRuleEditorScreen(ruleId: String?, onDone: () -> Unit) {
    val context = LocalContext.current
    val state by AirplaneScheduleManager.state.collectAsState()
    val existing = ruleId?.let { state.rule(it) }

    // Seeded once; the editor owns its draft until the user saves.
    var turnOn by remember { mutableStateOf(existing?.turnOn ?: true) }
    var hour by remember { mutableStateOf(existing?.hour ?: 23) }
    var minute by remember { mutableStateOf(existing?.minute ?: 0) }
    var daysMask by remember { mutableStateOf(existing?.daysMask ?: AirplaneRule.ALL_DAYS) }
    var showTimePicker by remember { mutableStateOf(false) }
    var showNoDaysError by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        Text(if (existing == null) "Новое правило" else "Правило", fontSize = 22.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(24.dp))

        Text("Действие", fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChoiceButton("Включить", selected = turnOn) { turnOn = true }
            ChoiceButton("Выключить", selected = !turnOn) { turnOn = false }
        }

        Spacer(Modifier.height(20.dp))
        Text("Время", fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { showTimePicker = true }) {
            Text(timeLabel(hour, minute), fontSize = 20.sp)
        }

        Spacer(Modifier.height(20.dp))
        Text("Дни недели", fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (day in AirplaneRule.WEEK) {
                val bit = AirplaneRule.dayBit(day)
                DayToggle(AirplaneRule.dayShortName(day), selected = daysMask and bit != 0) {
                    daysMask = daysMask xor bit
                    showNoDaysError = false
                }
            }
        }
        if (showNoDaysError) {
            Spacer(Modifier.height(8.dp))
            Text("Выберите хотя бы один день", color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
        }

        Spacer(Modifier.height(32.dp))
        Row {
            Button(onClick = {
                if (daysMask == 0) {
                    showNoDaysError = true
                } else {
                    AirplaneScheduleManager.saveRule(context, existing?.id, turnOn, hour, minute, daysMask)
                    onDone()
                }
            }) { Text("Сохранить") }
            Spacer(Modifier.width(12.dp))
            OutlinedButton(onClick = onDone) { Text("Отмена") }
        }
    }

    if (showTimePicker) {
        val pickerState = rememberTimePickerState(initialHour = hour, initialMinute = minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text("Время") },
            text = { TimePicker(state = pickerState) },
            confirmButton = {
                TextButton(onClick = {
                    hour = pickerState.hour
                    minute = pickerState.minute
                    showTimePicker = false
                }) { Text("Готово") }
            },
            dismissButton = { TextButton(onClick = { showTimePicker = false }) { Text("Отмена") } }
        )
    }
}

@Composable
private fun ChoiceButton(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick) { Text(label) }
    }
}

@Composable
private fun DayToggle(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(40.dp)
            .background(if (selected) colors.primary else colors.surface, CircleShape)
            .border(1.dp, colors.primary, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, fontSize = 13.sp, color = if (selected) colors.onPrimary else colors.primary)
    }
}
