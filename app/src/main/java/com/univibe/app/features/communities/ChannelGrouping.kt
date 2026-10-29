package com.univibe.app.features.communities

import com.univibe.app.data.model.ChannelMessage
import java.time.OffsetDateTime
import java.time.ZoneId

/** Messages from one person closer together than this read as one run. Same value the one-to-one chats use. */
const val CHANNEL_GROUP_WINDOW_MILLIS = 5 * 60_000L

private fun ChannelMessage.millis(): Long? = runCatching { OffsetDateTime.parse(createdAt).toInstant().toEpochMilli() }.getOrNull()

/**
 * True when [newer] continues [older] (neighbours, oldest first): same author, close in time, same calendar day.
 * Anything that carries its own header never continues a run: a reply (its preview sits on top), a titled post
 * (question or announcement), a poll, a pinned message (its pin icon lives in the header), and a deleted message (a tombstone). A message with no known author or with an
 * unreadable timestamp never joins either, so a wrong guess can only show an extra header, never hide one.
 */
fun joinsChannelGroup(older: ChannelMessage?, newer: ChannelMessage, zone: ZoneId = ZoneId.systemDefault()): Boolean {
    if (older == null) return false
    val a = older.author?.id ?: return false
    if (a != newer.author?.id) return false
    if (older.deleted || newer.deleted) return false
    if (newer.replyTo != null || newer.title != null || newer.poll != null || newer.pinned) return false
    val t0 = older.millis() ?: return false
    val t1 = newer.millis() ?: return false
    if (t1 < t0 || t1 - t0 > CHANNEL_GROUP_WINDOW_MILLIS) return false
    return java.time.Instant.ofEpochMilli(t0).atZone(zone).toLocalDate() == java.time.Instant.ofEpochMilli(t1).atZone(zone).toLocalDate()
}

/**
 * Ids of the messages that continue the message before them. One pass over an oldest-first list, so it is computed once
 * per list change (not per row, not per frame) and a reaction or typing change elsewhere never recomputes it.
 */
fun channelContinuations(oldestFirst: List<ChannelMessage>, zone: ZoneId = ZoneId.systemDefault()): Set<String> {
    val out = HashSet<String>()
    for (i in 1 until oldestFirst.size) if (joinsChannelGroup(oldestFirst[i - 1], oldestFirst[i], zone)) out += oldestFirst[i].id
    return out
}
