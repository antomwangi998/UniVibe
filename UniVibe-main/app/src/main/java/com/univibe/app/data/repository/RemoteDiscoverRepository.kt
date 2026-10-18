package com.univibe.app.data.repository

import com.univibe.app.data.model.Student
import com.univibe.app.data.net.AuthedApi
import com.univibe.app.data.net.parseStudent
import com.univibe.app.data.net.send
import com.univibe.app.domain.auth.AuthUser
import org.json.JSONObject

/**
 * Discover from the UniVibe server: real students of your university who allow discovery, minus people you
 * already passed on, followed, or blocked. The server decides who is eligible; nothing is invented here.
 * "Connect" is a follow, and a pass is remembered by the server, so both survive restarts and show up in Profile.
 */
class RemoteDiscoverRepository(
    private val api: AuthedApi,
    private val account: () -> AuthUser?,
) : DiscoverRepository {

    override suspend fun getCandidates(): List<Student> {
        val me = account()
        val items = JSONObject(api.send("GET", "/api/v1/discover?limit=30")).getJSONArray("items")
        val mine = me?.interests.orEmpty()
        return (0 until items.length()).map { i ->
            val student = parseStudent(items.getJSONObject(i))
            student.copy(
                university = me?.universityName.orEmpty(),
                // The data layer computes what you have in common; the UI never guesses it.
                shared = student.interests.filter { it in mine },
                sameCourse = student.course.isNotBlank() && student.course == me?.course,
            )
        }
    }

    override suspend fun recordDecision(studentId: String, liked: Boolean) {
        api.send("POST", "/api/v1/discover/$studentId/${if (liked) "like" else "pass"}", ok = setOf(200, 204))
    }
}
