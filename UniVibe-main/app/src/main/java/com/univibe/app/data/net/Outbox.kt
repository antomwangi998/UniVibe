package com.univibe.app.data.net

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Things the person did while offline that are sent as soon as the connection is back: chat messages, likes and saves.
 * Kept in a file so they survive closing the app, and belong to one signed-in account (another account, or a deliberate
 * sign-out, starts empty). Only actions the server can safely receive twice are queued: a message carries its own
 * client id, and a like or save states the wanted end result.
 */
class Outbox(private val file: File, private val now: () -> Long = { System.currentTimeMillis() }) {
    data class Entry(val id: String, val kind: String, val key: String?, val data: JSONObject, val createdAt: Long, val attempts: Int)

    private val lock = Any()
    private var account: String? = null
    private var entries = mutableListOf<Entry>()
    private val _count = MutableStateFlow(0)
    /** How many actions are waiting to be sent. */
    val count: StateFlow<Int> = _count.asStateFlow()

    /** Loads what this account left waiting; anything saved for a different account is discarded. */
    fun useAccount(id: String) = synchronized(lock) {
        if (account == id) return@synchronized
        account = id
        entries = load(id)
        _count.value = entries.size
        save()
    }

    /** Deliberate sign-out: forget everything. */
    fun clear() = synchronized(lock) {
        account = null
        entries = mutableListOf()
        _count.value = 0
        runCatching { file.delete() }
        Unit
    }

    /**
     * Queues an action. A new entry with the same [key] replaces the older one, so tapping like, unlike, like while offline
     * leaves just the final state. False when nobody is signed in or the queue is full: the caller then reports the failure as usual.
     */
    fun add(kind: String, data: JSONObject, key: String? = null): Boolean = synchronized(lock) {
        if (account == null) return@synchronized false
        if (key != null) entries.removeAll { it.key == key && it.kind == kind }
        if (entries.size >= MAX_ENTRIES) return@synchronized false
        entries.add(Entry(UUID.randomUUID().toString(), kind, key, data, now(), 0))
        _count.value = entries.size
        save()
        true
    }

    fun snapshot(): List<Entry> = synchronized(lock) { entries.toList() }

    fun pending(kind: String): List<Entry> = synchronized(lock) { entries.filter { it.kind == kind } }

    fun remove(id: String) = synchronized(lock) {
        if (entries.removeAll { it.id == id }) { _count.value = entries.size; save() }
    }

    fun recordAttempt(id: String) = synchronized(lock) {
        val i = entries.indexOfFirst { it.id == id }
        if (i >= 0) { entries[i] = entries[i].copy(attempts = entries[i].attempts + 1); save() }
    }

    private fun load(id: String): MutableList<Entry> = runCatching {
        if (!file.exists()) return@runCatching mutableListOf<Entry>()
        val root = JSONObject(file.readText())
        if (root.optString("account") != id) return@runCatching mutableListOf<Entry>()
        val items = root.getJSONArray("entries")
        (0 until items.length()).map { i ->
            val o = items.getJSONObject(i)
            Entry(o.getString("id"), o.getString("kind"), o.optString("key").ifEmpty { null }, o.getJSONObject("data"), o.getLong("createdAt"), o.optInt("attempts", 0))
        }.toMutableList()
    }.getOrDefault(mutableListOf())

    /** Written to a temporary file first, so a crash mid-write never leaves a half-written queue. */
    private fun save() {
        val id = account ?: return
        runCatching {
            val items = JSONArray()
            entries.forEach {
                items.put(JSONObject().put("id", it.id).put("kind", it.kind).put("key", it.key ?: "").put("data", it.data).put("createdAt", it.createdAt).put("attempts", it.attempts))
            }
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(JSONObject().put("account", id).put("entries", items).toString())
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        }
    }

    companion object {
        const val MAX_ENTRIES = 100
    }
}

enum class OutboxResult {
    /** Sent. */ Done,
    /** No connection or the server is busy: keep it and try again later. */ Retry,
    /** The server refused it for good (or it is no longer wanted): forget it. */ Drop,
}

/** Sends what the [Outbox] holds, oldest first, stopping at the first one that cannot go yet. */
class OutboxRunner(private val outbox: Outbox, private val now: () -> Long = { System.currentTimeMillis() }) {
    private val handlers = ConcurrentHashMap<String, suspend (Outbox.Entry) -> OutboxResult>()
    private val running = Mutex()

    fun register(kind: String, handler: suspend (Outbox.Entry) -> OutboxResult) { handlers[kind] = handler }

    /** Safe to call from several places at once: a second call while one is running does nothing. */
    suspend fun flush() {
        if (!running.tryLock()) return
        try {
            for (entry in outbox.snapshot()) {
                val handler = handlers[entry.kind] ?: continue
                // Old or hopeless entries are not kept forever.
                if (now() - entry.createdAt > MAX_AGE_MS || entry.attempts >= MAX_ATTEMPTS) { outbox.remove(entry.id); continue }
                val result = try { handler(entry) } catch (e: CancellationException) { throw e } catch (e: Exception) { OutboxResult.Retry }
                when (result) {
                    OutboxResult.Done, OutboxResult.Drop -> outbox.remove(entry.id)
                    OutboxResult.Retry -> { outbox.recordAttempt(entry.id); return }
                }
            }
        } finally {
            running.unlock()
        }
    }

    companion object {
        const val MAX_AGE_MS = 3L * 24 * 60 * 60 * 1000
        const val MAX_ATTEMPTS = 60
    }
}
