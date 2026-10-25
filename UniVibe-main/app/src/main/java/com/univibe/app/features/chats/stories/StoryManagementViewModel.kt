package com.univibe.app.features.chats.stories

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.univibe.app.app.AppContainer
import com.univibe.app.data.model.MyStory
import com.univibe.app.data.model.StoryViewer
import com.univibe.app.data.net.ApiException
import com.univibe.app.data.repository.StoryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class StoryManagementUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val stories: List<MyStory> = emptyList(),
    val confirmDeleteId: String? = null,
    val notice: String? = null,
    /** The Story whose viewer list is open, if any. */
    val viewersFor: String? = null,
    val viewers: List<StoryViewer> = emptyList(),
    val viewersTotal: Int = 0,
    val viewersLoading: Boolean = false,
    val viewersError: String? = null,
    val viewersCursor: String? = null,
)

class StoryManagementViewModel(
    private val stories: StoryRepository = AppContainer.storyRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(StoryManagementUiState())
    val state: StateFlow<StoryManagementUiState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            try {
                val list = stories.myStories()
                _state.update { it.copy(loading = false, error = null, stories = list) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Keep what is on screen if a refresh fails; only show the error when there is nothing to show.
                _state.update { if (it.stories.isEmpty()) it.copy(loading = false, error = "Couldn't load your stories. Check your connection.") else it.copy(loading = false) }
            }
        }
    }

    fun retry() { _state.update { it.copy(loading = true, error = null) }; refresh() }
    fun consumeNotice() = _state.update { it.copy(notice = null) }
    fun askDelete(id: String) = _state.update { it.copy(confirmDeleteId = id) }
    fun cancelDelete() = _state.update { it.copy(confirmDeleteId = null) }

    fun delete() {
        val id = _state.value.confirmDeleteId ?: return
        _state.update { it.copy(confirmDeleteId = null) }
        viewModelScope.launch {
            try {
                stories.delete(id)
                _state.update { st -> st.copy(stories = st.stories.filterNot { it.item.id == id }, notice = "Story deleted") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(notice = (e as? ApiException)?.message ?: "Couldn't delete the story. Try again.") }
            }
        }
    }

    /** Changes who can see a Story. Shown at once and put back if the server says no. */
    fun setAudience(id: String, audience: String) {
        val old = _state.value.stories.firstOrNull { it.item.id == id }?.audience ?: return
        if (old == audience) return
        replaceAudience(id, audience)
        viewModelScope.launch {
            try {
                stories.setAudience(id, audience)
                _state.update { it.copy(notice = if (audience == "followers") "Now visible to followers only" else "Now visible to your campus") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                replaceAudience(id, old)
                _state.update { it.copy(notice = (e as? ApiException)?.message ?: "Couldn't change the audience. Try again.") }
            }
        }
    }

    private fun replaceAudience(id: String, audience: String) =
        _state.update { st -> st.copy(stories = st.stories.map { if (it.item.id == id) it.copy(audience = audience) else it }) }

    fun openViewers(id: String) {
        _state.update { it.copy(viewersFor = id, viewers = emptyList(), viewersTotal = 0, viewersCursor = null, viewersError = null) }
        loadMoreViewers()
    }

    fun closeViewers() = _state.update { it.copy(viewersFor = null, viewers = emptyList(), viewersCursor = null, viewersError = null, viewersLoading = false) }

    fun loadMoreViewers() {
        val s = _state.value
        val id = s.viewersFor ?: return
        if (s.viewersLoading || (s.viewers.isNotEmpty() && s.viewersCursor == null)) return
        _state.update { it.copy(viewersLoading = true, viewersError = null) }
        viewModelScope.launch {
            try {
                val page = stories.viewers(id, s.viewersCursor)
                _state.update { st ->
                    if (st.viewersFor != id) st // the sheet was closed or switched while this was loading
                    else st.copy(viewers = st.viewers + page.viewers, viewersTotal = page.total, viewersCursor = page.nextCursor, viewersLoading = false)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(viewersLoading = false, viewersError = (e as? ApiException)?.message ?: "Couldn't load viewers.") }
            }
        }
    }
}
