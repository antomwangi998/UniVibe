package com.univibe.app.data.repository

import com.univibe.app.data.model.MyStory
import com.univibe.app.data.model.StoryItem
import com.univibe.app.data.model.StoryViewer
import com.univibe.app.data.model.StoryViewersPage
import com.univibe.app.data.model.StoryKind
import com.univibe.app.data.model.StoryTrayEntry
import com.univibe.app.data.net.AuthedApi
import com.univibe.app.data.net.ChatEvent
import com.univibe.app.data.net.LiveConnection
import com.univibe.app.data.net.MediaUploader
import com.univibe.app.data.net.send
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import org.json.JSONObject
import java.io.IOException
import java.time.Instant

/** Stories: temporary photo, video or text updates. A separate data type from Notes, shown through the same row. */
interface StoryRepository {
    /** Who has an active Story I may see (one entry per person). */
    suspend fun tray(): List<StoryTrayEntry>
    /** Fires when the server says a Story changed, and after every reconnect (so missed events are reconciled). */
    fun changes(): Flow<Unit> = emptyFlow()
    /** Publishes a Story (uploading its photo first when it has one). Returns the new Story's id. Safe to retry: [StoryDraft.clientId] makes a repeat return the first Story. */
    suspend fun publish(draft: StoryDraft): String

    /**
     * Sends a Story's photo or video to storage and returns its media id. [onProgress] gets 0..1. Kept apart from
     * [publish] so a failed publish can be retried without sending the file again.
     */
    suspend fun uploadStoryMedia(path: String, video: Boolean, durationMs: Int?, onProgress: (Float) -> Unit = {}): String =
        throw IOException("Stories need a connection to the UniVibe server.")

    /** One person's active Stories, oldest first, with fresh media links. Throws [com.univibe.app.data.net.ApiException] (404) when there is nothing this student may see. */
    suspend fun stories(authorId: String): List<StoryItem> = throw IOException("Stories need a connection to the UniVibe server.")
    /** Records that I watched a Story. Safe to repeat; the server counts one view per person. */
    suspend fun markViewed(storyId: String) {}
    /** Sets (or with null, removes) my reaction. */
    suspend fun react(storyId: String, reaction: String?): Unit = throw IOException("Stories need a connection to the UniVibe server.")
    /** Sends a reply as an ordinary chat message to the author. [clientId] makes a retry safe. */
    suspend fun reply(storyId: String, text: String, clientId: String): Unit = throw IOException("Stories need a connection to the UniVibe server.")
    suspend fun delete(storyId: String): Unit = throw IOException("Stories need a connection to the UniVibe server.")

    /** My own active Stories, newest first, with view counts and reaction totals. */
    suspend fun myStories(): List<MyStory> = throw IOException("Stories need a connection to the UniVibe server.")
    /** Who watched one of my Stories, newest first. Only the author can ask. */
    suspend fun viewers(storyId: String, cursor: String? = null): StoryViewersPage = throw IOException("Stories need a connection to the UniVibe server.")
    /** Changes who can see one of my active Stories: "campus" or "followers". */
    suspend fun setAudience(storyId: String, audience: String): Unit = throw IOException("Stories need a connection to the UniVibe server.")
}

const val STORY_TEXT_MAX = 280
const val STORY_CAPTION_MAX = 140
/** The server refuses longer video Stories. */
const val STORY_VIDEO_MAX_MS = 30_000
val STORY_BACKGROUNDS = listOf("teal", "indigo", "rose", "amber", "slate")

/** What the composer hands over. Photo or video Story: [photoPath] or [videoPath] + optional [caption]. Text Story: [text] + [background]. */
data class StoryDraft(
    val photoPath: String? = null,
    val videoPath: String? = null,
    val videoDurationMs: Int? = null,
    /** The file's media id when an earlier attempt already uploaded it, so a retry does not send it again. */
    val mediaId: String? = null,
    val caption: String = "",
    val text: String = "",
    val background: String = "teal",
    val audience: String = "campus",
    val clientId: String,
) {
    val kind get() = when { videoPath != null -> "video"; photoPath != null -> "photo"; else -> "text" }
}

/** Used only in the offline preview build: no invented Stories. */
object UnavailableStoryRepository : StoryRepository {
    override suspend fun tray(): List<StoryTrayEntry> = throw IOException("Stories need a connection to the UniVibe server.")
    override suspend fun publish(draft: StoryDraft): String = throw IOException("Stories need a connection to the UniVibe server.")
}

