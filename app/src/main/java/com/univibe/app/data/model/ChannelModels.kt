package com.univibe.app.data.model

/** The five kinds of discussion channel the server supports. Each one has its own rules there; the app only mirrors the shape. */
enum class ChannelType(val key: String, val label: String) {
    Text("text", "Chat"),
    Announcement("announcement", "Announcements"),
    Qa("qa", "Questions & answers"),
    Resources("resources", "Resources"),
    Media("media", "Media");

    /** Top-level posts need a title (announcements and questions). */
    val needsTitle: Boolean get() = this == Announcement || this == Qa
    /** The timeline lists top-level items only; answers and comments live in the thread. */
    val threadedTimeline: Boolean get() = this == Announcement || this == Qa

    companion object {
        fun parse(key: String?) = entries.firstOrNull { it.key == key } ?: Text
    }
}

/** What the signed-in student may do in a channel. Computed by the server; the app never decides. */
data class ChannelAccess(
    val send: Boolean = false,
    val reply: Boolean = false,
    val react: Boolean = false,
    val attach: Boolean = false,
    val pin: Boolean = false,
    val manage: Boolean = false,
    /** Why writing is unavailable (not_a_member, suspended, community_inactive, channel_archived, channel_locked, staff_only), or null. */
    val readOnlyReason: String? = null,
) {
    val canWrite: Boolean get() = send || reply

    fun readOnlyText(): String = when (readOnlyReason) {
        "channel_locked" -> "This channel is locked."
        "channel_archived" -> "This channel is archived."
        "staff_only" -> "Only moderators can post in this channel."
        "suspended" -> "Your membership in this community is restricted."
        "community_inactive" -> "This community is read-only right now."
        "not_a_member" -> "Join this community to take part."
        else -> "You can't write in this channel."
    }
}

data class Channel(
    val id: String,
    val groupId: String?,
    val name: String,
    val description: String,
    val type: ChannelType,
    val locked: Boolean,
    val archived: Boolean,
    val unread: Int,
    val pinnedCount: Int,
    val access: ChannelAccess,
)

data class ChannelGroup(val id: String, val name: String)

data class ChannelDirectory(
    val groups: List<ChannelGroup>,
    val channels: List<Channel>,
    val canManage: Boolean,
    val canDelete: Boolean,
) {
    /** Channels in display order: ungrouped first, then each group in its order. */
    val sections: List<Pair<ChannelGroup?, List<Channel>>>
        get() {
            val loose = channels.filter { c -> c.groupId == null || groups.none { it.id == c.groupId } }
            return buildList {
                if (loose.isNotEmpty()) add(null to loose)
                groups.forEach { g -> channels.filter { it.groupId == g.id }.takeIf { it.isNotEmpty() }?.let { add(g to it) } }
            }
        }
}

data class ChannelAuthor(val id: String, val name: String, val handle: String, val avatarUrl: String?, val accent: Int, val verified: Boolean)

data class ChannelAttachment(val id: String, val kind: String, val url: String?, val fileName: String?, val sizeBytes: Long?) {
    val isImage: Boolean get() = kind == "image"
}

data class ReplyPreview(val id: String, val preview: String?, val deleted: Boolean)

data class ChannelMessage(
    val id: String,
    val channelId: String,
    /** Per-channel message number: the order messages happened in, and the cursor for catching up after a reconnect. */
    val seq: Long,
    val author: ChannelAuthor?,
    /** "owner", "moderator", "member" or null (the author left). */
    val authorRole: String?,
    val title: String?,
    val content: String?,
    val deleted: Boolean,
    val removedByModerators: Boolean,
    val createdAt: String,
    val edited: Boolean,
    val pinned: Boolean,
    val replyCount: Int,
    val replyTo: ReplyPreview?,
    val acceptedAnswerId: String?,
    val isAcceptedAnswer: Boolean,
    val attachments: List<ChannelAttachment>,
    val reactions: Map<String, Int>,
    val myReactions: Set<String>,
) {
    val resolved: Boolean get() = acceptedAnswerId != null
    val isStaffAuthor: Boolean get() = authorRole == "owner" || authorRole == "moderator"
}

data class ChannelDraft(
    val title: String?,
    val content: String,
    val replyToId: String?,
    val photoPaths: List<String>,
    /** Stable for retries: the server returns the stored message instead of creating a second one. */
    val clientId: String,
)

data class MessagePage(val items: List<ChannelMessage>, val nextCursor: String?, val latestSeq: Long = 0L)

/** The reactions a message can carry, in display order, with the emoji shown for each. */
val CHANNEL_REACTIONS: List<Pair<String, String>> = listOf(
    "heart" to "\u2764\uFE0F", "like" to "\uD83D\uDC4D", "laugh" to "\uD83D\uDE02", "fire" to "\uD83D\uDD25", "sad" to "\uD83D\uDE22",
)

// ------------------------------------------------------------------ pure list logic (unit-tested)

/**
 * Puts [incoming] into a message list kept oldest-first. The server message id is the identity, so the same message
 * arriving twice (over the socket and again in a fetch) is one row. [keepMine] carries over this student's own
 * reactions, because socket payloads are viewer-neutral.
 */
fun List<ChannelMessage>.upserted(incoming: ChannelMessage, keepMine: Boolean = false): List<ChannelMessage> {
    val old = firstOrNull { it.id == incoming.id }
    val merged = if (keepMine && old != null) incoming.copy(myReactions = old.myReactions) else incoming
    val without = if (old == null) this else filter { it.id != incoming.id }
    return (without + merged).sortedWith(compareBy<ChannelMessage> { it.seq }.thenBy { it.id })
}

fun List<ChannelMessage>.upsertedAll(items: List<ChannelMessage>, keepMine: Boolean = false): List<ChannelMessage> =
    items.fold(this) { acc, m -> acc.upserted(m, keepMine) }

/** What a deleted message looks like: the row stays so replies keep pointing at it, but its words and files are gone. */
fun List<ChannelMessage>.tombstoned(messageId: String): List<ChannelMessage> = map {
    if (it.id != messageId) it else it.copy(
        deleted = true, title = null, content = null, pinned = false, attachments = emptyList(), reactions = emptyMap(), myReactions = emptySet(),
        acceptedAnswerId = null, isAcceptedAnswer = false,
    )
}

/** Applies a reaction event. Counts come from the server; [myId] decides whether it was this student's own reaction. */
fun List<ChannelMessage>.withReaction(messageId: String, userId: String, reaction: String, active: Boolean, counts: Map<String, Int>, myId: String?): List<ChannelMessage> = map {
    if (it.id != messageId || it.deleted) it else it.copy(
        reactions = counts,
        myReactions = if (userId == myId) (if (active) it.myReactions + reaction else it.myReactions - reaction) else it.myReactions,
    )
}

/** The highest message number held, for "everything after this" catch-up. */
fun List<ChannelMessage>.latestSeq(): Long = maxOfOrNull { it.seq } ?: 0L
