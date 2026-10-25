package com.univibe.app.data.settings

import com.univibe.app.data.net.AuthedApi
import com.univibe.app.data.net.parseStudent
import com.univibe.app.data.net.send
import kotlinx.coroutines.flow.first
import org.json.JSONObject

/** The privacy choices the server stores and enforces, so they hold on every device and for everyone else's view of you. */
data class ServerPrivacy(
    val discoverable: Boolean,
    val showOnline: Boolean,
    val readReceipts: Boolean,
    val messagePolicy: MessagePolicy,
    /** When off, other people are never told you are typing. Enforced by the server. */
    val typingIndicators: Boolean = true,
)

/** Only the fields that are set are sent. */
data class PrivacyChange(
    val discoverable: Boolean? = null,
    val showOnline: Boolean? = null,
    val readReceipts: Boolean? = null,
    val messagePolicy: MessagePolicy? = null,
    val typingIndicators: Boolean? = null,
)

fun ServerPrivacy.updated(c: PrivacyChange) = ServerPrivacy(
    c.discoverable ?: discoverable, c.showOnline ?: showOnline, c.readReceipts ?: readReceipts, c.messagePolicy ?: messagePolicy,
    c.typingIndicators ?: typingIndicators,
)

interface PrivacyRepository {
    /** False in the offline preview build: choices then only live on this device and nobody else is affected. */
    val syncedWithServer: Boolean
    suspend fun load(): ServerPrivacy
    suspend fun save(change: PrivacyChange): ServerPrivacy
    suspend fun blockedUsers(): List<BlockedUser>
    suspend fun block(id: String, name: String)
    suspend fun unblock(id: String)
}

/** Offline preview build: everything stays on the device. */
class LocalPrivacyRepository(private val settings: SettingsRepository) : PrivacyRepository {
    override val syncedWithServer = false
    private suspend fun current(): ServerPrivacy = settings.settings.first().let {
        ServerPrivacy(it.discoverable, it.showOnline, it.showReadReceipts, it.messagePolicy, it.showTyping)
    }
    override suspend fun load() = current()
    override suspend fun save(change: PrivacyChange): ServerPrivacy {
        settings.update { s ->
            s.copy(
                discoverable = change.discoverable ?: s.discoverable, showOnline = change.showOnline ?: s.showOnline,
                showReadReceipts = change.readReceipts ?: s.showReadReceipts, messagePolicy = change.messagePolicy ?: s.messagePolicy,
                showTyping = change.typingIndicators ?: s.showTyping,
            )
        }
        return current()
    }
    override suspend fun blockedUsers(): List<BlockedUser> = settings.settings.first().blocked
    override suspend fun block(id: String, name: String) {
        settings.update { s -> if (s.blocked.any { it.id == id }) s else s.copy(blocked = s.blocked + BlockedUser(id, name)) }
    }
    override suspend fun unblock(id: String) { settings.update { s -> s.copy(blocked = s.blocked.filterNot { it.id == id }) } }
}

/**
 * The server is the source of truth. After every successful call the result is copied into the local settings, which the
 * rest of the app reads (feed filtering, read-receipt display) and which keeps working offline. A failed call changes nothing.
 */
class RemotePrivacyRepository(private val api: AuthedApi, private val settings: SettingsRepository) : PrivacyRepository {
    override val syncedWithServer = true

    private fun parse(json: JSONObject) = ServerPrivacy(
        discoverable = json.optBoolean("discoverable", true),
        showOnline = json.optBoolean("showOnline", true),
        readReceipts = json.optBoolean("readReceipts", true),
        typingIndicators = json.optBoolean("typingIndicators", true),
        messagePolicy = when (json.optString("messagePermission")) {
            "followers" -> MessagePolicy.Following
            "nobody" -> MessagePolicy.Nobody
            else -> MessagePolicy.Everyone
        },
    )

    private suspend fun cache(p: ServerPrivacy): ServerPrivacy {
        settings.update { it.copy(discoverable = p.discoverable, showOnline = p.showOnline, showReadReceipts = p.readReceipts, messagePolicy = p.messagePolicy, showTyping = p.typingIndicators) }
        return p
    }

    override suspend fun load() = cache(parse(JSONObject(api.send("GET", "/api/v1/me/privacy"))))

    override suspend fun save(change: PrivacyChange): ServerPrivacy {
        val body = JSONObject()
        change.discoverable?.let { body.put("discoverable", it) }
        change.showOnline?.let { body.put("showOnline", it) }
        change.readReceipts?.let { body.put("readReceipts", it) }
        change.typingIndicators?.let { body.put("typingIndicators", it) }
        change.messagePolicy?.let {
            body.put("messagePermission", when (it) { MessagePolicy.Everyone -> "everyone"; MessagePolicy.Following -> "followers"; MessagePolicy.Nobody -> "nobody" })
        }
        return cache(parse(JSONObject(api.send("PUT", "/api/v1/me/privacy", body))))
    }

    override suspend fun blockedUsers(): List<BlockedUser> {
        val all = ArrayList<BlockedUser>()
        var cursor: String? = null
        repeat(MAX_PAGES) {
            val path = "/api/v1/me/blocked-users?limit=50" + (cursor?.let { "&cursor=" + java.net.URLEncoder.encode(it, "UTF-8") } ?: "")
            val json = JSONObject(api.send("GET", path))
            val items = json.getJSONArray("items")
            for (i in 0 until items.length()) parseStudent(items.getJSONObject(i)).let { all += BlockedUser(it.id, it.name) }
            cursor = if (json.isNull("nextCursor")) null else json.optString("nextCursor").ifEmpty { null }
            if (cursor == null) return replaceCache(all)
        }
        return replaceCache(all)
    }

    private suspend fun replaceCache(list: List<BlockedUser>): List<BlockedUser> {
        settings.update { it.copy(blocked = list) }
        return list
    }

    override suspend fun block(id: String, name: String) {
        api.send("POST", "/api/v1/users/$id/block", JSONObject(), ok = setOf(204))
        settings.update { s -> if (s.blocked.any { it.id == id }) s else s.copy(blocked = s.blocked + BlockedUser(id, name)) }
    }

    override suspend fun unblock(id: String) {
        api.send("DELETE", "/api/v1/users/$id/block", ok = setOf(204))
        settings.update { s -> s.copy(blocked = s.blocked.filterNot { it.id == id }) }
    }

    private companion object { const val MAX_PAGES = 10 }
}
