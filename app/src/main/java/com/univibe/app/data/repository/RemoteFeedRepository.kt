package com.univibe.app.data.repository

import com.univibe.app.data.net.ApiException
import com.univibe.app.data.media.PhotoStore
import com.univibe.app.data.media.VideoStore
import com.univibe.app.data.model.FeedFilter
import com.univibe.app.data.model.NewPost
import com.univibe.app.data.model.MembershipState
import com.univibe.app.data.model.Post
import com.univibe.app.data.model.PostLimits
import com.univibe.app.data.model.PostCommunity
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
import com.univibe.app.data.net.Outbox
import com.univibe.app.data.net.OutboxResult
import java.io.IOException

private val NO_CONTENT = setOf(204)

fun parsePost(o: JSONObject, fromFollowing: Boolean = false): Post {
    // Photos and videos arrive as short-lived links; the screens load them like local files.
    val images = mutableListOf<String>()
    val ratios = mutableListOf<Float?>()
    val thumbs = mutableListOf<String?>()
    var video: String? = null
    var videoRatio: Float? = null
    val media = o.optJSONArray("media")
    if (media != null) {
        for (i in 0 until media.length()) {
            val item = media.getJSONObject(i)
            val w = item.optInt("width", 0)
            val h = item.optInt("height", 0)
            val ratio = if (w > 0 && h > 0) w.toFloat() / h else null
            if (item.getString("kind") == "video") {
                video = item.getString("url")
                videoRatio = ratio
            } else {
                images += item.getString("url")
                ratios += ratio
                thumbs += if (item.isNull("thumbUrl")) null else item.optString("thumbUrl").ifBlank { null }
            }
        }
    }
    // Thumbnails are used only when every photo of the post has one, so the lists stay in step.
    val thumbPaths = if (thumbs.isNotEmpty() && thumbs.all { it != null }) thumbs.filterNotNull() else emptyList()
    return buildPost(o, fromFollowing, images, video, ratios, videoRatio, thumbPaths)
}

