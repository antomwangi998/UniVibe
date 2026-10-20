package com.univibe.app.data.net

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * What of the community data may be saved for offline use. The server decides who can read a private community, and
 * it can't reach into a saved copy, so private content is simply never saved: a member who is removed or leaves
 * can't read it back from this phone, and nothing private is left on disk after sign-out races or crashes.
 *
 * Never saved: member lists, join requests, invitations, posts of private communities (and their comments).
 * Saved as before: public posts, community profiles (name, description, counts), everything outside communities.
 */
object CommunityCachePolicy {
    private val NEVER = listOf("/members", "/requests", "/invitations", "/community-invitations")
    private val POST_ID_IN_URL = Regex("/posts/([0-9a-fA-F-]{36})")
    private const val MAX_REMEMBERED = 500
    private val COMMUNITY_AUDIENCE = Regex("\"audience\"\\s*:\\s*\"community\"")

    /** Posts seen to belong to a private community, so their comment lists are not saved either. */
    private val privatePostIds = LinkedHashSet<String>()

    /** The body to save, or null to save nothing. Pure apart from remembering private post ids. */
    @Synchronized
    fun sanitize(url: String, body: String): String? {
        if (NEVER.any { url.contains(it) }) return null
        POST_ID_IN_URL.find(url)?.groupValues?.get(1)?.let { if (it in privatePostIds) return null }
        if (!COMMUNITY_AUDIENCE.containsMatchIn(body)) return body // no community posts in here: unchanged
        return try {
            val trimmed = body.trimStart()
            when {
                trimmed.startsWith("[") -> filterArray(JSONArray(body)).toString()
                trimmed.startsWith("{") -> {
                    val o = JSONObject(body)
                    if (isUnsafePost(o)) null else { filterObject(o); o.toString() }
                }
                else -> body
            }
        } catch (e: JSONException) {
            null // unreadable: save nothing rather than something unchecked
        }
    }

    /** A post of a private community, or a community post we can't classify (a search hit has no visibility). */
    private fun isUnsafePost(o: JSONObject): Boolean {
        if (o.optString("audience") != "community") return false
        val visibility = o.optJSONObject("community")?.optString("visibility")
        val unsafe = visibility != "public"
        if (unsafe && visibility == "private") remember(o.optString("id"))
        return unsafe
    }

    private fun remember(postId: String) {
        if (postId.isBlank()) return
        privatePostIds.add(postId)
        while (privatePostIds.size > MAX_REMEMBERED) privatePostIds.remove(privatePostIds.first())
    }

    private fun filterArray(array: JSONArray): JSONArray {
        val kept = JSONArray()
        for (i in 0 until array.length()) {
            val item = array.opt(i)
            if (item is JSONObject && isUnsafePost(item)) continue
            if (item is JSONObject) filterObject(item)
            kept.put(item)
        }
        return kept
    }

    /** Filters any array of posts directly inside this object ("items", search "posts"). */
    private fun filterObject(o: JSONObject) {
        for (key in listOf("items", "posts")) {
            val arr = o.optJSONArray(key) ?: continue
            o.put(key, filterArray(arr))
        }
    }

    /** For tests. */
    @Synchronized
    internal fun forgetAll() { privatePostIds.clear() }
}
