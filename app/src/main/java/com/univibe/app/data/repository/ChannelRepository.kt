package com.univibe.app.data.repository

import com.univibe.app.data.model.Channel
import com.univibe.app.data.model.ChannelAccess
import com.univibe.app.data.model.ChannelAttachment
import com.univibe.app.data.model.ChannelAuthor
import com.univibe.app.data.model.LinkPreview
import com.univibe.app.data.model.ChannelDirectory
import com.univibe.app.data.model.ChannelDraft
import com.univibe.app.data.model.ChannelGroup
import com.univibe.app.data.model.ChannelMessage
import com.univibe.app.data.model.ChannelType
import com.univibe.app.data.model.MessagePage
import com.univibe.app.data.model.ReplyPreview
import com.univibe.app.data.net.ApiException
import com.univibe.app.data.net.AuthedApi
import com.univibe.app.data.net.ChatEvent
import com.univibe.app.data.net.LiveConnection
import com.univibe.app.data.net.MediaUploader
import com.univibe.app.data.net.accentFor
import com.univibe.app.data.net.parsePoll
import com.univibe.app.data.net.send
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/** A reaction toggle's answer: the new counts, and which of them are this student's. */
data class ReactionState(val reactions: Map<String, Int>, val mine: Set<String>)

/** One page of a thread: the original message and its replies, oldest first. */
data class ThreadPage(val root: ChannelMessage, val replies: List<ChannelMessage>, val nextCursor: String?)

data class SearchFilters(val text: String = "", val pinnedOnly: Boolean = false, val withAttachments: Boolean = false)

/**
 * Discussion channels of a community. The server decides who may see, write, pin or moderate; this interface only asks.
 * A 403 or 404 from any call means access was lost (removed, suspended, channel archived or made staff-only): callers
 * close the screen rather than keep showing stale content.
 */
interface ChannelRepository {
    /** What the server pushes about channels. Empty when there is no live connection. */
    val live: Flow<ChatEvent>
    val myId: String?

    suspend fun directory(communityId: String): ChannelDirectory
    suspend fun history(communityId: String, channelId: String, cursor: String?): MessagePage
    /** Everything newer than [afterSeq], oldest first, for catching up after the socket was down. */
    suspend fun catchUp(communityId: String, channelId: String, afterSeq: Long): List<ChannelMessage>
    suspend fun send(communityId: String, channelId: String, draft: ChannelDraft): ChannelMessage
    suspend fun edit(communityId: String, channelId: String, messageId: String, title: String?, content: String): ChannelMessage
    suspend fun delete(communityId: String, channelId: String, messageId: String)
    suspend fun react(communityId: String, channelId: String, messageId: String, reaction: String, on: Boolean): ReactionState
    suspend fun pin(communityId: String, channelId: String, messageId: String, on: Boolean): ChannelMessage
    suspend fun pins(communityId: String, channelId: String): List<ChannelMessage>
    suspend fun thread(communityId: String, channelId: String, messageId: String, cursor: String?): ThreadPage
    suspend fun markRead(communityId: String, channelId: String, upToSeq: Long)
    suspend fun search(communityId: String, channelId: String, filters: SearchFilters, cursor: String?): MessagePage
    /** Marks [answerId] as the accepted answer of the question, or clears it when null. */
    suspend fun acceptAnswer(communityId: String, channelId: String, questionId: String, answerId: String?): ChannelMessage
    suspend fun createChannel(communityId: String, name: String, description: String, type: ChannelType): String
    suspend fun updateChannel(communityId: String, channelId: String, locked: Boolean? = null, archived: Boolean? = null)
    suspend fun report(messageId: String, reason: String)
    /** Votes in a channel poll (one vote per student, final). Returns the message with the new counts. */
    suspend fun vote(communityId: String, channelId: String, messageId: String, optionIndex: Int): ChannelMessage

    // Channel management (moderators; deleting is the owner's).
    suspend fun editChannel(communityId: String, channelId: String, edit: ChannelEdit)
    suspend fun deleteChannel(communityId: String, channelId: String)
    suspend fun createGroup(communityId: String, name: String)
    suspend fun renameGroup(communityId: String, groupId: String, name: String)
    suspend fun deleteGroup(communityId: String, groupId: String)
    /** The full display order: groups in order, then every channel in order with the group it sits in (null = none). */
    suspend fun reorder(communityId: String, groupOrder: List<String>, channels: List<Pair<String, String?>>)
}

