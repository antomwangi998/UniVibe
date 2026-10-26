package com.univibe.app.guide

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt

/**
 * Where to put the tooltip card, inside [safe] (the screen minus system bars, navigation bar and keyboard).
 * Prefers just below the target, then just above it; if neither fits it sits on the roomier side. It never leaves [safe].
 * With no target the card is centred.
 */
fun placeTooltip(safe: Rect, target: Rect?, tip: IntSize, gap: Int, margin: Int): IntOffset {
    val minX = (safe.left + margin).roundToInt()
    val minY = (safe.top + margin).roundToInt()
    val maxX = maxOf(minX, (safe.right - margin - tip.width).roundToInt())
    val maxY = maxOf(minY, (safe.bottom - margin - tip.height).roundToInt())
    if (target == null) {
        return IntOffset(((safe.left + safe.right - tip.width) / 2f).roundToInt().coerceIn(minX, maxX), ((safe.top + safe.bottom - tip.height) / 2f).roundToInt().coerceIn(minY, maxY))
    }
    val x = (target.center.x - tip.width / 2f).roundToInt().coerceIn(minX, maxX)
    val below = target.bottom + gap
    val above = target.top - gap - tip.height
    val y = when {
        below + tip.height <= safe.bottom - margin -> below
        above >= safe.top + margin -> above
        target.center.y < (safe.top + safe.bottom) / 2f -> maxY.toFloat()
        else -> minY.toFloat()
    }
    return IntOffset(x, y.roundToInt().coerceIn(minY, maxY))
}
