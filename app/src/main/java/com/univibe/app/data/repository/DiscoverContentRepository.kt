package com.univibe.app.data.repository

import com.univibe.app.data.campus.withCampusScope
import com.univibe.app.data.model.FeedFilter
import com.univibe.app.data.model.Post
import com.univibe.app.data.net.AuthedApi
import com.univibe.app.data.net.send
import org.json.JSONObject

/** One page of Discover posts. [nextCursor] is null on the last page. */
data class DiscoverPage(val posts: List<Post>, val nextCursor: String?)

data class DiscoverTopic(val tag: String, val posts: Int, val authors: Int)

enum class TrendingWindow(val query: String, val label: String) {
    Day("24h", "Today"), Week("7d", "This week"),
}

/** The reasons the server may attach to a Discover post. Anything else is shown without an explanation. */
object DiscoverReason {
    fun label(code: String?): String? = when (code) {
        "following" -> "From someone you follow"
        "interests" -> "Matches your interests"
        "trending_campus" -> "Trending on your campus"
        "popular_campus" -> "Popular on your campus"
        else -> null
    }
}

/**
 * Discover's post surfaces: For You, Trending, photos, videos and topics, plus the private "not interested" signals.
 * Posts are the same [Post] the Home feed uses, so likes, saves, votes and comments go through [FeedRepository].
 * The server decides what each student may see; nothing here filters for privacy.
 */
interface DiscoverContentRepository {
    suspend fun forYou(cursor: String?): DiscoverPage
    suspend fun trending(window: TrendingWindow, cursor: String?): DiscoverPage
    suspend fun photos(cursor: String?): DiscoverPage
    suspend fun videos(cursor: String?): DiscoverPage
    suspend fun topics(): List<DiscoverTopic>
    suspend fun topicPosts(tag: String, cursor: String?): DiscoverPage
    suspend fun notInterested(postId: String)
    suspend fun hideAuthor(authorId: String)
    suspend fun muteTopic(tag: String)
}

private val NO_CONTENT = setOf(204)

class RemoteDiscoverContentRepository(private val api: AuthedApi) : DiscoverContentRepository {

    private fun cursorQuery(cursor: String?) = if (cursor == null) "" else "&cursor=$cursor"

    private suspend fun page(path: String): DiscoverPage {
        val body = JSONObject(api.send("GET", path.withCampusScope()))
        val items = body.getJSONArray("items")
        val posts = (0 until items.length()).map { i ->
            val o = items.getJSONObject(i)
            val reason = if (o.isNull("reason")) null else o.optString("reason").ifBlank { null }
            parsePost(o).copy(discoverReason = reason)
        }
        return DiscoverPage(posts, if (body.isNull("nextCursor")) null else body.optString("nextCursor").ifBlank { null })
    }

    override suspend fun forYou(cursor: String?) = page("/api/v1/discover/feed?limit=15${cursorQuery(cursor)}")
    override suspend fun trending(window: TrendingWindow, cursor: String?) =
        page("/api/v1/discover/trending?window=${window.query}&limit=15${cursorQuery(cursor)}")
    override suspend fun photos(cursor: String?) = page("/api/v1/discover/photos?limit=24${cursorQuery(cursor)}")
    override suspend fun videos(cursor: String?) = page("/api/v1/discover/videos?limit=10${cursorQuery(cursor)}")
    override suspend fun topicPosts(tag: String, cursor: String?) = page("/api/v1/discover/topics/$tag?limit=15${cursorQuery(cursor)}")

    override suspend fun topics(): List<DiscoverTopic> {
        val items = JSONObject(api.send("GET", "/api/v1/discover/topics".withCampusScope())).getJSONArray("items")
        return (0 until items.length()).map { i ->
            val o = items.getJSONObject(i)
            DiscoverTopic(o.getString("tag"), o.optInt("posts"), o.optInt("authors"))
        }
    }

    private suspend fun feedback(body: JSONObject) {
        api.send("POST", "/api/v1/discover/feedback", body, ok = NO_CONTENT)
    }

    override suspend fun notInterested(postId: String) = feedback(JSONObject().put("kind", "not_interested").put("postId", postId))
    override suspend fun hideAuthor(authorId: String) = feedback(JSONObject().put("kind", "hide_author").put("authorId", authorId))
    override suspend fun muteTopic(tag: String) = feedback(JSONObject().put("kind", "mute_topic").put("topic", tag))
}

private val HASHTAG = Regex("(?:^|[^A-Za-z0-9_])#([A-Za-z0-9_]{2,30})")

/**
 * Offline demo only (no server): builds the same surfaces from the sample feed so the screens can be explored.
 * It has no ranking signals, so it never gives a reason, and "not interested" is remembered only until the app closes.
 */
class FeedBackedDiscoverContent(private val feed: FeedRepository) : DiscoverContentRepository {
    private val hiddenPosts = mutableSetOf<String>()
    private val hiddenAuthors = mutableSetOf<String>()
    private val mutedTopics = mutableSetOf<String>()

    private fun tagsOf(p: Post) = HASHTAG.findAll(p.text).map { it.groupValues[1].lowercase() }.toSet()

    private suspend fun posts() = feed.getPosts(FeedFilter.Campus).filter {
        it.id !in hiddenPosts && it.author.id !in hiddenAuthors && tagsOf(it).none { t -> t in mutedTopics }
    }

    override suspend fun forYou(cursor: String?) = DiscoverPage(posts().sortedByDescending { it.likeCount + it.commentCount }, null)
    override suspend fun trending(window: TrendingWindow, cursor: String?) = forYou(cursor)
    override suspend fun photos(cursor: String?) = DiscoverPage(posts().filter { it.imagePaths.isNotEmpty() || it.imagePath != null }, null)
    override suspend fun videos(cursor: String?) = DiscoverPage(posts().filter { it.videoPath != null }, null)
    override suspend fun topics(): List<DiscoverTopic> =
        posts().flatMap { p -> tagsOf(p).map { it to p.author.id } }
            .groupBy({ it.first }, { it.second })
            .map { (tag, authors) -> DiscoverTopic(tag, authors.size, authors.toSet().size) }
            .filter { it.authors >= 2 && it.tag !in mutedTopics }
            .sortedByDescending { it.authors * 2 + it.posts }
    override suspend fun topicPosts(tag: String, cursor: String?) = DiscoverPage(posts().filter { tag.lowercase() in tagsOf(it) }, null)
    override suspend fun notInterested(postId: String) { hiddenPosts += postId }
    override suspend fun hideAuthor(authorId: String) { hiddenAuthors += authorId }
    override suspend fun muteTopic(tag: String) { mutedTopics += tag.lowercase() }
}
