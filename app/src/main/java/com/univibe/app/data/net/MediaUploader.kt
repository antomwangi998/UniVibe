package com.univibe.app.data.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Sends one file to a short-lived upload link. Throws [IOException] if it does not arrive. */
fun interface FileUploader {
    suspend fun put(url: String, contentType: String, file: File)
}

/** Streams the file straight from disk to the storage link (a signed PUT), so big videos never sit in memory. */
class UrlConnectionFileUploader(
    private val connectTimeoutMs: Int = 20_000,
    private val readTimeoutMs: Int = 120_000,
) : FileUploader {
    override suspend fun put(url: String, contentType: String, file: File) = withContext(Dispatchers.IO) {
        if (!url.startsWith("https://")) throw IOException("Uploads must use HTTPS")
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "PUT"
            connection.doOutput = true
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.setFixedLengthStreamingMode(file.length())
            connection.setRequestProperty("Content-Type", contentType)
            file.inputStream().use { input -> connection.outputStream.use { output -> input.copyTo(output) } }
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
class MediaUploader(private val api: AuthedApi, private val files: FileUploader) {
    suspend fun upload(path: String, kind: String, purpose: String): String {
        val file = File(path)
        if (!file.isFile || file.length() == 0L) throw IOException("The file is missing")
        val contentType = contentTypeOf(file, kind)
        val request = JSONObject()
            .put("kind", kind).put("purpose", purpose).put("contentType", contentType).put("size", file.length())
        val created = JSONObject(api.send("POST", "/media/uploads", request, setOf(201)))
        files.put(created.getString("uploadUrl"), contentType, file)
        val id = created.getString("mediaId")
        // Accept HTTP 200 from some servers for the complete call (tests and servers may return 200)
        api.send("POST", "/media/$id/complete", null, setOf(200))
        return id
    }

    private fun contentTypeOf(file: File, kind: String): String = when (file.extension.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "mp4" -> "video/mp4"
        "mov" -> "video/quicktime"
        "webm" -> "video/webm"
        else -> if (kind == "video") "video/mp4" else "image/jpeg"
    }
}
