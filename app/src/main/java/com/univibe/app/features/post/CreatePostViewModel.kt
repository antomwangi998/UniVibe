package com.univibe.app.features.post

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.univibe.app.app.AppContainer
import com.univibe.app.data.media.PhotoStore
import com.univibe.app.data.model.Community
import com.univibe.app.data.model.CommunityRole
import com.univibe.app.data.model.NewPost
import com.univibe.app.data.model.PostAudience
import com.univibe.app.data.model.Student
import com.univibe.app.data.repository.CommunityRepository
import com.univibe.app.data.repository.FeedRepository
import com.univibe.app.features.home.MAX_POST_LENGTH
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

const val MAX_POST_PHOTOS = 4

data class CreatePostUiState(
    val me: Student? = null,
    val text: String = "",
    val photos: List<String> = emptyList(),
    val audience: PostAudience = PostAudience.Campus,
    /** Only communities you belong to; you can't post into others. The server checks this again. */
    val communities: List<Community> = emptyList(),
    val importing: Boolean = false,
    val publishing: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val published: Boolean = false,
) {
    val tooLong: Boolean get() = text.length > MAX_POST_LENGTH
    val charsLeft: Int get() = MAX_POST_LENGTH - text.length
    val hasContent: Boolean get() = text.isNotBlank() || photos.isNotEmpty()
    val canAddPhoto: Boolean get() = photos.size < MAX_POST_PHOTOS && !importing && !publishing
    val canPost: Boolean get() = hasContent && !tooLong && !importing && !publishing && !published
    /** Anything the user would lose by leaving. */
    val isDirty: Boolean get() = hasContent && !published
}

class CreatePostViewModel(
    private val repository: FeedRepository = AppContainer.feedRepository,
    private val communityRepository: CommunityRepository = AppContainer.communityRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(CreatePostUiState())
    val state: StateFlow<CreatePostUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val me = runCatching { repository.getCurrentUser() }.getOrNull()
            val joined = runCatching { communityRepository.getCommunities() }.getOrDefault(emptyList())
                .filter { it.role != CommunityRole.None }
            _state.update { it.copy(me = me, communities = joined) }
        }
    }

    fun setText(value: String) = _state.update { it.copy(text = value, error = null) }

    fun setImporting(importing: Boolean) = _state.update { it.copy(importing = importing) }

    /** [failed] is how many picked images couldn't be used (not an image, unreadable, ...). */
    fun addPhotos(paths: List<String>, failed: Int) {
        val room = MAX_POST_PHOTOS - _state.value.photos.size
        val keep = paths.take(room)
        paths.drop(room).forEach(PhotoStore::delete) // never keep files we won't use
        _state.update {
            it.copy(
                photos = it.photos + keep,
                notice = if (failed > 0) "$failed ${if (failed == 1) "image" else "images"} couldn't be used." else null,
            )
        }
    }

    fun removePhoto(path: String) {
        PhotoStore.delete(path)
        _state.update { it.copy(photos = it.photos - path) }
    }

    fun selectAudience(audience: PostAudience) = _state.update { it.copy(audience = audience) }
    fun consumeNotice() = _state.update { it.copy(notice = null) }

    /** Client checks are convenience only; the backend must validate again. */
    fun publish() {
        val s = _state.value
        if (!s.canPost) return
        viewModelScope.launch {
            _state.update { it.copy(publishing = true, error = null) }
            try {
                repository.publishPost(NewPost(s.text.trim(), s.photos, s.audience))
                _state.update { it.copy(publishing = false, published = true) }
            } catch (e: Exception) {
                _state.update { it.copy(publishing = false, error = "Couldn't post. Your draft is safe, so try again.") }
            }
        }
    }

    /** User chose to throw the draft away. */
    fun discard() {
        _state.value.photos.forEach(PhotoStore::delete)
        _state.update { it.copy(photos = emptyList(), text = "") }
    }

    override fun onCleared() {
        // Photos of a post that was never published are private files nobody references.
        if (!_state.value.published) _state.value.photos.forEach(PhotoStore::delete)
    }
}
