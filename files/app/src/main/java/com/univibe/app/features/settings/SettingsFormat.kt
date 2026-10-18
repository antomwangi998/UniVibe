package com.univibe.app.features.settings

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A server timestamp (ISO-8601, UTC) shown in the device's time zone. The stored value is never changed. Empty when unreadable. */
fun formatServerTime(iso: String, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
    runCatching { DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a", locale).format(Instant.parse(iso).atZone(zone)) }.getOrDefault("")

fun campusVerificationLabel(status: String): String = when (status) {
    "verified" -> "Verified"
    "pending" -> "Pending review"
    "rejected" -> "Not approved"
    "expired" -> "Expired"
    else -> "Not verified"
}

/** What a moderator did, in words the person can read. Unknown actions fall back to a neutral phrase rather than guessing. */
fun noticeActionLabel(action: String): String = when (action) {
    "suspend_user" -> "Account suspended"
    "unsuspend_user" -> "Suspension lifted"
    "remove_content" -> "Content removed"
    "restore_content" -> "Content restored"
    else -> "Moderation update"
}
