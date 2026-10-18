package com.univibe.app.features.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.univibe.app.app.AppContainer
import com.univibe.app.data.media.PhotoStore
import com.univibe.app.data.net.ApiException
import java.io.IOException
import com.univibe.app.data.media.VideoStore
import com.univibe.app.data.model.FeedFilter
import com.univibe.app.data.settings.BlockedUser
import com.univibe.app.data.settings.SettingsRepository
import com.univibe.app.data.model.Post
import com.univibe.app.data.model.PostLimits
import com.univibe.app.data.model.Student
import com.univibe.app.data.repository.FeedRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

const val MAX_POST_LENGTH = PostLimits.MAX_TEXT

data class HomeUiState(
    val loading: Boolean = true,
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
    val notice: String? = null,
) {
    val visiblePosts: List<Post> get() = posts.filter { it.author.id !in blocked }
    val visibleStories: List<Student> get() = stories.filter { it.id !in blocked }
}

class HomeViewModel(
    private val repository: FeedRepository = AppContainer.feedRepository,
    private val settings: SettingsRepository = AppContainer.settingsRepository,
) : ViewModel() {
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
        viewModelScope.launch {
            settings.settings.collect { s -> _state.update { it.copy(blocked = s.blocked.map { b -> b.id }.toSet()) } }
        }
    }

    fun load(silent: Boolean = false) {
        viewModelScope.launch {
            if (!silent) _state.update { it.copy(loading = true, error = null) }
            try {
                val me = repository.getCurrentUser()
                val posts = repository.getPosts(_state.value.filter)
                val stories = repository.getStories()
                _state.update { it.copy(loading = false, me = me, posts = posts, stories = stories) }
            } catch (e: Exception) {
                if (silent) return@launch
                _state.update { it.copy(loading = false, error = "We couldn't load your feed. Check your connection and try again.") }
            }
        }
    }

    /** Quietly re-reads the feed (e.g. after returning from comments) without a loading state. */
    fun refresh() = load(silent = true)

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

    private fun updatePost(id: String, change: (Post) -> Post) =
        _state.update { s -> s.copy(posts = s.posts.map { if (it.id == id) change(it) else it }) }

    fun openPostMenu(post: Post) = _state.update { it.copy(menuPost = post) }
    fun closePostMenu() = _state.update { it.copy(menuPost = null) }
    fun consumeNotice() = _state.update { it.copy(notice = null) }

    fun blockAuthor() {
        val author = _state.value.menuPost?.author ?: return
        viewModelScope.launch {
            settings.update { s ->
                if (s.blocked.any { it.id == author.id }) s else s.copy(blocked = s.blocked + BlockedUser(author.id, author.name))
            }
        }
        _state.update { it.copy(menuPost = null, notice = "${author.name} blocked. You can undo this in Settings.") }
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

    fun confirmDelete() {
        val post = _state.value.deletingPost ?: return
        _state.update { it.copy(deletingPost = null) }
        viewModelScope.launch {
            try {
                repository.deletePost(post.id)
                _state.update { s -> s.copy(posts = s.posts.filterNot { it.id == post.id }, notice = "Post deleted.") }
                post.imagePaths.ifEmpty { listOfNotNull(post.imagePath) }.forEach { PhotoStore.delete(it) }
                VideoStore.delete(post.videoPath)
            } catch (e: Exception) {
                _state.update { it.copy(notice = "Couldn't delete the post. Please try again.") }
            }
        }
    }

    // ---- polls and sharing
    fun vote(postId: String, optionIndex: Int) {
        val before = _state.value.posts.firstOrNull { it.id == postId }?.poll ?: return
        if (before.myVote != null || optionIndex !in before.options.indices) return
        updatePost(postId) { post ->
            val poll = post.poll ?: return@updatePost post
            val options = poll.options.mapIndexed { i, o -> if (i == optionIndex) o.copy(votes = o.votes + 1) else o }
            post.copy(poll = poll.copy(options = options, myVote = optionIndex))
        }
        viewModelScope.launch {
            try { repository.vote(postId, optionIndex) } catch (e: Exception) {
                updatePost(postId) { it.copy(poll = before) }
                _state.update { it.copy(notice = "Couldn't record your vote. Please try again.") }
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
    ) {
        val clean = text.trim()
        val options = pollOptions.map { it.trim() }
        val hasPoll = options.isNotEmpty()
        if (hasPoll && (clean.isEmpty() || options.size < PostLimits.MIN_POLL_OPTIONS || options.any { it.isEmpty() })) return
        if ((clean.isEmpty() && !hasMedia && !hasPoll) || clean.length > MAX_POST_LENGTH || _state.value.publishing) return
        viewModelScope.launch {
            _state.update { it.copy(publishing = true, publishError = null) }
            try {
                val post = repository.createPost(clean, hasMedia, imagePath, videoPath, options)
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
