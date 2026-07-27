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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.delay

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
     * USE_FULL_SCREEN_INTENT is revoked by default unless the installer recognised the app
     * as a clock/calling app, and without it setFullScreenIntent() silently degrades to an
     * ordinary heads-up notification — the screen never turns on.
     */
    private val fullScreenIntentAllowed = mutableStateOf(true)
    private val exactAlarmAllowed = mutableStateOf(true)
    private val activityRecognitionGranted = mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TimerManager.init(this)
        requestNotificationPermissionIfNeeded()

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val navController = rememberNavController()
                    NavHost(navController = navController, startDestination = "timer") {
                        composable("timer") {
                            TimerScreen(
                                fullScreenIntentAllowed = fullScreenIntentAllowed.value,
                                exactAlarmAllowed = exactAlarmAllowed.value,
                                onGrantFullScreenIntent = { openFullScreenIntentSettings() },
                                onGrantExactAlarm = { openExactAlarmSettings() },
                                onOpenSettings = { navController.navigate("settings") }
                            )
                        }
                        composable("settings") {
                            SettingsScreen(
                                activityRecognitionGranted = activityRecognitionGranted.value,
                                onRequestActivityRecognition = { requestActivityRecognition() },
                                onBack = { navController.popBackStack() }
                            )
                        }
                    }
                }
            }
        }
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

    private fun requestActivityRecognition() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            activityRecognitionLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            if (!granted) notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
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

@Composable
fun TimerScreen(
    fullScreenIntentAllowed: Boolean,
    exactAlarmAllowed: Boolean,
    onGrantFullScreenIntent: () -> Unit,
    onGrantExactAlarm: () -> Unit,
    onOpenSettings: () -> Unit
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
        TextButton(
            onClick = onOpenSettings,
            modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)
        ) {
            Text("Настройки")
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
                            Button(onClick = onGrantFullScreenIntent) {
                                Text("Разрешить")
                            }
                        }
                        if (!exactAlarmAllowed) {
                            Text("Нужно разрешение на точные будильники.", textAlign = TextAlign.Center)
                            Button(onClick = onGrantExactAlarm) {
                                Text("Разрешить точные будильники")
                            }
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
            Text(text = formatTime(remainingMillis), fontSize = 64.sp, fontWeight = FontWeight.Bold)
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
                }
                TimerStatus.PAUSED -> {
                    Row {
                        Button(onClick = { TimerManager.resume(context) }) { Text("Продолжить") }
                        Spacer(Modifier.width(16.dp))
                        OutlinedButton(onClick = { TimerManager.reset(context) }) { Text("Сброс") }
                    }
                }
                TimerStatus.FINISHED -> {
                    val label = if (state.phase == TimerPhase.WORK) {
                        "Начать перерыв (${state.breakDurationMillis / 60000} мин)"
                    } else {
                        "Начать работу (${state.workDurationMillis / 60000} мин)"
                    }
                    Row {
                        Button(onClick = { TimerManager.advancePhaseAndStart(context) }) {
                            Text(label, fontSize = 18.sp)
                        }
                        Spacer(Modifier.width(16.dp))
                        OutlinedButton(onClick = { TimerManager.reset(context) }) {
                            Text("Сброс")
                        }
                    }
                }
            }
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

    var workMinutes by remember(state.workDurationMillis) {
        mutableStateOf((state.workDurationMillis / 60000).toString())
    }
    var breakMinutes by remember(state.breakDurationMillis) {
        mutableStateOf((state.breakDurationMillis / 60000).toString())
    }
    var idleMinutes by remember(state.idleThresholdMillis) {
        mutableStateOf((state.idleThresholdMillis / 60000).toString())
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Настройки", fontSize = 22.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(32.dp))

        OutlinedTextField(
            value = workMinutes,
            onValueChange = { value ->
                workMinutes = value.filter { it.isDigit() }
                workMinutes.toIntOrNull()?.let { TimerManager.setWorkMinutes(context, it) }
            },
            label = { Text("Минуты работы") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = breakMinutes,
            onValueChange = { value ->
                breakMinutes = value.filter { it.isDigit() }
                breakMinutes.toIntOrNull()?.let { TimerManager.setBreakMinutes(context, it) }
            },
            label = { Text("Минуты перерыва") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )

        Spacer(Modifier.height(32.dp))
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
                checked = state.autoStartEnabled,
                onCheckedChange = { enabled ->
                    if (enabled && !activityRecognitionGranted) {
                        onRequestActivityRecognition()
                    } else {
                        TimerManager.setAutoStartEnabled(context, enabled)
                    }
                }
            )
        }

        if (state.autoStartEnabled && !activityRecognitionGranted) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Нужен доступ к данным о физической активности, иначе шаги не читаются.",
                textAlign = TextAlign.Center
            )
            Button(onClick = onRequestActivityRecognition) { Text("Выдать доступ") }
        }

        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = idleMinutes,
            onValueChange = { value ->
                idleMinutes = value.filter { it.isDigit() }
                idleMinutes.toIntOrNull()?.let { TimerManager.setIdleMinutes(context, it) }
            },
            label = { Text("Минуты покоя до автозапуска") },
            singleLine = true,
            enabled = state.autoStartEnabled,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )

        Spacer(Modifier.height(32.dp))
        Button(onClick = onBack) { Text("Назад") }
    }
}

private fun formatTime(millis: Long): String {
    val totalSeconds = millis / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}
