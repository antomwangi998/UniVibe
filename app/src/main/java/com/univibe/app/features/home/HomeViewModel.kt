package com.univibe.app.features.home

import com.univibe.app.data.campus.CampusScopeStore
import kotlinx.coroutines.flow.drop
import com.univibe.app.data.repository.DiscoverRepository
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.univibe.app.app.AppContainer
import com.univibe.app.data.media.PhotoStore
import com.univibe.app.data.net.ApiException
import java.io.IOException
import com.univibe.app.data.media.VideoStore
import com.univibe.app.data.model.FeedFilter
import com.univibe.app.data.settings.PrivacyRepository
import com.univibe.app.data.settings.SettingsRepository
import com.univibe.app.data.model.Post
import com.univibe.app.data.model.RsvpState
import com.univibe.app.data.repository.EventRepository
import com.univibe.app.data.model.PostLimits
import com.univibe.app.data.model.Student
import com.univibe.app.data.repository.FeedRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import com.univibe.app.ui.components.UNDO_WINDOW_MS

const val MAX_POST_LENGTH = PostLimits.MAX_TEXT

/** Two rows of six: one early in the feed, one much further down. */
const val SUGGESTION_LIMIT = 12

data class HomeUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val error: String? = null,
    val filter: FeedFilter = FeedFilter.Campus,
    val me: Student? = null,
    val posts: List<Post> = emptyList(),
    val stories: List<Student> = emptyList(),
    val publishing: Boolean = false,
    val publishError: String? = null,
    /** Increments after each successful post so the composer can clear itself. */
    val postedCount: Int = 0,
    val blocked: Set<String> = emptySet(),
    val menuPost: Post? = null,
    val editingPost: Post? = null,
    val savingEdit: Boolean = false,
    val deletingPost: Post? = null,
    /** A post deleted a moment ago; it can still be brought back until the undo window closes. */
    val undoPost: Post? = null,
    val notice: String? = null,
    /** True while the next page is being fetched at the bottom of the feed. */
    val loadingMore: Boolean = false,
    /** True once the server has no older posts to give. */
    val endReached: Boolean = false,
    /** The event announcement whose RSVP sheet is open. */
    val rsvpPost: Post? = null,
    /** Students you might follow, best match first. Shown a few at a time between posts, never on every screenful. */
    val suggestions: List<Student> = emptyList(),
    /** The one paid card the server chose for this feed, if any. Never more than one. */
    val sponsoredCard: com.univibe.app.data.repository.SponsoredCard? = null,
) {
    val visibleSuggestions: List<Student> get() = suggestions.filter { it.id !in blocked }
    val visiblePosts: List<Post> get() = posts.filter { it.author.id !in blocked }
    val visibleStories: List<Student> get() = stories.filter { it.id !in blocked }
}

