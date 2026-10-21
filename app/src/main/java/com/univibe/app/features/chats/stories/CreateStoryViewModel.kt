package com.univibe.app.features.chats.stories

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.univibe.app.app.AppContainer
import com.univibe.app.data.repository.STORY_CAPTION_MAX
import com.univibe.app.data.repository.STORY_TEXT_MAX
import com.univibe.app.data.repository.StoryDraft
import com.univibe.app.data.repository.StoryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class CreateStoryUiState(
    /** Chosen or shot photo (app storage path). Null = a text Story. */
    val photoPath: String? = null,
    val caption: String = "",
    val text: String = "",
    val background: String = "teal",
    /** "campus" or "followers". */
    val audience: String = "campus",
    val publishing: Boolean = false,
    val notice: String? = null,
    val published: Boolean = false,
) {
    val isPhoto get() = photoPath != null
    val canPublish: Boolean
        get() = !publishing && !published && if (isPhoto) caption.length <= STORY_CAPTION_MAX else text.trim().isNotEmpty() && text.length <= STORY_TEXT_MAX
}

class CreateStoryViewModel(
    private val stories: StoryRepository = AppContainer.storyRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(CreateStoryUiState())
    val state: StateFlow<CreateStoryUiState> = _state.asStateFlow()

    /** One id per composer session: a retry after a dropped connection returns the first Story instead of posting twice. */
    private val clientId = UUID.randomUUID().toString()

    fun setPhoto(path: String?) = _state.update { it.copy(photoPath = path) }
    fun setCaption(v: String) = _state.update { it.copy(caption = v.take(STORY_CAPTION_MAX)) }
    fun setText(v: String) = _state.update { it.copy(text = v.take(STORY_TEXT_MAX)) }
    fun setBackground(v: String) = _state.update { it.copy(background = v) }
    fun setAudience(v: String) = _state.update { it.copy(audience = v) }
    fun consumeNotice() = _state.update { it.copy(notice = null) }

    fun publish() {
        val s = _state.value
        if (!s.canPublish) return
        _state.update { it.copy(publishing = true) }
        viewModelScope.launch {
            val draft = StoryDraft(s.photoPath, s.caption, s.text, s.background, s.audience, clientId)
            runCatching { stories.publish(draft) }
                .onSuccess { _state.update { it.copy(publishing = false, published = true, notice = "Added to your story") } }
                .onFailure { e -> _state.update { it.copy(publishing = false, notice = e.message?.takeIf { m -> m.isNotBlank() } ?: "Couldn't post your story. Try again.") } }
        }
    }
}
