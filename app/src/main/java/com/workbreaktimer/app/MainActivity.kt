package com.workbreaktimer.app

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val notifPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val activityRecognitionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            activityRecognitionGranted.value = granted
            // The permission is only ever requested because the user asked for auto-start, and
            // granting it is what unblocks the health foreground service.
            if (granted) TimerManager.setAutoStartEnabled(this, true)
        }

    /**
     * Whether the alarm may launch AlarmActivity over the lock screen. Since Android 14
     * USE_FULL_SCREEN_INTENT is revoked by default unless the installer recognised the app as
     * a clock or calling app, and without it setFullScreenIntent() silently degrades to an
     * ordinary heads-up notification — the screen never turns on.
     */
    private val fullScreenIntentAllowed = mutableStateOf(true)
    private val exactAlarmAllowed = mutableStateOf(true)
    private val activityRecognitionGranted = mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TimerManager.init(this)
        ReminderManager.init(this)
        requestNotificationPermissionIfNeeded()

        setContent {
            MaterialTheme {
                val navController = rememberNavController()
                val backStackEntry by navController.currentBackStackEntryAsState()
                val route = backStackEntry?.destination?.route
                // The bar belongs to the two top-level modes only; detail screens get the
                // whole height and their own back button.
                val topLevel = route == ROUTE_TIMER || route == ROUTE_REMINDERS

                Scaffold(
                    bottomBar = {
                        if (topLevel) {
                            NavigationBar {
                                NavigationBarItem(
                                    selected = route == ROUTE_TIMER,
                                    onClick = { navController.switchTab(ROUTE_TIMER) },
                                    icon = { Text("⏱", fontSize = 18.sp) },
                                    label = { Text("Таймер") }
                                )
                                NavigationBarItem(
                                    selected = route == ROUTE_REMINDERS,
                                    onClick = { navController.switchTab(ROUTE_REMINDERS) },
                                    icon = { Text("🔔", fontSize = 18.sp) },
                                    label = { Text("Напоминания") }
                                )
                            }
                        }
                    }
                ) { padding ->
                    Surface(modifier = Modifier.fillMaxSize().padding(padding)) {
                        NavHost(navController = navController, startDestination = ROUTE_TIMER) {
                            composable(ROUTE_TIMER) {
                                TimerScreen(
                                    fullScreenIntentAllowed = fullScreenIntentAllowed.value,
                                    exactAlarmAllowed = exactAlarmAllowed.value,
                                    onGrantFullScreenIntent = { openFullScreenIntentSettings() },
                                    onGrantExactAlarm = { openExactAlarmSettings() },
                                    onOpenSettings = { navController.navigate("settings") },
                                    onOpenHistory = { navController.navigate("history") }
                                )
                            }
                            composable("settings") {
                                SettingsScreen(
                                    activityRecognitionGranted = activityRecognitionGranted.value,
                                    onRequestActivityRecognition = { requestActivityRecognition() },
                                    onBack = { navController.popBackStack() }
                                )
                            }
                            composable("history") {
                                HistoryScreen(onBack = { navController.popBackStack() })
                            }
                            composable(ROUTE_REMINDERS) {
                                FoldersScreen(
                                    onOpenFolder = { navController.navigate("folder/$it") }
                                )
                            }
                            composable("folder/{folderId}") { entry ->
                                val folderId = entry.arguments?.getString("folderId").orEmpty()
                                FolderScreen(
                                    folderId = folderId,
                                    onBack = { navController.popBackStack() },
                                    onEditReminder = { reminderId ->
                                        navController.navigate("editor/$folderId/${reminderId ?: NEW_REMINDER}")
                                    }
                                )
                            }
                            composable("editor/{folderId}/{reminderId}") { entry ->
                                val folderId = entry.arguments?.getString("folderId").orEmpty()
                                val reminderId = entry.arguments?.getString("reminderId")
                                ReminderEditorScreen(
                                    folderId = folderId,
                                    reminderId = reminderId.takeIf { it != NEW_REMINDER },
                                    onDone = { navController.popBackStack() }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private companion object {
        const val ROUTE_TIMER = "timer"
        const val ROUTE_REMINDERS = "reminders"
        const val NEW_REMINDER = "new"
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionState()
    }

    private fun refreshPermissionState() {
        fullScreenIntentAllowed.value =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
            } else true
        exactAlarmAllowed.value =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
            } else true
        activityRecognitionGranted.value = TimerManager.hasActivityRecognitionPermission(this)
        TimerManager.syncStepTracking(this)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            if (!granted) notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun requestActivityRecognition() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            activityRecognitionLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
        } else {
            TimerManager.setAutoStartEnabled(this, true)
        }
    }

    private fun openFullScreenIntentSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                    Uri.parse("package:$packageName")
                )
            )
        }
    }

    private fun openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            startActivity(
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName"))
            )
        }
    }
}