/** What a moderator changed. Null fields are left as they are; [setGroup] says whether [groupId] (which may be null: "no group") applies. */
data class ChannelEdit(
    val name: String? = null,
    val description: String? = null,
    val setGroup: Boolean = false,
    val groupId: String? = null,
    val repliesEnabled: Boolean? = null,
    val allowAttachments: Boolean? = null,
    val viewRole: String? = null,
    val postRole: String? = null,
    val locked: Boolean? = null,
    val archived: Boolean? = null,
)

/** Used in offline demo mode: channels live on the server only. */
object UnavailableChannelRepository : ChannelRepository {
    override val live: Flow<ChatEvent> = emptyFlow()
    override val myId: String? = null
    private fun fail(): Nothing = throw ApiException(0, "unavailable", "Channels need a connection to UniVibe.")
    override suspend fun directory(communityId: String) = fail()
    override suspend fun history(communityId: String, channelId: String, cursor: String?) = fail()
    override suspend fun catchUp(communityId: String, channelId: String, afterSeq: Long) = fail()
    override suspend fun send(communityId: String, channelId: String, draft: ChannelDraft) = fail()
    override suspend fun edit(communityId: String, channelId: String, messageId: String, title: String?, content: String) = fail()
    override suspend fun delete(communityId: String, channelId: String, messageId: String) = fail()
    override suspend fun react(communityId: String, channelId: String, messageId: String, reaction: String, on: Boolean) = fail()
    override suspend fun pin(communityId: String, channelId: String, messageId: String, on: Boolean) = fail()
    override suspend fun pins(communityId: String, channelId: String) = fail()
    override suspend fun thread(communityId: String, channelId: String, messageId: String, cursor: String?) = fail()
    override suspend fun markRead(communityId: String, channelId: String, upToSeq: Long) = fail()
    override suspend fun search(communityId: String, channelId: String, filters: SearchFilters, cursor: String?) = fail()
    override suspend fun acceptAnswer(communityId: String, channelId: String, questionId: String, answerId: String?) = fail()
    override suspend fun createChannel(communityId: String, name: String, description: String, type: ChannelType) = fail()
    override suspend fun updateChannel(communityId: String, channelId: String, locked: Boolean?, archived: Boolean?) = fail()
    override suspend fun report(messageId: String, reason: String) = fail()
    override suspend fun vote(communityId: String, channelId: String, messageId: String, optionIndex: Int) = fail()
    override suspend fun editChannel(communityId: String, channelId: String, edit: ChannelEdit) = fail()
    override suspend fun deleteChannel(communityId: String, channelId: String) = fail()
    override suspend fun createGroup(communityId: String, name: String) = fail()
    override suspend fun renameGroup(communityId: String, groupId: String, name: String) = fail()
    override suspend fun deleteGroup(communityId: String, groupId: String) = fail()
    override suspend fun reorder(communityId: String, groupOrder: List<String>, channels: List<Pair<String, String?>>) = fail()
}

private const val V1 = "/api/v1"
private val NO_CONTENT = setOf(204)

// ------------------------------------------------------------------ parsing

private fun JSONObject.str(key: String): String? = if (isNull(key)) null else optString(key).ifBlank { null }

private fun parseAuthor(o: JSONObject?): ChannelAuthor? {
    if (o == null) return null
    val id = o.getString("id")
    return ChannelAuthor(id, o.optString("fullName"), "@" + o.optString("handle"), o.str("avatarUrl"), accentFor(id), o.optBoolean("studentVerified", false))
}

