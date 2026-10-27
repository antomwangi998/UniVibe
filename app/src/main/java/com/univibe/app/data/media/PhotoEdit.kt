package com.univibe.app.data.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

/**
 * Reading, rotating and cropping photos that [PhotoStore] already put in app storage (the photo editor uses this).
 * Edited copies go in the same folder, so [PhotoStore.delete] can remove them like any other photo.
 */
object PhotoEdit {
    private const val DIR = "photos"
    private const val JPEG_QUALITY = 90

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
}
