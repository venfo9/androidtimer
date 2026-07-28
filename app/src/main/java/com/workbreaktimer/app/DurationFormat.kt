package com.workbreaktimer.app

/** Countdown display: always mm:ss so the digits do not jump around as time runs out. */
fun formatClock(millis: Long): String {
    val totalSeconds = millis.coerceAtLeast(0) / 1000
    return "%02d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

/** Prose duration for button labels, where "25 мин" reads better than "25:00". */
fun formatDurationShort(millis: Long): String {
    val totalSeconds = millis.coerceAtLeast(0) / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return when {
        minutes == 0L -> "$seconds сек"
        seconds == 0L -> "$minutes мин"
        else -> "$minutes мин $seconds сек"
    }
}

/** Accumulated time in history, where hours matter and seconds do not. */
fun formatDurationLong(millis: Long): String {
    val totalMinutes = millis.coerceAtLeast(0) / 60_000
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) "$hours ч $minutes мин" else "$minutes мин"
}
