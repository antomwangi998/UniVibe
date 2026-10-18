package com.univibe.app.data.repository

import com.univibe.app.data.model.Chat
import com.univibe.app.data.net.ApiException
import com.univibe.app.data.net.AuthedApi
import com.univibe.app.data.net.ChatEvent
import com.univibe.app.data.net.LiveConnection
import com.univibe.app.data.net.parseStudent
import com.univibe.app.data.net.send
import com.univibe.app.data.net.timeAgo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
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

    override suspend fun getChats(): List<Chat> {
        val chats = mutableListOf<Chat>()
        var cursor: String? = null
        // The server pages the list; five pages of 50 is far more than a student needs on one screen.
        repeat(MAX_PAGES) {
            val query = "limit=50" + (cursor?.let { "&cursor=" + URLEncoder.encode(it, "UTF-8") } ?: "")
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
            pinned = json.optBoolean("pinned", false),
            muted = isMuted(json.strOrNull("mutedUntil")),
            studentId = student?.id,
        )
    }

    override suspend fun setMuted(chatId: String, muted: Boolean) {
        // The server stores "muted until"; a date far in the future means "until I turn it back on".
        val body = JSONObject().put("mutedUntil", if (muted) FOREVER else JSONObject.NULL)
        api.send("PUT", "/api/v1/conversations/${enc(chatId)}/settings", body)
    }

    override suspend fun archive(chatId: String) {
        api.send("PUT", "/api/v1/conversations/${enc(chatId)}/settings", JSONObject().put("archived", true))
    }

    private fun parseListItem(o: JSONObject): Chat {
        val isGroup = o.getString("kind") == "group"
        val with = o.optJSONObject("with")?.let(::parseStudent)
        val last = o.optJSONObject("lastMessage")
        val preview = when {
            last == null -> ""
            last.optBoolean("deleted", false) -> "Message deleted"
            else -> last.strOrNull("content")?.takeIf { it.isNotBlank() } ?: "Photo"
        }
        return Chat(
            id = o.getString("id"),
            title = if (isGroup) o.strOrNull("title") ?: "Group" else with?.name ?: o.strOrNull("title") ?: "Chat",
            lastMessage = preview,
            timeLabel = ageLabel(o.strOrNull("lastMessageAt")),
            unread = if (o.optBoolean("markedUnread", false)) maxOf(1, o.optInt("unreadCount", 0)) else o.optInt("unreadCount", 0),
            isGroup = isGroup,
            pinned = o.optBoolean("pinned", false),
            muted = isMuted(o.strOrNull("mutedUntil")),
            studentId = with?.id,
        )
    }

    private fun isMuted(until: String?): Boolean =
        until != null && runCatching { Instant.parse(until).isAfter(Instant.now()) }.getOrDefault(false)

    private fun ageLabel(iso: String?): String {
        val at = iso?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: return ""
        return timeAgo(((System.currentTimeMillis() - at) / 1000).coerceAtLeast(0))
    }

    private companion object {
        const val MAX_PAGES = 5
        const val FOREVER = "2099-01-01T00:00:00.000Z"
    }
}

/** A JSON string, or null when the key is missing or JSON null (Android's optString would return the text "null"). */
internal fun JSONObject.strOrNull(key: String): String? = if (isNull(key)) null else getString(key)

internal fun enc(segment: String): String = URLEncoder.encode(segment, "UTF-8")
