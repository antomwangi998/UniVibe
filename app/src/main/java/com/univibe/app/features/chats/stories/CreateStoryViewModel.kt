package com.univibe.app.features.chats.stories

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.univibe.app.app.AppContainer
import com.univibe.app.data.media.PhotoStore
import com.univibe.app.data.media.VideoStore
import com.univibe.app.data.net.ApiException
import com.univibe.app.data.repository.STORY_CAPTION_MAX
import com.univibe.app.data.repository.STORY_TEXT_MAX
import com.univibe.app.data.repository.STORY_VIDEO_MAX_MS
import com.univibe.app.data.repository.StoryDraft
import com.univibe.app.data.repository.StoryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class CreateStoryUiState(
    /** Chosen or shot photo (app storage path). */
    val photoPath: String? = null,
    /** Chosen or recorded video (app storage path) and its length. */
    val videoPath: String? = null,
    val videoDurationMs: Int? = null,
    val caption: String = "",
    val text: String = "",
    val background: String = "teal",
    /** "campus" or "followers". */
    val audience: String = "campus",
    val publishing: Boolean = false,
    /** 0..1 while the file is being sent; null while posting (or when nothing is uploading). */
    val uploadProgress: Float? = null,
    /** Why the last attempt failed. The composer keeps everything so the person can retry. */
    val failure: String? = null,
    val notice: String? = null,
    val published: Boolean = false,
) {
    val isVideo get() = videoPath != null
    val isPhoto get() = photoPath != null
    val hasMedia get() = isVideo || isPhoto
    val canPublish: Boolean
        get() = !publishing && !published && if (hasMedia) caption.length <= STORY_CAPTION_MAX else text.trim().isNotEmpty() && text.length <= STORY_TEXT_MAX
}

class CreateStoryViewModel(
    private val stories: StoryRepository = AppContainer.storyRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(CreateStoryUiState())
    val state: StateFlow<CreateStoryUiState> = _state.asStateFlow()

    /** One id per composer session: a retry after a dropped connection returns the first Story instead of posting twice. */
    private val clientId = UUID.randomUUID().toString()

    /** The file's media id once it has been uploaded, so a retry after a failed publish does not send it again. */
    private var uploadedMediaId: String? = null
    private var job: Job? = null

    fun setPhoto(path: String?) {
        if (_state.value.publishing) return
        discardMedia()
        _state.update { it.copy(photoPath = path, videoPath = null, videoDurationMs = null, failure = null) }
    }

    /** Checks the length before accepting a video: the server refuses anything over 30 seconds. */
    fun setVideo(path: String) {
        if (_state.value.publishing) { VideoStore.delete(path); return }
        viewModelScope.launch {
            val ms = VideoStore.durationMs(path)
            when {
                ms == null -> reject(path, "Couldn't read that video.")
                ms > STORY_VIDEO_MAX_MS -> reject(path, "Story videos can be up to ${STORY_VIDEO_MAX_MS / 1000} seconds.")
                else -> {
                    discardMedia()
                    _state.update { it.copy(photoPath = null, videoPath = path, videoDurationMs = ms, failure = null) }
                }
            }
        }
    }

    private fun reject(path: String, message: String) {
        VideoStore.delete(path)
        _state.update { it.copy(notice = message) }
    }

    /** Forgets the current media (and its upload) before it is replaced or removed. */
    private fun discardMedia() {
        val s = _state.value
        PhotoStore.delete(s.photoPath)
        VideoStore.delete(s.videoPath)
        uploadedMediaId = null
    }

    fun clearMedia() = setPhoto(null)
    fun setCaption(v: String) = _state.update { it.copy(caption = v.take(STORY_CAPTION_MAX)) }
    fun setText(v: String) = _state.update { it.copy(text = v.take(STORY_TEXT_MAX)) }
    fun setBackground(v: String) = _state.update { it.copy(background = v) }
    fun setAudience(v: String) = _state.update { it.copy(audience = v) }
    fun consumeNotice() = _state.update { it.copy(notice = null) }
    fun dismissFailure() = _state.update { it.copy(failure = null) }

    /** Uploads the file (if it has not been uploaded yet), then posts the Story. Calling it again after a failure is the retry. */
    fun publish() {
        val s = _state.value
        if (!s.canPublish) return
        val needsUpload = s.hasMedia && uploadedMediaId == null
        _state.update { it.copy(publishing = true, failure = null, uploadProgress = if (needsUpload) 0f else null) }
        job = viewModelScope.launch {
            try {
                val path = s.videoPath ?: s.photoPath
                if (path != null && uploadedMediaId == null) {
                    uploadedMediaId = stories.uploadStoryMedia(path, s.isVideo, s.videoDurationMs) { f -> _state.update { it.copy(uploadProgress = f) } }
                }
                _state.update { it.copy(uploadProgress = null) }
                stories.publish(
                    StoryDraft(
                        photoPath = s.photoPath, videoPath = s.videoPath, videoDurationMs = s.videoDurationMs, mediaId = uploadedMediaId,
                        caption = s.caption, text = s.text, background = s.background, audience = s.audience, clientId = clientId,
                    ),
                )
                // The files are on the server now; the local copies are not needed any more.
                PhotoStore.delete(s.photoPath)
                VideoStore.delete(s.videoPath)
                _state.update { it.copy(publishing = false, published = true, uploadProgress = null, notice = "Added to your story") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = (e as? ApiException)?.message?.takeIf { it.isNotBlank() }
                    ?: if (e is java.io.IOException && e.message?.startsWith("That file is too large") == true) e.message else null
                _state.update { it.copy(publishing = false, uploadProgress = null, failure = message ?: "Couldn't post your story. Check your connection and try again.") }
            }
        }
    }

    /** Stops an upload in progress. Everything stays in the composer. */
    fun cancelUpload() {
        job?.cancel()
        job = null
        _state.update { it.copy(publishing = false, uploadProgress = null, notice = "Upload cancelled") }
    }
}
