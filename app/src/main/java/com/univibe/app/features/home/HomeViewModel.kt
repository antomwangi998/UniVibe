package com.univibe.app.features.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.univibe.app.app.AppContainer
import com.univibe.app.data.model.FeedFilter
import com.univibe.app.data.settings.BlockedUser
import com.univibe.app.data.settings.SettingsRepository
import com.univibe.app.data.model.Post
import com.univibe.app.data.model.Student
import com.univibe.app.data.repository.FeedRepository
import com.univibe.app.data.repository.MockFeedRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

const val MAX_POST_LENGTH = 280

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
    val notice: String? = null,
) {
    val visiblePosts: List<Post> get() = posts.filter { it.author.id !in blocked }
    val visibleStories: List<Student> get() = stories.filter { it.id !in blocked }
}

class HomeViewModel(
    private val repository: FeedRepository = MockFeedRepository(),
    private val settings: SettingsRepository = AppContainer.settingsRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    init {
        load()
        viewModelScope.launch {
            settings.settings.collect { s -> _state.update { it.copy(blocked = s.blocked.map { b -> b.id }.toSet()) } }
        }
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val me = repository.getCurrentUser()
                val posts = repository.getPosts(_state.value.filter)
                val stories = repository.getStories()
                _state.update { it.copy(loading = false, me = me, posts = posts, stories = stories) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = "We couldn't load your feed. Check your connection and try again.") }
            }
        }
    }

    fun setFilter(filter: FeedFilter) {
        if (filter == _state.value.filter) return
        _state.update { it.copy(filter = filter) }
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

    /** Client-side checks are convenience only; the backend must validate again. */
    fun publish(text: String, hasMedia: Boolean) {
        val clean = text.trim()
        if ((clean.isEmpty() && !hasMedia) || clean.length > MAX_POST_LENGTH || _state.value.publishing) return
        viewModelScope.launch {
            _state.update { it.copy(publishing = true, publishError = null) }
            try {
                val post = repository.createPost(clean, hasMedia)
                _state.update { it.copy(publishing = false, posts = listOf(post) + it.posts, postedCount = it.postedCount + 1) }
            } catch (e: Exception) {
                _state.update { it.copy(publishing = false, publishError = "Couldn't post. Please try again.") }
            }
        }
    }
}
