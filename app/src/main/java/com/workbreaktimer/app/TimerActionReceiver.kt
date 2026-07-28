package com.workbreaktimer.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Handles the quick actions (Пауза/Продолжить/Сброс) on the live countdown notification. */
class TimerActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_PAUSE = "com.workbreaktimer.app.action.NOTIF_PAUSE"
        const val ACTION_RESUME = "com.workbreaktimer.app.action.NOTIF_RESUME"
        const val ACTION_RESET = "com.workbreaktimer.app.action.NOTIF_RESET"
    }

    override fun onReceive(context: Context, intent: Intent) {
        TimerManager.init(context)
        when (intent.action) {
            ACTION_PAUSE -> TimerManager.pause(context)
            ACTION_RESUME -> TimerManager.resume(context)
            ACTION_RESET -> TimerManager.reset(context)
        }
    }
}
