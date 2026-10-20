package com.univibe.app.data.repository

import com.univibe.app.data.model.Community
import com.univibe.app.data.model.CommunityMember
import com.univibe.app.data.model.CommunityPrivacy
import com.univibe.app.data.model.CommunityRole
import com.univibe.app.data.model.MembershipState
import com.univibe.app.data.model.Post
import com.univibe.app.data.model.ReportItem
import com.univibe.app.data.model.Student
import com.univibe.app.data.model.communityCategoryKey
import com.univibe.app.data.model.communityCategoryLabel
import com.univibe.app.data.net.ApiException
import com.univibe.app.data.net.AuthedApi
import com.univibe.app.data.net.accentFor
import com.univibe.app.data.net.parseStudent
import com.univibe.app.data.net.send
import com.univibe.app.data.net.stringList
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

private val NO_CONTENT = setOf(204)
private const val V1 = "/api/v1"

private fun roleOf(value: String?) = when (value) {
    "owner" -> CommunityRole.Owner
    "moderator" -> CommunityRole.Moderator
    "member" -> CommunityRole.Member
    else -> CommunityRole.None
}

private fun roleKey(role: CommunityRole) = when (role) {
    CommunityRole.Moderator -> "moderator"
    CommunityRole.Member -> "member"
    else -> throw IllegalArgumentException("Choose Member or Moderator.")
}

private fun JSONObject.optUrl(key: String): String? = if (isNull(key)) null else optString(key).ifBlank { null }

fun parseCommunity(o: JSONObject): Community {
    val id = o.getString("id")
    val membership = MembershipState.parse(o.optString("membership"))
    return Community(
        id = id,
        name = o.getString("name"),
        description = o.optString("description"),
        members = o.optInt("memberCount", 0),
        category = communityCategoryLabel(o.optString("category")),
        accent = accentFor(id),
        role = roleOf(if (o.isNull("myRole")) null else o.optString("myRole")),
        muted = o.optBoolean("muted", false),
        rules = stringList(o.optJSONArray("rules")),
        privacy = if (o.optString("visibility") == "private") CommunityPrivacy.Private else CommunityPrivacy.Public,
        joinRequested = membership == MembershipState.Pending,
        createdLabel = o.optString("createdAt").take(10),
        membership = membership,
        avatarUrl = o.optUrl("avatarUrl"),
        coverUrl = o.optUrl("coverUrl"),
        canPost = o.optBoolean("canPost", false),
        archived = o.optString("status") == "archived",
    )
}

/**
 * Communities from the UniVibe server. The server decides who may see, join, post and moderate; this class only
 * asks and shows. Every membership change is reported to [onMembership] so the shared store (and with it the Home
 * feed, Discover, search and the community page) stays in step.
 */
