package com.workbreaktimer.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * Wakes StepTrackingService when either the idle countdown or the confirmation window runs
 * out, even from Doze. Which of the two it is travels on the intent action.
 */
class StepIdleDeadlineReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        TimerManager.init(context)
        if (!TimerManager.state.value.shouldTrackSteps) return
        val action = intent.action ?: StepTrackingService.ACTION_IDLE_DEADLINE
        val service = Intent(context, StepTrackingService::class.java).setAction(action)
        try {
            ContextCompat.startForegroundService(context, service)
        } catch (e: Exception) {
            // Foreground start refused; tracking resumes on the next state change.
        }
    }
}
