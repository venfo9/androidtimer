package com.workbreaktimer.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** Fired by AlarmManager exactly when the current phase's time runs out. */
class TimerAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        TimerManager.init(context)
        TimerManager.onAlarmFired(context)
        ContextCompat.startForegroundService(context, Intent(context, AlarmRingService::class.java))
    }
}
