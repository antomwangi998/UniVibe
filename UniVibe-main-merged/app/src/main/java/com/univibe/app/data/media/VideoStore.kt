package com.univibe.app.data.media

import android.content.Context
import android.net.Uri
import com.univibe.app.data.model.PostLimits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Copies a picked video into private app storage so the post keeps working after the picker's temporary access ends.
 * Videos over [PostLimits.MAX_VIDEO_BYTES] are rejected while copying. Mock-only: a real backend uploads the file
 * (pre-signed upload), transcodes it server-side and returns a streaming URL. The app can't strip metadata from video.
 */
object VideoStore {
    private const val DIR = "videos"

    suspend fun import(context: Context, uri: Uri, prefix: String): String? = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        val file = File(dir, "${prefix}_${System.currentTimeMillis()}.mp4")
        val ok = runCatching {
            val resolver = context.contentResolver
            val type = resolver.getType(uri)
            if (type == null || !type.startsWith("video/")) return@runCatching false
            resolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > PostLimits.MAX_VIDEO_BYTES) return@runCatching false
                        output.write(buffer, 0, read)
                    }
                    total > 0
                }
            } ?: false
        }.getOrDefault(false)
        if (ok) file.absolutePath else { file.delete(); null }
    }

    fun delete(path: String?) {
        if (path == null) return
        runCatching {
            val file = File(path)
            if (file.parentFile?.name == DIR) file.delete()
        }
    }
}
