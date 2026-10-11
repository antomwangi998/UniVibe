package com.univibe.app.features.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.univibe.app.data.model.FeedFilter
import com.univibe.app.data.model.Post
import com.univibe.app.data.model.Student
import com.univibe.app.data.repository.FeedRepository
import com.univibe.app.data.repository.MockFeedRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val filter: FeedFilter = FeedFilter.Campus,
    val posts: List<Post> = emptyList(),
    val stories: List<Student> = emptyList(),
)

class HomeViewModel(
    private val repository: FeedRepository = MockFeedRepository(),
) : ViewModel() {
    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val posts = repository.getPosts(_state.value.filter)
                val stories = repository.getStories()
                _state.update { it.copy(loading = false, posts = posts, stories = stories) }
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
        _state.update { s ->
            s.copy(posts = s.posts.map {
                if (it.id == postId) it.copy(likedByMe = nowLiked, likeCount = it.likeCount + if (nowLiked) 1 else -1) else it
            })
        }
        viewModelScope.launch { runCatching { repository.setLiked(postId, nowLiked) } }
    }
}
