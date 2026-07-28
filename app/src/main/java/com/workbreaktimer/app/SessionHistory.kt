package com.workbreaktimer.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** One finished work or break session, appended when the phase leaves RUNNING. */
data class SessionRecord(
    val phase: TimerPhase,
    val startedAtMillis: Long,
    val endedAtMillis: Long,
    val plannedMillis: Long,
    /** True when the phase ran to its alarm; false when it was reset or replaced early. */
    val completed: Boolean,
    val autoStarted: Boolean,
    val snoozedMillis: Long
) {
    val actualMillis: Long get() = (endedAtMillis - startedAtMillis).coerceAtLeast(0)
}

data class HistoryStats(
    val todayWorkMillis: Long = 0,
    val todayWorkSessions: Int = 0,
    val weekWorkMillis: Long = 0,
    val weekWorkSessions: Int = 0,
    val todayBreakMillis: Long = 0,
    val streakDays: Int = 0
)

/** One column of the history bar chart: totals for a single hour, day, week or month. */
data class ChartBucket(
    val label: String,
    val workMillis: Long,
    val breakMillis: Long
)

/**
 * Storage for finished sessions. Behind an interface so the file implementation can be
 * swapped for a database without the screens noticing.
 */
interface SessionHistoryRepository {
    fun append(record: SessionRecord)
    fun recent(limit: Int): List<SessionRecord>
    fun stats(nowMillis: Long = System.currentTimeMillis()): HistoryStats
    fun clear()

    /** 24 buckets, one per hour, for the calendar day containing [dayMillis]. */
    fun hourlyBuckets(dayMillis: Long): List<ChartBucket>

    /** One bucket per calendar day, the oldest first, ending on the day containing [endMillis]. */
    fun dailyBuckets(days: Int, endMillis: Long = System.currentTimeMillis()): List<ChartBucket>

    /** One bucket per calendar week, the oldest first, ending on the current week. */
    fun weeklyBuckets(weeks: Int): List<ChartBucket>

    /** One bucket per calendar month, the oldest first, ending on the current month. */
    fun monthlyBuckets(months: Int): List<ChartBucket>
}

/**
 * JSON-file backed history. The volume here is a few thousand rows a year, which reads and
 * writes whole comfortably; a database would buy queries this app never makes.
 */
class FileSessionHistoryRepository(context: Context) : SessionHistoryRepository {

    private val file = File(context.applicationContext.filesDir, FILE_NAME)
    private val lock = Any()

    override fun append(record: SessionRecord) = synchronized(lock) {
        val records = readAll().toMutableList()
        records.add(record)
        // Bound the file; the oldest rows stop affecting any statistic long before this.
        while (records.size > MAX_RECORDS) records.removeAt(0)
        writeAll(records)
    }

    override fun recent(limit: Int): List<SessionRecord> = synchronized(lock) {
        readAll().takeLast(limit).reversed()
    }

    override fun clear() = synchronized(lock) {
        if (file.exists()) file.delete()
        Unit
    }

    override fun stats(nowMillis: Long): HistoryStats = synchronized(lock) {
        val records = readAll()
        val startOfToday = startOfDay(nowMillis)
        val startOfWeek = startOfToday - 6 * DAY_MILLIS

        var todayWork = 0L
        var todayWorkCount = 0
        var weekWork = 0L
        var weekWorkCount = 0
        var todayBreak = 0L

        for (record in records) {
            val isWork = record.phase == TimerPhase.WORK
            if (record.endedAtMillis >= startOfWeek && isWork) {
                weekWork += record.actualMillis
                weekWorkCount++
            }
            if (record.endedAtMillis >= startOfToday) {
                if (isWork) {
                    todayWork += record.actualMillis
                    todayWorkCount++
                } else {
                    todayBreak += record.actualMillis
                }
            }
        }

        HistoryStats(
            todayWorkMillis = todayWork,
            todayWorkSessions = todayWorkCount,
            weekWorkMillis = weekWork,
            weekWorkSessions = weekWorkCount,
            todayBreakMillis = todayBreak,
            streakDays = streakDays(records, startOfToday)
        )
    }

    /**
     * Consecutive days ending today that contain at least one work session. Today not having
     * one yet does not break the streak — it is still in progress — so counting starts at
     * yesterday in that case.
     */
    private fun streakDays(records: List<SessionRecord>, startOfToday: Long): Int {
        if (records.isEmpty()) return 0
        val workDays = HashSet<Long>()
        for (record in records) {
            if (record.phase == TimerPhase.WORK) workDays.add(startOfDay(record.endedAtMillis))
        }
        if (workDays.isEmpty()) return 0

        var streak = 0
        var day = if (workDays.contains(startOfToday)) startOfToday else startOfToday - DAY_MILLIS
        while (workDays.contains(day)) {
            streak++
            day -= DAY_MILLIS
        }
        return streak
    }

    override fun hourlyBuckets(dayMillis: Long): List<ChartBucket> = synchronized(lock) {
        val dayStart = startOfDay(dayMillis)
        val dayEnd = dayStart + DAY_MILLIS
        val work = LongArray(24)
        val brk = LongArray(24)
        val calendar = Calendar.getInstance()
        for (record in readAll()) {
            if (record.endedAtMillis < dayStart || record.endedAtMillis >= dayEnd) continue
            calendar.timeInMillis = record.endedAtMillis
            val hour = calendar.get(Calendar.HOUR_OF_DAY)
            if (record.phase == TimerPhase.WORK) work[hour] += record.actualMillis else brk[hour] += record.actualMillis
        }
        (0 until 24).map { h -> ChartBucket(h.toString(), work[h], brk[h]) }
    }

