package com.workbreaktimer.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.util.Locale

private const val STEPS_REFRESH_MILLIS = 30_000L

private fun formatSteps(steps: Long): String = String.format(Locale("ru"), "%,d", steps)

/**
 * Two bars under the timer: steps walked today against the daily goal, and, when walk reset
 * is on, the current series of steps against the count that restarts work.
 */
@Composable
fun StepsPanel() {
    val context = LocalContext.current
    val timer by TimerManager.state.collectAsState()
    val series by WalkResetService.seriesSteps.collectAsState()
    val availability = remember { StepsToday.availability(context) }
    var granted by remember { mutableStateOf<Boolean?>(null) }
    var steps by remember { mutableStateOf<Long?>(null) }
    var refresh by remember { mutableStateOf(0) }

    val launcher = rememberLauncherForActivityResult(StepsToday.permissionContract()) { result ->
        granted = StepsToday.READ_STEPS in result
        refresh++
    }

    // Health Connect pushes nothing to readers, so the total is read again every half minute.
    LaunchedEffect(refresh) {
        if (availability != StepsToday.Availability.AVAILABLE) return@LaunchedEffect
        while (true) {
            val allowed = StepsToday.hasPermission(context)
            granted = allowed
            if (allowed) StepsToday.read(context)?.let { steps = it }
            delay(STEPS_REFRESH_MILLIS)
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        when {
            availability == StepsToday.Availability.UNAVAILABLE ->
                Text("Шаги за день недоступны: телефон не поддерживает Health Connect.", fontSize = 13.sp)
            availability == StepsToday.Availability.NEEDS_INSTALL -> {
                Text("Для шагов за день нужен Health Connect.", fontSize = 13.sp)
                TextButton(onClick = { runCatching { context.startActivity(StepsToday.installIntent()) } }) {
                    Text("Установить Health Connect")
                }
            }
            granted == false -> {
                Text("Шаги за день берутся из Health Connect.", fontSize = 13.sp)
                TextButton(onClick = { launcher.launch(setOf(StepsToday.READ_STEPS)) }) {
                    Text("Разрешить доступ к шагам")
                }
            }
            else -> {
                val goal = timer.settings.dailyStepGoal.toLong()
                val today = steps
                LabeledBar(
                    title = "Шаги сегодня",
                    value = if (today == null) "…" else "${formatSteps(today)} из ${formatSteps(goal)}",
                    fraction = (today ?: 0L).toFloat() / goal
                )
                if (today == 0L) {
                    Text(
                        "Если число не растёт, проверьте, что Google Fit или другое приложение " +
                            "записывает шаги в Health Connect.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                }
            }
        }

        if (timer.settings.walkResetEnabled) {
            val target = timer.settings.walkResetSteps
            val counting = timer.watchesWalking
            LabeledBar(
                title = "До сброса таймера",
                value = if (counting) "$series из $target шагов" else "считает во время работы",
                fraction = if (counting) series.toFloat() / target else 0f
            )
        }
    }
}

@Composable
private fun LabeledBar(title: String, value: String, fraction: Float) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(title, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
    Spacer(Modifier.height(4.dp))
    LinearProgressIndicator(
        progress = { fraction.coerceIn(0f, 1f) },
        modifier = Modifier.fillMaxWidth().height(8.dp)
    )
    Spacer(Modifier.height(12.dp))
}
