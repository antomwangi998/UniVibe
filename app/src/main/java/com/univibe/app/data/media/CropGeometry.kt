package com.univibe.app.data.media

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A crop window, as fractions (0..1) of the photo's width and height. */
data class CropRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    /** True when nothing would be cut off. */
    val isFull: Boolean get() = left <= 0.001f && top <= 0.001f && right >= 0.999f && bottom >= 0.999f

    companion object {
        val Full = CropRect(0f, 0f, 1f, 1f)
    }
}

/** Which part of the crop window a finger is holding. */
enum class CropHandle {
    TopLeft, Top, TopRight, Right, BottomRight, Bottom, BottomLeft, Left, Move;

    val isCorner: Boolean get() = this == TopLeft || this == TopRight || this == BottomLeft || this == BottomRight
}

/** A crop in whole pixels of the (already rotated) photo. */
data class PixelCrop(val x: Int, val y: Int, val width: Int, val height: Int)

/**
 * The maths of the photo cropper, with no Android types so it can be unit tested.
 * Every function keeps the crop window inside the photo and at least [MIN_SIDE] of each side.
 */
object CropGeometry {
    /** The smallest crop edge, as a fraction of the photo's side. */
    const val MIN_SIDE = 0.12f

    private fun clamp(v: Float, lo: Float, hi: Float): Float = if (lo > hi) lo else v.coerceIn(lo, hi)

    /** Width / height of the window in real pixels. */
    fun ratioOf(r: CropRect, imgW: Int, imgH: Int): Float = (r.width * imgW) / (r.height * imgH)

    /** The largest window of pixel ratio [aspect] (width / height) that fits the whole photo, centred. */
    fun fit(aspect: Float, imgW: Int, imgH: Int): CropRect {
        val imgAspect = imgW.toFloat() / imgH
        val w: Float
        val h: Float
        if (aspect >= imgAspect) { w = 1f; h = imgAspect / aspect } else { h = 1f; w = aspect / imgAspect }
        val l = (1f - w) / 2f
        val t = (1f - h) / 2f
        return CropRect(l, t, l + w, t + h)
    }

    fun move(r: CropRect, dx: Float, dy: Float): CropRect {
        val mx = clamp(dx, -r.left, 1f - r.right)
        val my = clamp(dy, -r.top, 1f - r.bottom)
        return CropRect(r.left + mx, r.top + my, r.right + mx, r.bottom + my)
    }

    /** Free-shape resize: a corner moves two edges, an edge moves one. */
    fun resizeFree(r: CropRect, handle: CropHandle, dx: Float, dy: Float): CropRect {
        val movesLeft = handle == CropHandle.TopLeft || handle == CropHandle.Left || handle == CropHandle.BottomLeft
        val movesRight = handle == CropHandle.TopRight || handle == CropHandle.Right || handle == CropHandle.BottomRight
        val movesTop = handle == CropHandle.TopLeft || handle == CropHandle.Top || handle == CropHandle.TopRight
        val movesBottom = handle == CropHandle.BottomLeft || handle == CropHandle.Bottom || handle == CropHandle.BottomRight
        var l = r.left
        var t = r.top
        var rt = r.right
        var b = r.bottom
        if (movesLeft) l = clamp(r.left + dx, 0f, r.right - MIN_SIDE)
        if (movesRight) rt = clamp(r.right + dx, r.left + MIN_SIDE, 1f)
        if (movesTop) t = clamp(r.top + dy, 0f, r.bottom - MIN_SIDE)
        if (movesBottom) b = clamp(r.bottom + dy, r.top + MIN_SIDE, 1f)
        return CropRect(l, t, rt, b)
    }

