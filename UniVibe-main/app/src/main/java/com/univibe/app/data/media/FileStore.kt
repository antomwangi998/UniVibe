package com.univibe.app.data.media

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** A file the person picked to share, already copied into app storage. [name] is what people will see. */
class PickedFile(val path: String, val name: String, val sizeBytes: Long, val contentType: String)

sealed interface FileImport {
    class Ok(val file: PickedFile) : FileImport
    class Refused(val reason: String) : FileImport
}

/**
 * Brings a picked document into app storage and checks it the way the server will (type, size), so a file that
 * would be refused is turned down at once instead of after an upload. The list of types matches the server's
 * (backend/src/documents.ts): study files only, nothing that can run.
 */
object FileStore {
    const val MAX_BYTES = 25L * 1024 * 1024
    private const val DIR = "chat-files"

    val TYPES: Map<String, String> = mapOf(
        "pdf" to "application/pdf",
        "txt" to "text/plain",
        "csv" to "text/csv",
        "rtf" to "application/rtf",
        "doc" to "application/msword",
        "xls" to "application/vnd.ms-excel",
        "ppt" to "application/vnd.ms-powerpoint",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "odt" to "application/vnd.oasis.opendocument.text",
        "ods" to "application/vnd.oasis.opendocument.spreadsheet",
        "odp" to "application/vnd.oasis.opendocument.presentation",
    )

    /** The type from the file's own extension (a phone's guess is often just "binary"), else the phone's guess if it is one we allow. */
    fun typeFor(name: String, reported: String?): String? =
        TYPES[name.substringAfterLast('.', "").lowercase()] ?: reported?.takeIf { it in TYPES.values }

    /** Letters, digits and ordinary punctuation only: no control or direction-changing characters in a name people will read. */
    fun cleanName(raw: String): String =
        raw.substringAfterLast('/').substringAfterLast('\\')
            .filterNot { it.isISOControl() || it in '\u200b'..'\u200f' || it in '\u202a'..'\u202e' || it in '\u2066'..'\u2069' || it == '\ufeff' }
            .trim().ifBlank { "document" }

    suspend fun import(context: Context, uri: Uri): FileImport = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            var displayName: String? = null
            var size = -1L
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    displayName = c.getString(0)
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
            val shown = cleanName(displayName ?: uri.lastPathSegment ?: "document")
            val type = typeFor(shown, resolver.getType(uri))
                ?: return@runCatching FileImport.Refused("That kind of file can't be shared here. PDFs, Office documents and text files work.")
            if (size > MAX_BYTES) return@runCatching FileImport.Refused("That file is too large (the limit is 25 MB).")
            val ext = shown.substringAfterLast('.', "").lowercase().takeIf { TYPES[it] == type } ?: TYPES.entries.first { it.value == type }.key
            val out = File(File(context.filesDir, DIR).apply { mkdirs() }, "${UUID.randomUUID()}.$ext")
            var copied = 0L
            val input = resolver.openInputStream(uri) ?: return@runCatching FileImport.Refused("Couldn't open that file.")
            input.use { src ->
                out.outputStream().use { dst ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = src.read(buffer)
                        if (n < 0) break
                        copied += n
                        if (copied > MAX_BYTES) break // a provider that did not report its size
                        dst.write(buffer, 0, n)
                    }
                }
            }
            when {
                copied > MAX_BYTES -> { out.delete(); FileImport.Refused("That file is too large (the limit is 25 MB).") }
                copied == 0L -> { out.delete(); FileImport.Refused("That file is empty.") }
                else -> FileImport.Ok(PickedFile(out.absolutePath, shown, copied, type))
            }
        }.getOrElse { FileImport.Refused("Couldn't read that file.") }
    }
}