class RemoteStoryRepository(
    private val api: AuthedApi,
    private val live: LiveConnection? = null,
    private val media: MediaUploader? = null,
) : StoryRepository {
    override fun changes(): Flow<Unit> = live?.events
        ?.filter { it is ChatEvent.StoryChanged || it is ChatEvent.Connected }
        ?.map { }
        ?: emptyFlow()

    override suspend fun tray(): List<StoryTrayEntry> = parseStoryTray(api.send("GET", "/api/v1/stories"))

    override suspend fun publish(draft: StoryDraft): String {
        val body = JSONObject().put("audience", draft.audience).put("clientId", draft.clientId)
        if (draft.kind != "text") {
            val mediaId = draft.mediaId ?: uploadStoryMedia(draft.videoPath ?: draft.photoPath!!, draft.kind == "video", draft.videoDurationMs)
            body.put("kind", draft.kind).put("mediaId", mediaId)
            draft.caption.trim().takeIf { it.isNotEmpty() }?.let { body.put("caption", it) }
        } else {
            body.put("kind", "text").put("text", draft.text.trim()).put("background", draft.background)
        }
        return JSONObject(api.send("POST", "/api/v1/stories", body, setOf(200, 201))).getString("id")
    }

    override suspend fun uploadStoryMedia(path: String, video: Boolean, durationMs: Int?, onProgress: (Float) -> Unit): String {
        val uploader = media ?: throw IOException("Stories need a connection to the UniVibe server.")
        return uploader.upload(path, if (video) "video" else "image", "story", durationMs = durationMs, onProgress = onProgress)
    }

    override suspend fun stories(authorId: String): List<StoryItem> =
        parseStoryItems(api.send("GET", "/api/v1/users/$authorId/stories"))

    override suspend fun markViewed(storyId: String) {
        api.send("POST", "/api/v1/stories/$storyId/view", ok = setOf(200, 204))
    }

    override suspend fun react(storyId: String, reaction: String?) {
        if (reaction == null) api.send("DELETE", "/api/v1/stories/$storyId/reaction", ok = setOf(200, 204))
        else api.send("PUT", "/api/v1/stories/$storyId/reaction", JSONObject().put("reaction", reaction))
    }

    override suspend fun reply(storyId: String, text: String, clientId: String) {
        api.send("POST", "/api/v1/stories/$storyId/reply", JSONObject().put("content", text.trim()).put("clientId", clientId), setOf(200, 201))
    }

    override suspend fun delete(storyId: String) {
        api.send("DELETE", "/api/v1/stories/$storyId", ok = setOf(200, 204))
    }

    override suspend fun myStories(): List<MyStory> = parseMyStories(api.send("GET", "/api/v1/me/stories"))

    override suspend fun viewers(storyId: String, cursor: String?): StoryViewersPage {
        val query = if (cursor == null) "" else "?cursor=" + java.net.URLEncoder.encode(cursor, "UTF-8")
        return parseStoryViewers(api.send("GET", "/api/v1/stories/$storyId/viewers$query"))
    }

    override suspend fun setAudience(storyId: String, audience: String) {
        api.send("PATCH", "/api/v1/stories/$storyId", JSONObject().put("audience", audience))
    }
}

fun parseMyStories(body: String): List<MyStory> {
    val root = JSONObject(body)
    val raw = root.getJSONArray("items")
    val parsed = parseStoryItems(body)
    // parseStoryItems skips unknown kinds, so match each parsed item back to its raw row by id.
    val rawById = (0 until raw.length()).associateBy({ raw.getJSONObject(it).getString("id") }, { raw.getJSONObject(it) })
    return parsed.mapNotNull { item ->
        val o = rawById[item.id] ?: return@mapNotNull null
        val reactions = o.optJSONObject("reactions")
        MyStory(
            item = item,
            audience = o.optString("audience", "campus"),
            expiresAtMs = Instant.parse(o.getString("expiresAt")).toEpochMilli(),
            viewCount = o.optInt("viewCount", 0),
            reactions = reactions?.keys()?.asSequence()?.associateWith { reactions.optInt(it) }.orEmpty(),
        )
    }
}

fun parseStoryViewers(body: String): StoryViewersPage {
    val root = JSONObject(body)
    val items = root.getJSONArray("items")
    return StoryViewersPage(
        total = root.optInt("total", items.length()),
        nextCursor = if (root.isNull("nextCursor")) null else root.optString("nextCursor").ifEmpty { null },
        viewers = (0 until items.length()).map { i ->
            val o = items.getJSONObject(i)
            val v = o.getJSONObject("viewer")
            StoryViewer(
                id = v.getString("id"), name = v.getString("fullName"), handle = v.optString("handle"),
                avatarUrl = if (v.isNull("avatarUrl")) null else v.optString("avatarUrl").ifEmpty { null },
                viewedAtMs = Instant.parse(o.getString("viewedAt")).toEpochMilli(),
                reaction = if (o.isNull("reaction")) null else o.optString("reaction").ifEmpty { null },
            )
        },
    )
}

fun parseStoryItems(body: String): List<StoryItem> {
    val items = JSONObject(body).getJSONArray("items")
    return (0 until items.length()).mapNotNull { i ->
        val o = items.getJSONObject(i)
        val kind = when (o.optString("kind")) { "photo" -> StoryKind.Photo; "video" -> StoryKind.Video; "text" -> StoryKind.Text; else -> return@mapNotNull null }
        fun str(k: String) = if (o.isNull(k)) null else o.optString(k).ifEmpty { null }
        StoryItem(
            id = o.getString("id"), kind = kind, mediaUrl = str("mediaUrl"),
            durationMs = if (o.isNull("durationMs")) null else o.optLong("durationMs"),
            text = str("text"), caption = str("caption"), background = str("background"),
            createdAtMs = Instant.parse(o.getString("createdAt")).toEpochMilli(),
            viewedByMe = o.optBoolean("viewedByMe", false), myReaction = str("myReaction"),
        )
    }
}

fun parseStoryTray(body: String): List<StoryTrayEntry> {
    val items = JSONObject(body).getJSONArray("items")
    return (0 until items.length()).map { i ->
        val o = items.getJSONObject(i)
        val author = o.getJSONObject("author")
        StoryTrayEntry(
            authorId = author.getString("id"),
            authorName = author.getString("fullName"),
            authorHandle = author.optString("handle"),
            avatarUrl = if (author.isNull("avatarUrl")) null else author.optString("avatarUrl").ifEmpty { null },
            count = o.optInt("count", 1),
            latestAtMs = Instant.parse(o.getString("latestAt")).toEpochMilli(),
            hasUnviewed = o.optBoolean("hasUnviewed", false),
            isMine = o.optBoolean("isMine", false),
        )
    }
}