    /** Resize by a corner while keeping the pixel ratio [aspect]: the opposite corner stays put. */
    fun resizeLocked(r: CropRect, handle: CropHandle, dx: Float, dy: Float, aspect: Float, imgW: Int, imgH: Int): CropRect {
        if (!handle.isCorner) return r
        val imgAspect = imgW.toFloat() / imgH
        val left = handle == CropHandle.TopLeft || handle == CropHandle.BottomLeft
        val top = handle == CropHandle.TopLeft || handle == CropHandle.TopRight
        val anchorX = if (left) r.right else r.left
        val anchorY = if (top) r.bottom else r.top
        val movingX = (if (left) r.left else r.right) + dx
        val movingY = (if (top) r.top else r.bottom) + dy
        val dirX = if (left) -1f else 1f
        val dirY = if (top) -1f else 1f
        val availW = if (left) anchorX else 1f - anchorX
        val availH = if (top) anchorY else 1f - anchorY
        var w = ((movingX - anchorX) * dirX).coerceAtLeast(0f)
        var h = ((movingY - anchorY) * dirY).coerceAtLeast(0f)
        // The window follows whichever of width and height the finger asked for more of.
        val hForW = w * imgAspect / aspect
        if (hForW >= h) h = hForW else w = h * aspect / imgAspect
        val minW = max(MIN_SIDE, MIN_SIDE * aspect / imgAspect)
        if (w < minW) { w = minW; h = w * imgAspect / aspect }
        val scale = min(1f, min(availW / w, availH / h))
        w *= scale
        h *= scale
        val l = if (left) anchorX - w else anchorX
        val t = if (top) anchorY - h else anchorY
        return CropRect(l, t, l + w, t + h)
    }

    fun drag(r: CropRect, handle: CropHandle, dx: Float, dy: Float, aspect: Float?, imgW: Int, imgH: Int): CropRect = when {
        handle == CropHandle.Move -> move(r, dx, dy)
        aspect != null -> resizeLocked(r, handle, dx, dy, aspect, imgW, imgH)
        else -> resizeFree(r, handle, dx, dy)
    }

    /**
     * What a finger put down at ([x], [y]) is holding, given the window's edges on screen. Corners win over edges,
     * edges over the inside. [edges] is false when the shape is locked, since a locked window only resizes by corners.
     */
    fun pickHandle(l: Float, t: Float, r: Float, b: Float, x: Float, y: Float, slop: Float, edges: Boolean): CropHandle? {
        var best: CropHandle? = null
        var bestD = Float.MAX_VALUE
        fun consider(h: CropHandle, cx: Float, cy: Float) {
            val dx = abs(x - cx)
            val dy = abs(y - cy)
            if (dx <= slop && dy <= slop) {
                val d = dx * dx + dy * dy
                if (d < bestD) { best = h; bestD = d }
            }
        }
        consider(CropHandle.TopLeft, l, t)
        consider(CropHandle.TopRight, r, t)
        consider(CropHandle.BottomLeft, l, b)
        consider(CropHandle.BottomRight, r, b)
        if (best != null) return best
        if (edges) {
            if (y >= t - slop && y <= b + slop) {
                if (abs(x - l) <= slop) return CropHandle.Left
                if (abs(x - r) <= slop) return CropHandle.Right
            }
            if (x >= l - slop && x <= r + slop) {
                if (abs(y - t) <= slop) return CropHandle.Top
                if (abs(y - b) <= slop) return CropHandle.Bottom
            }
        }
        if (x >= l && x <= r && y >= t && y <= b) return CropHandle.Move
        return null
    }

    /** The window in whole pixels of an [imgW] x [imgH] photo, always inside it and at least 1 px each way. */
    fun toPixels(r: CropRect, imgW: Int, imgH: Int): PixelCrop {
        val x = (r.left * imgW).roundToInt().coerceIn(0, imgW - 1)
        val y = (r.top * imgH).roundToInt().coerceIn(0, imgH - 1)
        val right = (r.right * imgW).roundToInt().coerceIn(x + 1, imgW)
        val bottom = (r.bottom * imgH).roundToInt().coerceIn(y + 1, imgH)
        return PixelCrop(x, y, right - x, bottom - y)
    }
}
