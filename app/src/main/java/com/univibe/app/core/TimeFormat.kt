package com.univibe.app.core

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

fun formatClock(millis: Long): String =
    DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()).format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
