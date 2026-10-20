package com.univibe.app.data.net

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** True while the app is showing saved copies because the server could not be reached. The offline banner reads this. */
object OfflineState {
    private val _servedFromCache = MutableStateFlow(false)
    val servedFromCache: StateFlow<Boolean> = _servedFromCache.asStateFlow()
    internal fun set(value: Boolean) { _servedFromCache.value = value }
}

/**
 * Saved copies of what the server last sent (feed, comments, chats, events, profiles), so the app still has something to
 * show with no signal. Lives in the app's private cache folder, belongs to one signed-in account, is wiped when that
 * account changes or signs out, and is capped in size. Never holds sign-in, session or device data.
 */
class OfflineCache(private val dir: File, private val maxBytes: Long = 12L * 1024 * 1024) {
    @Volatile private var account: String? = null
    private var puts = 0

    private val marker get() = File(dir, ".account")

    /** Call when a student is signed in. A different account than last time starts from an empty cache. */
    @Synchronized
    fun useAccount(id: String) {
        if (account == id) return
        val saved = runCatching { marker.takeIf { it.exists() }?.readText() }.getOrNull()
        if (saved != id) { wipe(); runCatching { dir.mkdirs(); marker.writeText(id) } }
        account = id
    }

    /** Deliberate sign-out: forget everything. */
    @Synchronized
    fun clear() { account = null; wipe() }

    private fun wipe() { runCatching { dir.listFiles()?.forEach { it.delete() } } }

    private fun fileFor(url: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8))
        return File(dir, digest.joinToString("") { "%02x".format(it) }.take(40))
    }

    fun get(url: String): String? {
        if (account == null) return null
        return runCatching { fileFor(url).takeIf { it.exists() }?.readText() }.getOrNull()
    }

    @Synchronized
    fun put(url: String, body: String) {
        if (account == null) return
        runCatching {
            dir.mkdirs()
            fileFor(url).writeText(body)
            if (++puts % 25 == 0) trim()
        }
    }

    /** Drops the oldest copies once the folder grows past the cap. */
    private fun trim() {
        val files = dir.listFiles { f -> f.isFile && f.name != ".account" } ?: return
        var total = files.sumOf { it.length() }
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= maxBytes) break
            total -= f.length()
            f.delete()
        }
    }
}

/**
 * Wraps the real transport: a successful signed-in GET is saved, and when the network fails (or the server is waking up
 * and answers 502/503/504) the saved copy is returned instead, so screens open offline. Anything that changes data
 * (POST, PUT, PATCH, DELETE) is never answered from the cache and still fails offline as before.
 */
class CachingTransport(private val inner: HttpTransport, private val cache: OfflineCache) : HttpTransport {
    override suspend fun execute(request: HttpRequest): HttpResponse {
        val cacheable = isCacheable(request)
        val response = try {
            inner.execute(request)
        } catch (e: IOException) {
            if (cacheable) cache.get(request.url)?.let { OfflineState.set(true); return HttpResponse(200, it) }
            throw e
        }
        if (cacheable && response.status in 502..504) {
            cache.get(request.url)?.let { OfflineState.set(true); return HttpResponse(200, it) }
        }
        if (response.status < 500) OfflineState.set(false)
        if (cacheable && response.status == 200 && response.body.length <= MAX_ENTRY_CHARS) cache.put(request.url, response.body)
        return response
    }

    private fun isCacheable(request: HttpRequest): Boolean =
        request.method == "GET" &&
            request.headers.keys.any { it.equals("Authorization", ignoreCase = true) } &&
            NEVER_CACHED.none { request.url.contains(it) }

    private companion object {
        const val MAX_ENTRY_CHARS = 600_000
        /** Sign-in, sessions, security and device data are never written to disk. */
        val NEVER_CACHED = listOf("/auth/", "/me/sessions", "/me/security", "/me/devices", "/devices", "/push")
    }
}
