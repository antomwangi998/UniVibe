package com.univibe.app.data.repository

import com.univibe.app.data.model.Comment
import com.univibe.app.data.net.AuthedApi
import com.univibe.app.data.net.parseStudent
import com.univibe.app.data.net.send
import com.univibe.app.data.net.timeAgo
import org.json.JSONArray
import org.json.JSONObject

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
