package com.univibe.app.features.chats

import com.univibe.app.core.isSameDay
import com.univibe.app.data.model.Message
import kotlin.math.abs

/** Messages from the same person closer together than this belong to one visual group. Defined once, used everywhere. */
const val GROUP_WINDOW_MILLIS = 5 * 60_000L

/**
 * True when [a] and [b] (neighbours in the conversation, either order) read as one run from the same sender: same person,
 * close in time, same day. Messages still on their way never join a confirmed group's tail ambiguity because ids and
 * timestamps are the only inputs; a deleted message simply is not in the list, so the neighbours re-evaluate by themselves.
 */
fun joinsGroup(a: Message?, b: Message?): Boolean =
    a != null && b != null &&
        a.senderId == b.senderId &&
        abs(b.timeMillis - a.timeMillis) <= GROUP_WINDOW_MILLIS &&
        isSameDay(a.timeMillis, b.timeMillis)

/** Where a message sits inside its group; drives corner shapes, spacing, sender label and avatar. */
data class GroupPosition(val joinsOlder: Boolean, val joinsNewer: Boolean) {
    val isFirst get() = !joinsOlder
    val isLast get() = !joinsNewer
}

fun groupPosition(m: Message, older: Message?, newer: Message?) = GroupPosition(joinsGroup(older, m), joinsGroup(m, newer))

/**
 * How many messages from other people arrived after [previousNewestId], not counting any in [alreadyCounted].
 * Messages before it (older history that was paged in) never count, and neither do our own. Null when the previous
 * newest message is gone from the list (deleted), in which case nothing can be said.
 */
fun newIncomingAfter(messages: List<Message>, previousNewestId: String?, alreadyCounted: Set<String>): List<String>? {
    if (previousNewestId == null) return emptyList()
    val at = messages.indexOfFirst { it.id == previousNewestId }
    if (at < 0) return null
    return messages.drop(at + 1).filter { !it.isMine && it.id !in alreadyCounted }.map { it.id }
}