    override fun dailyBuckets(days: Int, endMillis: Long): List<ChartBucket> = synchronized(lock) {
        val records = readAll()
        val endDayStart = startOfDay(endMillis)
        val weekdayFormat = SimpleDateFormat("EEE", Locale("ru"))
        (days - 1 downTo 0).map { i ->
            val bucketStart = endDayStart - i * DAY_MILLIS
            val bucketEnd = bucketStart + DAY_MILLIS
            var work = 0L
            var brk = 0L
            for (record in records) {
                if (record.endedAtMillis < bucketStart || record.endedAtMillis >= bucketEnd) continue
                if (record.phase == TimerPhase.WORK) work += record.actualMillis else brk += record.actualMillis
            }
            val label = weekdayFormat.format(Date(bucketStart)).replaceFirstChar { it.uppercase() }
            ChartBucket(label, work, brk)
        }
    }

    override fun weeklyBuckets(weeks: Int): List<ChartBucket> = synchronized(lock) {
        val records = readAll()
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = startOfDay(System.currentTimeMillis())
        // Roll back to this week's first day, whatever the locale considers that to be.
        while (calendar.get(Calendar.DAY_OF_WEEK) != calendar.firstDayOfWeek) {
            calendar.add(Calendar.DAY_OF_YEAR, -1)
        }
        val currentWeekStart = calendar.timeInMillis
        val labelFormat = SimpleDateFormat("d.MM", Locale.getDefault())
        (weeks - 1 downTo 0).map { i ->
            val bucketStart = currentWeekStart - i * 7 * DAY_MILLIS
            val bucketEnd = bucketStart + 7 * DAY_MILLIS
            var work = 0L
            var brk = 0L
            for (record in records) {
                if (record.endedAtMillis < bucketStart || record.endedAtMillis >= bucketEnd) continue
                if (record.phase == TimerPhase.WORK) work += record.actualMillis else brk += record.actualMillis
            }
            ChartBucket(labelFormat.format(Date(bucketStart)), work, brk)
        }
    }

    override fun monthlyBuckets(months: Int): List<ChartBucket> = synchronized(lock) {
        val records = readAll()
        val labelFormat = SimpleDateFormat("LLL", Locale("ru"))
        val base = Calendar.getInstance()
        base.timeInMillis = startOfDay(System.currentTimeMillis())
        base.set(Calendar.DAY_OF_MONTH, 1)
        (months - 1 downTo 0).map { i ->
            val bucketStartCal = base.clone() as Calendar
            bucketStartCal.add(Calendar.MONTH, -i)
            val bucketStart = bucketStartCal.timeInMillis
            val bucketEndCal = bucketStartCal.clone() as Calendar
            bucketEndCal.add(Calendar.MONTH, 1)
            val bucketEnd = bucketEndCal.timeInMillis
            var work = 0L
            var brk = 0L
            for (record in records) {
                if (record.endedAtMillis < bucketStart || record.endedAtMillis >= bucketEnd) continue
                if (record.phase == TimerPhase.WORK) work += record.actualMillis else brk += record.actualMillis
            }
            val label = labelFormat.format(Date(bucketStart)).replaceFirstChar { it.uppercase() }
            ChartBucket(label, work, brk)
        }
    }

    private fun startOfDay(millis: Long): Long {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = millis
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    private fun readAll(): List<SessionRecord> {
        if (!file.exists()) return emptyList()
        return try {
            val array = JSONArray(file.readText())
            val result = ArrayList<SessionRecord>(array.length())
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                result.add(
                    SessionRecord(
                        phase = runCatching { TimerPhase.valueOf(item.getString(F_PHASE)) }
                            .getOrDefault(TimerPhase.WORK),
                        startedAtMillis = item.optLong(F_STARTED),
                        endedAtMillis = item.optLong(F_ENDED),
                        plannedMillis = item.optLong(F_PLANNED),
                        completed = item.optBoolean(F_COMPLETED),
                        autoStarted = item.optBoolean(F_AUTO),
                        snoozedMillis = item.optLong(F_SNOOZED)
                    )
                )
            }
            result
        } catch (e: Exception) {
            // A truncated or corrupt file must not take the app down; history is not critical.
            emptyList()
        }
    }

    private fun writeAll(records: List<SessionRecord>) {
        try {
            val array = JSONArray()
            for (record in records) {
                array.put(
                    JSONObject()
                        .put(F_PHASE, record.phase.name)
                        .put(F_STARTED, record.startedAtMillis)
                        .put(F_ENDED, record.endedAtMillis)
                        .put(F_PLANNED, record.plannedMillis)
                        .put(F_COMPLETED, record.completed)
                        .put(F_AUTO, record.autoStarted)
                        .put(F_SNOOZED, record.snoozedMillis)
                )
            }
            file.writeText(array.toString())
        } catch (e: Exception) {
            // Out of space or unwritable storage; losing a history row is acceptable.
        }
    }

    private companion object {
        const val FILE_NAME = "session_history.json"
        const val MAX_RECORDS = 2000
        const val DAY_MILLIS = 24 * 60 * 60 * 1000L
        const val F_PHASE = "phase"
        const val F_STARTED = "started"
        const val F_ENDED = "ended"
        const val F_PLANNED = "planned"
        const val F_COMPLETED = "completed"
        const val F_AUTO = "auto"
        const val F_SNOOZED = "snoozed"
    }
}
