package com.univibe.app.data.net

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/** One open WebSocket. */
interface SocketConnection {
    fun send(text: String): Boolean
    fun close(code: Int, reason: String)
}

/** What a socket reports back. Called from the socket's own thread. */
interface SocketListener {
    fun onOpen()
    fun onText(text: String)
    fun onClosed(code: Int, reason: String)
    fun onFailure(error: Throwable)
}

/** Opens sockets. The real one is [OkHttpSocketFactory]; tests pass a fake. */
fun interface SocketFactory {
    fun open(url: String, listener: SocketListener): SocketConnection
}

/** The live connection as the rest of the app sees it. */
interface LiveConnection {
    val events: SharedFlow<ChatEvent>
    /** True once the server has accepted our token on the current socket. */
    val connected: StateFlow<Boolean>
    /** Sends a frame if connected. False means it was not sent (and nothing is queued). */
    fun send(frame: JSONObject): Boolean
}

/** What the server pushes. Only what the app reacts to; everything else is ignored. */
sealed interface ChatEvent {
    val conversationId: String? get() = null

    /** The socket just became ready (first connect or after a reconnect). Re-sync anything shown on screen. */
    data object Connected : ChatEvent
    data class MessageNew(override val conversationId: String) : ChatEvent
    /** Edited, unsent, reacted to, pinned, or otherwise changed. */
    data class MessageChanged(override val conversationId: String) : ChatEvent
    data class Read(override val conversationId: String, val userId: String, val upToSeq: Long) : ChatEvent
    data class Typing(override val conversationId: String, val userId: String, val typing: Boolean) : ChatEvent
    data class Presence(val userId: String, val online: Boolean) : ChatEvent
    /** A notification arrived, or was read or removed on another device. The server's count is the truth. */
    data class NotificationsChanged(val unreadCount: Int) : ChatEvent

    companion object {
        fun parse(o: JSONObject): ChatEvent? = when (o.optString("type")) {
            "message:new" -> MessageNew(o.getString("conversationId"))
            "message:updated", "message:deleted", "message:reaction", "conversation:updated" -> MessageChanged(o.getString("conversationId"))
            "message:read" -> Read(o.getString("conversationId"), o.getString("userId"), o.getLong("upToSeq"))
            "typing:update" -> Typing(o.getString("conversationId"), o.getString("userId"), o.getBoolean("typing"))
            "presence:update" -> Presence(o.getString("userId"), o.getBoolean("online"))
            "notification:created", "notification:unread" -> NotificationsChanged(o.optInt("unreadCount", 0))
            else -> null
        }
    }
}

/**
 * Keeps one authenticated WebSocket to the server open while the student is signed in: authenticates with the first
 * frame, reconnects with exponential backoff and jitter, asks for a fresh token after the server rejects one, and
 * announces [ChatEvent.Connected] so screens can re-sync what they missed. Missed events never matter for correctness:
 * the repositories re-fetch from the server (the source of truth) whenever they are nudged.
 */
class ChatSocket(
    private val url: String,
    private val factory: SocketFactory,
    /** A fresh access token, or null when signed out. [stale] is the token the server just rejected, if any. */
    private val token: suspend (stale: String?) -> String?,
    private val scope: CoroutineScope,
    private val random: Random = Random.Default,
    private val minBackoffMs: Long = 1_000,
    private val maxBackoffMs: Long = 30_000,
) : LiveConnection {

    private val _events = MutableSharedFlow<ChatEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<ChatEvent> = _events.asSharedFlow()

    private val _connected = MutableStateFlow(false)
    override val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private var job: Job? = null
    @Volatile private var connection: SocketConnection? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { run() }
    }

    /** Signed out (or the app is shutting down): close the socket and stop reconnecting. */
    fun stop() {
        job?.cancel()
        job = null
        connection?.close(1000, "signed out")
        connection = null
        _connected.value = false
    }

    override fun send(frame: JSONObject): Boolean {
        if (!_connected.value) return false
        return connection?.send(frame.toString()) == true
    }

    private class Outcome(val authenticated: Boolean, val rejected: Boolean)

    private suspend fun run() {
        var stale: String? = null
        var failures = 0
        while (currentCoroutineContext().isActive) {
            val jwt = token(stale)
            if (jwt == null) { delay(SIGNED_OUT_RETRY_MS); continue }
            val outcome = connectOnce(jwt)
            _connected.value = false
            connection = null
            stale = if (outcome.rejected) jwt else null
            failures = if (outcome.authenticated) 1 else failures + 1
            delay(backoff(failures))
        }
    }

    private suspend fun connectOnce(jwt: String): Outcome {
        val opened = CompletableDeferred<Unit>()
        val closed = CompletableDeferred<Int>() // close code; -1 for a failure with no code
        val authenticated = AtomicBoolean(false)
        val listener = object : SocketListener {
            override fun onOpen() { opened.complete(Unit) }
            override fun onText(text: String) {
                val json = runCatching { JSONObject(text) }.getOrNull() ?: return
                if (json.optString("type") == "auth:ok") {
                    authenticated.set(true)
                    _connected.value = true
                    _events.tryEmit(ChatEvent.Connected)
                    return
                }
                runCatching { ChatEvent.parse(json) }.getOrNull()?.let { _events.tryEmit(it) }
            }
            override fun onClosed(code: Int, reason: String) { closed.complete(code); opened.complete(Unit) }
            override fun onFailure(error: Throwable) { closed.complete(-1); opened.complete(Unit) }
        }
        val conn = factory.open(url, listener)
        connection = conn
        try {
            val ready = withTimeoutOrNull(OPEN_TIMEOUT_MS) { opened.await() } != null
            if (!ready || closed.isCompleted) return Outcome(authenticated = false, rejected = false)
            // The token goes in the first frame, never in the URL (URLs end up in server logs).
            conn.send(JSONObject().put("type", "auth").put("token", jwt).toString())
            val code = closed.await()
            return Outcome(authenticated.get(), rejected = code == AUTH_REJECTED)
        } finally {
            conn.close(1000, "done")
        }
    }

    /** "Equal jitter": between half and all of 1s, 2s, 4s ... capped at [maxBackoffMs]. */
    private fun backoff(failures: Int): Long {
        val base = minOf(maxBackoffMs, minBackoffMs shl (failures - 1).coerceIn(0, 10))
        return base / 2 + random.nextLong(base / 2 + 1)
    }

    private companion object {
        const val AUTH_REJECTED = 4401 // the server's close code for a missing, expired or revoked token
        const val OPEN_TIMEOUT_MS = 15_000L
        const val SIGNED_OUT_RETRY_MS = 5_000L
    }
}