class HomeViewModel(
    private val repository: FeedRepository = AppContainer.feedRepository,
    private val discover: DiscoverRepository = AppContainer.discoverRepository,
    private val settings: SettingsRepository = AppContainer.settingsRepository,
    private val privacy: PrivacyRepository = AppContainer.privacyRepository,
    private val events: EventRepository = AppContainer.eventRepository,
    private val sponsored: com.univibe.app.data.repository.SponsoredRepository = AppContainer.sponsoredRepository,
) : ViewModel() {
    val supportsInterested: Boolean get() = events.supportsInterested
    val supportsSavedEvents: Boolean get() = events.supportsSaved

    /** False when the server can't store photos and videos yet: the composer then hides those buttons. */
    val supportsMedia: Boolean = repository.supportsMedia
    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            // Open on the feed the student chose last time; fall back to the default if settings can't be read.
            val saved = runCatching { settings.settings.first().feedFilter }.getOrNull()
            if (saved != null) _state.update { it.copy(filter = saved) }
            load()
        }
        // The campus filter changed: reload the feed for the chosen campuses.
        viewModelScope.launch { CampusScopeStore.scope.drop(1).collect { load() } }
        viewModelScope.launch {
            settings.settings.collect { s -> _state.update { it.copy(blocked = s.blocked.map { b -> b.id }.toSet()) } }
        }
    }

    fun load(silent: Boolean = false, pull: Boolean = false) {
        viewModelScope.launch {
            if (!silent) _state.update { it.copy(loading = true, error = null) }
            if (pull) _state.update { it.copy(refreshing = true) }
            try {
                val me = repository.getCurrentUser()
                val page = repository.getPostsPage(_state.value.filter, null, PAGE_SIZE)
                val stories = repository.getStories()
                _state.update { cur ->
                    // A quiet refresh keeps the older pages the student already scrolled through, so the list never jumps.
                    val posts = if (silent) mergeFirstPage(cur.posts, page) else page
                    val onlyFirstPage = posts.size == page.size
                    cur.copy(
                        loading = false, refreshing = false, me = me, posts = posts, stories = stories, loadingMore = false,
                        endReached = if (onlyFirstPage) page.size < PAGE_SIZE else cur.endReached,
                    )
                }
                // Quiet refreshes keep the row the student is looking at; it only reshuffles on a fresh load or pull.
                if (!silent || _state.value.suggestions.isEmpty()) loadSuggestions()
                // Paid cards belong to the campus feed only, and a quiet refresh keeps the one already shown.
                if (_state.value.filter == FeedFilter.Campus && (!silent || _state.value.sponsoredCard == null)) loadSponsored()
            } catch (e: Exception) {
                _state.update { it.copy(refreshing = false) }
                if (silent) return@launch
                _state.update { it.copy(loading = false, error = "We couldn't load your feed. Check your connection and try again.") }
            }
        }
    }

    private val reportedSeen = HashSet<String>()

    private fun loadSponsored() {
        viewModelScope.launch {
            // No card, or any failure, simply means no ad. The feed never waits for or depends on this.
            val card = runCatching { sponsored.card("home_feed") }.getOrNull()
            _state.update { it.copy(sponsoredCard = card) }
        }
    }

    /** Called once the card has been on screen long enough. Each served card is reported at most once from here. */
    fun sponsoredShown() {
        val card = _state.value.sponsoredCard ?: return
        if (!reportedSeen.add(card.impressionToken)) return
        viewModelScope.launch { runCatching { sponsored.impression(card) } }
    }

    fun sponsoredClicked() {
        val card = _state.value.sponsoredCard ?: return
        viewModelScope.launch { runCatching { sponsored.click(card) } }
    }

    fun reportSponsored(reason: String) {
        val card = _state.value.sponsoredCard ?: return
        _state.update { it.copy(sponsoredCard = null) }
        viewModelScope.launch {
            val sent = runCatching { sponsored.report(card, reason) }.isSuccess
            explain(if (sent) "Thanks. We'll review this ad." else "We couldn't send that report. Try again later.")
        }
    }

    /** Who to suggest: the same eligible people as Discover (never someone already followed, passed on or blocked). */
    private fun loadSuggestions() {
        viewModelScope.launch {
            runCatching { discover.getCandidates() }.onSuccess { list ->
                val best = list.sortedByDescending { it.shared.size + if (it.sameCourse) 1 else 0 }.take(SUGGESTION_LIMIT)
                _state.update { it.copy(suggestions = best) }
            }
        }
    }

    /** Follow from the feed is Discover's "connect": the server follows them and remembers it. */
    fun followSuggestion(id: String) {
        _state.update { s -> s.copy(suggestions = s.suggestions.filter { it.id != id }) }
        viewModelScope.launch { runCatching { discover.recordDecision(id, true) } }
    }

    /** The X on a suggestion: the server remembers the pass, so they do not come back here or in Discover. */
    fun dismissSuggestion(id: String) {
        _state.update { s -> s.copy(suggestions = s.suggestions.filter { it.id != id }) }
        viewModelScope.launch { runCatching { discover.recordDecision(id, false) } }
    }

    /** Fetches the next page when the student nears the bottom. Does nothing while loading or at the end. */
    fun loadMore() {
        val s = _state.value
        if (s.loading || s.loadingMore || s.endReached || s.error != null) return
        val last = s.posts.lastOrNull() ?: return
        val filter = s.filter
        _state.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            try {
                val page = repository.getPostsPage(filter, last.id, PAGE_SIZE)
                _state.update { cur ->
                    if (cur.filter != filter) {
                        cur.copy(loadingMore = false) // the student switched feeds while this was loading
                    } else {
                        val known = cur.posts.mapTo(HashSet()) { it.id }
                        cur.copy(posts = cur.posts + page.filter { it.id !in known }, loadingMore = false, endReached = page.size < PAGE_SIZE)
                    }
                }
            } catch (e: Exception) {
                // Leave the list as it is; scrolling away from the bottom and back tries again.
                _state.update { it.copy(loadingMore = false) }
            }
        }
    }

    /** Quietly re-reads the feed (e.g. after returning from comments) without a loading state. */
    fun refresh() = load(silent = true)

    /** Pull down to refresh: the same quiet reload, with the spinner shown until it finishes. */
    fun pullRefresh() = load(silent = true, pull = true)

    fun setFilter(filter: FeedFilter) {
        if (filter == _state.value.filter) return
        _state.update { it.copy(filter = filter) }
        viewModelScope.launch { runCatching { settings.update { it.copy(feedFilter = filter) } } }
        load()
    }

    fun toggleLike(postId: String) {
        val target = _state.value.posts.firstOrNull { it.id == postId } ?: return
        val nowLiked = !target.likedByMe
        updatePost(postId) { it.copy(likedByMe = nowLiked, likeCount = it.likeCount + if (nowLiked) 1 else -1) }
        viewModelScope.launch { runCatching { repository.setLiked(postId, nowLiked) } }
    }

    fun toggleSave(postId: String) {
        val target = _state.value.posts.firstOrNull { it.id == postId } ?: return
        val nowSaved = !target.savedByMe
        updatePost(postId) { it.copy(savedByMe = nowSaved) }
        viewModelScope.launch { runCatching { repository.setSaved(postId, nowSaved) } }
    }

    fun toggleRepost(postId: String) {
        val target = _state.value.posts.firstOrNull { it.id == postId } ?: return
        val now = !target.repostedByMe
        updatePost(postId) { it.copy(repostedByMe = now, repostCount = (it.repostCount + if (now) 1 else -1).coerceAtLeast(0)) }
        viewModelScope.launch { runCatching { repository.setReposted(postId, now) } }
    }

    // Posts the student has looked at are reported in small batches; each id is sent once per session.
    private val seenIds = mutableSetOf<String>()
    private val unsentViews = mutableListOf<String>()
    private var viewFlush: Job? = null

    fun noteSeen(postId: String) {
        if (!seenIds.add(postId)) return
        unsentViews += postId
        if (viewFlush?.isActive == true) return
        viewFlush = viewModelScope.launch {
            while (unsentViews.isNotEmpty()) {
                delay(2_000)
                val batch = unsentViews.take(50)
                unsentViews.subList(0, batch.size).clear()
                runCatching { repository.recordViews(batch) }
            }
        }
    }

    // ---- event announcements: every change goes to the event itself, so the Events tab and details agree with Home.
    fun openRsvp(post: Post) = _state.update { it.copy(rsvpPost = post) }
    fun closeRsvp() = _state.update { it.copy(rsvpPost = null) }

    fun chooseRsvp(newState: RsvpState) {
        val post = _state.value.rsvpPost ?: return
        val event = post.event ?: return
        _state.update { it.copy(rsvpPost = null) }
        updatePost(post.id) { p ->
            val e = p.event ?: return@updatePost p
            var going = e.going
            var interested = e.interested
            if (e.rsvp == RsvpState.Going) going--
            if (e.rsvp == RsvpState.Interested) interested--
            if (newState == RsvpState.Going) going++
            if (newState == RsvpState.Interested) interested++
            p.copy(event = e.copy(rsvp = newState, going = going, interested = interested))
        }
        viewModelScope.launch {
            try { events.setRsvp(event.id, newState) } catch (e: Exception) {
                // The server said no (full, closed, offline): show what it really has instead of a false answer.
                _state.update { it.copy(notice = e.message?.takeIf { m -> m.length < 90 } ?: "Couldn't save your RSVP.") }
                refresh()
            }
        }
    }

    fun toggleEventSave(post: Post) {
        val event = post.event ?: return
        val nowSaved = !event.saved
        updatePost(post.id) { p -> p.copy(event = p.event?.copy(saved = nowSaved)) }
        viewModelScope.launch {
            try { events.setSaved(event.id, nowSaved) } catch (e: Exception) {
                updatePost(post.id) { p -> p.copy(event = p.event?.copy(saved = !nowSaved)) }
                _state.update { it.copy(notice = "Couldn't update your saved events.") }
            }
        }
    }

    private fun updatePost(id: String, change: (Post) -> Post) =
        _state.update { s -> s.copy(posts = s.posts.map { if (it.id == id) change(it) else it }) }

    fun openPostMenu(post: Post) = _state.update { it.copy(menuPost = post) }
    fun closePostMenu() = _state.update { it.copy(menuPost = null) }
    fun consumeNotice() = _state.update { it.copy(notice = null) }

    fun blockAuthor() {
        val author = _state.value.menuPost?.author ?: return
        _state.update { it.copy(menuPost = null) }
        viewModelScope.launch {
            // Only say "blocked" once the server has actually recorded it.
            val notice = try {
                privacy.block(author.id, author.name)
                "${author.name} blocked. You can undo this in Settings."
            } catch (e: IOException) {
                "Couldn't block ${author.name}. Check your connection and try again."
            } catch (e: ApiException) {
                "Couldn't block ${author.name}. Try again."
            }
            _state.update { it.copy(notice = notice) }
        }
    }

    fun reportPost(reason: String) {
        val post = _state.value.menuPost ?: return
        viewModelScope.launch { runCatching { repository.reportPost(post.id, reason) } }
        _state.update { it.copy(menuPost = null, notice = "Thanks. We'll review this post.") }
    }

    // ---- own posts: edit and delete
    fun startEdit() = _state.update { it.copy(editingPost = it.menuPost, menuPost = null) }
    fun cancelEdit() = _state.update { it.copy(editingPost = null) }

    fun saveEdit(text: String) {
        val post = _state.value.editingPost ?: return
        val clean = text.trim()
        val hasContent = post.imagePath != null || post.imagePaths.isNotEmpty() || post.videoPath != null ||
            post.mediaAccent != null || post.poll != null
        if (clean.length > MAX_POST_LENGTH || (clean.isEmpty() && !hasContent) || _state.value.savingEdit) return
        if (clean == post.text) { cancelEdit(); return }
        viewModelScope.launch {
            _state.update { it.copy(savingEdit = true) }
            try {
                val saved = repository.editPost(post.id, clean)
                updatePost(post.id) { it.copy(text = saved.text, edited = saved.edited) }
                _state.update { it.copy(savingEdit = false, editingPost = null, notice = "Post updated.") }
            } catch (e: Exception) {
                _state.update { it.copy(savingEdit = false, notice = "Couldn't save your changes. Please try again.") }
            }
        }
    }

    fun askDelete() = _state.update { it.copy(deletingPost = it.menuPost, menuPost = null) }
    fun cancelDelete() = _state.update { it.copy(deletingPost = null) }

    // The post leaves the feed at once, but the server is only told after the undo window, so Undo can bring it back.
    private val deleteScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pendingDelete: Job? = null
    private var pendingIndex = 0

    fun confirmDelete() {
        val post = _state.value.deletingPost ?: return
        flushPendingDelete()
        pendingIndex = _state.value.posts.indexOfFirst { it.id == post.id }.coerceAtLeast(0)
        _state.update { s -> s.copy(deletingPost = null, posts = s.posts.filterNot { it.id == post.id }, undoPost = post) }
        pendingDelete = deleteScope.launch { delay(UNDO_WINDOW_MS); commitDelete(post) }
    }

    fun undoDelete() {
        val post = _state.value.undoPost ?: return
        pendingDelete?.cancel(); pendingDelete = null
        _state.update { s ->
            val list = s.posts.toMutableList().also { it.add(pendingIndex.coerceIn(0, it.size), post) }
            s.copy(posts = list, undoPost = null)
        }
    }

    /** A second delete (or leaving) while the first is still undoable: make the first one final now. */
    private fun flushPendingDelete() {
        val post = _state.value.undoPost ?: return
        pendingDelete?.cancel(); pendingDelete = null
        deleteScope.launch { commitDelete(post) }
    }

    private suspend fun commitDelete(post: Post) {
        _state.update { if (it.undoPost?.id == post.id) it.copy(undoPost = null) else it }
        try {
            repository.deletePost(post.id)
            post.imagePaths.ifEmpty { listOfNotNull(post.imagePath) }.forEach { PhotoStore.delete(it) }
            VideoStore.delete(post.videoPath)
        } catch (e: Exception) {
            // Put it back so nothing disappears that the server still has.
            _state.update { s ->
                val list = s.posts.toMutableList().also { it.add(pendingIndex.coerceIn(0, it.size), post) }
                s.copy(posts = list, notice = "Couldn't delete the post. Please try again.")
            }
        }
    }

    // ---- polls and sharing
    fun vote(postId: String, optionIndex: Int) {
        val before = _state.value.posts.firstOrNull { it.id == postId }?.poll ?: return
        if (before.myVote != null || optionIndex !in before.options.indices) return
        if (before.hasEnded()) { _state.update { it.copy(notice = "This poll has ended.") }; return }
        updatePost(postId) { post ->
            val poll = post.poll ?: return@updatePost post
            val options = poll.options.mapIndexed { i, o -> if (i == optionIndex) o.copy(votes = o.votes + 1) else o }
            post.copy(poll = poll.copy(options = options, myVote = optionIndex))
        }
        viewModelScope.launch {
            try { repository.vote(postId, optionIndex) } catch (e: Exception) {
                val closed = e is ApiException && e.code == "poll_ended"
                updatePost(postId) { it.copy(poll = if (closed) before.copy(endsAtMillis = System.currentTimeMillis()) else before) }
                _state.update { it.copy(notice = if (closed) "This poll has ended." else "Couldn't record your vote. Please try again.") }
            }
        }
    }

    fun noteShared(postId: String) {
        updatePost(postId) { it.copy(shareCount = it.shareCount + 1) }
        viewModelScope.launch { runCatching { repository.recordShare(postId) } }
    }

    fun explain(message: String) = _state.update { it.copy(notice = message) }

    /** Client-side checks are convenience only; the backend must validate again. */
    fun publish(
        text: String,
        hasMedia: Boolean,
        imagePath: String?,
        videoPath: String? = null,
        pollOptions: List<String> = emptyList(),
        pollHours: Int = PostLimits.DEFAULT_POLL_HOURS,
    ) {
        val clean = text.trim()
        val options = pollOptions.map { it.trim() }
        val hasPoll = options.isNotEmpty()
        if (hasPoll && (clean.isEmpty() || options.size < PostLimits.MIN_POLL_OPTIONS || options.any { it.isEmpty() })) return
        if ((clean.isEmpty() && !hasMedia && !hasPoll) || clean.length > MAX_POST_LENGTH || _state.value.publishing) return
        viewModelScope.launch {
            _state.update { it.copy(publishing = true, publishError = null) }
            try {
                val post = repository.createPost(clean, hasMedia, imagePath, videoPath, options, pollHours)
                _state.update { it.copy(publishing = false, posts = listOf(post) + it.posts, postedCount = it.postedCount + 1) }
            } catch (e: Exception) {
                val message = when {
                    e is ApiException && e.code == "storage_unavailable" -> "Photo and video uploads aren't available right now. Try again later."
                    e is ApiException && e.status == 400 -> e.message ?: "That post can't be sent."
                    e is IOException -> "No connection. Check your internet and try again."
                    else -> "Couldn't post. Please try again."
                }
                _state.update { it.copy(publishing = false, publishError = message) }
            }
        }
    }
}

/** Posts per page of the home feed. */
const val PAGE_SIZE = 20

/**
 * Puts a freshly fetched first page in front of the older posts already loaded, without gaps or repeats.
 * Posts after the first page's last post are kept; anything the first page now covers is dropped from the tail.
 */
internal fun mergeFirstPage(old: List<Post>, first: List<Post>, pageSize: Int = PAGE_SIZE): List<Post> {
    if (first.size < pageSize || old.size <= first.size) return first
    val firstIds = first.mapTo(HashSet()) { it.id }
    val anchor = old.indexOfFirst { it.id == first.last().id }
    val tail = if (anchor >= 0) old.drop(anchor + 1) else old
    return first + tail.filter { it.id !in firstIds }
}
