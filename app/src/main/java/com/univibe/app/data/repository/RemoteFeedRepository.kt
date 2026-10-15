package com.univibe.app.data.repository

import com.univibe.app.data.model.FeedFilter
import com.univibe.app.data.model.NewPost
import com.univibe.app.data.model.Post
import com.univibe.app.data.model.PostAudience
import com.univibe.app.data.model.Student
import com.univibe.app.data.net.AuthedApi
import com.univibe.app.data.net.accentFor
import com.univibe.app.data.net.parsePoll
import com.univibe.app.data.net.parseStudent
import com.univibe.app.data.net.send
import com.univibe.app.data.net.timeAgo
import com.univibe.app.domain.auth.AuthUser
import org.json.JSONArray
import org.json.JSONObject

private val NO_CONTENT = setOf(204)

fun parsePost(o: JSONObject, fromFollowing: Boolean = false): Post = Post(
    id = o.getString("id"),
    author = parseStudent(o.getJSONObject("author")),
    timeAgo = timeAgo(o.optLong("ageSeconds", 0L)),
    text = o.getString("text"),
    likeCount = o.optInt("likeCount", 0),
    commentCount = o.optInt("commentCount", 0),
    shareCount = o.optInt("shareCount", 0),
    likedByMe = o.optBoolean("likedByMe", false),
    savedByMe = o.optBoolean("savedByMe", false),
    fromFollowing = fromFollowing,
    audience = if (o.optString("audience") == "followers") PostAudience.Followers else PostAudience.Campus,
    poll = parsePoll(o.optJSONObject("poll")),
    edited = o.optBoolean("edited", false),
)

private fun parsePosts(body: String, fromFollowing: Boolean = false): List<Post> {
    val array = JSONArray(body)
    return (0 until array.length()).map { parsePost(array.getJSONObject(it), fromFollowing) }
}

/**
 * Posts from the UniVibe server. Text and polls only for now: photos and video need file storage on the server,
 * so [supportsMedia] is false and the app hides those buttons instead of faking them.
 */
class RemoteFeedRepository(
    private val api: AuthedApi,
    private val account: () -> AuthUser?,
) : FeedRepository {
    override val supportsMedia: Boolean = false

    override suspend fun getCurrentUser(): Student {
        val me = account() ?: throw IllegalStateException("Not signed in")
        val handle = me.handle.ifBlank { me.fullName.lowercase().filter { it.isLetterOrDigit() } }
        return Student(
            id = me.id, name = me.fullName, handle = "@$handle", age = 0, course = me.course, year = me.yearOfStudy,
            university = me.universityName, interests = me.interests, verified = me.studentVerified,
            online = true, accent = accentFor(me.id),
        )
    }

    override suspend fun getPosts(filter: FeedFilter): List<Post> {
        val following = filter == FeedFilter.Following
        return parsePosts(api.send("GET", "/posts?filter=${if (following) "following" else "campus"}"), following)
    }

    /** Stories are not built yet, so there is nothing to show (and nothing invented). */
    override suspend fun getStories(): List<Student> = emptyList()

    override suspend fun setLiked(postId: String, liked: Boolean) {
        api.send(if (liked) "PUT" else "DELETE", "/posts/$postId/like", ok = NO_CONTENT)
    }

    override suspend fun setSaved(postId: String, saved: Boolean) {
        api.send(if (saved) "PUT" else "DELETE", "/posts/$postId/save", ok = NO_CONTENT)
    }

    override suspend fun reportPost(postId: String, reason: String) {
        api.send("POST", "/posts/$postId/report", JSONObject().put("reason", reason), NO_CONTENT)
    }

    override suspend fun createPost(
        text: String,
        hasMedia: Boolean,
        imagePath: String?,
        videoPath: String?,
        pollOptions: List<String>,
    ): Post {
        if (hasMedia || imagePath != null || videoPath != null) {
            throw UnsupportedOperationException("Photos and videos can't be posted yet.")
        }
        val body = JSONObject().put("text", text.trim()).put("audience", "campus")
        if (pollOptions.isNotEmpty()) {
            body.put("poll", JSONObject().put("options", JSONArray(pollOptions.map { it.trim() })))
        }
        return parsePost(JSONObject(api.send("POST", "/posts", body, setOf(201))))
    }

    override suspend fun publishPost(post: NewPost): Post {
        if (post.imagePaths.isNotEmpty()) throw UnsupportedOperationException("Photos can't be posted yet.")
        val audience = when (post.audience) {
            PostAudience.Campus -> "campus"
            PostAudience.Followers -> "followers"
            is PostAudience.Community -> throw UnsupportedOperationException("Community posts aren't available yet.")
        }
        val body = JSONObject().put("text", post.text.trim()).put("audience", audience)
        return parsePost(JSONObject(api.send("POST", "/posts", body, setOf(201))))
    }

    override suspend fun getSavedPosts(): List<Post> = parsePosts(api.send("GET", "/posts/saved"))

    override suspend fun vote(postId: String, optionIndex: Int): Post =
        parsePost(JSONObject(api.send("POST", "/posts/$postId/vote", JSONObject().put("optionIndex", optionIndex))))

    override suspend fun editPost(postId: String, text: String): Post =
        parsePost(JSONObject(api.send("PUT", "/posts/$postId", JSONObject().put("text", text.trim()))))

    override suspend fun deletePost(postId: String) {
        api.send("DELETE", "/posts/$postId", ok = NO_CONTENT)
    }

    override suspend fun recordShare(postId: String) {
        api.send("POST", "/posts/$postId/share", ok = NO_CONTENT)
    }
}