/** [mine] is this student's id; it is only used to know which reactions are theirs when the payload says so. */
fun parseChannelMessage(o: JSONObject): ChannelMessage {
    val media = o.optJSONArray("media") ?: JSONArray()
    val counts = o.optJSONObject("reactions")?.let { r -> r.keys().asSequence().associateWith { r.optInt(it, 0) } } ?: emptyMap()
    val mineArray = o.optJSONArray("myReactions")
    val replyTo = o.optJSONObject("replyTo")
    return ChannelMessage(
        id = o.getString("id"),
        channelId = o.getString("channelId"),
        seq = o.optLong("seq", 0L),
        author = parseAuthor(o.optJSONObject("author")),
        authorRole = o.str("authorRole"),
        title = o.str("title"),
        content = if (o.isNull("content")) null else o.optString("content"),
        deleted = o.optBoolean("deleted", false),
        removedByModerators = o.optBoolean("removedByModerators", false),
        createdAt = o.optString("createdAt"),
        edited = !o.isNull("editedAt"),
        pinned = o.optBoolean("pinned", false),
        replyCount = o.optInt("replyCount", 0),
        replyTo = replyTo?.let { ReplyPreview(it.getString("id"), it.str("preview"), it.optBoolean("deleted", false), it.str("authorName")) },
        acceptedAnswerId = o.str("acceptedAnswerId"),
        isAcceptedAnswer = o.optBoolean("isAcceptedAnswer", false),
        attachments = (0 until media.length()).map {
            val m = media.getJSONObject(it)
            ChannelAttachment(m.getString("id"), m.optString("kind"), m.str("url"), m.str("fileName"), if (m.isNull("sizeBytes")) null else m.optLong("sizeBytes"))
        },
        reactions = counts,
        myReactions = if (mineArray == null) emptySet() else (0 until mineArray.length()).map { mineArray.getString(it) }.toSet(),
        poll = parsePoll(o.optJSONObject("poll")),
        linkPreview = parseLinkPreview(o.optJSONObject("linkPreview")),
    )
}

/** Same rules as for chat messages: only web addresses are accepted, so the card can never open anything else. */
private fun parseLinkPreview(l: JSONObject?): LinkPreview? {
    if (l == null) return null
    val url = l.str("url")?.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) } ?: return null
    val w = l.optInt("imageWidth", 0); val h = l.optInt("imageHeight", 0)
    return LinkPreview(
        url, l.optString("host", ""), l.str("title"), l.str("description"), l.str("siteName"),
        imageUrl = l.str("imageUrl")?.takeIf { it.startsWith("https://", true) || it.startsWith("http://", true) },
        imageRatio = if (w > 0 && h > 0) w.toFloat() / h else null,
    )
}

private fun parseAccess(o: JSONObject?): ChannelAccess = if (o == null) ChannelAccess() else ChannelAccess(
    send = o.optBoolean("send"), reply = o.optBoolean("reply"), react = o.optBoolean("react"), attach = o.optBoolean("attach"),
    pin = o.optBoolean("pin"), manage = o.optBoolean("manage"), readOnlyReason = o.str("readOnlyReason"),
)

private fun parseMessages(array: JSONArray?): List<ChannelMessage> = if (array == null) emptyList() else (0 until array.length()).map { parseChannelMessage(array.getJSONObject(it)) }

private fun encode(v: String) = URLEncoder.encode(v, "UTF-8")