/** Tab switch that keeps one entry per mode on the stack rather than piling them up. */
private fun NavHostController.switchTab(route: String) {
    navigate(route) {
        popUpTo(graph.startDestinationId) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
fun TimerScreen(
    fullScreenIntentAllowed: Boolean,
    exactAlarmAllowed: Boolean,
    onGrantFullScreenIntent: () -> Unit,
    onGrantExactAlarm: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenHistory: () -> Unit
) {
    val state by TimerManager.state.collectAsState()
    val context = LocalContext.current

    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.status) {
        while (state.status == TimerStatus.RUNNING) {
            now = System.currentTimeMillis()
            delay(200)
        }
    }

    val remainingMillis = if (state.status == TimerStatus.RUNNING) {
        (state.endTimeMillis - now).coerceAtLeast(0)
    } else {
        state.remainingMillis
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
            TextButton(onClick = onOpenHistory) { Text("История") }
            TextButton(onClick = onOpenSettings) { Text("Настройки") }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (!fullScreenIntentAllowed || !exactAlarmAllowed) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "Будильник не сможет включить экран",
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(8.dp))
                        if (!fullScreenIntentAllowed) {
                            Text(
                                "Разрешите приложению показывать экран будильника поверх " +
                                    "блокировки, иначе вместо него придёт обычное уведомление.",
                                textAlign = TextAlign.Center
                            )
                            Button(onClick = onGrantFullScreenIntent) { Text("Разрешить") }
                        }
                        if (!exactAlarmAllowed) {
                            Text("Нужно разрешение на точные будильники.", textAlign = TextAlign.Center)
                            Button(onClick = onGrantExactAlarm) { Text("Разрешить точные будильники") }
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }

            Text(
                text = if (state.phase == TimerPhase.WORK) "Рабочий таймер" else "Перерыв",
                fontSize = 22.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(16.dp))
            Text(text = formatClock(remainingMillis), fontSize = 64.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(32.dp))

            when (state.status) {
                TimerStatus.IDLE -> {
                    Button(onClick = { TimerManager.start(context) }) {
                        Text("Старт", fontSize = 20.sp)
                    }
                }
                TimerStatus.RUNNING -> {
                    Row {
                        Button(onClick = { TimerManager.pause(context) }) { Text("Пауза") }
                        Spacer(Modifier.width(16.dp))
                        OutlinedButton(onClick = { TimerManager.reset(context) }) { Text("Сброс") }
                    }
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = { TimerManager.finishEarly(context) }) {
                        Text(finishEarlyLabel(state.phase))
                    }
                }
                TimerStatus.PAUSED -> {
                    Row {
                        Button(onClick = { TimerManager.resume(context) }) { Text("Продолжить") }
                        Spacer(Modifier.width(16.dp))
                        OutlinedButton(onClick = { TimerManager.reset(context) }) { Text("Сброс") }
                    }
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = { TimerManager.finishEarly(context) }) {
                        Text(finishEarlyLabel(state.phase))
                    }
                }
                TimerStatus.FINISHED -> {
                    val advanceLabel = if (state.phase == TimerPhase.WORK) {
                        "Начать перерыв (${formatDurationShort(state.settings.breakMillis)})"
                    } else {
                        "Начать работу (${formatDurationShort(state.settings.workMillis)})"
                    }
                    val snoozeLabel = if (state.phase == TimerPhase.WORK) {
                        "Ещё поработать (${formatDurationShort(state.settings.snoozeMillis)})"
                    } else {
                        "Ещё отдохнуть (${formatDurationShort(state.settings.snoozeMillis)})"
                    }
                    Button(onClick = { TimerManager.advancePhaseAndStart(context) }) {
                        Text(advanceLabel, fontSize = 18.sp)
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = { TimerManager.snooze(context) }) { Text(snoozeLabel) }
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = { TimerManager.reset(context) }) { Text("Сброс") }
                }
            }
        }
    }
}

private fun finishEarlyLabel(phase: TimerPhase): String =
    if (phase == TimerPhase.WORK) "Завершить работу и начать перерыв" else "Завершить перерыв и начать работу"

/**
 * Minutes and seconds as two fields rather than one free-form string: it keeps the numeric
 * keypad usable and removes any parsing of "1:30" style input.
 */
