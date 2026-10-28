package com.univibe.app.data.repository

import com.univibe.app.data.campus.withCampusScope
import android.content.Context
import com.univibe.app.data.model.Community
import com.univibe.app.data.model.Student
import com.univibe.app.data.net.AuthedApi
import com.univibe.app.data.net.parseStudent
import com.univibe.app.data.net.send
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

data class SearchPostHit(val id: String, val snippet: String, val author: Student)
data class SearchEventHit(val id: String, val title: String, val venue: String, val category: String)

/** A few matches of each kind. The server limits who can be found (same university, not blocked) before this is built. */
data class SearchOverview(
    val people: List<Student>,
    val posts: List<SearchPostHit>,
    val events: List<SearchEventHit>,
    /** Community profiles only. Private communities' posts and members are never searchable by outsiders. */
    val communities: List<Community> = emptyList(),
) {
    val isEmpty: Boolean get() = people.isEmpty() && posts.isEmpty() && events.isEmpty() && communities.isEmpty()
}

const val MIN_SEARCH_LENGTH = 2

interface SearchRepository {
    suspend fun search(query: String): SearchOverview
}

class RemoteSearchRepository(private val api: AuthedApi) : SearchRepository {
    override suspend fun search(query: String): SearchOverview {
        val o = JSONObject(api.send("GET", "/api/v1/search?q=${URLEncoder.encode(query.trim(), "UTF-8")}".withCampusScope()))
        val users = o.getJSONArray("users")
        val posts = o.getJSONArray("posts")
        val events = o.getJSONArray("events")
        val communities = o.optJSONArray("communities")
        return SearchOverview(
            people = (0 until users.length()).map { parseStudent(users.getJSONObject(it)) },
            posts = (0 until posts.length()).map {
                val p = posts.getJSONObject(it)
                SearchPostHit(p.getString("id"), p.optString("snippet"), parseStudent(p.getJSONObject("author")))
            },
            events = (0 until events.length()).map {
                val e = events.getJSONObject(it)
                SearchEventHit(e.getString("id"), e.getString("title"), e.optString("venueName"), e.optString("category"))
            },
            communities = if (communities == null) emptyList() else (0 until communities.length()).map { parseCommunity(communities.getJSONObject(it)) },
        )
    }
}

/** Offline demo has no server to search; say so instead of searching sample data. */
object UnavailableSearchRepository : SearchRepository {
    override suspend fun search(query: String): SearchOverview = throw IOException("Search needs a connection to UniVibe.")
}

/** The student's last few searches, kept on this phone only. */
interface RecentSearches {
    fun all(): List<String>
    fun add(query: String)
    fun remove(query: String)
    fun clear()
}

class PrefsRecentSearches(context: Context) : RecentSearches {
    private val prefs = context.getSharedPreferences("univibe_recent_searches", Context.MODE_PRIVATE)

    override fun all(): List<String> = prefs.getString(KEY, "").orEmpty().split('\n').filter { it.isNotBlank() }

    override fun add(query: String) {
        val q = query.trim().replace('\n', ' ')
        if (q.length < MIN_SEARCH_LENGTH) return
        save((listOf(q) + all().filterNot { it.equals(q, ignoreCase = true) }).take(MAX))
    }

    override fun remove(query: String) = save(all().filterNot { it == query })
    override fun clear() = save(emptyList())
    private fun save(list: List<String>) { prefs.edit().putString(KEY, list.joinToString("\n")).apply() }

    private companion object { const val KEY = "recent"; const val MAX = 8 }
}

class InMemoryRecentSearches : RecentSearches {
    private var items = emptyList<String>()
    override fun all() = items
    override fun add(query: String) {
        val q = query.trim()
        if (q.length >= MIN_SEARCH_LENGTH) items = (listOf(q) + items.filterNot { it.equals(q, ignoreCase = true) }).take(8)
    }
    override fun remove(query: String) { items = items.filterNot { it == query } }
    override fun clear() { items = emptyList() }
}
