package com.workbreaktimer.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar
import java.util.UUID

/** One line of the airplane-mode schedule: switch on or off at a time, on chosen weekdays. */
data class AirplaneRule(
    val id: String,
    val turnOn: Boolean,
    val hour: Int,
    val minute: Int,
    /** Bit (Calendar.DAY_OF_WEEK - 1) is set for every day the rule runs. */
    val daysMask: Int,
    val enabled: Boolean = true,
    val requestCode: Int
) {
    fun runsOn(calendarDay: Int): Boolean = daysMask and dayBit(calendarDay) != 0

    /** First moment strictly after [afterMillis] that matches the time and the days. */
    fun nextTrigger(afterMillis: Long = System.currentTimeMillis()): Long? {
        if (daysMask == 0) return null
        for (offset in 0..7) {
            val calendar = Calendar.getInstance()
            calendar.timeInMillis = afterMillis
            calendar.add(Calendar.DAY_OF_YEAR, offset)
            calendar.set(Calendar.HOUR_OF_DAY, hour)
            calendar.set(Calendar.MINUTE, minute)
            calendar.set(Calendar.SECOND, 0)
            calendar.set(Calendar.MILLISECOND, 0)
            if (calendar.timeInMillis > afterMillis && runsOn(calendar.get(Calendar.DAY_OF_WEEK))) {
                return calendar.timeInMillis
            }
        }
        return null
    }

    companion object {
        /** Monday first, the way the week reads in Russian. */
        val WEEK = listOf(
            Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY,
            Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY
        )
        val ALL_DAYS: Int = WEEK.fold(0) { mask, day -> mask or dayBit(day) }
        private val WORKDAYS: Int = WEEK.take(5).fold(0) { mask, day -> mask or dayBit(day) }
        private val WEEKEND: Int = dayBit(Calendar.SATURDAY) or dayBit(Calendar.SUNDAY)

        fun dayBit(calendarDay: Int): Int = 1 shl (calendarDay - 1)

        fun dayShortName(calendarDay: Int): String = when (calendarDay) {
            Calendar.MONDAY -> "Пн"
            Calendar.TUESDAY -> "Вт"
            Calendar.WEDNESDAY -> "Ср"
            Calendar.THURSDAY -> "Чт"
            Calendar.FRIDAY -> "Пт"
            Calendar.SATURDAY -> "Сб"
            else -> "Вс"
        }

        fun daysLabel(mask: Int): String = when (mask) {
            ALL_DAYS -> "каждый день"
            WORKDAYS -> "будни"
            WEEKEND -> "выходные"
            else -> WEEK.filter { mask and dayBit(it) != 0 }.joinToString(" ") { dayShortName(it) }
        }
    }
}

/** The outcome of the last scheduled switch, shown on the screen as proof the feature works. */
data class AirplaneRun(
    val atMillis: Long,
    val turnOn: Boolean,
    val ok: Boolean,
    val message: String
)

data class AirplaneState(
    val rules: List<AirplaneRule> = emptyList(),
    val lastRun: AirplaneRun? = null
) {
    fun rule(id: String): AirplaneRule? = rules.firstOrNull { it.id == id }
}

/**
 * Owns the airplane-mode rules and keeps two alarms per enabled rule: the switch itself, and a
 * check fifteen minutes earlier that warns when Shizuku is not ready, while there is still
 * time to start it.
 */
object AirplaneScheduleManager {

    private const val FILE_NAME = "airplane_rules.json"
    private const val BASE_REQUEST_CODE = 30_000
    private const val PRECHECK_LEAD_MILLIS = 15 * 60_000L

    private val _state = MutableStateFlow(AirplaneState())
    val state: StateFlow<AirplaneState> = _state.asStateFlow()

