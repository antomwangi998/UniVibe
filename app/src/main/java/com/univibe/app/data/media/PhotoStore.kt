package com.univibe.app.data.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
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
    /** Longest edge kept on the phone. Generous on purpose: tall phone photos are narrow, and the server does the final shrinking. */
    private const val MAX_EDGE = 2560
    private const val JPEG_QUALITY = 90
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
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
            file.absolutePath
        }.getOrNull()
    }

    /** Width / height of a stored photo, or null when it can't be read. Reads only the file header. */
    suspend fun aspectOf(path: String): Float? = withContext(Dispatchers.IO) {
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth > 0 && bounds.outHeight > 0) bounds.outWidth.toFloat() / bounds.outHeight else null
        }.getOrNull()
    }

    /** A screen-sized copy of a stored photo for the editor (the real file is only read again when saving). */
    suspend fun preview(path: String, maxEdge: Int = 1600): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / sample > maxEdge) sample *= 2
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrNull()
    }

    /** [bitmap] turned clockwise by [quarterTurns] quarter turns (the same bitmap when that is a multiple of four). */
    fun rotated(bitmap: Bitmap, quarterTurns: Int): Bitmap {
        val turns = ((quarterTurns % 4) + 4) % 4
        if (turns == 0) return bitmap
        val matrix = Matrix().apply { postRotate(90f * turns) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /**
     * Rotates a stored photo by [quarterTurns] and cuts out [crop] (fractions of the rotated photo), saving the result
     * as a new file in app storage at full resolution. Returns its path, or [path] itself when nothing changed
     * (so a photo is never re-compressed for no reason), or null if it could not be done. The old file is left for the caller to delete.
     */
    suspend fun edit(context: Context, path: String, crop: CropRect, quarterTurns: Int): String? = withContext(Dispatchers.IO) {
        runCatching<String?> {
            if (crop.isFull && quarterTurns % 4 == 0) return@runCatching path
            val source = BitmapFactory.decodeFile(path) ?: return@runCatching null
            val turned = rotated(source, quarterTurns)
            val px = CropGeometry.toPixels(crop, turned.width, turned.height)
            val cut = Bitmap.createBitmap(turned, px.x, px.y, px.width, px.height)
            val dir = File(context.filesDir, DIR).apply { mkdirs() }
            val prefix = File(path).name.substringBefore('_')
            val file = File(dir, "${prefix}_${System.currentTimeMillis()}.jpg")
            file.outputStream().use { cut.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
            if (turned !== source) source.recycle()
            if (cut !== turned) turned.recycle()
            cut.recycle()
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
