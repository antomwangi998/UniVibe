package com.univibe.app.data.repository

import com.univibe.app.data.model.Comment
import com.univibe.app.data.net.ApiException
import com.univibe.app.data.net.AuthedApi
import com.univibe.app.data.net.Outbox
import com.univibe.app.data.net.OutboxResult
import com.univibe.app.data.net.parseStudent
import com.univibe.app.data.net.send
import com.univibe.app.data.net.timeAgo
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

private val NO_CONTENT = setOf(204)

fun parseComment(o: JSONObject): Comment = Comment(
    id = o.getString("id"),
    postId = o.getString("postId"),
    author = parseStudent(o.getJSONObject("author")),
    text = o.getString("text"),
    timeAgo = timeAgo(o.optLong("ageSeconds", 0L)),
    likeCount = o.optInt("likeCount", 0),
    likedByMe = o.optBoolean("likedByMe", false),
    isMine = o.optBoolean("isMine", false),
    parentId = if (o.isNull("parentId")) null else o.getString("parentId"),
    replyToHandle = if (o.isNull("replyToHandle")) null else "@" + o.getString("replyToHandle"),
    isPostAuthor = o.optBoolean("isPostAuthor", false),
)

/** Comments from the UniVibe server. The server decides who may comment, reply, like and delete. */
class RemoteCommentRepository(private val api: AuthedApi) : CommentRepository {
    /** Where pending deletes wait so they survive the app being closed. Set by the app container. */
    var outbox: Outbox? = null

    override fun scheduleDelete(commentId: String, notBefore: Long): Boolean =
        outbox?.add(DELETE_KIND, JSONObject().put("commentId", commentId).put("notBefore", notBefore), key = "$DELETE_KIND:$commentId") == true

    override fun cancelScheduledDelete(commentId: String) {
        val box = outbox ?: return
        box.pending(DELETE_KIND).filter { it.key == "$DELETE_KIND:$commentId" }.forEach { box.remove(it.id) }
    }

    /** Sends one saved delete. Deleting twice is harmless: a comment that is already gone counts as done. */
    suspend fun deliverQueued(entry: Outbox.Entry): OutboxResult {
        val id = entry.data.optString("commentId")
        if (id.isEmpty()) return OutboxResult.Drop
        // Still inside the undo window: leave it for the next round.
        if (System.currentTimeMillis() < entry.data.optLong("notBefore", 0L)) return OutboxResult.Retry
        return try {
            api.send("DELETE", "/comments/$id", ok = NO_CONTENT)
            OutboxResult.Done
        } catch (e: IOException) {
            OutboxResult.Retry
        } catch (e: ApiException) {
            if (e.status >= 500 || e.status == 429 || e.status == 408 || e.status == 401) OutboxResult.Retry else OutboxResult.Drop
        }
    }

    override suspend fun getComments(postId: String): List<Comment> {
        val array = JSONArray(api.send("GET", "/posts/$postId/comments"))
        return (0 until array.length()).map { parseComment(array.getJSONObject(it)) }
    }

    override suspend fun addComment(postId: String, text: String, replyToId: String?): Comment {
        val body = JSONObject().put("text", text.trim())
        if (replyToId != null) body.put("replyToId", replyToId)
        return parseComment(JSONObject(api.send("POST", "/posts/$postId/comments", body, setOf(201))))
    }

    override suspend fun setLiked(commentId: String, liked: Boolean) {
        api.send(if (liked) "PUT" else "DELETE", "/comments/$commentId/like", ok = NO_CONTENT)
    }

    override suspend fun deleteComment(commentId: String) {
        api.send("DELETE", "/comments/$commentId", ok = NO_CONTENT)
    }

    override suspend fun reportComment(commentId: String, reason: String) {
        api.send("POST", "/comments/$commentId/report", JSONObject().put("reason", reason), NO_CONTENT)
    }
}

const val DELETE_KIND = "comment-delete"
