package com.univibe.app.features.discover

import com.univibe.app.data.campus.CampusScopeStore
import kotlinx.coroutines.flow.drop
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.univibe.app.app.AppContainer
import com.univibe.app.data.model.Post
import com.univibe.app.data.repository.DiscoverContentRepository
import com.univibe.app.data.repository.DiscoverPage
import com.univibe.app.data.repository.DiscoverTopic
import com.univibe.app.data.repository.FeedRepository
import com.univibe.app.data.repository.TrendingWindow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

enum class DiscoverCategory(val label: String) {
    ForYou("For You"), Trending("Trending"), People("People"), Communities("Communities"), Photos("Photos"), Videos("Videos"), Events("Events"), Topics("Topics"),
}

/** Which list of posts. Each keeps its own posts, cursor and scroll position, so switching tabs reloads nothing. */
sealed interface ListKey {
    data object ForYou : ListKey
    data class Trending(val window: TrendingWindow) : ListKey
    data object Photos : ListKey
    data object Videos : ListKey
    data class Topic(val tag: String) : ListKey
}

data class PostListState(
    val posts: List<Post> = emptyList(),
    val nextCursor: String? = null,
    /** First load with nothing to show yet. */
    val loading: Boolean = false,
    /** Reloading while earlier posts stay on screen. */
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    /** Set only when there is nothing to show. With posts on screen a failed refresh sets [stale] instead. */
    val error: String? = null,
    /** The latest refresh failed, so what is shown may be out of date. */
    val stale: Boolean = false,
    /** Loading the next page failed; it is retried only when the student asks, never in a loop. */
    val moreFailed: Boolean = false,
    val loaded: Boolean = false,
) {
    val endReached: Boolean get() = loaded && nextCursor == null
}

data class TopicsState(
    val topics: List<DiscoverTopic> = emptyList(),
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val error: String? = null,
    val loaded: Boolean = false,
)

data class DiscoverContentState(
    val category: DiscoverCategory = DiscoverCategory.ForYou,
    val window: TrendingWindow = TrendingWindow.Day,
    val lists: Map<ListKey, PostListState> = emptyMap(),
    val topics: TopicsState = TopicsState(),
    /** The topic whose posts are open inside the Topics tab. */
    val openTopic: String? = null,
    val menuPost: Post? = null,
    val meId: String? = null,
    val notice: String? = null,
) {
    fun list(key: ListKey): PostListState = lists[key] ?: PostListState()
    val currentKey: ListKey?
        get() = when (category) {
            DiscoverCategory.ForYou -> ListKey.ForYou
            DiscoverCategory.Trending -> ListKey.Trending(window)
            DiscoverCategory.Photos -> ListKey.Photos
            DiscoverCategory.Videos -> ListKey.Videos
            DiscoverCategory.Topics -> openTopic?.let { ListKey.Topic(it) }
            DiscoverCategory.People, DiscoverCategory.Events, DiscoverCategory.Communities -> null
        }
}

private val HASHTAG = Regex("(?:^|[^A-Za-z0-9_])#([A-Za-z0-9_]{2,30})")

/** The first hashtag in a post, lower-cased the way the server normalises it. */
fun firstHashtag(post: Post): String? = HASHTAG.find(post.text)?.groupValues?.get(1)?.lowercase()

