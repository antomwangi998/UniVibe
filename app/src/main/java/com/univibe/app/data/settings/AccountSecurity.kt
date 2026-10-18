package com.univibe.app.data.settings

import com.univibe.app.data.net.AuthedApi
import com.univibe.app.data.net.send
import org.json.JSONObject

/** One login, as the server reports it. [startedAt] and [lastActiveAt] are the server's ISO-8601 UTC timestamps. */
data class LoginSession(
    val id: String,
    /** A label the app sent at login (for example "Pixel 8"); empty for logins that did not send one. */
    val deviceLabel: String,
    val startedAt: String,
    val lastActiveAt: String,
    val current: Boolean,
)

/** [currentSessionId] is null when this device logged in before sessions were tracked: it must log in again to manage them. */
data class SessionList(val currentSessionId: String?, val items: List<LoginSession>)

data class StatusNotice(val id: String, val action: String, val message: String, val createdAt: String)

/** Everything here comes from GET /me/account-status. Nothing is inferred on the device. */
data class AccountStatus(
    val suspended: Boolean,
    val suspendedUntil: String?,
    val removedPosts: Int,
    val removedComments: Int,
    /** unverified | pending | verified | rejected | expired */
    val campusVerification: String,
    val notices: List<StatusNotice>,
    val appealsSupported: Boolean,
)

/** Sessions and account status. Both need the real server, so the offline preview build reports [available] = false. */
interface AccountSecurityRepository {
    val available: Boolean
    suspend fun sessions(): SessionList
    suspend fun endSession(id: String)
    /** Returns how many other logins were ended. */
    suspend fun endOtherSessions(): Int
    suspend fun accountStatus(): AccountStatus
}

object UnavailableAccountSecurityRepository : AccountSecurityRepository {
    override val available = false
    private fun nope(): Nothing = throw IllegalStateException("Needs the UniVibe server")
    override suspend fun sessions(): SessionList = nope()
    override suspend fun endSession(id: String) = nope()
    override suspend fun endOtherSessions(): Int = nope()
    override suspend fun accountStatus(): AccountStatus = nope()
}

class RemoteAccountSecurityRepository(private val api: AuthedApi) : AccountSecurityRepository {
    override val available = true

    override suspend fun sessions(): SessionList {
        val json = JSONObject(api.send("GET", "/api/v1/me/sessions"))
        val items = json.getJSONArray("items")
        return SessionList(
            currentSessionId = json.optString("currentSessionId").takeIf { it.isNotEmpty() && !json.isNull("currentSessionId") },
            items = (0 until items.length()).map {
                val o = items.getJSONObject(it)
                LoginSession(o.getString("id"), o.optString("deviceLabel"), o.optString("createdAt"), o.optString("lastActiveAt"), o.optBoolean("current"))
            },
        )
    }

    override suspend fun endSession(id: String) { api.send("DELETE", "/api/v1/me/sessions/$id", ok = setOf(204)) }

    override suspend fun endOtherSessions(): Int =
        JSONObject(api.send("POST", "/api/v1/me/sessions/revoke-others", JSONObject())).optInt("revoked", 0)

    override suspend fun accountStatus(): AccountStatus {
        val json = JSONObject(api.send("GET", "/api/v1/me/account-status"))
        val restrictions = json.optJSONArray("restrictions")
        val until = if (restrictions != null && restrictions.length() > 0) restrictions.getJSONObject(0).optString("until").ifEmpty { null } else null
        val removed = json.optJSONObject("removedContent")
        val notices = json.optJSONArray("notices")
        return AccountStatus(
            suspended = json.optString("standing") == "suspended",
            suspendedUntil = until,
            removedPosts = removed?.optInt("posts", 0) ?: 0,
            removedComments = removed?.optInt("comments", 0) ?: 0,
            campusVerification = json.optString("campusVerification", "unverified"),
            notices = (0 until (notices?.length() ?: 0)).map {
                val o = notices!!.getJSONObject(it)
                StatusNotice(o.getString("id"), o.optString("action"), o.optString("message"), o.optString("createdAt"))
            },
            appealsSupported = json.optJSONObject("appeals")?.optBoolean("supported", false) ?: false,
        )
    }
}
