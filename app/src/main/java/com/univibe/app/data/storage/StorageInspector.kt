package com.univibe.app.data.storage

import android.content.Context
import android.os.StatFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** What UniVibe uses on this phone, measured from the real folders. Nothing here is estimated. */
data class StorageSummary(
    /** Pictures loaded from the internet, kept so they open faster. Safe to clear: they download again when needed. */
    val imageCacheBytes: Long,
    /** Map tiles kept for event venue maps. Safe to clear. */
    val mapCacheBytes: Long,
    /** Anything else in the app's cache folder. Safe to clear. */
    val otherCacheBytes: Long,
    /** Photos and videos copied into the app when you add them to a post, message or profile. Not cache: not cleared here. */
    val mediaBytes: Long,
    val deviceAvailableBytes: Long,
    val deviceTotalBytes: Long,
) {
    val cacheBytes: Long get() = imageCacheBytes + mapCacheBytes + otherCacheBytes
}

/**
 * Measures and clears UniVibe's cache. Clearing the cache only deletes copies the app can fetch again; it never touches
 * your account, your messages or your posts on the server, and it never deletes [StorageSummary.mediaBytes].
 * Takes plain folders so it can be tested without a phone.
 */
class StorageInspector(
    private val cacheDir: File,
    private val filesDir: File,
    private val deviceStorage: () -> Pair<Long, Long>,
) {
    suspend fun measure(): StorageSummary = withContext(Dispatchers.IO) {
        val image = sizeOf(File(cacheDir, IMAGE_CACHE))
        val map = sizeOf(File(cacheDir, MAP_CACHE))
        val all = sizeOf(cacheDir)
        val media = sizeOf(File(filesDir, "photos")) + sizeOf(File(filesDir, "videos"))
        val (available, total) = deviceStorage()
        StorageSummary(image, map, (all - image - map).coerceAtLeast(0), media, available, total)
    }

    /** Deletes everything inside the cache folder (not the folder itself). Returns how many bytes were freed. */
    suspend fun clearCache(): Long = withContext(Dispatchers.IO) {
        val before = sizeOf(cacheDir)
        cacheDir.listFiles()?.forEach { runCatching { it.deleteRecursively() } }
        (before - sizeOf(cacheDir)).coerceAtLeast(0)
    }

    companion object {
        const val IMAGE_CACHE = "image_cache"
        const val MAP_CACHE = "osmdroid"

        fun forContext(context: Context): StorageInspector = StorageInspector(context.cacheDir, context.filesDir) {
            runCatching {
                val stat = StatFs(context.filesDir.absolutePath)
                stat.availableBytes to stat.totalBytes
            }.getOrDefault(0L to 0L)
        }

        fun sizeOf(file: File): Long = when {
            !file.exists() -> 0L
            file.isFile -> file.length()
            else -> file.listFiles()?.sumOf { sizeOf(it) } ?: 0L
        }
    }
}

/** "0 B", "512 B", "1.5 KB", "23.4 MB", "1.20 GB" (1 KB = 1024 bytes, as Android's own storage screens show). */
fun formatBytes(bytes: Long, locale: Locale = Locale.US): String {
    if (bytes < 1024) return "${bytes.coerceAtLeast(0)} B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = -1
    while (value >= 1024 && unit < units.lastIndex) { value /= 1024; unit++ }
    val pattern = if (unit >= 2) "%.2f %s" else "%.1f %s"
    return String.format(locale, pattern, value, units[unit])
}
