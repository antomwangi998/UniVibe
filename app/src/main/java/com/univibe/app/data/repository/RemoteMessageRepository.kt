package com.univibe.app.data.repository

import com.univibe.app.data.model.Message
import com.univibe.app.data.model.MessageStatus
import com.univibe.app.data.model.Reaction
import com.univibe.app.data.model.ReplyRef
import com.univibe.app.data.model.Student
import com.univibe.app.data.net.AuthedApi
import com.univibe.app.data.net.ChatEvent
import com.univibe.app.data.net.LiveConnection
import com.univibe.app.data.net.MediaUploader
import com.univibe.app.data.net.send
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Chat messages from the server (`/api/v1/conversations/{id}/messages`).
 *
 * While a conversation is open the latest messages are re-fetched from the server. The live connection ([live]) makes
 * that happen the moment something changes (new message, edit, unsend, reaction, pin) and carries typing and "seen";
 * without it, or while it is down, the app falls back to polling every few seconds. The server is always the source of
 * truth: an event only tells us to look again, so a missed event can never leave the screen wrong for long.
 */
class RemoteMessageRepository(
    private val api: AuthedApi,
    /** Sends photos. Without it, sending a photo fails with a clear message. */
    private val uploader: MediaUploader? = null,
    /** Live events (new messages, typing, seen). Optional: without it the repository polls. */
    private val live: LiveConnection? = null,
    private val myId: () -> String?,
) : MessageRepository {

    private class ChatState {
        val messages = MutableStateFlow<List<Message>>(emptyList())
        val seqById = HashMap<String, Long>()
        val pinned = MutableStateFlow<String?>(null)
        /** Photos this phone sent: shown from the local file (instant, works offline) instead of a temporary download link. */
        val localPhotos = HashMap<String, String>()
        val hasOlder = MutableStateFlow(false)
        @Volatile var windowSeen = false
        /** Lowest seq seen so far, unsent messages included, so paging back always makes progress. */
        var oldestSeq = Long.MAX_VALUE
        @Volatile var names: Map<String, String> = emptyMap()
        /** How far each other member has read (seq). Drives the "seen" tick; a group counts as seen once everyone has read. */
        val readCursors = HashMap<String, Long>()
        @Volatile var reloadMembers = false
        var polls = 0
    }

    private val states = ConcurrentHashMap<String, ChatState>()
    private fun state(chatId: String) = states.getOrPut(chatId) { ChatState() }

    override fun messages(chatId: String): Flow<List<Message>> = channelFlow {
        val s = state(chatId)
        launch { s.messages.collect { trySend(it) } }
        // Anything that says "look again" wakes the loop below at once; several at the same moment count as one.
        val nudge = Channel<Unit>(Channel.CONFLATED)
        live?.let { link ->
            launch {
                link.events.collect { e ->
                    when {
                        e is ChatEvent.Read && e.conversationId == chatId -> onRead(chatId, e)
                        e is ChatEvent.MessageNew && e.conversationId == chatId -> nudge.trySend(Unit)
                        e is ChatEvent.MessageChanged && e.conversationId == chatId -> nudge.trySend(Unit)
                    }
                }
            }
            // After a reconnect we may have missed events: reload messages and who has read what.
            launch { link.connected.collect { up -> if (up) { s.reloadMembers = true; nudge.trySend(Unit) } } }
        }
        while (isActive) {
            runCatching { refresh(chatId) } // offline or a server hiccup: try again at the next tick
            // With the live link up, polling is only a safety net; without it, it is how new messages arrive.
            withTimeoutOrNull(if (live?.connected?.value == true) LIVE_POLL_MS else POLL_MS) { nudge.receive() }
        }
    }

    override fun typing(chatId: String): Flow<Boolean> {
        val link = live ?: return flowOf(false)
        return channelFlow {
            trySend(false)
            val me = myId()
            var expire: kotlinx.coroutines.Job? = null
            link.events.collect { e ->
                if (e is ChatEvent.Typing && e.conversationId == chatId && e.userId != me) {
                    expire?.cancel()
                    trySend(e.typing)
                    // The server stops typing by itself too; this guards against a stop event we never received.
                    if (e.typing) expire = launch { delay(TYPING_EXPIRY_MS); trySend(false) }
                }
            }
        }
    }

    override suspend fun typingChanged(chatId: String, typing: Boolean) {
        live?.send(JSONObject().put("type", if (typing) "typing:start" else "typing:stop").put("conversationId", chatId))
    }

    private fun onRead(chatId: String, e: ChatEvent.Read) {
        if (e.userId == myId()) return
        val s = state(chatId)
        synchronized(s) { s.readCursors[e.userId] = maxOf(s.readCursors[e.userId] ?: 0L, e.upToSeq) }
        applySeen(s)
    }

    /** Re-marks our own messages as Seen or Sent from the read cursors. */
    private fun applySeen(s: ChatState) {
        synchronized(s) {
            val seen = seenUpTo(s)
            s.messages.value = s.messages.value.map { m ->
                val seq = s.seqById[m.id]
                if (!m.isMine || seq == null) m
                else {
                    val status = if (seen > 0 && seq <= seen) MessageStatus.Seen else MessageStatus.Sent
                    if (status == m.status) m else m.copy(status = status)
                }
            }
        }
    }

    private fun seenUpTo(s: ChatState): Long = if (s.readCursors.isEmpty()) 0L else s.readCursors.values.min()

    private suspend fun refresh(chatId: String) {
        val s = state(chatId)
        // Members (names and read cursors) load once, again after a reconnect, and now and then while offline.
        val reload = s.names.isEmpty() || s.reloadMembers || (live?.connected?.value != true && ++s.polls % 5 == 0)
        if (reload) { s.reloadMembers = false; loadNames(chatId) }
        val json = JSONObject(api.send("GET", "/api/v1/conversations/${enc(chatId)}/messages?limit=$WINDOW"))
        val items = json.getJSONArray("items")
        val incoming = (0 until items.length()).map { items.getJSONObject(it) }
        s.pinned.value = json.strOrNull("pinnedMessageId")
        if (!s.windowSeen) { s.windowSeen = true; s.hasOlder.value = !json.isNull("nextBefore") }
        // A sender we have no name for yet (someone joined): reload the member list once.
        val me = myId()
        if (incoming.any { m -> m.strOrNull("senderId").let { it != null && it != me && it !in s.names } }) loadNames(chatId)
        merge(chatId, incoming)
    }

    private suspend fun loadNames(chatId: String) {
        val json = JSONObject(api.send("GET", "/api/v1/conversations/${enc(chatId)}"))
        val members = json.getJSONArray("members")
        val s = state(chatId)
        val me = myId()
        val names = HashMap<String, String>()
        for (i in 0 until members.length()) {
            val m = members.getJSONObject(i)
            val id = m.getString("id")
            names[id] = m.getString("fullName")
            if (id != me && !m.isNull("lastReadSeq")) {
                synchronized(s) { s.readCursors[id] = maxOf(s.readCursors[id] ?: 0L, m.getLong("lastReadSeq")) }
            }
        }
        synchronized(s) { s.readCursors.keys.retainAll(names.keys) } // someone who left no longer counts
        s.names = names
    }

    /** Folds server messages into the list: replaces by id, drops unsent ones and our unconfirmed copies, keeps server order. */
    private fun merge(chatId: String, incoming: List<JSONObject>) {
        val s = state(chatId)
        synchronized(s) {
            val byId = LinkedHashMap<String, Message>()
            s.messages.value.filter { !it.id.startsWith(LOCAL) }.forEach { byId[it.id] = it }
            for (o in incoming) {
                val id = o.getString("id")
                s.oldestSeq = minOf(s.oldestSeq, o.getLong("seq"))
                if (o.optBoolean("deleted", false)) { byId.remove(id); s.seqById.remove(id); continue }
                s.seqById[id] = o.getLong("seq")
                byId[id] = toMessage(o, chatId, s.names)
            }
            val sent = byId.values.sortedBy { s.seqById[it.id] ?: Long.MAX_VALUE }
            // Messages still on their way keep showing at the bottom until the server confirms them.
            val pending = s.messages.value.filter { it.id.startsWith(LOCAL) }
            s.messages.value = sent + pending
        }
    }

    override suspend fun send(chatId: String, text: String, imagePath: String?, replyTo: ReplyRef?) {
        val clean = text.trim()
        if (clean.isEmpty() && imagePath == null) return
        val me = myId() ?: throw IllegalStateException("Sign in to send messages.")
        val s = state(chatId)
        // The same id is sent with every retry, so a flaky connection can never create two copies.
        val clientId = UUID.randomUUID().toString()
        val local = Message("$LOCAL$clientId", chatId, me, "You", clean, System.currentTimeMillis(), true, MessageStatus.Sending, imagePath = imagePath, replyTo = replyTo)
        s.messages.update { it + local }
        try {
            // The photo goes up first (ask for a link, send the file, confirm); the message then just points at it.
            val mediaId = imagePath?.let { path ->
                (uploader ?: throw IllegalStateException("Photos aren't available right now.")).upload(path, "image", "message")
            }
            val body = JSONObject().put("content", clean).put("clientId", clientId)
            mediaId?.let { body.put("mediaIds", JSONArray().put(it)) }
            replyTo?.let { body.put("replyToId", it.messageId) }
            val sent = JSONObject(api.send("POST", "/api/v1/conversations/${enc(chatId)}/messages", body, ok = setOf(200, 201)))
            if (imagePath != null) synchronized(s) { s.localPhotos[sent.getString("id")] = imagePath }
            s.messages.update { list -> list.filterNot { it.id == local.id } }
            merge(chatId, listOf(sent))
        } catch (e: Exception) {
            s.messages.update { list -> list.filterNot { it.id == local.id } }
            throw e
        }
    }

    override suspend fun deleteMessage(chatId: String, messageId: String) {
        if (messageId.startsWith(LOCAL)) return
        api.send("DELETE", "/api/v1/messages/${enc(messageId)}", ok = setOf(204))
        val s = state(chatId)
        synchronized(s) { s.seqById.remove(messageId) }
        s.messages.update { list -> list.filterNot { it.id == messageId } }
    }

    override suspend fun react(chatId: String, messageId: String, reaction: Reaction?) {
        if (messageId.startsWith(LOCAL)) return
        val path = "/api/v1/messages/${enc(messageId)}/reaction"
        val json = if (reaction == null) JSONObject(api.send("DELETE", path))
        else JSONObject(api.send("PUT", path, JSONObject().put("reaction", reaction.name.lowercase())))
        val counts = parseReactions(json.optJSONObject("reactions"))
        state(chatId).messages.update { list ->
            list.map { if (it.id == messageId) it.copy(reactions = counts, myReaction = reaction) else it }
        }
    }

    override suspend fun clearChat(chatId: String) {
        api.send("POST", "/api/v1/conversations/${enc(chatId)}/clear", JSONObject(), ok = setOf(204))
        val s = state(chatId)
        synchronized(s) { s.seqById.clear(); s.oldestSeq = Long.MAX_VALUE }
        s.messages.value = emptyList()
        s.hasOlder.value = false
    }

    override suspend fun editMessage(chatId: String, messageId: String, text: String) {
        val clean = text.trim()
        require(clean.isNotEmpty() && clean.length <= 4000) { "Invalid message" }
        val json = JSONObject(api.send("PUT", "/api/v1/messages/${enc(messageId)}", JSONObject().put("content", clean)))
        merge(chatId, listOf(json))
    }

    override suspend fun forward(fromChatId: String, messageId: String, toChatIds: List<String>) {
        val body = JSONObject().put("conversationIds", org.json.JSONArray(toChatIds))
        api.send("POST", "/api/v1/messages/${enc(messageId)}/forward", body)
    }

    override fun pinnedMessageId(chatId: String): Flow<String?> = state(chatId).pinned

    override suspend fun pin(chatId: String, messageId: String?) {
        val path = "/api/v1/conversations/${enc(chatId)}/pin"
        if (messageId == null) api.send("DELETE", path, ok = setOf(204))
        else api.send("PUT", path, JSONObject().put("messageId", messageId), ok = setOf(204))
        state(chatId).pinned.value = messageId
    }

    override fun hasOlder(chatId: String): Flow<Boolean> = state(chatId).hasOlder

    override suspend fun loadOlder(chatId: String) {
        val s = state(chatId)
        val oldest = synchronized(s) { s.oldestSeq }
        if (oldest == Long.MAX_VALUE) return
        val json = JSONObject(api.send("GET", "/api/v1/conversations/${enc(chatId)}/messages?limit=$WINDOW&before=$oldest"))
        val items = json.getJSONArray("items")
        merge(chatId, (0 until items.length()).map { items.getJSONObject(it) })
        s.hasOlder.value = !json.isNull("nextBefore")
    }

    override suspend fun markRead(chatId: String) {
        api.send("POST", "/api/v1/conversations/${enc(chatId)}/read", JSONObject(), ok = setOf(204))
    }

    override suspend fun openChatWith(student: Student): String {
        val body = JSONObject().put("type", "direct").put("userId", student.id)
        return JSONObject(api.send("POST", "/api/v1/conversations", body, ok = setOf(200, 201))).getString("id")
    }

    private fun toMessage(o: JSONObject, chatId: String, names: Map<String, String>): Message {
        val me = myId()
        val senderId = o.strOrNull("senderId").orEmpty()
        val mine = senderId == me
        val media = o.optJSONArray("media")
        val content = o.strOrNull("content").orEmpty()
        val id = o.getString("id")
        val st = state(chatId)
        val seen = synchronized(st) { seenUpTo(st) }
        // Our own photos open from the local copy; everyone else's from the server's temporary link.
        val photo = synchronized(state(chatId)) { state(chatId).localPhotos[id] }?.takeIf { File(it).isFile }
            ?: media?.takeIf { it.length() > 0 }?.getJSONObject(0)?.strOrNull("url")
        val reply = o.optJSONObject("replyTo")?.let { r ->
            val author = r.strOrNull("senderId")
            ReplyRef(
                messageId = r.getString("id"),
                senderName = if (author == me) "You" else names[author] ?: "Student",
                preview = r.strOrNull("preview")?.takeIf { it.isNotBlank() } ?: "Message",
            )
        }
        return Message(
            id = id,
            chatId = chatId,
            senderId = senderId,
            senderName = if (mine) "You" else names[senderId] ?: "Student",
            // A photo with no caption shows as the picture alone; the word is only a fallback when no link is available.
            text = if (content.isBlank() && photo == null && media != null && media.length() > 0) "Photo" else content,
            imagePath = photo,
            timeMillis = runCatching { Instant.parse(o.getString("createdAt")).toEpochMilli() }.getOrDefault(0L),
            isMine = mine,
            status = if (mine && seen > 0 && o.getLong("seq") <= seen) MessageStatus.Seen else MessageStatus.Sent,
            replyTo = reply,
            reactions = parseReactions(o.optJSONObject("reactions")),
            myReaction = o.strOrNull("myReaction")?.let(::reactionOf),
            forwarded = o.optBoolean("forwarded", false),
            edited = !o.isNull("editedAt"),
        )
    }

    private fun parseReactions(o: JSONObject?): Map<Reaction, Int> {
        if (o == null) return emptyMap()
        val result = LinkedHashMap<Reaction, Int>()
        for (key in o.keys()) reactionOf(key)?.let { result[it] = o.getInt(key) }
        return result
    }

    private fun reactionOf(value: String): Reaction? = Reaction.values().firstOrNull { it.name.equals(value, ignoreCase = true) }

    private companion object {
        const val LOCAL = "local-"
        const val POLL_MS = 3_000L
        const val LIVE_POLL_MS = 20_000L
        const val TYPING_EXPIRY_MS = 7_000L
        const val WINDOW = 50
    }
}
