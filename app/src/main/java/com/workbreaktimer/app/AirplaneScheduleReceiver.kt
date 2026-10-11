package com.workbreaktimer.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlin.concurrent.thread

/** Fires for an airplane rule: the switch itself, or the warning check before it. */
class AirplaneScheduleReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_FIRE = "com.workbreaktimer.app.action.AIRPLANE_FIRE"
        const val ACTION_PRECHECK = "com.workbreaktimer.app.action.AIRPLANE_PRECHECK"
        const val EXTRA_RULE_ID = "rule_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val ruleId = intent.getStringExtra(EXTRA_RULE_ID) ?: return
        val appContext = context.applicationContext
        AirplaneScheduleManager.init(appContext)
        val rule = AirplaneScheduleManager.state.value.rule(ruleId) ?: return

        if (intent.action == ACTION_FIRE) {
            // Arm the next occurrence first, so a failure below never stops the schedule.
            AirplaneScheduleManager.rearm(appContext, rule)
        }
        if (!rule.enabled) return

        // Talking to Shizuku takes a few seconds; goAsync keeps the broadcast alive meanwhile.
        val pending = goAsync()
        thread(name = "airplane-schedule") {
            try {
                when (intent.action) {
                    ACTION_FIRE -> AirplaneSwitcher.runScheduled(appContext, rule.turnOn)
                    ACTION_PRECHECK -> AirplaneSwitcher.precheck(appContext, rule)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
