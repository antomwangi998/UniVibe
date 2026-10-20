package com.univibe.app.data.repository

import com.univibe.app.data.model.StoryTrayEntry
import com.univibe.app.data.net.AuthedApi
import com.univibe.app.data.net.ChatEvent
import com.univibe.app.data.net.LiveConnection
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
}

/** Used only in the offline preview build: no invented Stories. */
object UnavailableStoryRepository : StoryRepository {
    override suspend fun tray(): List<StoryTrayEntry> = throw IOException("Stories need a connection to the UniVibe server.")
}

class RemoteStoryRepository(
    private val api: AuthedApi,
    private val live: LiveConnection? = null,
) : StoryRepository {
    override fun changes(): Flow<Unit> = live?.events
        ?.filter { it is ChatEvent.StoryChanged || it is ChatEvent.Connected }
        ?.map { }
        ?: emptyFlow()

    override suspend fun tray(): List<StoryTrayEntry> = parseStoryTray(api.send("GET", "/api/v1/stories"))
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
