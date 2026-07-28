package com.workbreaktimer.app

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private fun reminderDateFormat() = SimpleDateFormat("d MMM yyyy", Locale.getDefault())
private fun reminderTimeFormat() = SimpleDateFormat("HH:mm", Locale.getDefault())

@Composable
fun FoldersScreen(onOpenFolder: (String) -> Unit) {
    val context = LocalContext.current
    val state by ReminderManager.state.collectAsState()
    var showCreate by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<ReminderFolder?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        Text("Напоминания", fontSize = 22.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(16.dp))

        if (state.folders.isEmpty()) {
            Text(
                "Пока нет ни одной папки. Создайте первую, чтобы добавлять напоминания.",
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(16.dp))
        }

        for (folder in state.folders) {
            val items = state.remindersIn(folder.id)
            val active = items.count { it.enabled }
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
                    .clickable { onOpenFolder(folder.id) }
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(folder.name, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(2.dp))
                        Text("${items.size} записей, активных $active", fontSize = 13.sp)
                    }
                    TextButton(onClick = { pendingDelete = folder }) { Text("Удалить") }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Button(onClick = { showCreate = true }) { Text("Новая папка") }
    }

    if (showCreate) {
        TextPromptDialog(
            title = "Новая папка",
            label = "Название",
            initial = "",
            onDismiss = { showCreate = false },
            onConfirm = { name ->
                ReminderManager.addFolder(context, name)
                showCreate = false
            }
        )
    }

    pendingDelete?.let { folder ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Удалить папку?") },
            text = { Text("Вместе с «${folder.name}» удалятся все её напоминания.") },
            confirmButton = {
                TextButton(onClick = {
                    ReminderManager.deleteFolder(context, folder.id)
                    pendingDelete = null
                }) { Text("Удалить") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Отмена") }
            }
        )
    }
}

