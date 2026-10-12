package com.univibe.app.features.comments

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.univibe.app.app.AppContainer
import com.univibe.app.data.model.Comment
import com.univibe.app.data.repository.CommentRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

const val MAX_COMMENT = 280

data class CommentsUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val comments: List<Comment> = emptyList(),
    val input: String = "",
    val sending: Boolean = false,
    val notice: String? = null,
) {
    val canSend get() = input.isNotBlank() && input.length <= MAX_COMMENT && !sending
}

class CommentsViewModel(
    private val postId: String,
    private val repository: CommentRepository = AppContainer.commentRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(CommentsUiState())
    val state: StateFlow<CommentsUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val list = repository.getComments(postId)
                _state.update { it.copy(loading = false, comments = list) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = "We couldn't load the comments. Check your connection and try again.") }
            }
        }
    }

    fun onInput(v: String) = _state.update { it.copy(input = v) }

    fun send() {
        val s = _state.value
        if (!s.canSend) return
        viewModelScope.launch {
            _state.update { it.copy(sending = true) }
            try {
                val c = repository.addComment(postId, s.input)
                _state.update { it.copy(sending = false, input = "", comments = it.comments + c) }
            } catch (e: Exception) {
                _state.update { it.copy(sending = false, notice = "Couldn't post your comment. Try again.") }
            }
        }
    }

    fun toggleLike(id: String) {
        val c = _state.value.comments.firstOrNull { it.id == id } ?: return
        val liked = !c.likedByMe
        _state.update { s -> s.copy(comments = s.comments.map { if (it.id == id) it.copy(likedByMe = liked, likeCount = it.likeCount + if (liked) 1 else -1) else it }) }
        viewModelScope.launch { runCatching { repository.setLiked(id, liked) } }
    }

    fun delete(id: String) {
        _state.update { s -> s.copy(comments = s.comments.filterNot { it.id == id }) }
        viewModelScope.launch { runCatching { repository.deleteComment(id) } }
    }

    fun report(id: String) {
        viewModelScope.launch { runCatching { repository.reportComment(id, "Reported by user") } }
        _state.update { it.copy(notice = "Thanks. We'll review this comment.") }
    }

    fun consumeNotice() = _state.update { it.copy(notice = null) }
}
