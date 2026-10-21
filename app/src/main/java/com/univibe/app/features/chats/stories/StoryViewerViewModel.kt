package com.univibe.app.features.chats.stories

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.univibe.app.app.AppContainer
import com.univibe.app.data.model.StoryItem
import com.univibe.app.data.model.StoryTrayEntry
import com.univibe.app.data.net.ApiException
import com.univibe.app.data.repository.StoryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class StoryViewerUiState(
    val loading: Boolean = true,
    val error: String? = null,
    /** Everyone with a Story, in tray order, so the viewer can move on to the next person. */
    val authors: List<StoryTrayEntry> = emptyList(),
    val authorIndex: Int = 0,
    /** The current person's Stories, oldest first. */
    val items: List<StoryItem> = emptyList(),
    val itemIndex: Int = 0,
    val reelLoading: Boolean = false,
    /** Bumped to restart the current Story's progress (going back from the first Story). */
    val restartTick: Int = 0,
    val holding: Boolean = false,
    val composing: Boolean = false,
    val menuOpen: Boolean = false,
    val confirmDelete: Boolean = false,
    val backgrounded: Boolean = false,
    val mediaFailed: Boolean = false,
    val replyText: String = "",
    val sending: Boolean = false,
    val notice: String? = null,
    val closed: Boolean = false,
) {
    val author: StoryTrayEntry? get() = authors.getOrNull(authorIndex)
    val item: StoryItem? get() = items.getOrNull(itemIndex)
    /** Progress stops while the person holds, types, has a menu open, or anything is still loading. */
    val paused: Boolean get() = holding || composing || menuOpen || confirmDelete || backgrounded || reelLoading || sending || loading || error != null
}

private enum class Start { Unviewed, First }

