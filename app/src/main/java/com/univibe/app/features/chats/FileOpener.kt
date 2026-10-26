package com.univibe.app.features.chats

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.univibe.app.data.media.FileStore
import com.univibe.app.data.model.FileAttachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Gets a shared file onto the phone and hands it to the app that can show it. */
object FileOpener {
    private const val DOWNLOADS = "chat-files-dl"

    /**
     * The file on this phone: our own copy if it is still here, otherwise it is downloaded once into the cache and reused.
     * Null when it cannot be had (no connection, link expired, file too big).
     */
    suspend fun ensureLocal(context: Context, file: FileAttachment, messageId: String): File? = withContext(Dispatchers.IO) {
        runCatching {
            if (!file.source.startsWith("http", ignoreCase = true)) return@runCatching File(file.source).takeIf { it.isFile }
            // One folder per message, and the real name inside it, so other apps show the name people expect.
            val folder = File(File(context.cacheDir, DOWNLOADS), messageId.filter { it.isLetterOrDigit() || it == '-' }.take(64).ifBlank { "message" }).apply { mkdirs() }
            val target = File(folder, File(file.name).name.ifBlank { "document" })
            if (target.isFile && target.length() > 0) return@runCatching target
            val part = File(folder, "download.part")
            val connection = (URL(file.source).openConnection() as HttpURLConnection).apply { connectTimeout = 15_000; readTimeout = 30_000 }
            try {
                if (connection.responseCode !in 200..299) return@runCatching null
                var total = 0L
                connection.inputStream.use { src ->
                    part.outputStream().use { dst ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val n = src.read(buffer)
                            if (n < 0) break
                            total += n
                            if (total > FileStore.MAX_BYTES + 1024 * 1024) { part.delete(); return@runCatching null }
                            dst.write(buffer, 0, n)
                        }
                    }
                }
                if (part.renameTo(target)) target else null
            } finally {
                connection.disconnect()
                part.delete()
            }
        }.getOrNull()
    }

    /** Opens [file] in an app that handles [mime]. False when no app on the phone can. Other apps only ever get a one-time link, never the path. */
    fun open(context: Context, file: File, mime: String): Boolean {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newRawUri(file.name, uri) // lets the chooser pass the permission on to the app that is picked
        }
        val chooser = Intent.createChooser(view, "Open with").apply { if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        return try { context.startActivity(chooser); true } catch (e: ActivityNotFoundException) { false }
    }
}
