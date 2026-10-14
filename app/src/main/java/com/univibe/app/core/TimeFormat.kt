package com.univibe.app.core

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

fun formatClock(millis: Long): String =
    DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()).format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

fun isSameDay(a: Long, b: Long): Boolean {
    val zone = ZoneId.systemDefault()
    return Instant.ofEpochMilli(a).atZone(zone).toLocalDate() == Instant.ofEpochMilli(b).atZone(zone).toLocalDate()
}

/** "Today", "Yesterday", or e.g. "Mon, 12 Oct". */
fun formatDayLabel(millis: Long, now: Long = System.currentTimeMillis()): String {
    val zone = ZoneId.systemDefault()
    val day = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return when (day) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> DateTimeFormatter.ofPattern("EEE, d MMM", Locale.getDefault()).format(day)
    }
}

/** Month ("MAY"), day ("18") and full label ("Fri, 18 May · 7:00 PM") for an event start, in the device time zone. */
fun eventDateParts(millis: Long): Triple<String, String, String> {
    val dt = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
    val locale = Locale.getDefault()
    return Triple(
        DateTimeFormatter.ofPattern("MMM", locale).format(dt).uppercase(locale),
        DateTimeFormatter.ofPattern("dd", locale).format(dt),
        DateTimeFormatter.ofPattern("EEE, d MMM · h:mm a", locale).format(dt),
    )
}

/** "Fri, 18 May, 7:00 PM" */
fun formatDateTime(millis: Long): String =
    DateTimeFormatter.ofPattern("EEE, d MMM, h:mm a", Locale.getDefault()).format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

/** "Fri, 18 May" */
fun formatDate(millis: Long): String =
    DateTimeFormatter.ofPattern("EEE, d MMM", Locale.getDefault()).format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
