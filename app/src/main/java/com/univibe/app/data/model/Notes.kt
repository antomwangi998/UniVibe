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
