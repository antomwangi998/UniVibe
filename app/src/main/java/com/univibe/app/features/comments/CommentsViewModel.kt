package com.univibe.app.features.comments

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.univibe.app.app.AppContainer
import com.univibe.app.data.model.Comment
import com.univibe.app.data.model.FeedFilter
import com.univibe.app.data.model.Post
import com.univibe.app.data.model.Student
import com.univibe.app.data.net.ApiException
import com.univibe.app.data.repository.CommentRepository
import com.univibe.app.data.repository.FeedRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.IOException
import com.univibe.app.data.repository.optimisticCall
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import com.univibe.app.ui.components.UNDO_WINDOW_MS
import com.univibe.app.data.prefs.DraftStore
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest

const val MAX_COMMENT = 280

enum class CommentSort { Top, Newest }

data class CommentThread(val parent: Comment, val replies: List<Comment>)

data class CommentsUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val error: String? = null,
    val comments: List<Comment> = emptyList(),
    val post: Post? = null,
    /** The post could not be loaded (network or server error) while the comments could. The screen then offers a retry. */
    val postFailed: Boolean = false,
    /** The server answered but there is no such post any more (deleted, or hidden from this student). */
    val postGone: Boolean = false,
    /** The signed-in student, for the avatar beside the reply box. */
    val me: Student? = null,
    val input: String = "",
    val sending: Boolean = false,
    val notice: String? = null,
    val sort: CommentSort = CommentSort.Top,
    val expanded: Set<String> = emptySet(),
    val replyingTo: Comment? = null,
    val menuComment: Comment? = null,
    val confirmDelete: Comment? = null,
    /** The comment deleted a moment ago (its replies went with it); Undo brings them back. */
    val undoComment: Comment? = null,
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

    private val draftKey = "comment:$postId"
    private val deleteScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pendingDelete: Job? = null
    private var removedWithComment: List<Comment> = emptyList()
    private var orderBeforeDelete: List<String> = emptyList()
    /** Deletes saved to disk for the undo window (see [CommentRepository.scheduleDelete]), by comment id. */
    private val savedDeletes = mutableSetOf<String>()

    init {
        _state.update { it.copy(input = DraftStore.get(draftKey)) }
        load()
        viewModelScope.launch { runCatching { feed.getCurrentUser() }.onSuccess { me -> _state.update { it.copy(me = me) } } }
        // Keep what you typed, so leaving the screen or closing the app does not lose it.
        viewModelScope.launch {
            _state.map { it.input to it.replyingTo }.distinctUntilChanged().collectLatest { (text, replying) ->
                delay(400)
                if (replying == null) DraftStore.set(draftKey, text)
            }
        }
    }

    fun pullRefresh() = load(pull = true)

    fun load(pull: Boolean = false) {
        viewModelScope.launch {
            _state.update { if (pull) it.copy(refreshing = true) else it.copy(loading = true, error = null) }
            try {
                val list = repository.getComments(postId)
                var postFailed = false
                val post = try {
                    feed.getPost(postId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    postFailed = true
                    null
                }
                _state.update {
                    // A pull-to-refresh that fails to reload the post keeps the one already on screen.
                    val shown = post ?: it.post.takeIf { pull }
                    it.copy(
                        loading = false, refreshing = false, comments = list, post = shown,
                        postFailed = postFailed && shown == null, postGone = !postFailed && shown == null,
                    )
                }
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
        viewModelScope.launch {
            optimisticCall(
                call = { repository.setLiked(id, liked) },
                revert = { _state.update { s -> s.copy(comments = s.comments.map { if (it.id == id && it.likedByMe == liked) it.copy(likedByMe = !liked, likeCount = (it.likeCount + if (liked) -1 else 1).coerceAtLeast(0)) else it }) } },
                onFail = { _state.update { it.copy(notice = if (liked) "Couldn't like that comment. Try again." else "Couldn't remove your like. Try again.") } },
            )
        }
    }

    // The post at the top of the screen has the same actions as in the feed; each flips at once and tells the server.
    fun togglePostLike() {
        val p = _state.value.post ?: return
        val on = !p.likedByMe
        _state.update { s -> s.copy(post = s.post?.copy(likedByMe = on, likeCount = (p.likeCount + if (on) 1 else -1).coerceAtLeast(0))) }
        viewModelScope.launch {
            optimisticCall(
                call = { feed.setLiked(postId, on) },
                revert = { _state.update { s -> s.copy(post = s.post?.takeIf { it.likedByMe == on }?.let { it.copy(likedByMe = !on, likeCount = (it.likeCount + if (on) -1 else 1).coerceAtLeast(0)) } ?: s.post) } },
                onFail = { _state.update { it.copy(notice = if (on) "Couldn't like that post. Try again." else "Couldn't remove your like. Try again.") } },
            )
        }
    }

    fun togglePostSave() {
        val p = _state.value.post ?: return
        val on = !p.savedByMe
        _state.update { s -> s.copy(post = s.post?.copy(savedByMe = on)) }
        viewModelScope.launch {
            optimisticCall(
                call = { feed.setSaved(postId, on) },
                revert = { _state.update { s -> s.copy(post = s.post?.takeIf { it.savedByMe == on }?.copy(savedByMe = !on) ?: s.post) } },
                onFail = { _state.update { it.copy(notice = if (on) "Couldn't save that post. Try again." else "Couldn't remove it from saved. Try again.") } },
            )
        }
    }

    fun togglePostRepost() {
        val p = _state.value.post ?: return
        val on = !p.repostedByMe
        _state.update { s -> s.copy(post = s.post?.copy(repostedByMe = on, repostCount = (p.repostCount + if (on) 1 else -1).coerceAtLeast(0))) }
        viewModelScope.launch {
            optimisticCall(
                call = { feed.setReposted(postId, on) },
                revert = { _state.update { s -> s.copy(post = s.post?.takeIf { it.repostedByMe == on }?.let { it.copy(repostedByMe = !on, repostCount = (it.repostCount + if (on) -1 else 1).coerceAtLeast(0)) } ?: s.post) } },
                onFail = { _state.update { it.copy(notice = if (on) "Couldn't repost. Try again." else "Couldn't undo your repost. Try again.") } },
            )
        }
    }

    /** Votes in the post's poll: the result shows at once and is put back if the server says no. */
    fun vote(optionIndex: Int) {
        val before = _state.value.post?.poll ?: return
        if (before.myVote != null || optionIndex !in before.options.indices) return
        if (before.hasEnded()) { _state.update { it.copy(notice = "This poll has ended.") }; return }
        _state.update { s ->
            s.copy(post = s.post?.let { post ->
                val poll = post.poll ?: return@let post
                val options = poll.options.mapIndexed { i, o -> if (i == optionIndex) o.copy(votes = o.votes + 1) else o }
                post.copy(poll = poll.copy(options = options, myVote = optionIndex))
            })
        }
        viewModelScope.launch {
            try {
                feed.vote(postId, optionIndex)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val closed = e is ApiException && e.code == "poll_ended"
                _state.update { s ->
                    s.copy(
                        post = s.post?.copy(poll = if (closed) before.copy(endsAtMillis = System.currentTimeMillis()) else before),
                        notice = if (closed) "This poll has ended." else "Couldn't record your vote. Please try again.",
                    )
                }
            }
        }
    }

    fun openMenu(c: Comment?) = _state.update { it.copy(menuComment = c) }
    fun askDelete(c: Comment) = _state.update { it.copy(confirmDelete = c, menuComment = null) }
    fun dismissDelete() = _state.update { it.copy(confirmDelete = null) }

    fun confirmDelete() {
        val c = _state.value.confirmDelete ?: return
        flushPendingDelete()
        // Deleting a top-level comment removes its replies too.
        val before = _state.value.comments
        orderBeforeDelete = before.map { it.id }
        removedWithComment = before.filter { it.id == c.id || it.parentId == c.id }
        _state.update { s ->
            s.copy(
                confirmDelete = null,
                comments = s.comments.filterNot { it.id == c.id || it.parentId == c.id },
                replyingTo = if (s.replyingTo?.id == c.id) null else s.replyingTo,
                undoComment = c,
            )
        }
        // Saved to disk first: if the app is closed during the undo window, the delete still goes out afterwards.
        if (repository.scheduleDelete(c.id, System.currentTimeMillis() + UNDO_WINDOW_MS)) savedDeletes += c.id
        pendingDelete = deleteScope.launch { delay(UNDO_WINDOW_MS); commitDelete(c, removedWithComment) }
    }

    fun undoDelete() {
        val undone = _state.value.undoComment ?: return
        pendingDelete?.cancel(); pendingDelete = null
        repository.cancelScheduledDelete(undone.id); savedDeletes -= undone.id
        restore(removedWithComment)
        _state.update { it.copy(undoComment = null) }
    }

    private fun restore(removed: List<Comment>) {
        val order = orderBeforeDelete
        _state.update { s ->
            val merged = (s.comments + removed.filter { r -> s.comments.none { it.id == r.id } })
                .sortedBy { c -> order.indexOf(c.id).let { if (it < 0) Int.MAX_VALUE else it } }
            s.copy(comments = merged)
        }
    }

    private fun flushPendingDelete() {
        val c = _state.value.undoComment ?: return
        val removed = removedWithComment
        pendingDelete?.cancel(); pendingDelete = null
        deleteScope.launch { commitDelete(c, removed) }
    }

    private suspend fun commitDelete(c: Comment, removed: List<Comment>) {
        _state.update { if (it.undoComment?.id == c.id) it.copy(undoComment = null) else it }
        try {
            repository.deleteComment(c.id)
            repository.cancelScheduledDelete(c.id); savedDeletes -= c.id
        } catch (e: Exception) {
            // No connection and the delete is saved: keep the comment hidden, it is sent when the phone is online again.
            if (e is IOException && c.id in savedDeletes) return
            repository.cancelScheduledDelete(c.id); savedDeletes -= c.id
            restore(removed)
            _state.update { it.copy(notice = "Couldn't delete the comment. Try again.") }
        }
    }

    fun startReport(c: Comment) = _state.update { it.copy(reporting = c, menuComment = null) }
    fun dismissReport() = _state.update { it.copy(reporting = null) }

    fun report(reason: String) {
        val c = _state.value.reporting ?: return
        _state.update { it.copy(reporting = null, notice = "Thanks. We'll review this comment.") }
        viewModelScope.launch { runCatching { repository.reportComment(c.id, reason) } }
    }
}