@Composable
private fun DurationField(
    label: String,
    millis: Long,
    enabled: Boolean = true,
    onChange: (Long) -> Unit
) {
    // Seeded once and then owned by the fields: keying this on `millis` would feed each
    // keystroke back through the store and clobber the other field mid-edit.
    var minutes by remember { mutableStateOf((millis / 60_000).toString()) }
    var seconds by remember { mutableStateOf((millis / 1000 % 60).toString()) }

    fun push() {
        onChange(TimerSettings.toMillis(minutes.toIntOrNull() ?: 0, seconds.toIntOrNull() ?: 0))
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = minutes,
                onValueChange = { minutes = it.filter { c -> c.isDigit() }.take(3); push() },
                label = { Text("мин") },
                singleLine = true,
                enabled = enabled,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(12.dp))
            OutlinedTextField(
                value = seconds,
                onValueChange = { seconds = it.filter { c -> c.isDigit() }.take(2); push() },
                label = { Text("сек") },
                singleLine = true,
                enabled = enabled,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
fun SettingsScreen(
    activityRecognitionGranted: Boolean,
    onRequestActivityRecognition: () -> Unit,
    onBack: () -> Unit
) {
    val state by TimerManager.state.collectAsState()
    val context = LocalContext.current
    val settings = state.settings

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Настройки", fontSize = 22.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(24.dp))

        DurationField("Работа", settings.workMillis) { millis ->
            TimerManager.updateSettings(context) { it.copy(workMillis = millis) }
        }
        Spacer(Modifier.height(16.dp))
        DurationField("Перерыв", settings.breakMillis) { millis ->
            TimerManager.updateSettings(context) { it.copy(breakMillis = millis) }
        }
        Spacer(Modifier.height(16.dp))
        DurationField("Снуз (отложить будильник)", settings.snoozeMillis) { millis ->
            TimerManager.updateSettings(context) { it.copy(snoozeMillis = millis) }
        }

        Spacer(Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        Text("Автозапуск по педометру", fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Если телефон не зафиксировал ни одного шага заданное время, считаем что " +
                "начался сидячий режим, и запускаем таймер работы сами.",
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(16.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Включить автозапуск")
            Spacer(Modifier.width(16.dp))
            Switch(
                checked = settings.autoStartEnabled,
                onCheckedChange = { enabled ->
                    if (enabled && !activityRecognitionGranted) {
                        onRequestActivityRecognition()
                    } else {
                        TimerManager.setAutoStartEnabled(context, enabled)
                    }
                }
            )
        }

        if (settings.autoStartEnabled && !activityRecognitionGranted) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Нужен доступ к данным о физической активности, иначе шаги не читаются.",
                textAlign = TextAlign.Center
            )
            Button(onClick = onRequestActivityRecognition) { Text("Выдать доступ") }
        }

        Spacer(Modifier.height(16.dp))
        // Editable whether or not auto-start is on, so the threshold can be dialled in first.
        DurationField("Покой до автозапуска", settings.idleThresholdMillis) { millis ->
            TimerManager.updateSettings(context) { it.copy(idleThresholdMillis = millis) }
        }

        Spacer(Modifier.height(32.dp))
        Button(onClick = onBack) { Text("Назад") }

        Spacer(Modifier.height(24.dp))
        Text(
            "Версия ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private enum class ChartRange(val label: String) {
    DAY("День"), SEVEN_DAYS("7 дней"), WEEKS("Недели"), MONTHS("Месяцы")
}

private const val DAY_MILLIS = 24 * 60 * 60 * 1000L

@Composable
fun HistoryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val repository = remember { TimerManager.history(context) }
    // Read once per visit: nothing writes history while this screen is on top.
    val stats = remember { repository.stats() }
    val sessions = remember { repository.recent(50) }
    val timeFormat = remember { SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()) }

    var chartRange by remember { mutableStateOf(ChartRange.SEVEN_DAYS) }
    var dayOffset by remember { mutableStateOf(0) }
    val dayLabelFormat = remember { SimpleDateFormat("d MMMM", Locale("ru")) }

    val buckets = remember(chartRange, dayOffset) {
        when (chartRange) {
            ChartRange.DAY -> repository.hourlyBuckets(System.currentTimeMillis() - dayOffset * DAY_MILLIS)
            ChartRange.SEVEN_DAYS -> repository.dailyBuckets(7)
            ChartRange.WEEKS -> repository.weeklyBuckets(8)
            ChartRange.MONTHS -> repository.monthlyBuckets(6)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("История", fontSize = 22.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(24.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                StatRow("Сегодня работа", formatDurationLong(stats.todayWorkMillis))
                StatRow("Сегодня сессий", stats.todayWorkSessions.toString())
                StatRow("Сегодня перерывы", formatDurationLong(stats.todayBreakMillis))
                StatRow("За 7 дней", formatDurationLong(stats.weekWorkMillis))
                StatRow("Дней подряд", stats.streakDays.toString())
            }
        }

        Spacer(Modifier.height(24.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            for (range in ChartRange.values()) {
                val selected = range == chartRange
                OutlinedButton(
                    onClick = {
                        chartRange = range
                        if (range != ChartRange.DAY) dayOffset = 0
                    },
                    modifier = Modifier.weight(1f),
                    border = if (selected) {
                        BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                    } else {
                        ButtonDefaults.outlinedButtonBorder
                    }
                ) {
                    Text(range.label, fontSize = 13.sp)
                }
            }
        }
        Spacer(Modifier.height(16.dp))

        if (chartRange == ChartRange.DAY) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { dayOffset++ }) { Text("← Раньше") }
                Text(
                    if (dayOffset == 0) "Сегодня" else {
                        dayLabelFormat.format(Date(System.currentTimeMillis() - dayOffset * DAY_MILLIS))
                    },
                    fontWeight = FontWeight.Medium
                )
                TextButton(onClick = { if (dayOffset > 0) dayOffset-- }, enabled = dayOffset > 0) {
                    Text("Позже →")
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        val totalWork = buckets.sumOf { it.workMillis }
        val totalBreak = buckets.sumOf { it.breakMillis }
        Text(
            "Работа: ${formatDurationLong(totalWork)} · Перерыв: ${formatDurationLong(totalBreak)}",
            fontSize = 13.sp
        )
        Spacer(Modifier.height(12.dp))

        WorkBreakBarChart(buckets)

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            ChartLegendSwatch(WORK_BAR_COLOR)
            Spacer(Modifier.width(6.dp))
            Text("Работа", fontSize = 13.sp)
            Spacer(Modifier.width(16.dp))
            ChartLegendSwatch(BREAK_BAR_COLOR)
            Spacer(Modifier.width(6.dp))
            Text("Перерыв", fontSize = 13.sp)
        }

        Spacer(Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        if (sessions.isEmpty()) {
            Text(
                "Пока пусто. Завершите первую сессию, и она появится здесь.",
                textAlign = TextAlign.Center
            )
        } else {
            for (session in sessions) {
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            if (session.phase == TimerPhase.WORK) "Работа" else "Перерыв",
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )
                        Text(formatDurationLong(session.actualMillis))
                    }
                    Spacer(Modifier.height(2.dp))
                    val marks = buildList {
                        add(timeFormat.format(Date(session.startedAtMillis)))
                        if (session.autoStarted) add("автозапуск")
                        if (session.snoozedMillis > 0) add("снуз ${formatDurationShort(session.snoozedMillis)}")
                        if (!session.completed) add("прервано")
                    }
                    Text(marks.joinToString(" · "), fontSize = 13.sp)
                }
                HorizontalDivider()
            }
        }

        Spacer(Modifier.height(32.dp))
        Button(onClick = onBack) { Text("Назад") }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, modifier = Modifier.weight(1f))
        Text(value, fontWeight = FontWeight.Medium)
    }
}

