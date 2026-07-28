package com.workbreaktimer.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** Wakes StepTrackingService when the idle countdown runs out, even from Doze. */
class StepIdleDeadlineReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        TimerManager.init(context)
        if (!TimerManager.state.value.shouldTrackSteps) return
        val service = Intent(context, StepTrackingService::class.java)
            .setAction(StepTrackingService.ACTION_IDLE_DEADLINE)
        try {
            ContextCompat.startForegroundService(context, service)
        } catch (e: Exception) {
            // Foreground start refused; tracking resumes on the next state change.
        }
    }
}
