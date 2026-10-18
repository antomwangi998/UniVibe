package com.univibe.app.data.repository

import com.univibe.app.data.model.Message
import com.univibe.app.data.model.MessageStatus
import com.univibe.app.data.model.Reaction
import com.univibe.app.data.model.ReplyRef
import com.univibe.app.data.model.Student
import com.univibe.app.data.net.AuthedApi
import com.univibe.app.data.net.send
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Chat messages from the server (`/api/v1/conversations/{id}/messages`).
 *
 * While a conversation is open the latest messages are re-fetched every few seconds. That also picks up edits,
 * unsends and reactions. A live WebSocket connection will replace the polling; the server already speaks it.
 * Not available yet (they fail with a clear message): photos in chat, forwarding, pinning, "clear chat", typing.
 */
class RemoteMessageRepository(
    private val api: AuthedApi,
    private val myId: () -> String?,
) : MessageRepository {

    private class ChatState {
        val messages = MutableStateFlow<List<Message>>(emptyList())
        val seqById = HashMap<String, Long>()
        val pinned = MutableStateFlow<String?>(null)
        val hasOlder = MutableStateFlow(false)
        @Volatile var windowSeen = false
        /** Lowest seq seen so far, unsent messages included, so paging back always makes progress. */
        var oldestSeq = Long.MAX_VALUE
        @Volatile var names: Map<String, String> = emptyMap()
    }

    private val states = ConcurrentHashMap<String, ChatState>()
    private fun state(chatId: String) = states.getOrPut(chatId) { ChatState() }

    override fun messages(chatId: String): Flow<List<Message>> = channelFlow {
        val s = state(chatId)
        launch { s.messages.collect { trySend(it) } }
        while (isActive) {
            runCatching { refresh(chatId) } // offline or a server hiccup: try again at the next tick
            delay(POLL_MS)
        }
    }

    override fun typing(chatId: String): Flow<Boolean> = flowOf(false)

    private suspend fun refresh(chatId: String) {
        val s = state(chatId)
        if (s.names.isEmpty()) loadNames(chatId)
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
        state(chatId).names = (0 until members.length()).associate {
            val m = members.getJSONObject(it)
            m.getString("id") to m.getString("fullName")
        }
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
        if (imagePath != null) throw IllegalStateException("Photos in chat aren't available yet.")
        val clean = text.trim()
        if (clean.isEmpty()) return
        val me = myId() ?: throw IllegalStateException("Sign in to send messages.")
        val s = state(chatId)
        // The same id is sent with every retry, so a flaky connection can never create two copies.
        val clientId = UUID.randomUUID().toString()
        val local = Message("$LOCAL$clientId", chatId, me, "You", clean, System.currentTimeMillis(), true, MessageStatus.Sending, replyTo = replyTo)
        s.messages.update { it + local }
        try {
            val body = JSONObject().put("content", clean).put("clientId", clientId)
            replyTo?.let { body.put("replyToId", it.messageId) }
            val sent = JSONObject(api.send("POST", "/api/v1/conversations/${enc(chatId)}/messages", body, ok = setOf(200, 201)))
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
        val reply = o.optJSONObject("replyTo")?.let { r ->
            val author = r.strOrNull("senderId")
            ReplyRef(
                messageId = r.getString("id"),
                senderName = if (author == me) "You" else names[author] ?: "Student",
                preview = r.strOrNull("preview")?.takeIf { it.isNotBlank() } ?: "Message",
            )
        }
        return Message(
            id = o.getString("id"),
            chatId = chatId,
            senderId = senderId,
            senderName = if (mine) "You" else names[senderId] ?: "Student",
            text = if (content.isBlank() && media != null && media.length() > 0) "Photo" else content,
            timeMillis = runCatching { Instant.parse(o.getString("createdAt")).toEpochMilli() }.getOrDefault(0L),
            isMine = mine,
            status = MessageStatus.Sent,
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
        const val WINDOW = 50
    }
}
