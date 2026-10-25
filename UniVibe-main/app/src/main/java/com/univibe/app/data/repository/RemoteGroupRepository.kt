package com.univibe.app.data.repository

import com.univibe.app.data.model.GroupInfo
import com.univibe.app.data.model.GroupMember
import com.univibe.app.data.model.GroupRole
import com.univibe.app.data.model.Student
import com.univibe.app.data.net.ApiException
import com.univibe.app.data.net.AuthedApi
import com.univibe.app.data.net.MediaUploader
import com.univibe.app.data.net.parseStudent
import com.univibe.app.data.net.send
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Groups on the real server. Every rule (who may edit, add, post, make admins) is enforced there; the app only mirrors
 * the flags it is told and reports the server's refusal when one comes back.
 */
class RemoteGroupRepository(
    private val api: AuthedApi,
    private val uploader: MediaUploader?,
    /** People the signed-in student follows: who they can add to a group. */
    private val following: suspend () -> List<Student>,
    private val myId: () -> String?,
) : GroupRepository {

    private fun enc(id: String) = URLEncoder.encode(id, "UTF-8")

    override suspend fun getGroup(chatId: String): GroupInfo? {
        val json = try {
            JSONObject(api.send("GET", "/api/v1/conversations/${enc(chatId)}"))
        } catch (e: ApiException) {
            if (e.status == 404) return null // not a member looks exactly like "no such group"
            throw e
        }
        if (json.getString("kind") != "group") return null
        return parseGroup(chatId, json)
    }

    internal fun parseGroup(chatId: String, json: JSONObject): GroupInfo {
        val me = myId()
        val arr = json.getJSONArray("members")
        val members = buildList {
            for (i in 0 until arr.length()) {
                val m = arr.getJSONObject(i)
                val role = when (m.optString("role")) { "owner" -> GroupRole.Owner; "admin" -> GroupRole.Admin; else -> GroupRole.Member }
                add(GroupMember(parseStudent(m), role, isMe = m.getString("id") == me))
            }
        }
        val perms = json.optJSONObject("permissions")
        val createdBy = json.optString("createdBy").let { id -> members.firstOrNull { it.student.id == id }?.student?.name ?: "" }
        val created = json.optString("createdAt").takeIf { it.isNotBlank() }?.let {
            runCatching { "Created " + DateTimeFormatter.ofPattern("d MMM yyyy").format(Instant.parse(it).atZone(ZoneId.systemDefault())) }.getOrNull()
        } ?: ""
        val mutedUntil = json.optString("mutedUntil").takeIf { it.isNotBlank() && it != "null" }
        return GroupInfo(
            chatId = chatId,
            name = json.optString("title").ifBlank { "Group" },
            description = json.optString("description"),
            photoPath = json.optString("photoUrl").takeIf { it.isNotBlank() && it != "null" },
            createdBy = createdBy,
            createdLabel = created,
            members = members,
            onlyAdminsCanMessage = perms?.optBoolean("onlyAdminsCanMessage", false) ?: false,
            onlyAdminsCanEditInfo = perms?.optBoolean("onlyAdminsCanEditInfo", true) ?: true,
            onlyAdminsCanAddMembers = perms?.optBoolean("onlyAdminsCanAddMembers", true) ?: true,
            muted = mutedUntil?.let { runCatching { Instant.parse(it).isAfter(Instant.now()) }.getOrDefault(false) } ?: false,
        )
    }

    override suspend fun updateInfo(chatId: String, name: String, description: String) {
        api.send("PUT", "/api/v1/conversations/${enc(chatId)}/info", JSONObject().put("title", name.trim()).put("description", description.trim()), ok = setOf(204))
    }

    override suspend fun setPhoto(chatId: String, path: String?) {
        val body = JSONObject()
        if (path == null) body.put("photoMediaId", JSONObject.NULL)
        else body.put("photoMediaId", (uploader ?: throw IllegalStateException("Photos aren't available right now.")).upload(path, "image", "group"))
        api.send("PUT", "/api/v1/conversations/${enc(chatId)}/info", body, ok = setOf(204))
    }

    override suspend fun getAddable(chatId: String): List<Student> {
        val inGroup = getGroup(chatId)?.members?.map { it.student.id }?.toSet() ?: emptySet()
        return following().filter { it.id !in inGroup }
    }

    override suspend fun addMembers(chatId: String, studentIds: List<String>) {
        api.send("POST", "/api/v1/conversations/${enc(chatId)}/members", JSONObject().put("userIds", JSONArray(studentIds)))
    }

    override suspend fun removeMember(chatId: String, studentId: String) {
        api.send("DELETE", "/api/v1/conversations/${enc(chatId)}/members/${enc(studentId)}", ok = setOf(204))
    }

    override suspend fun setRole(chatId: String, studentId: String, role: GroupRole) {
        require(role != GroupRole.Owner) { "Ownership is handed over by leaving the group." }
        api.send("PUT", "/api/v1/conversations/${enc(chatId)}/members/${enc(studentId)}/role", JSONObject().put("role", role.name.lowercase()), ok = setOf(204))
    }

    override suspend fun setPermissions(chatId: String, onlyAdminsCanMessage: Boolean, onlyAdminsCanEditInfo: Boolean, onlyAdminsCanAddMembers: Boolean) {
        val body = JSONObject().put("onlyAdminsCanMessage", onlyAdminsCanMessage).put("onlyAdminsCanEditInfo", onlyAdminsCanEditInfo)
            .put("onlyAdminsCanAddMembers", onlyAdminsCanAddMembers)
        api.send("PUT", "/api/v1/conversations/${enc(chatId)}/permissions", body, ok = setOf(204))
    }

    override suspend fun leave(chatId: String) {
        val me = myId() ?: throw IllegalStateException("Sign in first.")
        api.send("DELETE", "/api/v1/conversations/${enc(chatId)}/members/${enc(me)}", ok = setOf(204))
    }

    override suspend fun report(chatId: String, reason: String) {
        val key = reason.trim().lowercase().replace(' ', '_')
        val known = key in REPORT_REASONS
        val body = JSONObject().put("reason", if (known) key else "other")
        if (!known) body.put("details", reason.take(500))
        api.send("POST", "/api/v1/conversations/${enc(chatId)}/report", body, ok = setOf(204))
    }

    override suspend fun getContacts(): List<Student> = following()

    override suspend fun createGroup(name: String, description: String, photoPath: String?, memberIds: List<String>): String {
        val body = JSONObject().put("type", "group").put("title", name.trim()).put("memberIds", JSONArray(memberIds))
        val id = JSONObject(api.send("POST", "/api/v1/conversations", body, ok = setOf(200, 201))).getString("id")
        // The group exists now. Description and photo are saved next; if that part fails the group is still there to edit.
        if (description.isNotBlank() || photoPath != null) {
            try {
                val info = JSONObject()
                if (description.isNotBlank()) info.put("description", description.trim())
                if (photoPath != null) info.put("photoMediaId", (uploader ?: throw IllegalStateException("no uploader")).upload(photoPath, "image", "group"))
                api.send("PUT", "/api/v1/conversations/${enc(id)}/info", info, ok = setOf(204))
            } catch (_: Exception) { /* open the group info to try again */ }
        }
        return id
    }

    private companion object {
        val REPORT_REASONS = setOf("spam", "harassment", "hate", "sexual", "violence", "self_harm", "impersonation", "misinformation", "other")
    }
}