class RemoteChannelRepository(
    private val api: AuthedApi,
    private val socket: LiveConnection?,
    private val uploader: MediaUploader?,
    private val currentUserId: () -> String?,
) : ChannelRepository {
    override val live: Flow<ChatEvent> get() = socket?.events ?: emptyFlow()
    override val myId: String? get() = currentUserId()

    private fun base(communityId: String, channelId: String) = "$V1/communities/$communityId/channels/$channelId"

    override suspend fun directory(communityId: String): ChannelDirectory {
        val o = JSONObject(api.send("GET", "$V1/communities/$communityId/channels"))
        val groups = o.getJSONArray("groups").let { a -> (0 until a.length()).map { ChannelGroup(a.getJSONObject(it).getString("id"), a.getJSONObject(it).optString("name")) } }
        val channels = o.getJSONArray("channels").let { a ->
            (0 until a.length()).map {
                val c = a.getJSONObject(it)
                Channel(
                    id = c.getString("id"), groupId = c.str("groupId"), name = c.optString("name"), description = c.optString("description"),
                    type = ChannelType.parse(c.optString("type")), locked = c.optBoolean("locked"), archived = c.optBoolean("archived"),
                    unread = c.optInt("unreadCount"), pinnedCount = c.optInt("pinnedCount"), access = parseAccess(c.optJSONObject("permissions")),
                    viewRole = c.optString("viewRole", "members"), postRole = c.optString("postRole", "members"),
                    repliesEnabled = c.optBoolean("repliesEnabled", true), allowAttachments = c.optBoolean("allowAttachments", true),
                )
            }
        }
        return ChannelDirectory(groups, channels, o.optBoolean("canManage"), o.optBoolean("canDelete"))
    }

    override suspend fun history(communityId: String, channelId: String, cursor: String?): MessagePage {
        val o = JSONObject(api.send("GET", "${base(communityId, channelId)}/messages?limit=30" + (cursor?.let { "&cursor=${encode(it)}" } ?: "")))
        return MessagePage(parseMessages(o.getJSONArray("items")), o.str("nextCursor"), o.optLong("latestSeq", 0L))
    }

    override suspend fun catchUp(communityId: String, channelId: String, afterSeq: Long): List<ChannelMessage> {
        val out = mutableListOf<ChannelMessage>()
        var after = afterSeq
        repeat(5) { // up to 500 missed messages; older history is paged on demand
            val o = JSONObject(api.send("GET", "${base(communityId, channelId)}/messages?afterSeq=$after"))
            val items = parseMessages(o.getJSONArray("items"))
            out += items
            if (!o.optBoolean("hasMore") || items.isEmpty()) return out
            after = items.maxOf { it.seq }
        }
        return out
    }

    override suspend fun send(communityId: String, channelId: String, draft: ChannelDraft): ChannelMessage {
        // Photos are uploaded first, one after another in the order picked; the message is only sent once they are all ready.
        val mediaIds = JSONArray()
        for (path in draft.photoPaths) {
            mediaIds.put((uploader ?: throw ApiException(0, "unavailable", "Photos aren't available right now.")).upload(path, "image", "message", tag = draft.clientId))
        }
        val body = JSONObject().put("content", draft.content).put("clientId", draft.clientId).put("mediaIds", mediaIds)
        if (!draft.title.isNullOrBlank()) body.put("title", draft.title)
        if (draft.replyToId != null) body.put("replyToId", draft.replyToId)
        draft.poll?.let { p -> body.put("poll", JSONObject().put("options", JSONArray(p.options)).put("durationHours", p.durationHours)) }
        return parseChannelMessage(JSONObject(api.send("POST", "${base(communityId, channelId)}/messages", body, ok = setOf(200, 201))))
    }

    override suspend fun edit(communityId: String, channelId: String, messageId: String, title: String?, content: String): ChannelMessage {
        val body = JSONObject().put("content", content)
        if (title != null) body.put("title", title)
        return parseChannelMessage(JSONObject(api.send("PATCH", "${base(communityId, channelId)}/messages/$messageId", body)))
    }

    override suspend fun delete(communityId: String, channelId: String, messageId: String) {
        api.send("DELETE", "${base(communityId, channelId)}/messages/$messageId", ok = NO_CONTENT)
    }

    override suspend fun react(communityId: String, channelId: String, messageId: String, reaction: String, on: Boolean): ReactionState {
        val o = JSONObject(api.send(if (on) "PUT" else "DELETE", "${base(communityId, channelId)}/messages/$messageId/reactions/$reaction"))
        val counts = o.getJSONObject("reactions").let { r -> r.keys().asSequence().associateWith { r.optInt(it, 0) } }
        val mine = o.optJSONArray("myReactions")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet()
        return ReactionState(counts, mine)
    }

    override suspend fun pin(communityId: String, channelId: String, messageId: String, on: Boolean): ChannelMessage =
        parseChannelMessage(JSONObject(api.send(if (on) "PUT" else "DELETE", "${base(communityId, channelId)}/messages/$messageId/pin")))

    override suspend fun pins(communityId: String, channelId: String): List<ChannelMessage> =
        parseMessages(JSONObject(api.send("GET", "${base(communityId, channelId)}/pins")).getJSONArray("items"))

    override suspend fun thread(communityId: String, channelId: String, messageId: String, cursor: String?): ThreadPage {
        val o = JSONObject(api.send("GET", "${base(communityId, channelId)}/messages/$messageId/thread?limit=30" + (cursor?.let { "&cursor=${encode(it)}" } ?: "")))
        return ThreadPage(parseChannelMessage(o.getJSONObject("root")), parseMessages(o.getJSONArray("replies")), o.str("nextCursor"))
    }

    override suspend fun markRead(communityId: String, channelId: String, upToSeq: Long) {
        api.send("POST", "${base(communityId, channelId)}/read", JSONObject().put("upToSeq", upToSeq))
    }

    override suspend fun search(communityId: String, channelId: String, filters: SearchFilters, cursor: String?): MessagePage {
        val q = buildString {
            append("limit=30")
            if (filters.text.isNotBlank()) append("&q=").append(encode(filters.text.trim()))
            if (filters.pinnedOnly) append("&pinned=true")
            if (filters.withAttachments) append("&hasAttachments=true")
            if (cursor != null) append("&cursor=").append(encode(cursor))
        }
        val o = JSONObject(api.send("GET", "${base(communityId, channelId)}/search?$q"))
        return MessagePage(parseMessages(o.getJSONArray("items")), o.str("nextCursor"))
    }

    override suspend fun acceptAnswer(communityId: String, channelId: String, questionId: String, answerId: String?): ChannelMessage {
        val url = "${base(communityId, channelId)}/messages/$questionId/accepted"
        val raw = if (answerId != null) api.send("PUT", url, JSONObject().put("answerId", answerId)) else api.send("DELETE", url)
        return parseChannelMessage(JSONObject(raw))
    }

    override suspend fun createChannel(communityId: String, name: String, description: String, type: ChannelType): String =
        JSONObject(api.send("POST", "$V1/communities/$communityId/channels", JSONObject().put("name", name.trim()).put("description", description.trim()).put("type", type.key), ok = setOf(201)))
            .getString("id")

    override suspend fun updateChannel(communityId: String, channelId: String, locked: Boolean?, archived: Boolean?) {
        val body = JSONObject()
        if (locked != null) body.put("locked", locked)
        if (archived != null) body.put("archived", archived)
        api.send("PATCH", base(communityId, channelId), body)
    }

    override suspend fun vote(communityId: String, channelId: String, messageId: String, optionIndex: Int): ChannelMessage =
        parseChannelMessage(JSONObject(api.send("PUT", "${base(communityId, channelId)}/messages/$messageId/vote", JSONObject().put("optionIndex", optionIndex))))

    override suspend fun editChannel(communityId: String, channelId: String, edit: ChannelEdit) {
        val body = JSONObject()
        edit.name?.let { body.put("name", it.trim()) }
        edit.description?.let { body.put("description", it.trim()) }
        if (edit.setGroup) body.put("groupId", edit.groupId ?: JSONObject.NULL)
        edit.repliesEnabled?.let { body.put("repliesEnabled", it) }
        edit.allowAttachments?.let { body.put("allowAttachments", it) }
        edit.viewRole?.let { body.put("viewRole", it) }
        edit.postRole?.let { body.put("postRole", it) }
        edit.locked?.let { body.put("locked", it) }
        edit.archived?.let { body.put("archived", it) }
        api.send("PATCH", base(communityId, channelId), body)
    }

    override suspend fun deleteChannel(communityId: String, channelId: String) {
        api.send("DELETE", base(communityId, channelId), ok = NO_CONTENT)
    }

    override suspend fun createGroup(communityId: String, name: String) {
        api.send("POST", "$V1/communities/$communityId/channel-groups", JSONObject().put("name", name.trim()), ok = setOf(201))
    }

    override suspend fun renameGroup(communityId: String, groupId: String, name: String) {
        api.send("PATCH", "$V1/communities/$communityId/channel-groups/$groupId", JSONObject().put("name", name.trim()))
    }

    override suspend fun deleteGroup(communityId: String, groupId: String) {
        api.send("DELETE", "$V1/communities/$communityId/channel-groups/$groupId", ok = NO_CONTENT)
    }

    override suspend fun reorder(communityId: String, groupOrder: List<String>, channels: List<Pair<String, String?>>) {
        val body = JSONObject()
            .put("groups", JSONArray(groupOrder))
            .put("channels", JSONArray(channels.map { (id, group) -> JSONObject().put("id", id).put("groupId", group ?: JSONObject.NULL) }))
        api.send("PUT", "$V1/communities/$communityId/channel-order", body)
    }

    override suspend fun report(messageId: String, reason: String) {
        api.send("POST", "$V1/reports", JSONObject().put("targetType", "channel_message").put("targetId", messageId).put("reason", reason).put("details", ""), ok = setOf(200, 201))
    }
}