class DiscoverContentViewModel(
    private val content: DiscoverContentRepository = AppContainer.discoverContentRepository,
    private val feed: FeedRepository = AppContainer.feedRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(DiscoverContentState())
    val state: StateFlow<DiscoverContentState> = _state.asStateFlow()
    private val jobs = mutableMapOf<ListKey, Job>()
    private var topicsJob: Job? = null

    init {
        viewModelScope.launch { runCatching { feed.getCurrentUser().id }.onSuccess { id -> _state.update { it.copy(meId = id) } } }
        load(ListKey.ForYou)
        // The campus filter changed: every loaded list is for the old campuses, so drop them and reload what is on screen.
        viewModelScope.launch {
            CampusScopeStore.scope.drop(1).collect {
                _state.update { s -> s.copy(lists = emptyMap()) }
                _state.value.currentKey?.let { load(it, force = true) }
                if (_state.value.category == DiscoverCategory.Topics && _state.value.openTopic == null) loadTopics(force = true)
            }
        }
    }

    // ---- navigation inside Discover

    fun select(category: DiscoverCategory) {
        val reselected = _state.value.category == category
        _state.update { it.copy(category = category) }
        _state.value.currentKey?.let { load(it, force = reselected) }
        if (category == DiscoverCategory.Topics && _state.value.openTopic == null) loadTopics(force = reselected)
    }

    fun setWindow(window: TrendingWindow) {
        _state.update { it.copy(window = window) }
        load(ListKey.Trending(window))
    }

    fun openTopic(tag: String) {
        _state.update { it.copy(openTopic = tag) }
        load(ListKey.Topic(tag))
    }

    fun closeTopic() = _state.update { it.copy(openTopic = null) }

    // ---- loading

    private suspend fun fetch(key: ListKey, cursor: String?): DiscoverPage = when (key) {
        ListKey.ForYou -> content.forYou(cursor)
        is ListKey.Trending -> content.trending(key.window, cursor)
        ListKey.Photos -> content.photos(cursor)
        ListKey.Videos -> content.videos(cursor)
        is ListKey.Topic -> content.topicPosts(key.tag, cursor)
    }

    private fun message(e: Exception) =
        if (e is IOException) "No connection. Check your internet and try again." else "We couldn't load this right now."

    private fun update(key: ListKey, change: (PostListState) -> PostListState) =
        _state.update { s -> s.copy(lists = s.lists + (key to change(s.list(key)))) }

    /** Loads the first page unless it is already there; [force] reloads it, keeping the old posts on screen meanwhile. */
    fun load(key: ListKey, force: Boolean = false) {
        val cur = _state.value.list(key)
        if (!force && (cur.loaded || cur.loading)) return
        jobs[key]?.cancel() // an older request for the same list must not overwrite this one
        update(key) { it.copy(loading = it.posts.isEmpty(), refreshing = it.posts.isNotEmpty(), error = null, moreFailed = false) }
        jobs[key] = viewModelScope.launch {
            try {
                val page = fetch(key, null)
                update(key) { PostListState(posts = page.posts, nextCursor = page.nextCursor, loaded = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                update(key) {
                    if (it.posts.isEmpty()) it.copy(loading = false, refreshing = false, error = message(e))
                    else it.copy(loading = false, refreshing = false, stale = true)
                }
            }
        }
    }

    fun loadMore(key: ListKey) {
        val cur = _state.value.list(key)
        val cursor = cur.nextCursor ?: return
        if (cur.loading || cur.refreshing || cur.loadingMore || cur.moreFailed || cur.error != null) return
        update(key) { it.copy(loadingMore = true) }
        viewModelScope.launch {
            try {
                val page = fetch(key, cursor)
                update(key) { s ->
                    val known = s.posts.mapTo(HashSet()) { it.id }
                    s.copy(posts = s.posts + page.posts.filter { it.id !in known }, nextCursor = page.nextCursor, loadingMore = false)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                update(key) { it.copy(loadingMore = false, moreFailed = true) }
            }
        }
    }

    fun retryMore(key: ListKey) {
        update(key) { it.copy(moreFailed = false) }
        loadMore(key)
    }

    fun loadTopics(force: Boolean = false) {
        val cur = _state.value.topics
        if (!force && (cur.loaded || cur.loading)) return
        topicsJob?.cancel()
        _state.update { it.copy(topics = it.topics.copy(loading = it.topics.topics.isEmpty(), refreshing = it.topics.topics.isNotEmpty(), error = null)) }
        topicsJob = viewModelScope.launch {
            try {
                val topics = content.topics()
                _state.update { it.copy(topics = TopicsState(topics = topics, loaded = true)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(topics = it.topics.copy(loading = false, refreshing = false, error = if (it.topics.topics.isEmpty()) message(e) else null)) }
            }
        }
    }

    // ---- post actions: the same repository calls the Home feed makes, applied to every list the post appears in

    private fun updatePost(id: String, change: (Post) -> Post) = _state.update { s ->
        s.copy(lists = s.lists.mapValues { (_, l) -> l.copy(posts = l.posts.map { if (it.id == id) change(it) else it }) })
    }

    private fun findPost(id: String): Post? = _state.value.lists.values.firstNotNullOfOrNull { l -> l.posts.firstOrNull { it.id == id } }

    fun toggleLike(postId: String) {
        val target = findPost(postId) ?: return
        val nowLiked = !target.likedByMe
        updatePost(postId) { it.copy(likedByMe = nowLiked, likeCount = it.likeCount + if (nowLiked) 1 else -1) }
        viewModelScope.launch { runCatching { feed.setLiked(postId, nowLiked) } }
    }

    fun toggleSave(postId: String) {
        val target = findPost(postId) ?: return
        val nowSaved = !target.savedByMe
        updatePost(postId) { it.copy(savedByMe = nowSaved) }
        viewModelScope.launch { runCatching { feed.setSaved(postId, nowSaved) } }
    }

    fun toggleRepost(postId: String) {
        val target = findPost(postId) ?: return
        val now = !target.repostedByMe
        updatePost(postId) { it.copy(repostedByMe = now, repostCount = (it.repostCount + if (now) 1 else -1).coerceAtLeast(0)) }
        viewModelScope.launch { runCatching { feed.setReposted(postId, now) } }
    }

    fun vote(postId: String, optionIndex: Int) {
        val before = findPost(postId)?.poll ?: return
        if (before.myVote != null || optionIndex !in before.options.indices) return
        updatePost(postId) { post ->
            val poll = post.poll ?: return@updatePost post
            val options = poll.options.mapIndexed { i, o -> if (i == optionIndex) o.copy(votes = o.votes + 1) else o }
            post.copy(poll = poll.copy(options = options, myVote = optionIndex))
        }
        viewModelScope.launch {
            try { feed.vote(postId, optionIndex) } catch (e: Exception) {
                updatePost(postId) { it.copy(poll = before) }
                _state.update { it.copy(notice = "Couldn't record your vote. Please try again.") }
            }
        }
    }

    fun noteShared(postId: String) {
        updatePost(postId) { it.copy(shareCount = it.shareCount + 1) }
        viewModelScope.launch { runCatching { feed.recordShare(postId) } }
    }

    fun explain(message: String) = _state.update { it.copy(notice = message) }
    fun consumeNotice() = _state.update { it.copy(notice = null) }

    // ---- the "more" menu and private feedback

    /** Your own posts have no "not interested" or "hide" options. */
    fun openMenu(post: Post) = _state.update { if (post.author.id == it.meId) it.copy(notice = "This is your own post.") else it.copy(menuPost = post) }
    fun closeMenu() = _state.update { it.copy(menuPost = null) }

    private fun removeWhere(predicate: (Post) -> Boolean) = _state.update { s ->
        s.copy(menuPost = null, lists = s.lists.mapValues { (_, l) -> l.copy(posts = l.posts.filterNot(predicate)) })
    }

    /** Sends a feedback call; if the server did not record it, says so and re-reads what is on screen. */
    private fun sendFeedback(success: String, call: suspend () -> Unit) {
        val key = _state.value.currentKey
        viewModelScope.launch {
            try {
                call()
                _state.update { it.copy(notice = success) }
            } catch (e: Exception) {
                _state.update { it.copy(notice = "Couldn't save that. Check your connection and try again.") }
                key?.let { load(it, force = true) }
            }
        }
    }

    fun notInterested() {
        val post = _state.value.menuPost ?: return
        removeWhere { it.id == post.id }
        sendFeedback("Got it. You'll see fewer posts like this.") { content.notInterested(post.id) }
    }

    fun hideAuthor() {
        val author = _state.value.menuPost?.author ?: return
        removeWhere { it.author.id == author.id }
        sendFeedback("You won't see ${author.name} in Discover.") { content.hideAuthor(author.id) }
    }

    fun muteTopic(tag: String) {
        removeWhere { p -> HASHTAG.findAll(p.text).any { it.groupValues[1].equals(tag, ignoreCase = true) } }
        _state.update { it.copy(topics = it.topics.copy(topics = it.topics.topics.filter { t -> t.tag != tag }), openTopic = if (it.openTopic == tag) null else it.openTopic) }
        sendFeedback("#$tag muted in Discover.") { content.muteTopic(tag) }
    }

    fun report(reason: String) {
        val post = _state.value.menuPost ?: return
        viewModelScope.launch { runCatching { feed.reportPost(post.id, reason) } }
        _state.update { it.copy(menuPost = null, notice = "Thanks. We'll review this post.") }
    }
}
