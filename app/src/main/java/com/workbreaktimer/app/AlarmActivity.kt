package com.workbreaktimer.app

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Full-screen alarm UI shown over the lock screen when a phase completes. */
class AlarmActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TimerManager.init(this)
        setupWakeScreen()

        setContent {
            val state by TimerManager.state.collectAsState()
            val finishedPhase = state.phase
            // Captured once: the service clears these as it stops, and the screen should keep
            // showing what it was opened for while the user is reading it.
            val ringing = remember { AlarmRingService.ringingReminder.value }
            val reminderId = ringing?.id
            val reminderTitle = ringing?.title
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        if (reminderId != null) {
                            var showDisableConfirm by remember { mutableStateOf(false) }

                            Text(
                                reminderTitle ?: "Напоминание",
                                fontSize = 28.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )
                            Spacer(Modifier.height(48.dp))
                            Button(onClick = {
                                ReminderRingActions.done(this@AlarmActivity, reminderId)
                                finish()
                            }) {
                                Text("Выполнено", fontSize = 20.sp)
                            }
                            Spacer(Modifier.height(16.dp))
                            OutlinedButton(onClick = {
                                ReminderRingActions.snooze(this@AlarmActivity, reminderId)
                                finish()
                            }) {
                                Text(
                                    "Отложить (${formatDurationShort(state.settings.snoozeMillis)})",
                                    fontSize = 18.sp
                                )
                            }
                            Spacer(Modifier.height(16.dp))
                            TextButton(onClick = { showDisableConfirm = true }) {
                                Text("Отключить")
                            }

                            if (showDisableConfirm) {
                                DisableReminderConfirmDialog(
                                    onConfirm = {
                                        ReminderRingActions.disable(this@AlarmActivity, reminderId)
                                        finish()
                                    },
                                    onDismiss = { showDisableConfirm = false }
                                )
                            }
                            return@Column
                        }

                        val title = if (finishedPhase == TimerPhase.WORK) "Время работы вышло!" else "Перерыв окончен!"
                        Text(title, fontSize = 28.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(48.dp))
                        val buttonLabel = if (finishedPhase == TimerPhase.WORK) {
                            "Начать перерыв (${formatDurationShort(state.settings.breakMillis)})"
                        } else {
                            "Начать работу (${formatDurationShort(state.settings.workMillis)})"
                        }
                        Button(onClick = {
                            TimerManager.advancePhaseAndStart(this@AlarmActivity)
                            finish()
                        }) {
                            Text(buttonLabel, fontSize = 20.sp)
                        }
                        Spacer(Modifier.height(16.dp))
                        val snoozeLabel = if (finishedPhase == TimerPhase.WORK) {
                            "Ещё поработать (${formatDurationShort(state.settings.snoozeMillis)})"
                        } else {
                            "Ещё отдохнуть (${formatDurationShort(state.settings.snoozeMillis)})"
                        }
                        OutlinedButton(onClick = {
                            TimerManager.snooze(this@AlarmActivity)
                            finish()
                        }) {
                            Text(snoozeLabel, fontSize = 18.sp)
                        }
                        Spacer(Modifier.height(16.dp))
                        TextButton(onClick = {
                            TimerManager.stopRingingOnly(this@AlarmActivity)
                            finish()
                        }) {
                            Text("Просто остановить сигнал")
                        }
                    }
                }
            }
        }
    }

    /**
     * Shows this activity directly on top of the lock screen, without dismissing it —
     * requestDismissKeyguard()/FLAG_DISMISS_KEYGUARD would instead prompt the user to
     * enter their PIN/pattern, which defeats "control the alarm without unlocking".
     */
    private fun setupWakeScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}
