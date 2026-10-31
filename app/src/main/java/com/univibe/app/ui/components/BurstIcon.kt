package com.univibe.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private const val PARTICLES = 8

private fun easeOutCubic(x: Float): Float { val t = 1f - x; return 1f - t * t * t }

/**
 * The like animation used everywhere in the app: when [selected] turns true the icon shrinks, springs back with an
 * overshoot, a ring expands from behind it and fades, and a ring of small dots flies outward. Turning it off only
 * gives a small dip. Nothing plays when the icon first appears already selected (scrolling a liked post back into
 * view stays quiet). With "Reduce motion" on, the icon just changes state.
 *
 * The burst is drawn outside the icon's own bounds and never changes the layout. The animated values are read while
 * drawing, so the surrounding screen is not recomposed during the animation.
 */
@Composable
fun BurstIcon(
    icon: ImageVector,
    selected: Boolean,
    tint: Color,
    modifier: Modifier = Modifier,
    iconSize: Dp = 22.dp,
    ringColor: Color = tint,
    burstColors: List<Color> = defaultLikeBurstColors(),
) {
    val reduced = rememberReducedMotion()
    val pop = remember { Animatable(1f) }
    val burst = remember { Animatable(0f) }
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(selected) {
        // The first run only records the starting state, so an already-liked item does not animate on arrival.
        if (!armed) { armed = true; return@LaunchedEffect }
        if (reduced) return@LaunchedEffect
        if (selected) {
            burst.snapTo(0f)
            pop.snapTo(0.45f)
            coroutineScope {
                launch { pop.animateTo(1f, spring(dampingRatio = 0.38f, stiffness = Spring.StiffnessMedium)) }
                burst.animateTo(1f, tween(durationMillis = 650, easing = LinearEasing))
            }
            burst.snapTo(0f)
        } else {
            burst.snapTo(0f)
            pop.snapTo(0.82f)
            pop.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
        }
    }
    Box(
        modifier.size(iconSize).drawBehind { drawLikeBurst(burst.value, ringColor, burstColors) },
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(iconSize).graphicsLayer { scaleX = pop.value; scaleY = pop.value },
        )
    }
}

/** The colour of a liked heart everywhere in the app: the same hot pink X uses (#F91880). */
val LikeColor = Color(0xFFF91880)

/** Heart pink first, then the brand teal, a warm tone and a lighter pink. No purple, to match the rest of the app. */
@Composable
fun defaultLikeBurstColors(): List<Color> =
    listOf(LikeColor, MaterialTheme.colorScheme.primary, Color(0xFFFFB74D), Color(0xFFFF7AB6))

/**
 * One frame of the burst: an expanding ring and a ring of dots, around the centre of this draw area. [p] runs from 0
 * (nothing drawn) to 1 (finished); sizes follow the area, so the same burst works behind a small icon and a big heart.
 */
internal fun DrawScope.drawLikeBurst(p: Float, ringColor: Color, burstColors: List<Color>) {
    if (p <= 0f || p >= 1f) return
    val center = Offset(size.width / 2f, size.height / 2f)
    val base = size.minDimension / 2f
    // Ring: grows quickly and thins out as it fades.
    val rp = (p / 0.6f).coerceIn(0f, 1f)
    val grow = easeOutCubic(rp)
    drawCircle(
        color = ringColor.copy(alpha = (1f - rp) * 0.85f),
        radius = base * (0.75f + 1.15f * grow),
        center = center,
        style = Stroke(width = (base * 0.6f * (1f - rp)).coerceAtLeast(0.5f)),
    )
    // Dots: start slightly after the ring, fly out, shrink and fade at the end.
    val dpr = ((p - 0.12f) / 0.88f).coerceIn(0f, 1f)
    val fly = easeOutCubic(dpr)
    val fade = if (dpr < 0.55f) 1f else (1f - (dpr - 0.55f) / 0.45f)
    for (i in 0 until PARTICLES) {
        val angle = (2.0 * PI / PARTICLES * i + if (i % 2 == 0) 0.0 else 0.22).toFloat()
        val reach = base * (if (i % 2 == 0) 2.0f else 1.65f) * (0.55f + 0.45f * fly)
        val radius = base * (if (i % 2 == 0) 0.17f else 0.11f) * (1f - 0.7f * dpr)
        drawCircle(
            color = burstColors[i % burstColors.size].copy(alpha = fade.coerceIn(0f, 1f)),
            radius = radius,
            center = Offset(center.x + cos(angle) * reach, center.y + sin(angle) * reach),
        )
    }
}