class StoryViewerViewModel(
    private val startAuthorId: String,
    private val stories: StoryRepository = AppContainer.storyRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(StoryViewerUiState())
    val state: StateFlow<StoryViewerUiState> = _state.asStateFlow()

    private val reported = HashSet<String>()
    private var replyClientId: String? = null

    init { load() }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val tray = stories.tray()
                val start = tray.indexOfFirst { it.authorId == startAuthorId }
                if (start < 0) { close(); return@launch }
                // Start with the tapped person, then everyone after them in tray order.
                _state.update { it.copy(authors = tray, loading = false) }
                loadAuthor(start, +1, Start.Unviewed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = "Couldn't load stories. Check your connection.") }
            }
        }
    }

    private fun loadAuthor(index: Int, direction: Int, start: Start) {
        _state.update { it.copy(authorIndex = index, items = emptyList(), itemIndex = 0, reelLoading = true, error = null, mediaFailed = false, replyText = "") }
        replyClientId = null
        viewModelScope.launch {
            val authorId = _state.value.authors[index].authorId
            try {
                val items = stories.stories(authorId)
                val first = if (start == Start.Unviewed) items.indexOfFirst { !it.viewedByMe }.takeIf { it >= 0 } ?: 0 else 0
                _state.update { it.copy(items = items, itemIndex = first, reelLoading = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                // Nothing to show for this person any more (expired, blocked, audience changed): move on.
                if (e.status == 404 && index + direction in _state.value.authors.indices) loadAuthor(index + direction, direction, Start.First)
                else if (e.status == 404) close()
                else _state.update { it.copy(reelLoading = false, error = e.message ?: "Couldn't open this story.") }
            } catch (e: Exception) {
                _state.update { it.copy(reelLoading = false, error = "Couldn't open this story. Check your connection.") }
            }
        }
    }

    fun next() {
        val s = _state.value
        if (s.reelLoading || s.items.isEmpty()) return
        if (s.itemIndex < s.items.lastIndex) _state.update { it.copy(itemIndex = it.itemIndex + 1, mediaFailed = false, replyText = "") }.also { replyClientId = null }
        else nextAuthor()
    }

    fun previous() {
        val s = _state.value
        if (s.reelLoading || s.items.isEmpty()) return
        when {
            s.itemIndex > 0 -> { _state.update { it.copy(itemIndex = it.itemIndex - 1, mediaFailed = false, replyText = "") }; replyClientId = null }
            s.authorIndex > 0 -> loadAuthor(s.authorIndex - 1, -1, Start.First)
            else -> _state.update { it.copy(restartTick = it.restartTick + 1) }
        }
    }

    fun nextAuthor() {
        val s = _state.value
        if (s.reelLoading) return
        if (s.authorIndex < s.authors.lastIndex) loadAuthor(s.authorIndex + 1, +1, Start.Unviewed) else close()
    }

    fun previousAuthor() {
        val s = _state.value
        if (s.reelLoading || s.authorIndex == 0) return
        loadAuthor(s.authorIndex - 1, -1, Start.First)
    }

    fun close() = _state.update { it.copy(closed = true) }

    fun setHolding(v: Boolean) = _state.update { it.copy(holding = v) }
    fun setComposing(v: Boolean) = _state.update { it.copy(composing = v) }
    fun setBackgrounded(v: Boolean) = _state.update { it.copy(backgrounded = v) }
    fun setMenu(v: Boolean) = _state.update { it.copy(menuOpen = v) }
    fun askDelete() = _state.update { it.copy(menuOpen = false, confirmDelete = true) }
    fun cancelDelete() = _state.update { it.copy(confirmDelete = false) }
    fun mediaFailed() = _state.update { it.copy(mediaFailed = true) }
    fun consumeNotice() = _state.update { it.copy(notice = null) }
    fun setReply(v: String) = _state.update { it.copy(replyText = v.take(500)) }

    /** A view counts after the Story has been on screen for a moment (the screen calls this then), once per Story, never for my own. */
    fun markViewed(storyId: String) {
        val s = _state.value
        if (s.author?.isMine == true || !reported.add(storyId)) return
        viewModelScope.launch {
            try {
                stories.markViewed(storyId)
                _state.update { st -> st.copy(items = st.items.map { if (it.id == storyId) it.copy(viewedByMe = true) else it }) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reported.remove(storyId) // try again the next time it is shown
            }
        }
    }

    fun toggleHeart() {
        val s = _state.value
        val item = s.item ?: return
        if (s.author?.isMine == true) return
        val old = item.myReaction
        val new = if (old == "heart") null else "heart"
        setReaction(item.id, new)
        viewModelScope.launch {
            try {
                stories.react(item.id, new)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setReaction(item.id, old)
                _state.update { it.copy(notice = (e as? ApiException)?.message ?: "Couldn't send your reaction.") }
            }
        }
    }

    private fun setReaction(id: String, reaction: String?) =
        _state.update { st -> st.copy(items = st.items.map { if (it.id == id) it.copy(myReaction = reaction) else it }) }

    fun sendReply() {
        val s = _state.value
        val item = s.item ?: return
        val text = s.replyText.trim()
        if (text.isEmpty() || s.sending || s.author?.isMine == true) return
        val clientId = replyClientId ?: UUID.randomUUID().toString().also { replyClientId = it }
        _state.update { it.copy(sending = true) }
        viewModelScope.launch {
            try {
                stories.reply(item.id, text, clientId)
                replyClientId = null
                _state.update { it.copy(sending = false, replyText = "", composing = false, notice = "Reply sent") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(sending = false, notice = (e as? ApiException)?.message ?: "Couldn't send your reply. Try again.") }
            }
        }
    }

    fun deleteCurrent() {
        val s = _state.value
        val item = s.item ?: return
        if (s.author?.isMine != true) return
        _state.update { it.copy(confirmDelete = false) }
        viewModelScope.launch {
            try {
                stories.delete(item.id)
                val left = _state.value.items.filterNot { it.id == item.id }
                if (left.isEmpty()) {
                    _state.update { it.copy(notice = "Story deleted") }
                    close()
                } else {
                    _state.update { it.copy(items = left, itemIndex = it.itemIndex.coerceAtMost(left.lastIndex), notice = "Story deleted") }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(notice = (e as? ApiException)?.message ?: "Couldn't delete the story. Try again.") }
            }
        }
    }
}
