package com.univibe.app.data.repository

import com.univibe.app.data.model.FollowEntry
import com.univibe.app.data.model.StudentProfile
import com.univibe.app.data.net.ApiException
import com.univibe.app.data.net.AuthedApi
import com.univibe.app.data.net.parseStudent
import com.univibe.app.data.net.send
import org.json.JSONArray
import org.json.JSONObject

/** A profile save the server refused or could not complete, with a reason that is safe to show to the student. */
class ProfileSaveException(message: String) : Exception(message)

data class MyStats(val posts: Int, val followers: Int, val following: Int, val saved: Int)

/** The people side of the server: counts, other students' profiles and follows. */
interface ProfileBackend {
    suspend fun myStats(): MyStats
    /** Null when the student doesn't exist or belongs to another university. */
    suspend fun studentProfile(id: String): StudentProfile?
    suspend fun setFollowing(id: String, following: Boolean)
    suspend fun followers(): List<FollowEntry>
    suspend fun following(): List<FollowEntry>
    /** Another student's lists (the server hides blocked people and unknown students). */
    suspend fun followersOf(id: String): List<FollowEntry>
    suspend fun followingOf(id: String): List<FollowEntry>
}

class RemoteProfileBackend(private val api: AuthedApi) : ProfileBackend {
    override suspend fun myStats(): MyStats {
        val o = JSONObject(api.send("GET", "/me/stats"))
        return MyStats(o.optInt("posts"), o.optInt("followers"), o.optInt("following"), o.optInt("saved"))
    }

    override suspend fun studentProfile(id: String): StudentProfile? {
        val body = try {
            api.send("GET", "/students/$id")
        } catch (e: ApiException) {
            if (e.status == 404) return null
            throw e
        }
        val o = JSONObject(body)
        return StudentProfile(
            student = parseStudent(o.getJSONObject("student")),
            bio = o.optString("bio"),
            posts = o.optInt("posts"),
            followers = o.optInt("followers"),
            following = o.optInt("following"),
        )
    }

    override suspend fun setFollowing(id: String, following: Boolean) {
        api.send(if (following) "PUT" else "DELETE", "/students/$id/follow", ok = setOf(204))
    }

    override suspend fun followers(): List<FollowEntry> = entries(api.send("GET", "/me/followers"))
    override suspend fun following(): List<FollowEntry> = entries(api.send("GET", "/me/following"))
    override suspend fun followersOf(id: String): List<FollowEntry> = entries(api.send("GET", "/students/$id/followers"))
    override suspend fun followingOf(id: String): List<FollowEntry> = entries(api.send("GET", "/students/$id/following"))

    private fun entries(body: String): List<FollowEntry> {
        val array = JSONArray(body)
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            FollowEntry(parseStudent(o.getJSONObject("student")), o.optBoolean("isFollowing", false), o.optBoolean("isMe", false))
        }
    }
}
