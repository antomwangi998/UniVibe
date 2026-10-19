package com.univibe.app.data.net

import com.univibe.app.data.model.PostLimits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Sends one file to a short-lived upload link. Throws [IOException] if it does not arrive. */
fun interface FileUploader {
    suspend fun put(url: String, contentType: String, file: File)

    /**
     * Same, and tells [onProgress] how many bytes have been sent so far. Implementations that cannot say simply report
     * the whole file once it has arrived.
     */
    suspend fun put(url: String, contentType: String, file: File, onProgress: (sent: Long, total: Long) -> Unit) {
        put(url, contentType, file)
        onProgress(file.length(), file.length())
    }
}

/** Streams the file straight from disk to the storage link (a signed PUT), so big videos never sit in memory. */
class UrlConnectionFileUploader(
    private val connectTimeoutMs: Int = 20_000,
    private val readTimeoutMs: Int = 120_000,
) : FileUploader {
    override suspend fun put(url: String, contentType: String, file: File) = put(url, contentType, file) { _, _ -> }

    override suspend fun put(url: String, contentType: String, file: File, onProgress: (sent: Long, total: Long) -> Unit): Unit = withContext(Dispatchers.IO) {
        if (!url.startsWith("https://")) throw IOException("Uploads must use HTTPS")
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "PUT"
            connection.doOutput = true
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.setFixedLengthStreamingMode(file.length())
            connection.setRequestProperty("Content-Type", contentType)
            val total = file.length()
            var sent = 0L
            var lastPercent = -1
            val buffer = ByteArray(16 * 1024)
            file.inputStream().use { input ->
                connection.outputStream.use { output ->
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        sent += n
                        ensureActive() // leaving the screen or cancelling stops the upload
                        val percent = if (total > 0) (sent * 100 / total).toInt() else 100
                        if (percent != lastPercent) { lastPercent = percent; onProgress(sent, total) } // at most 101 callbacks
                    }
                }
            }
            val status = connection.responseCode
            if (status !in 200..299) throw IOException("Upload failed ($status)")
        } finally {
            connection.disconnect()
        }
    }
}

/**
 * Uploads a local photo or video in the three steps the server expects: ask for a link, send the file to it,
 * then tell the server it is done so it can check what arrived. Returns the media id to attach to a post or profile.
 */
/** Same ceiling as the server's voice-message limit; checked here first so a too-long recording fails before any upload. */
const val MAX_AUDIO_BYTES = 5L * 1024 * 1024

class MediaUploader(private val api: AuthedApi, private val files: FileUploader) {
    /**
     * [tag] ties the upload to something on screen (see [UploadTracker]); [onProgress] gets 0..1 as the file is sent.
     * Every upload is also visible in [UploadTracker.uploads] while it runs, and removed when it ends, however it ends.
     */
    suspend fun upload(
        path: String, kind: String, purpose: String, durationMs: Int? = null,
        tag: String? = null, onProgress: ((Float) -> Unit)? = null,
    ): String {
        val id = UploadTracker.begin(kind, tag)
        try {
            return transfer(path, kind, purpose, durationMs) { f -> UploadTracker.progress(id, f); onProgress?.invoke(f) }
        } finally {
            UploadTracker.end(id)
        }
    }

    private suspend fun transfer(path: String, kind: String, purpose: String, durationMs: Int?, report: (Float) -> Unit): String {
        val file = File(path)
        if (!file.isFile || file.length() == 0L) throw IOException("The file is missing")
        // Fail at once, before using any data, when the server would refuse the file anyway.
        val limit = when (kind) { "video" -> PostLimits.MAX_VIDEO_BYTES; "audio" -> MAX_AUDIO_BYTES; else -> PostLimits.MAX_IMAGE_UPLOAD_BYTES }
        if (file.length() > limit) throw IOException("That file is too large (the limit is ${limit / (1024 * 1024)} MB).")
        val contentType = contentTypeOf(file, kind)
        val request = JSONObject()
            .put("kind", kind).put("purpose", purpose).put("contentType", contentType).put("size", file.length())
        // Pixel size lets the feed reserve the right space before the file loads. Best effort: skipped if unreadable.
        if (kind != "audio") MediaDimensions.read(file, kind)?.let { (w, h) -> request.put("width", w).put("height", h) }
        if (kind == "audio") request.put("durationMs", durationMs ?: throw IOException("The recording has no length"))
        val created = JSONObject(api.send("POST", "/media/uploads", request, setOf(201)))
        report(0f)
        files.put(created.getString("uploadUrl"), contentType, file) { sent, total -> report(if (total > 0) sent.toFloat() / total else 1f) }
        val id = created.getString("mediaId")
        // Accept HTTP 200 from some servers for the complete call (tests and servers may return 200)
        api.send("POST", "/media/$id/complete", null, setOf(200))
        return id
    }

    private fun contentTypeOf(file: File, kind: String): String = if (kind == "audio") when (file.extension.lowercase()) {
        "aac" -> "audio/aac"
        "ogg" -> "audio/ogg"
        "weba", "webm" -> "audio/webm"
        else -> "audio/mp4" // m4a: what the app records
    } else when (file.extension.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "mp4" -> "video/mp4"
        "mov" -> "video/quicktime"
        "webm" -> "video/webm"
        else -> if (kind == "video") "video/mp4" else "image/jpeg"
    }
}

/** Reads the pixel size of a local photo or video without decoding it fully. Null when it can't be read. */
internal object MediaDimensions {
    fun read(file: File, kind: String): Pair<Int, Int>? = runCatching {
        val (w, h) = if (kind == "video") readVideo(file) else readImage(file)
        if (w > 0 && h > 0 && w <= 20_000 && h <= 20_000) w to h else null
    }.getOrNull()

    private fun readImage(file: File): Pair<Int, Int> {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.path, bounds)
        return bounds.outWidth to bounds.outHeight
    }

    private fun readVideo(file: File): Pair<Int, Int> {
        val retriever = android.media.MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.path)
            val w = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rotation = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            // A phone video recorded upright is stored sideways with a rotation flag.
            return if (rotation == 90 || rotation == 270) h to w else w to h
        } finally {
            retriever.release()
        }
    }
}
