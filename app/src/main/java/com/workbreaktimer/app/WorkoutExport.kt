package com.workbreaktimer.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Turns a range of days from the workout history into text for another app, such as a chat
 * with Claude that keeps a progress spreadsheet. Only reads [WorkoutData]; the history file is
 * never touched.
 *
 * The text is a Markdown table with one row per set, so it pastes into a chat as a table and
 * maps onto spreadsheet rows without retyping.
 */
object WorkoutExport {

    private val RU = Locale("ru")
    private val DAY_TITLE = DateTimeFormatter.ofPattern("d MMMM yyyy, EEEE", RU)
    private val ISO_DAY = DateTimeFormatter.ISO_LOCAL_DATE
    private val TIME = DateTimeFormatter.ofPattern("HH:mm", RU)

    fun localDate(millis: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

    /** Days with at least one saved workout, in the phone's time zone. */
    fun daysWithWorkouts(sessions: List<WorkoutSession>): Set<LocalDate> =
        sessions.mapTo(HashSet()) { localDate(it.startedAtMillis) }

    /** The material date pickers work in UTC midnights, whatever the phone's time zone. */
    fun fromPickerMillis(utcMillis: Long): LocalDate = localDate(utcMillis, ZoneOffset.UTC)

    fun toPickerMillis(date: LocalDate): Long = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    /**
     * Workouts from [from] to [to] inclusive, earliest first. History is stored newest first
     * with a superset's exercises in their own order under one start time; a stable sort by
     * start time keeps that order.
     */
    fun sessionsBetween(sessions: List<WorkoutSession>, from: LocalDate, to: LocalDate): List<WorkoutSession> =
        sessions.filter { localDate(it.startedAtMillis) in from..to }.sortedBy { it.startedAtMillis }

    fun title(from: LocalDate, to: LocalDate): String =
        if (from == to) {
            "Тренировки за ${DAY_TITLE.format(from)} (${ISO_DAY.format(from)})"
        } else {
            "Тренировки с ${ISO_DAY.format(from)} по ${ISO_DAY.format(to)}"
        }

    fun report(data: WorkoutData, from: LocalDate, to: LocalDate): String {
        val sessions = sessionsBetween(data.sessions, from, to)
        val zone = ZoneId.systemDefault()
        return buildString {
            appendLine(title(from, to))
            appendLine()
            if (sessions.isEmpty()) {
                appendLine("В эти дни тренировок нет.")
                return@buildString
            }
            appendLine("| Дата | Время | Папка | Упражнение | Суперсет | Подход | Повторы | План | Вес, кг |")
            appendLine("|---|---|---|---|---|---|---|---|---|")
            for (session in sessions) {
                val started = Instant.ofEpochMilli(session.startedAtMillis).atZone(zone)
                val date = ISO_DAY.format(started)
                val time = TIME.format(started)
                val folder = session.folderId?.let { data.folder(it)?.name }.orEmpty()
                val superset = session.supersetName.orEmpty()
                for (set in session.repsPerSet.indices) {
                    val planned = session.plannedRepsPerSet.getOrNull(set)?.toString().orEmpty()
                    val cells = listOf(
                        date, time, folder, session.name, superset, (set + 1).toString(),
                        session.repsPerSet[set].toString(), planned, formatNumber(session.weightOfSet(set))
                    )
                    appendLine(cells.joinToString(" | ", prefix = "| ", postfix = " |") { cell(it) })
                }
            }
            appendLine()
            appendLine("Итого по дням:")
            for ((day, daySessions) in sessions.groupBy { localDate(it.startedAtMillis) }) {
                appendLine()
                appendLine(DAY_TITLE.format(day))
                for (session in daySessions) {
                    val parts = buildList {
                        add("подходов ${session.repsPerSet.size}")
                        add("повторов ${session.totalReps}")
                        if (session.volumeKg > 0) add("объём ${formatWeight(session.volumeKg)}")
                        add("время ${formatClock(session.durationMillis)}")
                        add(if (session.completed) "план выполнен" else "остановлено, план ${session.plannedLabel}")
                    }
                    appendLine("- ${session.name}: ${parts.joinToString(", ")}")
                }
            }
            appendLine()
            append("Вес 0 означает собственный вес.")
        }
    }

    /** A pipe or a line break inside a name would split the Markdown table. */
    private fun cell(text: String): String = text.replace('|', '/').replace('\n', ' ').trim()

    /** Opens the share sheet; Claude, a messenger or the clipboard can take the text from there. */
    fun share(context: Context, subject: String, text: String): Boolean {
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, subject)
            .putExtra(Intent.EXTRA_TEXT, text)
        return try {
            context.startActivity(Intent.createChooser(send, "Экспорт тренировок"))
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }
}
