package com.univibe.app

import com.univibe.app.data.model.GroupRole
import com.univibe.app.data.model.Student
import com.univibe.app.data.net.AuthedApi
import com.univibe.app.data.net.HttpResponse
import com.univibe.app.data.repository.RemoteGroupRepository
import com.univibe.app.domain.auth.AuthResult
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteGroupRepositoryTest {
    private class Api(private val handler: (String, String, JSONObject?) -> HttpResponse) : AuthedApi {
        val calls = mutableListOf<Triple<String, String, JSONObject?>>()
        override suspend fun authedCall(method: String, path: String, body: JSONObject?): AuthResult<HttpResponse> {
            calls += Triple(method, path, body)
            return AuthResult.Success(handler(method, path, body))
        }
    }

    private val me = "u1"
    private fun person(id: String, name: String, role: String) =
        """{"id":"$id","fullName":"$name","handle":"${name.lowercase().replace(' ', '.')}","course":"Law","yearOfStudy":"2","universityName":"CUK","interests":[],"studentVerified":true,"avatarUrl":null,"role":"$role","lastReadSeq":0,"lastDeliveredSeq":0}"""
    private val view = """{"id":"g1","kind":"group","title":"Study group","createdAt":"2026-10-01T10:00:00.000Z","createdBy":"u1","description":"Notes and past papers","photoUrl":"https://cdn.test/g.jpg",
        "permissions":{"onlyAdminsCanMessage":true,"onlyAdminsCanEditInfo":false,"onlyAdminsCanAddMembers":true},"mutedUntil":null,
        "members":[${person("u1", "Me Myself", "owner")},${person("u2", "Jane Doe", "admin")},${person("u3", "Sam Ong", "member")}]}"""
    private fun student(id: String) = Student(id, "N$id", "@n$id", 0, "Law", "2", "CUK", emptyList(), true)

    private fun repo(api: Api, following: List<Student> = emptyList()) = RemoteGroupRepository(api, null, { following }) { me }

    @Test fun aGroupCarriesItsDescriptionPhotoRolesAndRules() = runBlocking {
        val g = repo(Api { _, _, _ -> HttpResponse(200, view) }).getGroup("g1")!!
        assertEquals("Study group", g.name)
        assertEquals("Notes and past papers", g.description)
        assertEquals("https://cdn.test/g.jpg", g.photoPath)
        assertEquals("Me Myself", g.createdBy)
        assertTrue(g.createdLabel.startsWith("Created "))
        assertEquals(listOf(GroupRole.Owner, GroupRole.Admin, GroupRole.Member), g.members.map { it.role })
        assertTrue(g.members.first().isMe)
        assertTrue(g.onlyAdminsCanMessage); assertFalse(g.onlyAdminsCanEditInfo); assertTrue(g.onlyAdminsCanAddMembers)
        assertTrue(g.canMessage) // I am the owner, so admins-only does not stop me
    }

    @Test fun anUnknownGroupOrADirectChatIsNull() = runBlocking {
        assertNull(repo(Api { _, _, _ -> HttpResponse(404, """{"error":"not_found","message":"Not found."}""") }).getGroup("x"))
        assertNull(repo(Api { _, _, _ -> HttpResponse(200, """{"id":"d","kind":"direct","members":[]}""") }).getGroup("d"))
    }

    @Test fun editsRolesRulesLeavingAndReportsGoToTheRealEndpoints() = runBlocking {
        val api = Api { _, _, _ -> HttpResponse(204, "") }
        val r = repo(api)
        r.updateInfo("g1", " New name ", " About us ")
        r.setPermissions("g1", true, false, true)
        r.setRole("g1", "u3", GroupRole.Admin)
        r.removeMember("g1", "u3")
        r.leave("g1")
        r.report("g1", "Harassment")
        r.report("g1", "They keep posting ads")
        val c = api.calls
        assertEquals("PUT" to "/api/v1/conversations/g1/info", c[0].first to c[0].second)
        assertEquals("New name", c[0].third!!.getString("title")); assertEquals("About us", c[0].third!!.getString("description"))
        assertEquals("/api/v1/conversations/g1/permissions", c[1].second); assertTrue(c[1].third!!.getBoolean("onlyAdminsCanMessage"))
        assertEquals("/api/v1/conversations/g1/members/u3/role", c[2].second); assertEquals("admin", c[2].third!!.getString("role"))
        assertEquals("DELETE" to "/api/v1/conversations/g1/members/u3", c[3].first to c[3].second)
        assertEquals("DELETE" to "/api/v1/conversations/g1/members/u1", c[4].first to c[4].second) // leaving removes me
        assertEquals("harassment", c[5].third!!.getString("reason"))
        assertEquals("other", c[6].third!!.getString("reason")); assertEquals("They keep posting ads", c[6].third!!.getString("details"))
    }

    @Test fun ownershipCannotBeHandedOutAsARole() {
        var failed = false
        try { runBlocking { repo(Api { _, _, _ -> HttpResponse(204, "") }).setRole("g1", "u2", GroupRole.Owner) } } catch (e: IllegalArgumentException) { failed = true }
        assertTrue(failed)
    }

    @Test fun addablePeopleAreThoseYouFollowWhoAreNotInTheGroupYet() = runBlocking {
        val addable = repo(Api { _, _, _ -> HttpResponse(200, view) }, following = listOf(student("u2"), student("u9"))).getAddable("g1")
        assertEquals(listOf("u9"), addable.map { it.id })
    }

    @Test fun creatingAGroupSendsItsMembersThenSavesTheDescription() = runBlocking {
        val api = Api { method, path, _ -> if (method == "POST") HttpResponse(201, """{"id":"g9"}""") else HttpResponse(204, "") }
        val id = repo(api).createGroup("Hikers", "Weekend trips", null, listOf("u2", "u3"))
        assertEquals("g9", id)
        assertEquals("group", api.calls[0].third!!.getString("type"))
        assertEquals(2, api.calls[0].third!!.getJSONArray("memberIds").length())
        assertEquals("/api/v1/conversations/g9/info", api.calls[1].second)
        assertEquals("Weekend trips", api.calls[1].third!!.getString("description"))
    }
}