class RemoteCommunityRepository(
    private val api: AuthedApi,
    private val myId: () -> String?,
    private val onMembership: (id: String, state: MembershipState, memberCount: Int) -> Unit = { _, _, _ -> },
) : CommunityRepository, MembershipActions {

    /** Join-request ids by community and student, filled by [getJoinRequests] (the server approves by request id). */
    private val requestIds = HashMap<String, MutableMap<String, String>>()

    private fun report(c: Community) = onMembership(c.id, c.effectiveMembership, c.members)

    override suspend fun getCommunities(): List<Community> {
        val out = mutableListOf<Community>()
        var cursor: String? = null
        var pages = 0
        do { // up to 200 communities; the list screen filters locally
            val page = JSONObject(api.send("GET", "$V1/communities?limit=50" + (cursor?.let { "&cursor=$it" } ?: "")))
            val items = page.getJSONArray("items")
            for (i in 0 until items.length()) out += parseCommunity(items.getJSONObject(i))
            cursor = if (page.isNull("nextCursor")) null else page.optString("nextCursor").ifBlank { null }
            pages++
        } while (cursor != null && pages < 4)
        out.forEach(::report)
        return out
    }

    /** Communities worth showing in Discover: real interest match and recent activity, never "trending". */
    suspend fun getSuggested(): List<Pair<Community, String?>> {
        val items = JSONObject(api.send("GET", "$V1/communities/suggested")).getJSONArray("items")
        return (0 until items.length()).map {
            val o = items.getJSONObject(it)
            parseCommunity(o).also(::report) to o.optString("reason").ifBlank { null }
        }
    }

    override suspend fun getCommunity(id: String): Community? = try {
        parseCommunity(JSONObject(api.send("GET", "$V1/communities/$id"))).also(::report)
    } catch (e: ApiException) {
        if (e.status == 404) null else throw e
    }

    override suspend fun join(id: String): MembershipResult = answer(api.send("PUT", "$V1/communities/$id/membership", ok = setOf(200, 202)))
    override suspend fun leave(id: String): MembershipResult = answer(api.send("DELETE", "$V1/communities/$id/membership"))

    private fun answer(body: String): MembershipResult {
        val o = JSONObject(body)
        val result = MembershipResult(MembershipState.parse(o.optString("membership")), o.optInt("memberCount", 0))
        onMembership(o.getString("communityId"), result.state, result.memberCount)
        return result
    }

    override suspend fun setMembership(id: String, join: Boolean) { if (join) join(id) else leave(id) }

    override suspend fun setMuted(id: String, muted: Boolean) {
        api.send(if (muted) "PUT" else "DELETE", "$V1/communities/$id/mute", ok = NO_CONTENT)
    }

    /** Community-level report queues don't exist on the server: reports go to UniVibe's own moderation queue. */
    override suspend fun getReports(id: String): List<ReportItem> = emptyList()
    override suspend fun resolveReport(communityId: String, reportId: String, removePost: Boolean) {
        throw UnsupportedOperationException("Reports are reviewed by UniVibe moderators.")
    }

    override suspend fun createCommunity(
        name: String, description: String, category: String, accent: Int, rules: List<String>, privacy: CommunityPrivacy, clientKey: String?,
    ): String {
        val body = JSONObject()
            .put("name", name.trim())
            .put("description", description.trim())
            .put("category", communityCategoryKey(category))
            .put("visibility", if (privacy == CommunityPrivacy.Private) "private" else "public")
            .put("rules", JSONArray(rules.map { it.trim() }.filter { it.isNotEmpty() }))
            // One key per form: a retried tap returns the community the first attempt made instead of a duplicate.
            .put("clientKey", (clientKey ?: UUID.randomUUID().toString()).replace("-", "_"))
        val created = parseCommunity(JSONObject(api.send("POST", "$V1/communities", body, setOf(200, 201))))
        report(created)
        return created.id
    }

    override suspend fun updateCommunity(id: String, description: String, category: String, accent: Int, rules: List<String>, privacy: CommunityPrivacy) {
        val body = JSONObject()
            .put("description", description.trim())
            .put("category", communityCategoryKey(category))
            .put("visibility", if (privacy == CommunityPrivacy.Private) "private" else "public")
            .put("rules", JSONArray(rules.map { it.trim() }.filter { it.isNotEmpty() }))
        report(parseCommunity(JSONObject(api.send("PATCH", "$V1/communities/$id", body))))
    }

    override suspend fun deleteCommunity(id: String) {
        val name = getCommunity(id)?.name ?: return
        // POST rather than DELETE-with-a-body, which some HTTP clients cannot send.
        api.send("POST", "$V1/communities/$id/delete", JSONObject().put("confirmName", name), NO_CONTENT)
        onMembership(id, MembershipState.Unavailable, 0)
    }

    override suspend fun getMembers(id: String): List<CommunityMember> {
        val me = myId()
        val items = JSONObject(api.send("GET", "$V1/communities/$id/members?limit=50")).getJSONArray("items")
        return (0 until items.length()).map {
            val o = items.getJSONObject(it)
            val student = parseStudent(o.getJSONObject("user"))
            CommunityMember(student, roleOf(o.optString("role")), isMe = student.id == me)
        }
    }

    override suspend fun setMemberRole(id: String, studentId: String, role: CommunityRole) {
        api.send("PATCH", "$V1/communities/$id/members/$studentId/role", JSONObject().put("role", roleKey(role)))
    }

    override suspend fun removeMember(id: String, studentId: String) {
        api.send("DELETE", "$V1/communities/$id/members/$studentId", ok = NO_CONTENT)
    }

    override suspend fun getJoinRequests(id: String): List<Student> {
        val items = JSONObject(api.send("GET", "$V1/communities/$id/requests?limit=50")).getJSONArray("items")
        val ids = requestIds.getOrPut(id) { HashMap() }
        ids.clear()
        return (0 until items.length()).map {
            val o = items.getJSONObject(it)
            val student = parseStudent(o.getJSONObject("user"))
            ids[student.id] = o.getString("id")
            student
        }
    }

    override suspend fun resolveJoinRequest(id: String, studentId: String, approve: Boolean) {
        val requestId = requestIds[id]?.get(studentId) ?: run { getJoinRequests(id); requestIds[id]?.get(studentId) }
            ?: throw ApiException(404, "not_found", "That request is no longer pending.")
        api.send("POST", "$V1/communities/$id/requests/$requestId/${if (approve) "approve" else "reject"}")
        requestIds[id]?.remove(studentId)
    }

    override suspend fun getPosts(id: String): List<Post> {
        val items = JSONObject(api.send("GET", "$V1/communities/$id/posts?limit=30")).getJSONArray("items")
        return (0 until items.length()).map { parsePost(items.getJSONObject(it)) }
    }
}