@Composable
fun FolderScreen(folderId: String, onBack: () -> Unit, onEditReminder: (String?) -> Unit) {
    val context = LocalContext.current
    val state by ReminderManager.state.collectAsState()
    val folder = state.folder(folderId)
    val items = state.remindersIn(folderId)
    val dateFormat = remember { reminderDateFormat() }
    val timeFormat = remember { reminderTimeFormat() }

    if (folder == null) {
        // The folder was deleted from under this screen.
        Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            Text("Папка удалена")
            Spacer(Modifier.height(16.dp))
            Button(onClick = onBack) { Text("Назад") }
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        Text(folder.name, fontSize = 22.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(16.dp))

        if (items.isEmpty()) {
            Text("Здесь пока пусто.", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            Spacer(Modifier.height(16.dp))
        }

        for (reminder in items) {
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Marks the occurrence that just fired as done — separate from the Switch,
                    // which controls whether the alarm keeps firing at all.
                    Checkbox(
                        checked = reminder.completed,
                        onCheckedChange = { ReminderManager.setCompleted(context, reminder.id, it) }
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onEditReminder(reminder.id) }
                    ) {
                        Text(
                            reminder.title,
                            fontWeight = FontWeight.Medium,
                            textDecoration = if (reminder.completed) TextDecoration.LineThrough else null
                        )
                        Spacer(Modifier.height(2.dp))
                        val date = Date(reminder.triggerAtMillis)
                        Text(
                            "${dateFormat.format(date)}, ${timeFormat.format(date)} · ${reminder.repeatLabel}",
                            fontSize = 13.sp
                        )
                    }
                    Switch(
                        checked = reminder.enabled,
                        onCheckedChange = { ReminderManager.setEnabled(context, reminder.id, it) }
                    )
                }
                Row {
                    TextButton(onClick = { onEditReminder(reminder.id) }) { Text("Изменить") }
                    TextButton(onClick = { ReminderManager.deleteReminder(context, reminder.id) }) {
                        Text("Удалить")
                    }
                }
            }
            HorizontalDivider()
        }

        Spacer(Modifier.height(16.dp))
        Row {
            Button(onClick = { onEditReminder(null) }) { Text("Добавить") }
            Spacer(Modifier.width(12.dp))
            OutlinedButton(onClick = onBack) { Text("Назад") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReminderEditorScreen(
    folderId: String,
    reminderId: String?,
    onDone: () -> Unit
) {
    val context = LocalContext.current
    val state by ReminderManager.state.collectAsState()
    val existing = reminderId?.let { state.reminder(it) }

    // Seeded once; the editor owns its draft until the user saves.
    val seed = remember {
        existing?.triggerAtMillis ?: (System.currentTimeMillis() + 60 * 60 * 1000L)
    }
    var title by remember { mutableStateOf(existing?.title ?: "") }
    var repeat by remember { mutableStateOf(existing?.repeat ?: RepeatRule.ONCE) }
    var triggerAt by remember { mutableStateOf(seed) }
    val seedInterval = remember { existing?.intervalMillis ?: Reminder.DEFAULT_INTERVAL_MILLIS }
    var intervalHours by remember { mutableStateOf((seedInterval / 3_600_000L).toString()) }
    var intervalMinutes by remember { mutableStateOf((seedInterval / 60_000L % 60).toString()) }

    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var repeatMenuOpen by remember { mutableStateOf(false) }

    val dateFormat = remember { reminderDateFormat() }
    val timeFormat = remember { reminderTimeFormat() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        Text(
            if (existing == null) "Новое напоминание" else "Напоминание",
            fontSize = 22.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.height(24.dp))

        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text("Название") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(16.dp))

        Row(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { showDatePicker = true }, modifier = Modifier.weight(1f)) {
                Text(dateFormat.format(Date(triggerAt)))
            }
            Spacer(Modifier.width(12.dp))
            OutlinedButton(onClick = { showTimePicker = true }, modifier = Modifier.weight(1f)) {
                Text(timeFormat.format(Date(triggerAt)))
            }
        }
        Spacer(Modifier.height(16.dp))

        Box {
            OutlinedButton(onClick = { repeatMenuOpen = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Повтор: ${repeat.label}")
            }
            DropdownMenu(expanded = repeatMenuOpen, onDismissRequest = { repeatMenuOpen = false }) {
                for (rule in RepeatRule.values()) {
                    DropdownMenuItem(
                        text = { Text(rule.label) },
                        onClick = {
                            repeat = rule
                            repeatMenuOpen = false
                        }
                    )
                }
            }
        }

        if (repeat == RepeatRule.INTERVAL) {
            Spacer(Modifier.height(16.dp))
            Text("Интервал повтора", fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = intervalHours,
                    onValueChange = { intervalHours = it.filter { c -> c.isDigit() }.take(3) },
                    label = { Text("часов") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(12.dp))
                OutlinedTextField(
                    value = intervalMinutes,
                    onValueChange = { intervalMinutes = it.filter { c -> c.isDigit() }.take(3) },
                    label = { Text("минут") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Первое срабатывание — в указанные дату и время, дальше через этот интервал. " +
                    "Минимум одна минута.",
                fontSize = 13.sp
            )
        }

        if (triggerAt <= System.currentTimeMillis() && repeat == RepeatRule.ONCE) {
            Spacer(Modifier.height(12.dp))
            Text("Это время уже прошло — напоминание не сработает.", fontSize = 13.sp)
        }

        Spacer(Modifier.height(32.dp))
        Row {
            Button(onClick = {
                val interval = (intervalHours.toLongOrNull() ?: 0L) * 3_600_000L +
                    (intervalMinutes.toLongOrNull() ?: 0L) * 60_000L
                ReminderManager.saveReminder(
                    context, reminderId, folderId, title, triggerAt, repeat, interval
                )
                onDone()
            }) { Text("Сохранить") }
            Spacer(Modifier.width(12.dp))
            OutlinedButton(onClick = onDone) { Text("Отмена") }
        }
    }

    if (showDatePicker) {
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = triggerAt)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { picked ->
                        triggerAt = combineDateAndTime(datePart = picked, timePart = triggerAt)
                    }
                    showDatePicker = false
                }) { Text("Готово") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Отмена") }
            }
        ) {
            DatePicker(state = pickerState)
        }
    }

    if (showTimePicker) {
        val calendar = Calendar.getInstance().apply { timeInMillis = triggerAt }
        val pickerState = rememberTimePickerState(
            initialHour = calendar.get(Calendar.HOUR_OF_DAY),
            initialMinute = calendar.get(Calendar.MINUTE),
            is24Hour = true
        )
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text("Время") },
            text = { TimePicker(state = pickerState) },
            confirmButton = {
                TextButton(onClick = {
                    triggerAt = withTime(triggerAt, pickerState.hour, pickerState.minute)
                    showTimePicker = false
                }) { Text("Готово") }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) { Text("Отмена") }
            }
        )
    }
}

@Composable
private fun TextPromptDialog(
    title: String,
    label: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(label) },
                singleLine = true
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(value) }) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

/**
 * The date picker reports UTC midnight of the chosen day, so the calendar fields are read in
 * UTC and re-applied to a local-time calendar. Taking the raw millis would shift the day for
 * anyone east or west of Greenwich.
 */
private fun combineDateAndTime(datePart: Long, timePart: Long): Long {
    val utc = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
    utc.timeInMillis = datePart
    val local = Calendar.getInstance()
    local.timeInMillis = timePart
    local.set(Calendar.YEAR, utc.get(Calendar.YEAR))
    local.set(Calendar.MONTH, utc.get(Calendar.MONTH))
    local.set(Calendar.DAY_OF_MONTH, utc.get(Calendar.DAY_OF_MONTH))
    return local.timeInMillis
}

private fun withTime(base: Long, hour: Int, minute: Int): Long {
    val calendar = Calendar.getInstance()
    calendar.timeInMillis = base
    calendar.set(Calendar.HOUR_OF_DAY, hour)
    calendar.set(Calendar.MINUTE, minute)
    calendar.set(Calendar.SECOND, 0)
    calendar.set(Calendar.MILLISECOND, 0)
    return calendar.timeInMillis
}
