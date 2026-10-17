package com.univibe.app.data.repository

import com.univibe.app.data.media.PhotoStore
import com.univibe.app.data.media.VideoStore
import com.univibe.app.data.model.FeedFilter
import com.univibe.app.data.model.NewPost
import com.univibe.app.data.model.Post
import com.univibe.app.data.model.PostAudience
import com.univibe.app.data.model.Student
import com.univibe.app.data.net.AuthedApi
import com.univibe.app.data.net.MediaUploader
import com.univibe.app.data.net.accentFor
import com.univibe.app.data.net.parsePoll
import com.univibe.app.data.net.parseStudent
import com.univibe.app.data.net.send
import com.univibe.app.data.net.timeAgo
import com.univibe.app.domain.auth.AuthUser
import org.json.JSONArray
import org.json.JSONObject

private val NO_CONTENT = setOf(204)

fun parsePost(o: JSONObject, fromFollowing: Boolean = false): Post {
    // Photos and videos arrive as short-lived links; the screens load them like local files.
    val images = mutableListOf<String>()
    var video: String? = null
    val media = o.optJSONArray("media")
    if (media != null) {
        for (i in 0 until media.length()) {
            val item = media.getJSONObject(i)
            if (item.getString("kind") == "video") video = item.getString("url") else images += item.getString("url")
        }
    }
    return buildPost(o, fromFollowing, images, video)
}

private fun buildPost(o: JSONObject, fromFollowing: Boolean, images: List<String>, video: String?): Post = Post(
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
    imagePath = images.firstOrNull(),
    imagePaths = images,
    videoPath = video,
)

private fun parsePosts(body: String, fromFollowing: Boolean = false): List<Post> {
    val array = JSONArray(body)
    return (0 until array.length()).map { parsePost(array.getJSONObject(it), fromFollowing) }
}

/**
 * Posts from the UniVibe server, including photos and videos. Files go to private storage through [media];
 * without an uploader, posts with files are refused instead of faked.
 */
class RemoteFeedRepository(
    private val api: AuthedApi,
    private val media: MediaUploader? = null,
    private val account: () -> AuthUser?,
) : FeedRepository {

    override suspend fun getCurrentUser(): Student {
        val me = account() ?: throw IllegalStateException("Not signed in")
        val handle = me.handle.ifBlank { me.fullName.lowercase().filter { it.isLetterOrDigit() } }
        return Student(
            id = me.id, name = me.fullName, handle = "@$handle", age = 0, course = me.course, year = me.yearOfStudy,
            university = me.universityName, interests = me.interests, verified = me.studentVerified,
            online = true, accent = accentFor(me.id), avatarUrl = me.avatarUrl,
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
        val body = JSONObject().put("text", text.trim()).put("audience", "campus")
        if (pollOptions.isNotEmpty()) {
            body.put("poll", JSONObject().put("options", JSONArray(pollOptions.map { it.trim() })))
        }
        if (imagePath != null || videoPath != null) {
            val ids = uploadAll(listOfNotNull(imagePath).map { it to "image" } + listOfNotNull(videoPath).map { it to "video" })
            body.put("mediaIds", JSONArray(ids))
        } else if (hasMedia) {
            throw UnsupportedOperationException("Choose a photo or video to post.")
        }
        // Accept 200 and 201 as valid creation responses to match test/mocked server behaviour
        val created = parsePost(JSONObject(api.send("POST", "/posts", body, setOf(200, 201))))
        discardLocalCopies(listOfNotNull(imagePath), videoPath)
        return created
    }

    private suspend fun uploadAll(files: List<Pair<String, String>>): List<String> {
        val uploader = media ?: throw UnsupportedOperationException("Photos and videos can't be posted right now.")
        return files.map { (path, kind) -> uploader.upload(path, kind, "post") }
    }

    /** The files now live on the server, so the temporary copies on this phone can go. */
    private fun discardLocalCopies(images: List<String>, video: String?) {
        images.forEach(PhotoStore::delete)
        VideoStore.delete(video)
    }

    override suspend fun publishPost(post: NewPost): Post {
        val audience = when (post.audience) {
            PostAudience.Campus -> "campus"
            PostAudience.Followers -> "followers"
            is PostAudience.Community -> throw UnsupportedOperationException("Community posts aren't available yet.")
        }
        val body = JSONObject().put("text", post.text.trim()).put("audience", audience)
        if (post.imagePaths.isNotEmpty()) body.put("mediaIds", JSONArray(uploadAll(post.imagePaths.map { it to "image" })))
        // Accept 200 and 201 as valid creation responses to match test/mocked server behaviour
        val created = parsePost(JSONObject(api.send("POST", "/posts", body, setOf(200, 201))))
        discardLocalCopies(post.imagePaths, null)
        return created
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
