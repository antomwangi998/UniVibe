package com.univibe.app.ui.components

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt

/**
 * A real blur behind ONE fixed bar (the bottom tab bar), Android 12+ only.
 *
 * How it works without a library: the screen content is drawn once into a [GraphicsLayer] and replayed from it. The
 * region under the bar is replayed a second time into a small bar-sized layer carrying a RenderEffect blur, then drawn
 * clipped to the bar's rounded shape. The bar's own translucent glass is drawn on top. Nothing is re-rendered: content is
 * recorded once and replayed, and the blur runs on the GPU over bar-sized pixels only.
 *
 * Used for fixed bars only, never per list item. Null (= nothing happens) below Android 12, with glass off (low-RAM /
 * High contrast), or when [GlassTokens.BackdropBlur] is false.
 */
@Stable
class GlassBackdropState internal constructor(internal val content: GraphicsLayer, internal val blurred: GraphicsLayer) {
    internal var sourceOrigin by mutableStateOf(Offset.Zero)
    /** The bar's bounds in root coordinates; null while the bar is not on screen. */
    internal var barBounds by mutableStateOf<Rect?>(null)
}

@Composable
fun rememberGlassBackdropState(): GlassBackdropState? {
    val supported = GlassTokens.BackdropBlur && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && LocalGlassEnabled.current
    if (!supported) return null
    val content = rememberGraphicsLayer()
    val blurred = rememberGraphicsLayer()
    return remember(content, blurred) { GlassBackdropState(content, blurred) }
}

/** Put on the container holding the screens (the NavHost). It looks the same as without it, plus the blurred patch under the bar. */
fun Modifier.glassBackdropSource(state: GlassBackdropState?): Modifier = if (state == null) this else this
    .onGloballyPositioned { state.sourceOrigin = it.positionInRoot() }
    .drawWithContent {
        state.content.record { this@drawWithContent.drawContent() }
        drawLayer(state.content)
        val bar = state.barBounds
        if (bar != null && bar.width > 1f && bar.height > 1f) {
            val local = bar.translate(-state.sourceOrigin)
            if (state.blurred.renderEffect == null) {
                val radius = GlassTokens.BlurRadius.toPx()
                state.blurred.renderEffect = BlurEffect(radius, radius, TileMode.Clamp)
            }
            state.blurred.record(size = IntSize(local.width.roundToInt(), local.height.roundToInt())) {
                translate(-local.left, -local.top) { drawLayer(state.content) }
            }
            val clip = Path().apply { addRoundRect(RoundRect(local, CornerRadius(GlassTokens.ComposerCorner.toPx()))) }
            clipPath(clip) { translate(local.left, local.top) { drawLayer(state.blurred) } }
        }
    }

/** Put on the bar itself (its visible glass shape). Reports where it is; clears itself when the bar leaves the screen. */
@Composable
fun Modifier.glassBackdropTarget(state: GlassBackdropState?): Modifier {
    if (state == null) return this
    DisposableEffect(state) { onDispose { state.barBounds = null } }
    return this.onGloballyPositioned { state.barBounds = it.boundsInRoot() }
}
