package com.workbreaktimer.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * A reboot clears the whole AlarmManager table, so every reminder has to be armed again or a
 * scheduler silently stops working after the first restart.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON" -> {
                ReminderManager.init(context)
                ReminderManager.rescheduleAll(context)
            }
        }
    }
}
