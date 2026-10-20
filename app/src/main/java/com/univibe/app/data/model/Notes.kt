package com.univibe.app.data.model

/** Who may see a Note. The server enforces this on every read; the app only offers the choice. */
enum class NoteAudience(val wire: String, val label: String) {
    Campus("campus", "Campus"),
    Followers("followers", "Followers");

    companion object {
        fun fromWire(value: String?): NoteAudience = entries.firstOrNull { it.wire == value } ?: Campus
    }
}

/** A short, expiring text update. Times are epoch milliseconds as the server reported them. */
data class Note(
    val id: String,
    val authorId: String,
    val authorName: String,
    val authorHandle: String,
    val avatarUrl: String?,
    val text: String,
    val audience: NoteAudience,
    /** Grows with every publish or edit, so an older answer can never replace a newer one. */
    val version: Long,
    val createdAtMs: Long,
    val expiresAtMs: Long,
    /** The author is connected right now. False also when they keep their activity private. */
    val online: Boolean = false,
    /** When the author was last active (epoch ms); null when private or unknown. */
    val lastActiveMs: Long? = null,
) {
    fun isActiveAt(nowMs: Long): Boolean = expiresAtMs > nowMs
}

/**
 * What `GET /api/v1/notes` answered: my own Note, the Notes I may see, the server's limits, and how far the phone's clock
 * is from the server's. Expiry is always judged on the server's clock ([clockOffsetMs] added to the phone's).
 */
data class NotesSnapshot(
    val mine: Note?,
    val items: List<Note>,
    val maxChars: Int,
    val ttlHours: Int,
    val clockOffsetMs: Long,
)

/** Number of characters as a person counts them: an emoji is one, not two. */
fun noteLength(text: String): Int = text.codePointCount(0, text.length)

/** What the app sends: surrounding whitespace removed, nothing else changed (the app never shortens a Note for the person). */
fun cleanNoteDraft(text: String): String = text.trim()

sealed interface NoteDraftCheck {
    data object Ok : NoteDraftCheck
    data object Empty : NoteDraftCheck
    data class TooLong(val length: Int, val max: Int) : NoteDraftCheck
}

fun checkNoteDraft(text: String, maxChars: Int): NoteDraftCheck {
    val clean = cleanNoteDraft(text)
    if (clean.isEmpty()) return NoteDraftCheck.Empty
    val length = noteLength(clean)
    return if (length > maxChars) NoteDraftCheck.TooLong(length, maxChars) else NoteDraftCheck.Ok
}

/**
 * "Active now", "Active 5m ago", "Active 3h ago", "Active yesterday", "Active 4d ago". Null when there is nothing honest to
 * say (private, never seen, or more than a month ago): the app then shows nothing rather than "Offline".
 */
fun activeLabel(online: Boolean, lastActiveMs: Long?, nowMs: Long): String? {
    if (online) return "Active now"
    val last = lastActiveMs ?: return null
    val minutes = ((nowMs - last) / 60_000).coerceAtLeast(0)
    return when {
        minutes < 1 -> "Active now"
        minutes < 60 -> "Active ${minutes}m ago"
        minutes < 24 * 60 -> "Active ${minutes / 60}h ago"
        minutes < 48 * 60 -> "Active yesterday"
        minutes < 30 * 24 * 60 -> "Active ${minutes / (24 * 60)}d ago"
        else -> null
    }
}
