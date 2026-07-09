package com.workbreaktimer.app

import android.Manifest
import android.app.AlarmManager
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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private val notifPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TimerManager.init(this)
        requestNotificationPermissionIfNeeded()

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    TimerScreen(onRequestExactAlarmPermission = { requestExactAlarmPermissionIfNeeded() })
                }
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            if (!granted) notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun requestExactAlarmPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val am = getSystemService(AlarmManager::class.java)
            if (!am.canScheduleExactAlarms()) {
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
            }
        }
    }
}

@Composable
fun TimerScreen(onRequestExactAlarmPermission: () -> Unit) {
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

    var minutesInput by remember(state.workDurationMillis) {
        mutableStateOf((state.workDurationMillis / 60000).toString())
    }

    LaunchedEffect(Unit) { onRequestExactAlarmPermission() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
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
                if (state.phase == TimerPhase.WORK) {
                    OutlinedTextField(
                        value = minutesInput,
                        onValueChange = { value ->
                            minutesInput = value.filter { it.isDigit() }
                            minutesInput.toIntOrNull()?.let { TimerManager.setWorkMinutes(context, it) }
                        },
                        label = { Text("Минуты работы") },
                        singleLine = true
                    )
                    Spacer(Modifier.height(24.dp))
                }
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
                    "Начать перерыв (5 мин)"
                } else {
                    "Начать работу (${state.workDurationMillis / 60000} мин)"
                }
                Button(onClick = {
                    TimerManager.stopRingingOnly(context)
                    TimerManager.advancePhaseAndStart(context)
                }) {
                    Text(label, fontSize = 18.sp)
                }
            }
        }
    }
}

private fun formatTime(millis: Long): String {
    val totalSeconds = millis / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}
