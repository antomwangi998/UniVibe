package com.univibe.app.data.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

/**
 * Copies a picked image into private app storage, downscaled and re-encoded as JPEG.
 * Re-encoding removes EXIF metadata (GPS, camera) before the photo is ever shared.
 * A real backend must still validate and re-process uploads server-side.
 */
object PhotoStore {
    private const val MAX_EDGE = 1600
    private const val DIR = "photos"

    suspend fun import(context: Context, uri: Uri, prefix: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            val type = resolver.getType(uri)
            if (type == null || !type.startsWith("image/")) return@runCatching null
            val bitmap: Bitmap = if (Build.VERSION.SDK_INT >= 28) {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val longest = max(info.size.width, info.size.height)
                    if (longest > MAX_EDGE) {
                        val ratio = MAX_EDGE.toFloat() / longest
                        decoder.setTargetSize((info.size.width * ratio).toInt().coerceAtLeast(1), (info.size.height * ratio).toInt().coerceAtLeast(1))
                    }
                }
            } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (max(bounds.outWidth, bounds.outHeight) / sample > MAX_EDGE) sample *= 2
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return@runCatching null
            }
            val dir = File(context.filesDir, DIR).apply { mkdirs() }
            val file = File(dir, "${prefix}_${System.currentTimeMillis()}.jpg")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            file.absolutePath
        }.getOrNull()
    }

    fun delete(path: String?) {
        if (path == null) return
        runCatching {
            val file = File(path)
            if (file.parentFile?.name == DIR) file.delete()
        }
    }
}