private fun buildPost(
    o: JSONObject,
    fromFollowing: Boolean,
    images: List<String>,
    video: String?,
    ratios: List<Float?> = emptyList(),
    videoRatio: Float? = null,
    thumbPaths: List<String> = emptyList(),
): Post = Post(
    id = o.getString("id"),
    author = parseStudent(o.getJSONObject("author")),
    timeAgo = timeAgo(o.optLong("ageSeconds", 0L)),
    text = o.getString("text"),
    likeCount = o.optInt("likeCount", 0),
    commentCount = o.optInt("commentCount", 0),
    shareCount = o.optInt("shareCount", 0),
    likedByMe = o.optBoolean("likedByMe", false),
    savedByMe = o.optBoolean("savedByMe", false),
    repostCount = o.optInt("repostCount", 0),
    repostedByMe = o.optBoolean("repostedByMe", false),
    viewCount = o.optInt("viewCount", 0),
    fromFollowing = fromFollowing,
    audience = when (o.optString("audience")) {
        "followers" -> PostAudience.Followers
        "community" -> o.optJSONObject("community")?.let { PostAudience.Community(it.getString("id"), it.optString("name")) } ?: PostAudience.Campus
        else -> PostAudience.Campus
    },
    community = o.optJSONObject("community")?.let {
        PostCommunity(
            id = it.getString("id"), name = it.optString("name"), isPrivate = it.optString("visibility") == "private",
            memberCount = it.optInt("memberCount", 0),
            avatarUrl = if (it.isNull("avatarUrl")) null else it.optString("avatarUrl").ifBlank { null },
            membership = MembershipState.parse(it.optString("membership")),
        )
    },
    announcement = o.optBoolean("announcement", false),
    // A malformed event never breaks the whole feed; the post then simply has no event card.
    event = o.optJSONObject("event")?.let { runCatching { parseCampusEvent(it) }.getOrNull() },
    sharedEvent = o.optBoolean("sharedEvent", false),
    poll = parsePoll(o.optJSONObject("poll")),
    edited = o.optBoolean("edited", false),
    imagePath = images.firstOrNull(),
    imagePaths = images,
    videoPath = video,
    mediaRatios = ratios,
    videoRatio = videoRatio,
    thumbPaths = thumbPaths,
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

    override suspend fun getPostsPage(filter: FeedFilter, beforeId: String?, limit: Int): List<Post> {
        val following = filter == FeedFilter.Following
        val before = if (beforeId != null) "&before=$beforeId" else ""
        return parsePosts(api.send("GET", "/posts?filter=${if (following) "following" else "campus"}&limit=$limit$before"), following)
    }

    override suspend fun getPost(id: String): Post? = try {
        parsePost(JSONObject(api.send("GET", "/posts/$id")))
    } catch (e: ApiException) {
        if (e.status == 404) null else throw e
    }

    override suspend fun getPostsBy(authorId: String, limit: Int): List<Post> =
        parsePosts(api.send("GET", "/posts?author=$authorId&limit=$limit"))

    override suspend fun getSavedPostsPage(beforeId: String?, limit: Int): List<Post> {
        val before = if (beforeId != null) "&before=$beforeId" else ""
        return parsePosts(api.send("GET", "/posts/saved?limit=$limit$before"))
    }

    /** Stories are not built yet, so there is nothing to show (and nothing invented). */
    override suspend fun getStories(): List<Student> = emptyList()

    /** Where likes and saves wait when there is no connection (set by the app; unset in tests). */
    var outbox: Outbox? = null

    override suspend fun setLiked(postId: String, liked: Boolean) = toggleOrQueue("like", postId, liked)

    override suspend fun setSaved(postId: String, saved: Boolean) = toggleOrQueue("save", postId, saved)

    override suspend fun setReposted(postId: String, reposted: Boolean) = toggleOrQueue("repost", postId, reposted)

    override suspend fun recordViews(postIds: List<String>) {
        if (postIds.isEmpty()) return
        api.send("POST", "/posts/views", JSONObject().put("ids", JSONArray(postIds.take(50))), ok = NO_CONTENT)
    }

    /** A like or save says what the end result should be, so sending it later (or twice) is harmless. */
    private suspend fun toggleOrQueue(kind: String, postId: String, on: Boolean) {
        try {
            api.send(if (on) "PUT" else "DELETE", "/posts/$postId/$kind", ok = NO_CONTENT)
        } catch (e: IOException) {
            if (outbox?.add(kind, JSONObject().put("postId", postId).put("on", on), key = "$kind:$postId") != true) throw e
        }
    }

    /** Sends one queued like or save. */
    suspend fun deliverQueued(entry: Outbox.Entry): OutboxResult {
        val postId = entry.data.optString("postId")
        if (postId.isEmpty() || entry.kind !in setOf("like", "save", "repost")) return OutboxResult.Drop
        return try {
            api.send(if (entry.data.optBoolean("on")) "PUT" else "DELETE", "/posts/$postId/${entry.kind}", ok = NO_CONTENT)
            OutboxResult.Done
        } catch (e: IOException) {
            OutboxResult.Retry
        } catch (e: ApiException) {
            // Busy or expired sign-in: try again later. Anything else (the post is gone, it is hidden) will never work.
            if (e.status >= 500 || e.status == 429 || e.status == 408 || e.status == 401) OutboxResult.Retry else OutboxResult.Drop
        }
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
        pollHours: Int,
    ): Post {
        val body = JSONObject().put("text", text.trim()).put("audience", "campus")
        if (pollOptions.isNotEmpty()) {
            body.put(
                "poll",
                JSONObject()
                    .put("options", JSONArray(pollOptions.map { it.trim() }))
                    .put("durationHours", pollHours.coerceIn(1, PostLimits.MAX_POLL_HOURS)),
            )
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
            is PostAudience.Community -> "campus" // the server derives the audience from communityId
        }
        val body = JSONObject().put("text", post.text.trim()).put("audience", audience)
        // The server checks membership and posting rights again; this only says where the post goes.
        (post.audience as? PostAudience.Community)?.let { body.put("communityId", it.id) }
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
