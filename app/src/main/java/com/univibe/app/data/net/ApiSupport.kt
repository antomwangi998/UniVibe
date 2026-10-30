package com.univibe.app.data.net

import com.univibe.app.data.model.Poll
import com.univibe.app.data.model.PollOption
import com.univibe.app.data.model.Student
import com.univibe.app.domain.auth.AuthError
import com.univibe.app.domain.auth.AuthResult
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** A request that needs a signed-in student. Implemented by the repository that holds the session. */
interface AuthedApi {
    suspend fun authedCall(method: String, path: String, body: JSONObject? = null): AuthResult<HttpResponse>

    /**
     * A usable access token for connections that cannot go through [authedCall] (the live socket), refreshed if it is
     * about to expire. Pass [stale] when the server just rejected that token. Null when signed out.
     */
    suspend fun currentAccessToken(stale: String? = null): String? = null
}

/** The server answered, but not with what the app needed. [code] is the server's error code, for example "already_voted". */
class ApiException(val status: Int, val code: String?, message: String) : Exception(message)

/**
 * Sends a request and returns the response body when the status is one of [ok]. Throws [IOException] when offline and
 * [ApiException] for anything else, so callers (view models) handle one kind of failure with a plain try/catch.
 */
suspend fun AuthedApi.send(method: String, path: String, body: JSONObject? = null, ok: Set<Int> = (200..299).toSet()): String =
    when (val result = authedCall(method, path, body)) {
        is AuthResult.Failure -> throw when (result.error) {
            AuthError.Network -> IOException("No connection")
            AuthError.SessionExpired -> ApiException(401, "unauthorized", "Your session expired")
            else -> ApiException(0, null, "The request could not be completed")
        }
        is AuthResult.Success -> {
            val response = result.value
            if (response.status in ok) {
                response.body
            } else {
                val json = runCatching { JSONObject(response.body) }.getOrNull()
                val code = json?.optString("error")?.ifEmpty { null }
                val message = json?.optString("message")?.ifEmpty { null } ?: "Request failed (${response.status})"
                throw ApiException(response.status, code, message)
            }
        }
    }

/** "now", "5m", "3h", "2d", "3w": the age the server reported, so the phone's clock never matters. */
fun timeAgo(ageSeconds: Long): String = when {
    ageSeconds < 60 -> "now"
    ageSeconds < 3_600 -> "${ageSeconds / 60}m"
    ageSeconds < 86_400 -> "${ageSeconds / 3_600}h"
    ageSeconds < 7 * 86_400 -> "${ageSeconds / 86_400}d"
    else -> "${ageSeconds / (7 * 86_400)}w"
}

/** Stable gradient colour for a student without a photo. */
fun accentFor(id: String): Int = Math.floorMod(id.hashCode(), 6)

fun stringList(array: JSONArray?): List<String> =
    if (array == null) emptyList() else (0 until array.length()).map { array.getString(it) }

fun parseStudent(o: JSONObject): Student {
    val id = o.getString("id")
    return Student(
        id = id,
        name = o.getString("fullName"),
        handle = "@" + o.getString("handle"),
        age = 0,
        course = o.optString("course"),
        year = o.optString("yearOfStudy"),
        university = o.optString("universityName"),
        universityCode = o.optString("universityCode").ifBlank { o.optString("universityId") },
        universityShort = if (o.isNull("universityShortName")) "" else o.optString("universityShortName"),
        campusName = if (o.isNull("campusName")) "" else o.optString("campusName"),
        interests = stringList(o.optJSONArray("interests")),
        verified = o.optBoolean("studentVerified", false),
        accent = accentFor(id),
        avatarUrl = if (o.isNull("avatarUrl")) null else o.optString("avatarUrl").ifEmpty { null },
        look = com.univibe.app.data.repository.parseLook(o.optJSONObject("look")),
    )
}

fun parsePoll(o: JSONObject?): Poll? {
    if (o == null) return null
    val options = o.getJSONArray("options")
    return Poll(
        options = (0 until options.length()).map { i ->
            val option = options.getJSONObject(i)
            PollOption(option.getString("text"), option.optInt("votes", 0))
        },
        myVote = if (o.isNull("myVote")) null else o.getInt("myVote"),
        // The server says how many seconds are left (0 once it has ended); a poll without the field never ends.
        endsAtMillis = if (o.has("secondsLeft")) System.currentTimeMillis() + o.optLong("secondsLeft", 0L).coerceAtLeast(0L) * 1000L else null,
    )
}
