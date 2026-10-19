package com.univibe.app.features.comments

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.univibe.app.app.AppContainer
import com.univibe.app.data.model.Comment
import com.univibe.app.data.model.FeedFilter
import com.univibe.app.data.model.Post
import com.univibe.app.data.repository.CommentRepository
import com.univibe.app.data.repository.FeedRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

const val MAX_COMMENT = 280

enum class CommentSort { Top, Newest }

data class CommentThread(val parent: Comment, val replies: List<Comment>)

data class CommentsUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val error: String? = null,
    val comments: List<Comment> = emptyList(),
    val post: Post? = null,
    val input: String = "",
    val sending: Boolean = false,
    val notice: String? = null,
    val sort: CommentSort = CommentSort.Top,
    val expanded: Set<String> = emptySet(),
    val replyingTo: Comment? = null,
    val menuComment: Comment? = null,
    val confirmDelete: Comment? = null,
    val reporting: Comment? = null,
    /** Set after posting so the screen can scroll to the new comment once. */
    val scrollToId: String? = null,
) {
    val canSend get() = input.isNotBlank() && input.length <= MAX_COMMENT && !sending

    /** Top-level comments with their replies (oldest first). Replies whose parent is gone are not shown. */
    val threads: List<CommentThread>
        get() {
            val tops = comments.filter { it.parentId == null }
            val replies = comments.filter { it.parentId != null }.groupBy { it.parentId }
            val ordered = when (sort) {
                CommentSort.Top -> tops.sortedByDescending { it.likeCount }
                CommentSort.Newest -> tops.asReversed()
            }
            return ordered.map { CommentThread(it, replies[it.id].orEmpty()) }
        }
    val total get() = comments.size
}

class CommentsViewModel(
    private val postId: String,
    private val repository: CommentRepository = AppContainer.commentRepository,
    private val feed: FeedRepository = AppContainer.feedRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(CommentsUiState())
    val state: StateFlow<CommentsUiState> = _state.asStateFlow()

    init { load() }

    fun pullRefresh() = load(pull = true)

    fun load(pull: Boolean = false) {
        viewModelScope.launch {
            _state.update { if (pull) it.copy(refreshing = true) else it.copy(loading = true, error = null) }
            try {
                val list = repository.getComments(postId)
                val post = runCatching { feed.getPost(postId) }.getOrNull()
                _state.update { it.copy(loading = false, refreshing = false, comments = list, post = post ?: it.post.takeIf { pull }) }
            } catch (e: Exception) {
                // A failed pull keeps the comments already on screen.
                _state.update { if (pull && it.comments.isNotEmpty()) it.copy(refreshing = false) else it.copy(loading = false, refreshing = false, error = "We couldn't load the comments. Check your connection and try again.") }
            }
        }
    }

    fun onInput(v: String) = _state.update { it.copy(input = v) }
    fun setSort(sort: CommentSort) = _state.update { it.copy(sort = sort) }
    fun toggleExpanded(id: String) = _state.update { it.copy(expanded = if (id in it.expanded) it.expanded - id else it.expanded + id) }
    fun consumeNotice() = _state.update { it.copy(notice = null) }
    fun consumeScroll() = _state.update { it.copy(scrollToId = null) }

    /** Replying to a reply still lands in the same thread, with a mention of the person you answer. */
    fun startReply(c: Comment) = _state.update {
        it.copy(replyingTo = c, menuComment = null, input = if (it.input.isBlank()) "${c.author.handle} " else it.input)
    }

    fun cancelReply() = _state.update { s ->
        val mention = s.replyingTo?.author?.handle
        s.copy(replyingTo = null, input = if (mention != null && s.input.trim() == mention) "" else s.input)
    }

    fun send() {
        val s = _state.value
        if (!s.canSend) return
        viewModelScope.launch {
            _state.update { it.copy(sending = true) }
            try {
                val c = repository.addComment(postId, s.input, s.replyingTo?.id)
                _state.update {
                    it.copy(
                        sending = false, input = "", replyingTo = null, comments = it.comments + c, scrollToId = c.id,
                        expanded = c.parentId?.let { p -> it.expanded + p } ?: it.expanded,
                    )
                }
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

    fun openMenu(c: Comment?) = _state.update { it.copy(menuComment = c) }
    fun askDelete(c: Comment) = _state.update { it.copy(confirmDelete = c, menuComment = null) }
    fun dismissDelete() = _state.update { it.copy(confirmDelete = null) }

    fun confirmDelete() {
        val c = _state.value.confirmDelete ?: return
        // Deleting a top-level comment removes its replies too.
        _state.update { s ->
            s.copy(
                confirmDelete = null,
                comments = s.comments.filterNot { it.id == c.id || it.parentId == c.id },
                replyingTo = if (s.replyingTo?.id == c.id) null else s.replyingTo,
            )
        }
        viewModelScope.launch { runCatching { repository.deleteComment(c.id) } }
    }

    fun startReport(c: Comment) = _state.update { it.copy(reporting = c, menuComment = null) }
    fun dismissReport() = _state.update { it.copy(reporting = null) }

    fun report(reason: String) {
        val c = _state.value.reporting ?: return
        _state.update { it.copy(reporting = null, notice = "Thanks. We'll review this comment.") }
        viewModelScope.launch { runCatching { repository.reportComment(c.id, reason) } }
    }
}
