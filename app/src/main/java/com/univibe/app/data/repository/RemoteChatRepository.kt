package com.univibe.app.data.repository

import com.univibe.app.data.model.Chat
import com.univibe.app.data.model.ChatMember
import com.univibe.app.data.model.Delivery
import com.univibe.app.data.model.LastKind
import com.univibe.app.data.net.ApiException
import com.univibe.app.data.net.AuthedApi
import com.univibe.app.data.net.ChatEvent
import com.univibe.app.data.net.LiveConnection
import com.univibe.app.data.net.parseStudent
import com.univibe.app.data.net.send
import com.univibe.app.data.net.timeAgo
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import org.json.JSONObject
import java.net.URLEncoder
import java.time.Instant

/** The signed-in student's chat list, from the server (`/api/v1/conversations`). */
class RemoteChatRepository(
    private val api: AuthedApi,
    /** Live events, so the list updates by itself. Optional. */
    private val live: LiveConnection? = null,
    private val myId: () -> String?,
) : ChatRepository {

    override fun changes(): Flow<Unit> = live?.events
        ?.filter { it is ChatEvent.MessageNew || it is ChatEvent.MessageChanged || it is ChatEvent.Connected }
        ?.map { }
        ?: emptyFlow()

    override fun presence(): Flow<PresenceChange> = live?.events
        ?.filterIsInstance<ChatEvent.Presence>()
        ?.map { PresenceChange(it.userId, it.online) }
        ?: emptyFlow()

    override suspend fun getChats(): List<Chat> = fetchList(archived = false)

    override suspend fun getArchivedChats(): List<Chat> = fetchList(archived = true)

    override suspend fun unarchive(chatId: String) {
        api.send("PUT", "/api/v1/conversations/${enc(chatId)}/settings", JSONObject().put("archived", false))
    }

    override fun connected(): Flow<Boolean> = live?.connected ?: kotlinx.coroutines.flow.flowOf(true)

    private suspend fun fetchList(archived: Boolean): List<Chat> {
        val chats = mutableListOf<Chat>()
        var cursor: String? = null
        // The server pages the list; five pages of 50 is far more than a student needs on one screen.
        repeat(MAX_PAGES) {
            val query = "limit=50" + (if (archived) "&archived=true" else "") + (cursor?.let { "&cursor=" + URLEncoder.encode(it, "UTF-8") } ?: "")
            val json = JSONObject(api.send("GET", "/api/v1/conversations?$query"))
            val items = json.getJSONArray("items")
            for (i in 0 until items.length()) chats += parseListItem(items.getJSONObject(i))
            cursor = json.strOrNull("nextCursor")
            if (cursor == null) return chats
        }
        return chats
    }

    override suspend fun getChat(chatId: String): Chat? {
        val json = try {
            JSONObject(api.send("GET", "/api/v1/conversations/${enc(chatId)}"))
        } catch (e: ApiException) {
            if (e.status == 404) return null // not a member looks exactly like "no such chat"
            throw e
        }
        val isGroup = json.getString("kind") == "group"
        val members = json.getJSONArray("members")
        val me = myId()
        var other: JSONObject? = null
        for (i in 0 until members.length()) {
            val m = members.getJSONObject(i)
            if (m.getString("id") != me) { other = m; break }
        }
        val student = if (!isGroup) other?.let(::parseStudent) else null
        return Chat(
            id = json.getString("id"),
            title = if (isGroup) json.strOrNull("title") ?: "Group" else student?.name ?: "Chat",
            lastMessage = "",
            timeLabel = "",
            isGroup = isGroup,
            online = other?.takeIf { !it.isNull("online") }?.optBoolean("online", false) ?: false,
            avatarUrl = student?.avatarUrl,
            look = student?.look,
            verified = student?.verified == true,
            groupPhotoUrl = json.strOrNull("photoUrl"),
            pinned = json.optBoolean("pinned", false),
            muted = isMuted(json.strOrNull("mutedUntil")),
            studentId = student?.id,
            lastActiveMs = other?.strOrNull("lastActiveAt")?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() },
        )
    }

    override suspend fun setMuted(chatId: String, muted: Boolean) {
        // The server stores "muted until"; a date far in the future means "until I turn it back on".
        val body = JSONObject().put("mutedUntil", if (muted) FOREVER else JSONObject.NULL)
        api.send("PUT", "/api/v1/conversations/${enc(chatId)}/settings", body)
    }

    override suspend fun setPinned(chatId: String, pinned: Boolean) {
        api.send("PUT", "/api/v1/conversations/${enc(chatId)}/settings", JSONObject().put("pinned", pinned))
    }

    override suspend fun markUnread(chatId: String) {
        api.send("POST", "/api/v1/conversations/${enc(chatId)}/mark-unread", JSONObject(), ok = setOf(204))
    }

    override suspend fun markRead(chatId: String) {
        api.send("POST", "/api/v1/conversations/${enc(chatId)}/read", JSONObject(), ok = setOf(204))
    }

    /**
     * Typing, per conversation, from live events. A "typing" entry lasts at most [TYPING_EXPIRY_MS] unless refreshed, so a
     * lost "stopped" event can never leave "typing…" on screen. Without a live connection nothing is ever emitted.
     */
    override fun typingByChat(): Flow<Map<String, Set<String>>> {
        val link = live ?: return emptyFlow()
        return channelFlow {
            val me = myId()
            val current = HashMap<String, Set<String>>()
            val timers = HashMap<String, Job>()
            fun publish() = trySend(synchronized(current) { HashMap(current) })
            fun set(chat: String, user: String, on: Boolean) {
                synchronized(current) {
                    val users = current[chat].orEmpty()
                    val next = if (on) users + user else users - user
                    if (next.isEmpty()) current.remove(chat) else current[chat] = next
                }
                publish()
            }
            trySend(emptyMap())
            link.events.collect { e ->
                // A reconnect may have swallowed "stopped" events, so start clean.
                if (e is ChatEvent.Connected) {
                    timers.values.forEach { it.cancel() }; timers.clear()
                    synchronized(current) { current.clear() }; publish()
                }
                if (e is ChatEvent.Typing && e.userId != me) {
                    val key = e.conversationId + "|" + e.userId
                    timers.remove(key)?.cancel()
                    set(e.conversationId, e.userId, e.typing)
                    if (e.typing) timers[key] = launch { delay(TYPING_EXPIRY_MS); set(e.conversationId, e.userId, false) }
                }
            }
        }
    }

    override suspend fun archive(chatId: String) {
        api.send("PUT", "/api/v1/conversations/${enc(chatId)}/settings", JSONObject().put("archived", true))
    }

    private fun parseListItem(o: JSONObject): Chat {
        val isGroup = o.getString("kind") == "group"
        val with = o.optJSONObject("with")?.let(::parseStudent)
        val last = o.optJSONObject("lastMessage")
        val deleted = last?.optBoolean("deleted", false) == true
        val text = last?.strOrNull("content")?.takeIf { it.isNotBlank() }
        // `mediaCount` is newer than some servers: when it is missing, an empty message is treated as a photo, as before.
        val voice = if (last == null || deleted) 0 else last.optInt("audioCount", 0)
        val photos = if (last == null || deleted) 0 else if (last.has("mediaCount")) last.optInt("mediaCount", 0) else if (text == null) 1 else 0
        val docs = if (last == null || deleted) 0 else last.optInt("documentCount", 0)
        val kind = when {
            last == null -> LastKind.None
            deleted -> LastKind.Deleted
            voice > 0 -> LastKind.Voice
            photos > 0 -> LastKind.Photo
            docs > 0 -> LastKind.File
            last.optBoolean("hasLocation", false) && text == null -> LastKind.Location
            else -> LastKind.Text
        }
        val preview = when (kind) {
            LastKind.None -> ""
            LastKind.Deleted -> "Message deleted"
            LastKind.Voice -> "Voice message"
            LastKind.Photo -> text ?: if (photos > 1) "$photos photos" else "Photo"
            LastKind.File -> text ?: "Document"
            LastKind.Location -> "Location"
            LastKind.Text -> text ?: if (last?.optBoolean("hasEvent", false) == true) "Event" else ""
        }
        val memberArr = o.optJSONArray("members")
        val members = buildList {
            if (memberArr != null) for (i in 0 until memberArr.length()) {
                val m = memberArr.getJSONObject(i)
                add(ChatMember(m.getString("id"), m.optString("name")))
            }
        }
        val delivery = when (last?.strOrNull("delivery")) {
            "read" -> Delivery.Read
            "delivered" -> Delivery.Delivered
            "sent" -> Delivery.Sent
            else -> Delivery.None
        }
        val realUnread = o.optInt("unreadCount", 0)
        val marked = o.optBoolean("markedUnread", false)
        return Chat(
            id = o.getString("id"),
            title = if (isGroup) o.strOrNull("title")?.takeIf { it.isNotBlank() } ?: untitledGroup(members) else with?.name ?: o.strOrNull("title") ?: "Chat",
            lastMessage = preview,
            timeLabel = ageLabel(o.strOrNull("lastMessageAt")),
            unread = if (marked) maxOf(1, realUnread) else realUnread,
            isGroup = isGroup,
            pinned = o.optBoolean("pinned", false),
            muted = isMuted(o.strOrNull("mutedUntil")),
            studentId = with?.id,
            lastKind = kind,
            lastFromMe = last != null && last.strOrNull("senderId") == myId(),
            lastSenderName = last?.strOrNull("senderName"),
            photoCount = photos,
            markedOnly = marked && realUnread == 0,
            members = members,
            memberCount = o.optInt("memberCount", 0),
            lastDelivery = delivery,
            avatarUrl = with?.avatarUrl,
            look = with?.look,
            verified = !isGroup && with?.verified == true,
            groupPhotoUrl = o.strOrNull("photoUrl"),
            // `presence` is null when the other person keeps their activity private: then there is no dot and no time.
            online = !isGroup && o.optJSONObject("presence")?.optBoolean("online", false) == true,
            lastActiveMs = if (isGroup) null else o.optJSONObject("presence")?.strOrNull("lastActiveAt")
                ?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() },
        )
    }

    /** A group with no name is called after its members, like "Jane, Sam and 2 others". */
    private fun untitledGroup(members: List<ChatMember>): String {
        val names = members.map { it.name.substringBefore(' ') }.filter { it.isNotBlank() }
        return when {
            names.isEmpty() -> "Group"
            names.size <= 2 -> names.joinToString(" and ")
            else -> "${names[0]}, ${names[1]} and ${names.size - 2} other" + if (names.size - 2 == 1) "" else "s"
        }
    }

    private fun isMuted(until: String?): Boolean =
        until != null && runCatching { Instant.parse(until).isAfter(Instant.now()) }.getOrDefault(false)

    private fun ageLabel(iso: String?): String {
        val at = iso?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: return ""
        return timeAgo(((System.currentTimeMillis() - at) / 1000).coerceAtLeast(0))
    }

    private companion object {
        const val MAX_PAGES = 5
        const val TYPING_EXPIRY_MS = 7_000L
        const val FOREVER = "2099-01-01T00:00:00.000Z"
    }
}

/** A JSON string, or null when the key is missing or JSON null (Android's optString would return the text "null"). */
internal fun JSONObject.strOrNull(key: String): String? = if (isNull(key)) null else getString(key)

internal fun enc(segment: String): String = URLEncoder.encode(segment, "UTF-8")