private val WORK_BAR_COLOR = Color(0xFF3B82F6)
private val BREAK_BAR_COLOR = Color(0xFF10B981)
private val CHART_BAR_HEIGHT = 140.dp

@Composable
private fun ChartLegendSwatch(color: Color) {
    Box(
        modifier = Modifier
            .width(12.dp)
            .height(12.dp)
            .background(color, shape = RoundedCornerShape(2.dp))
    )
}

/**
 * Two bars per bucket (work, break) scaled to the largest value on screen, in a horizontally
 * scrolling row — 24 hourly or 12 monthly buckets do not need to be squeezed to fit a phone
 * width when they can simply be scrolled instead.
 */
@Composable
private fun WorkBreakBarChart(buckets: List<ChartBucket>) {
    val maxMillis = (buckets.maxOfOrNull { maxOf(it.workMillis, it.breakMillis) } ?: 0L)
        .coerceAtLeast(60 * 60_000L)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.Bottom
    ) {
        for (bucket in buckets) {
            Column(
                modifier = Modifier.width(36.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.height(CHART_BAR_HEIGHT),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .width(13.dp)
                            .fillMaxHeight(
                                (bucket.workMillis.toFloat() / maxMillis).coerceIn(0f, 1f)
                            )
                            .background(WORK_BAR_COLOR, shape = RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp))
                    )
                    Box(
                        modifier = Modifier
                            .width(13.dp)
                            .fillMaxHeight(
                                (bucket.breakMillis.toFloat() / maxMillis).coerceIn(0f, 1f)
                            )
                            .background(BREAK_BAR_COLOR, shape = RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp))
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(bucket.label, fontSize = 10.sp, maxLines = 1)
            }
            Spacer(Modifier.width(10.dp))
        }
    }
}
