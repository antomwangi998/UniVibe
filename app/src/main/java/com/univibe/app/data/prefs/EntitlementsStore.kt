package com.univibe.app.data.prefs

import com.univibe.app.data.repository.PremiumRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the server last said this person may use. Held in memory only and never written to disk, so editing local
 * preferences cannot grant anything: after a restart the app asks the server again, and until it answers (or when it
 * cannot) Premium extras simply do not show. The server is the only source; nothing here decides anything.
 */
object EntitlementsStore {
    private val _keys = MutableStateFlow<Set<String>>(emptySet())
    val keys: StateFlow<Set<String>> = _keys.asStateFlow()
    private var fetchedAtMillis = 0L

    fun update(list: List<String>, nowMillis: Long = System.currentTimeMillis()) {
        _keys.value = list.toSet()
        fetchedAtMillis = nowMillis
    }

    fun has(key: String): Boolean = key in _keys.value

    /** Sign-out: forget everything so the next account starts with nothing. */
    fun clear() { _keys.value = emptySet(); fetchedAtMillis = 0L }

    /** Asks the server at most once a minute (the server also allows caching for a minute). A failed ask keeps the last answer. */
    suspend fun refresh(repository: PremiumRepository, nowMillis: Long = System.currentTimeMillis(), maxAgeMillis: Long = 60_000L) {
        if (nowMillis - fetchedAtMillis < maxAgeMillis) return
        try { update(repository.entitlements(), nowMillis) } catch (_: Exception) { /* offline or signed out: keep what we had */ }
    }
}