    private var file: File? = null
    private var initialized = false

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        val target = File(context.applicationContext.filesDir, FILE_NAME)
        file = target
        _state.value = load(target)
    }

    fun saveRule(
        context: Context,
        existingId: String?,
        turnOn: Boolean,
        hour: Int,
        minute: Int,
        daysMask: Int
    ) {
        init(context)
        val current = _state.value
        val existing = existingId?.let { current.rule(it) }
        val rule = existing?.copy(turnOn = turnOn, hour = hour, minute = minute, daysMask = daysMask)
            ?: AirplaneRule(
                id = UUID.randomUUID().toString(),
                turnOn = turnOn,
                hour = hour,
                minute = minute,
                daysMask = daysMask,
                requestCode = (current.rules.maxOfOrNull { it.requestCode } ?: BASE_REQUEST_CODE) + 1
            )
        val rules = if (existing == null) {
            current.rules + rule
        } else {
            current.rules.map { if (it.id == rule.id) rule else it }
        }
        commit(current.copy(rules = rules))
        rearm(context, rule)
    }

    fun setEnabled(context: Context, ruleId: String, enabled: Boolean) {
        init(context)
        val current = _state.value
        val rule = current.rule(ruleId)?.copy(enabled = enabled) ?: return
        commit(current.copy(rules = current.rules.map { if (it.id == rule.id) rule else it }))
        rearm(context, rule)
    }

    fun deleteRule(context: Context, ruleId: String) {
        init(context)
        val current = _state.value
        val rule = current.rule(ruleId) ?: return
        cancelAlarms(context, rule)
        commit(current.copy(rules = current.rules.filterNot { it.id == ruleId }))
    }

    fun recordRun(context: Context, run: AirplaneRun) {
        init(context)
        commit(_state.value.copy(lastRun = run))
    }

    /** A reboot or an app update clears AlarmManager, so every rule is armed again. */
    fun rescheduleAll(context: Context) {
        init(context)
        _state.value.rules.forEach { rearm(context, it) }
    }

    /** Arms the next occurrence; called after edits and after each rule fires. */
    fun rearm(context: Context, rule: AirplaneRule) {
        cancelAlarms(context, rule)
        if (!rule.enabled) return
        val trigger = rule.nextTrigger() ?: return
        val am = context.getSystemService(AlarmManager::class.java)
        setExact(am, trigger, pendingIntent(context, rule, AirplaneScheduleReceiver.ACTION_FIRE))
        val precheckAt = trigger - PRECHECK_LEAD_MILLIS
        if (precheckAt > System.currentTimeMillis()) {
            setExact(am, precheckAt, pendingIntent(context, rule, AirplaneScheduleReceiver.ACTION_PRECHECK))
        }
    }

    private fun setExact(am: AlarmManager, at: Long, intent: PendingIntent) {
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
        } catch (e: SecurityException) {
            // Exact alarms revoked: a few minutes late still beats not switching at all.
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
        }
    }

    private fun cancelAlarms(context: Context, rule: AirplaneRule) {
        val am = context.getSystemService(AlarmManager::class.java)
        am.cancel(pendingIntent(context, rule, AirplaneScheduleReceiver.ACTION_FIRE))
        am.cancel(pendingIntent(context, rule, AirplaneScheduleReceiver.ACTION_PRECHECK))
    }

    private fun pendingIntent(context: Context, rule: AirplaneRule, action: String): PendingIntent =
        PendingIntent.getBroadcast(
            context, rule.requestCode,
            Intent(context, AirplaneScheduleReceiver::class.java)
                .setAction(action)
                .putExtra(AirplaneScheduleReceiver.EXTRA_RULE_ID, rule.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    // region storage

    @Synchronized
    private fun commit(newState: AirplaneState) {
        _state.value = newState
        val target = file ?: return
        try {
            val rules = JSONArray()
            for (rule in newState.rules) {
                rules.put(
                    JSONObject()
                        .put("id", rule.id)
                        .put("on", rule.turnOn)
                        .put("hour", rule.hour)
                        .put("minute", rule.minute)
                        .put("days", rule.daysMask)
                        .put("enabled", rule.enabled)
                        .put("request_code", rule.requestCode)
                )
            }
            val root = JSONObject().put("rules", rules)
            newState.lastRun?.let { run ->
                root.put(
                    "last_run",
                    JSONObject()
                        .put("at", run.atMillis)
                        .put("on", run.turnOn)
                        .put("ok", run.ok)
                        .put("message", run.message)
                )
            }
            target.writeText(root.toString())
        } catch (e: Exception) {
            // Unwritable storage; the in-memory state still serves this session.
        }
    }

    private fun load(target: File): AirplaneState {
        if (!target.exists()) return AirplaneState()
        return try {
            val root = JSONObject(target.readText())
            val array = root.optJSONArray("rules") ?: JSONArray()
            val rules = (0 until array.length()).mapNotNull { i ->
                val item = array.optJSONObject(i) ?: return@mapNotNull null
                AirplaneRule(
                    id = item.optString("id"),
                    turnOn = item.optBoolean("on", true),
                    hour = item.optInt("hour"),
                    minute = item.optInt("minute"),
                    daysMask = item.optInt("days", AirplaneRule.ALL_DAYS),
                    enabled = item.optBoolean("enabled", true),
                    requestCode = item.optInt("request_code", BASE_REQUEST_CODE + i + 1)
                )
            }
            val lastRun = root.optJSONObject("last_run")?.let {
                AirplaneRun(
                    atMillis = it.optLong("at"),
                    turnOn = it.optBoolean("on"),
                    ok = it.optBoolean("ok"),
                    message = it.optString("message")
                )
            }
            AirplaneState(rules, lastRun)
        } catch (e: Exception) {
            // A corrupt file must not take the app down.
            AirplaneState()
        }
    }

    // endregion
}
