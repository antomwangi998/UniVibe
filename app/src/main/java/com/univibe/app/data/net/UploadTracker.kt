package com.univibe.app.data.net

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

/**
 * Every media upload (post photos and videos, profile and group photos, chat photos, voice messages) registers here for as
 * long as it runs, with how much of the file has been sent. Screens read this to show a real percentage; nothing here
 * changes how uploads work. An upload is removed when it succeeds, fails or is cancelled, so nothing is left "uploading".
 */
object UploadTracker {
    /** [tag] links an upload to something on screen, e.g. the id of a chat message that is still sending. */
    data class Upload(val id: Long, val kind: String, val tag: String?, val fraction: Float)

    private val nextId = AtomicLong(1)
    private val _uploads = MutableStateFlow<List<Upload>>(emptyList())
    val uploads: StateFlow<List<Upload>> = _uploads.asStateFlow()

    fun begin(kind: String, tag: String?): Long {
        val id = nextId.getAndIncrement()
        _uploads.update { it + Upload(id, kind, tag, 0f) }
        return id
    }

    fun progress(id: Long, fraction: Float) {
        val f = fraction.coerceIn(0f, 1f)
        _uploads.update { list -> list.map { if (it.id == id) it.copy(fraction = f) else it } }
    }

    fun end(id: Long) = _uploads.update { list -> list.filterNot { it.id == id } }

    internal fun reset() { _uploads.value = emptyList() }
}
