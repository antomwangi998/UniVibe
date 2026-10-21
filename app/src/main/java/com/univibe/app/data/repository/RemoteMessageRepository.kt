package com.univibe.app.data.repository

import com.univibe.app.data.model.FileAttachment
import com.univibe.app.data.media.PickedFile
import com.univibe.app.data.model.Message
import com.univibe.app.data.model.MessageStatus
import com.univibe.app.data.model.LinkPreview
import com.univibe.app.data.model.VoiceNote
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
import com.univibe.app.data.net.ApiException
import com.univibe.app.data.net.Outbox
import com.univibe.app.data.net.OutboxResult
import com.univibe.app.data.prefs.DraftStore
import java.io.IOException

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
        /** Files this phone sent: opened from the local copy instead of downloading them again. */
        val localFiles = HashMap<String, String>()
        val hasOlder = MutableStateFlow(false)
        @Volatile var windowSeen = false
        /** Lowest seq seen so far, unsent messages included, so paging back always makes progress. */
        var oldestSeq = Long.MAX_VALUE
        @Volatile var names: Map<String, String> = emptyMap()
        /** How far each other member has read (seq). Drives the "seen" tick; a group counts as seen once everyone has read. */
        val readCursors = HashMap<String, Long>()
        /** How far each other member's device has received (seq). Drives the "delivered" tick. */
        val deliveredCursors = HashMap<String, Long>()
        @Volatile var reloadMembers = false
        var polls = 0
    }

    private val states = ConcurrentHashMap<String, ChatState>()
    private fun state(chatId: String) = states.getOrPut(chatId) { ChatState().also { restoreQueued(chatId, it) } }

    /** Where messages wait when there is no connection (set by the app; unset in tests). */
    var outbox: Outbox? = null

    /** After a restart, messages still waiting to be sent show at the bottom of their chat again. */
    private fun restoreQueued(chatId: String, s: ChatState) {
        val box = outbox ?: return
        val me = myId() ?: return
        val waiting = box.pending("message").filter { it.data.optString("chatId") == chatId }.map { e ->
            val reply = e.data.optJSONObject("reply")?.let { ReplyRef(it.optString("messageId"), it.optString("senderName"), it.optString("preview")) }
            Message("$LOCAL${e.data.optString("clientId")}", chatId, me, "You", e.data.optString("content"), e.createdAt, true, MessageStatus.Sending, replyTo = reply)
        }
        if (waiting.isNotEmpty()) s.messages.value = waiting
    }

    /** Sends one queued message. The same client id as the first attempt, so a message that did get through is never doubled. */
    suspend fun deliverQueued(entry: Outbox.Entry): OutboxResult {
        val d = entry.data
        val chatId = d.optString("chatId")
        val clientId = d.optString("clientId")
        if (chatId.isEmpty() || clientId.isEmpty()) return OutboxResult.Drop
        val body = JSONObject().put("content", d.optString("content")).put("clientId", clientId)
        d.optJSONObject("reply")?.optString("messageId")?.takeIf { it.isNotEmpty() }?.let { body.put("replyToId", it) }
        return try {
            val sent = JSONObject(api.send("POST", "/api/v1/conversations/${enc(chatId)}/messages", body, ok = setOf(200, 201)))
            state(chatId).messages.update { list -> list.filterNot { it.id == "$LOCAL$clientId" } }
            merge(chatId, listOf(sent))
            OutboxResult.Done
        } catch (e: IOException) {
            OutboxResult.Retry
        } catch (e: ApiException) {
            if (e.status >= 500 || e.status == 429 || e.status == 408 || e.status == 401) return OutboxResult.Retry
            // Refused for good (left the chat, blocked, ...): take it off the screen and give the words back as a draft.
            state(chatId).messages.update { list -> list.filterNot { it.id == "$LOCAL$clientId" } }
            if (DraftStore.get("chat:$chatId").isBlank()) DraftStore.set("chat:$chatId", d.optString("content"))
            OutboxResult.Drop
        }
    }

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
                        e is ChatEvent.Delivered && e.conversationId == chatId -> onDelivered(chatId, e)
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

    private fun onDelivered(chatId: String, e: ChatEvent.Delivered) {
        if (e.userId == myId()) return
        val s = state(chatId)
        synchronized(s) { s.deliveredCursors[e.userId] = maxOf(s.deliveredCursors[e.userId] ?: 0L, e.upToSeq) }
        applySeen(s)
    }

    /** Re-marks our own messages as Seen, Delivered or Sent from the read and delivery cursors. */
    private fun applySeen(s: ChatState) {
        synchronized(s) {
            val seen = seenUpTo(s)
            val delivered = deliveredUpTo(s)
            s.messages.value = s.messages.value.map { m ->
                val seq = s.seqById[m.id]
                if (!m.isMine || seq == null) m
                else {
                    val status = statusFor(seq, seen, delivered)
                    if (status == m.status) m else m.copy(status = status)
                }
            }
        }
    }

    private fun seenUpTo(s: ChatState): Long = if (s.readCursors.isEmpty()) 0L else s.readCursors.values.min()

    // A group counts as delivered once everyone has it; reading implies delivery.
    private fun deliveredUpTo(s: ChatState): Long = if (s.deliveredCursors.isEmpty()) 0L else s.deliveredCursors.values.min()

    private fun statusFor(seq: Long, seen: Long, delivered: Long): MessageStatus = when {
        seen > 0 && seq <= seen -> MessageStatus.Seen
        delivered > 0 && seq <= delivered -> MessageStatus.Delivered
        else -> MessageStatus.Sent
    }

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
            if (id != me && m.has("lastDeliveredSeq") && !m.isNull("lastDeliveredSeq")) {
                synchronized(s) { s.deliveredCursors[id] = maxOf(s.deliveredCursors[id] ?: 0L, m.getLong("lastDeliveredSeq")) }
            }
        }
        synchronized(s) { s.readCursors.keys.retainAll(names.keys); s.deliveredCursors.keys.retainAll(names.keys) } // someone who left no longer counts
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
                (uploader ?: throw IllegalStateException("Photos aren't available right now.")).upload(path, "image", "message", tag = local.id)
            }
            val body = JSONObject().put("content", clean).put("clientId", clientId)
            mediaId?.let { body.put("mediaIds", JSONArray().put(it)) }
            replyTo?.let { body.put("replyToId", it.messageId) }
            val sent = JSONObject(api.send("POST", "/api/v1/conversations/${enc(chatId)}/messages", body, ok = setOf(200, 201)))
            if (imagePath != null) synchronized(s) { s.localPhotos[sent.getString("id")] = imagePath }
            s.messages.update { list -> list.filterNot { it.id == local.id } }
            merge(chatId, listOf(sent))
        } catch (e: Exception) {
            // No connection: a plain text message waits in the outbox and goes out by itself when the connection is back.
            if (e is IOException && imagePath == null && queueText(chatId, clientId, clean, replyTo)) return
            s.messages.update { list -> list.filterNot { it.id == local.id } }
            throw e
        }
    }

    override suspend fun sendPhotos(chatId: String, text: String, imagePaths: List<String>, replyTo: ReplyRef?) {
        if (imagePaths.size <= 1) return send(chatId, text, imagePaths.firstOrNull(), replyTo)
        val paths = imagePaths.take(MAX_MESSAGE_PHOTOS)
        val clean = text.trim()
        val me = myId() ?: throw IllegalStateException("Sign in to send messages.")
        val s = state(chatId)
        val clientId = UUID.randomUUID().toString()
        val local = Message("$LOCAL$clientId", chatId, me, "You", clean, System.currentTimeMillis(), true, MessageStatus.Sending, imagePath = paths[0], imagePaths = paths, replyTo = replyTo)
        s.messages.update { it + local }
        try {
            val up = uploader ?: throw IllegalStateException("Photos aren't available right now.")
            val mediaIds = JSONArray()
            for (p in paths) mediaIds.put(up.upload(p, "image", "message", tag = local.id)) // one after another, in the order picked
            val body = JSONObject().put("content", clean).put("clientId", clientId).put("mediaIds", mediaIds)
            replyTo?.let { body.put("replyToId", it.messageId) }
            val sent = JSONObject(api.send("POST", "/api/v1/conversations/${enc(chatId)}/messages", body, ok = setOf(200, 201)))
            synchronized(s) { s.localPhotos[sent.getString("id")] = paths[0] } // the first one shows instantly from the phone
            s.messages.update { list -> list.filterNot { it.id == local.id } }
            merge(chatId, listOf(sent))
        } catch (e: Exception) {
            s.messages.update { list -> list.filterNot { it.id == local.id } }
            throw e
        }
    }

    override suspend fun sendFile(chatId: String, text: String, file: PickedFile, replyTo: ReplyRef?) {
        val clean = text.trim()
        val me = myId() ?: throw IllegalStateException("Sign in to send messages.")
        val s = state(chatId)
        val clientId = UUID.randomUUID().toString()
        val local = Message("$LOCAL$clientId", chatId, me, "You", clean, System.currentTimeMillis(), true, MessageStatus.Sending, replyTo = replyTo,
            file = FileAttachment(file.name, file.sizeBytes, file.contentType, file.path))
        s.messages.update { it + local }
        try {
            val mediaId = (uploader ?: throw IllegalStateException("Sharing files isn't available right now."))
                .upload(file.path, "document", "message", tag = local.id, explicitType = file.contentType, fileName = file.name)
            val body = JSONObject().put("content", clean).put("clientId", clientId).put("mediaIds", JSONArray().put(mediaId))
            replyTo?.let { body.put("replyToId", it.messageId) }
            val sent = JSONObject(api.send("POST", "/api/v1/conversations/${enc(chatId)}/messages", body, ok = setOf(200, 201)))
            synchronized(s) { s.localFiles[sent.getString("id")] = file.path }
            s.messages.update { list -> list.filterNot { it.id == local.id } }
            merge(chatId, listOf(sent))
        } catch (e: Exception) {
            s.messages.update { list -> list.filterNot { it.id == local.id } }
            throw e
        }
    }

    private fun queueText(chatId: String, clientId: String, content: String, replyTo: ReplyRef?): Boolean {
        val data = JSONObject().put("chatId", chatId).put("clientId", clientId).put("content", content)
        replyTo?.let { data.put("reply", JSONObject().put("messageId", it.messageId).put("senderName", it.senderName).put("preview", it.preview)) }
        return outbox?.add("message", data) == true
    }

    override suspend fun sendVoice(chatId: String, audioPath: String, durationMs: Int, replyTo: ReplyRef?, waveform: List<Int>) {
        val me = myId() ?: throw IllegalStateException("Sign in to send messages.")
        val s = state(chatId)
        val clientId = UUID.randomUUID().toString()
        val local = Message("$LOCAL$clientId", chatId, me, "You", "", System.currentTimeMillis(), true, MessageStatus.Sending, voice = VoiceNote(audioPath, durationMs, waveform), replyTo = replyTo)
        s.messages.update { it + local }
        try {
            val mediaId = (uploader ?: throw IllegalStateException("Voice messages aren't available right now.")).upload(audioPath, "audio", "message", durationMs, tag = local.id, waveform = waveform)
            val body = JSONObject().put("content", "").put("clientId", clientId).put("mediaIds", JSONArray().put(mediaId))
            replyTo?.let { body.put("replyToId", it.messageId) }
            val sent = JSONObject(api.send("POST", "/api/v1/conversations/${enc(chatId)}/messages", body, ok = setOf(200, 201)))
            s.messages.update { list -> list.filterNot { it.id == local.id } }
            merge(chatId, listOf(sent))
            runCatching { java.io.File(audioPath).delete() } // it now plays from the server copy
        } catch (e: Exception) {
            s.messages.update { list -> list.filterNot { it.id == local.id } }
            throw e
        }
    }

    private fun firstOfKind(arr: JSONArray?, kind: String): JSONObject? {
        if (arr == null) return null
        for (i in 0 until arr.length()) { val o = arr.getJSONObject(i); if (o.optString("kind", "image") == kind) return o }
        return null
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
        val delivered = synchronized(st) { deliveredUpTo(st) }
        // Our own photos open from the local copy; everyone else's from the server's temporary link.
        val photo = synchronized(state(chatId)) { state(chatId).localPhotos[id] }?.takeIf { File(it).isFile }
            ?: firstOfKind(media, "image")?.strOrNull("url")
        // All attached photos (the server allows up to four); our own first photo still opens from the local copy.
        val allPhotos = buildList {
            var first = true
            if (media != null) for (i in 0 until media.length()) {
                val item = media.getJSONObject(i)
                if (item.optString("kind", "image") != "image") continue
                (if (first) photo else item.strOrNull("url"))?.let { add(it) }
                first = false
            }
        }
        val firstMedia = firstOfKind(media, "image")
        val voice = firstOfKind(media, "audio")?.let { a -> a.strOrNull("url")?.let { VoiceNote(it, a.optInt("durationMs", 0), a.optJSONArray("waveform")?.let { w -> List(w.length()) { i -> w.optInt(i) } } ?: emptyList()) } }
        val file = firstOfKind(media, "document")?.let { d ->
            val url = d.strOrNull("url") ?: return@let null
            val local = synchronized(st) { st.localFiles[id] }?.takeIf { File(it).isFile }
            FileAttachment(d.strOrNull("fileName") ?: "document", d.optLong("sizeBytes", 0L), d.optString("contentType", "application/octet-stream"), local ?: url)
        }
        val ratio = firstMedia?.let { m -> val w = m.optInt("width", 0); val h = m.optInt("height", 0); if (w > 0 && h > 0) w.toFloat() / h else null }
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
            imagePaths = if (allPhotos.size > 1) allPhotos else emptyList(),
            imageRatio = ratio,
            voice = voice,
            timeMillis = runCatching { Instant.parse(o.getString("createdAt")).toEpochMilli() }.getOrDefault(0L),
            isMine = mine,
            status = if (mine) statusFor(o.getLong("seq"), seen, delivered) else MessageStatus.Sent,
            replyTo = reply,
            reactions = parseReactions(o.optJSONObject("reactions")),
            myReaction = o.strOrNull("myReaction")?.let(::reactionOf),
            forwarded = o.optBoolean("forwarded", false),
            edited = !o.isNull("editedAt"),
            eventId = o.strOrNull("eventId"),
            file = file,
            linkPreview = o.optJSONObject("linkPreview")?.let { l ->
                l.strOrNull("url")?.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
                    ?.let { u ->
                        val w = l.optInt("imageWidth", 0); val h = l.optInt("imageHeight", 0)
                        LinkPreview(
                            u, l.optString("host", ""), l.strOrNull("title"), l.strOrNull("description"), l.strOrNull("siteName"),
                            imageUrl = l.strOrNull("imageUrl")?.takeIf { it.startsWith("https://", true) || it.startsWith("http://", true) },
                            imageRatio = if (w > 0 && h > 0) w.toFloat() / h else null,
                        )
                    }
            },
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
