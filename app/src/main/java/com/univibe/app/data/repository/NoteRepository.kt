package com.univibe.app.data.repository

import com.univibe.app.data.model.Note
import com.univibe.app.data.model.NoteAudience
import com.univibe.app.data.model.NotesSnapshot
import com.univibe.app.data.net.AuthedApi
import com.univibe.app.data.net.ChatEvent
import com.univibe.app.data.net.LiveConnection
import com.univibe.app.data.net.OfflineState
import com.univibe.app.data.net.send
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.json.JSONObject
import java.io.IOException
import java.time.Instant

/** Notes: one short expiring text per student, shown above the Chats list. Not a Story and not a post. */
interface NoteRepository {
    suspend fun getNotes(): NotesSnapshot
    /** Publishes, edits or replaces my Note (one active Note per student). Returns what the server stored. */
    suspend fun publish(text: String, audience: NoteAudience): Note
    suspend fun deleteMine()
    suspend fun setMuted(userId: String, muted: Boolean)
    /**
     * Answers someone's Note as a chat message in my conversation with them. [clientId] makes a retry safe: the same
     * reply sent twice arrives once.
     */
    suspend fun reply(authorId: String, text: String, clientId: String): Unit = throw IOException("Replying needs a connection to the UniVibe server.")
    /** Fires when the server says a Note changed, and after every reconnect (so missed events are reconciled). */
    fun changes(): Flow<Unit> = emptyFlow()
    /** Someone went online or offline (only for people who share their activity with me). */
    fun presence(): Flow<PresenceChange> = emptyFlow()
    /** Whether the live connection is up. Without one there is nothing to be offline from. */
    fun connected(): Flow<Boolean> = flowOf(true)
}

/** Used only in the offline preview build: no invented Notes, every call says plainly that a connection is needed. */
object UnavailableNoteRepository : NoteRepository {
    private fun offline(): Nothing = throw IOException("Notes need a connection to the UniVibe server.")
    override suspend fun getNotes(): NotesSnapshot = offline()
    override suspend fun publish(text: String, audience: NoteAudience): Note = offline()
    override suspend fun deleteMine() = offline()
    override suspend fun setMuted(userId: String, muted: Boolean) = offline()
}

class RemoteNoteRepository(
    private val api: AuthedApi,
    private val live: LiveConnection? = null,
    private val deviceClock: () -> Long = System::currentTimeMillis,
) : NoteRepository {

    override fun changes(): Flow<Unit> = live?.events
        ?.filter { it is ChatEvent.NoteChanged || it is ChatEvent.Connected }
        ?.map { }
        ?: emptyFlow()

    override fun presence(): Flow<PresenceChange> = live?.events
        ?.filterIsInstance<ChatEvent.Presence>()
        ?.map { PresenceChange(it.userId, it.online) }
        ?: emptyFlow()

    override fun connected(): Flow<Boolean> = live?.connected ?: flowOf(true)

    override suspend fun getNotes(): NotesSnapshot {
        val sentAt = deviceClock()
        val body = api.send("GET", "/api/v1/notes")
        // A saved copy (no signal) carries an old server time, which would make expired Notes look current.
        // For those, judge expiry on the phone's own clock instead.
        val fromCache = OfflineState.servedFromCache.value
        return parseNotesSnapshot(body, deviceNowMs = deviceClock(), trustServerClock = !fromCache, requestStartedMs = sentAt)
    }

    override suspend fun publish(text: String, audience: NoteAudience): Note {
        val body = api.send("PUT", "/api/v1/me/note", JSONObject().put("text", text).put("audience", audience.wire))
        return parseNote(JSONObject(body))
    }

    override suspend fun deleteMine() {
        api.send("DELETE", "/api/v1/me/note", ok = setOf(204, 200))
    }

    override suspend fun setMuted(userId: String, muted: Boolean) {
        api.send(if (muted) "PUT" else "DELETE", "/api/v1/users/$userId/note-mute", ok = setOf(204, 200))
    }

    override suspend fun reply(authorId: String, text: String, clientId: String) {
        api.send("POST", "/api/v1/users/$authorId/note/reply", JSONObject().put("content", text.trim()).put("clientId", clientId), setOf(200, 201))
    }
}

fun parseNote(o: JSONObject): Note {
    val author = o.getJSONObject("author")
    return Note(
        id = o.getString("id"),
        authorId = author.getString("id"),
        authorName = author.getString("fullName"),
        authorHandle = author.optString("handle"),
        authorVerified = author.optBoolean("studentVerified", false),
        avatarUrl = if (author.isNull("avatarUrl")) null else author.optString("avatarUrl").ifEmpty { null },
        text = o.getString("text"),
        audience = NoteAudience.fromWire(o.optString("audience")),
        version = o.optLong("version", 1),
        createdAtMs = Instant.parse(o.getString("createdAt")).toEpochMilli(),
        expiresAtMs = Instant.parse(o.getString("expiresAt")).toEpochMilli(),
        online = o.optJSONObject("presence")?.optBoolean("online", false) ?: false,
        lastActiveMs = o.optJSONObject("presence")?.takeIf { !it.isNull("lastActiveAt") }
            ?.optString("lastActiveAt")?.takeIf { it.isNotEmpty() }?.let { Instant.parse(it).toEpochMilli() },
    )
}

/**
 * [deviceNowMs] is the phone's clock right after the answer arrived. When the answer is fresh, the offset between the
 * server's clock and the phone's is measured at the midpoint of the request, which keeps network delay out of it.
 */
fun parseNotesSnapshot(body: String, deviceNowMs: Long, trustServerClock: Boolean, requestStartedMs: Long = deviceNowMs): NotesSnapshot {
    val json = JSONObject(body)
    val serverNow = Instant.parse(json.getString("serverTime")).toEpochMilli()
    val offset = if (trustServerClock) serverNow - (requestStartedMs + deviceNowMs) / 2 else 0L
    val limits = json.optJSONObject("limits")
    val items = json.getJSONArray("items")
    return NotesSnapshot(
        mine = if (json.isNull("mine")) null else parseNote(json.getJSONObject("mine")),
        items = (0 until items.length()).map { parseNote(items.getJSONObject(it)) },
        maxChars = limits?.optInt("maxChars", 60) ?: 60,
        ttlHours = limits?.optInt("ttlHours", 24) ?: 24,
        clockOffsetMs = offset,
    )
}
