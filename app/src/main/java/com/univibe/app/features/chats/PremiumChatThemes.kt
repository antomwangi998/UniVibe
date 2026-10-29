package com.univibe.app.features.chats

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import com.univibe.app.ui.components.LocalGlassEnabled
import com.univibe.app.ui.components.rememberReducedMotion
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** The server key that unlocks the packs below. */
const val CHAT_THEMES_KEY = "premium.chat_themes"

/** A Premium pack sets an accent and an animated wallpaper together. Both carry the same id, always starting with "p_". */
class PremiumPack(val id: String, val name: String, val tagline: String)

val PremiumPacks = listOf(
    PremiumPack("p_aurora", "Aurora Live", "Northern lights drifting behind your chat"),
    PremiumPack("p_neon", "Neon Nights", "A glowing grid that pulses"),
    PremiumPack("p_golden", "Golden Hour", "Warm sun and floating light"),
    PremiumPack("p_stars", "Starfield", "A night sky that twinkles"),
    PremiumPack("p_rain", "Rainy Window", "Soft rain on the glass"),
    PremiumPack("p_sakura", "Sakura", "Petals falling slowly"),
)

/** Accent pairs (colour, text on it) for light and dark mode; each keeps text on my bubbles at 4.5:1 or better. */
val PremiumChatThemes = listOf(
    ChatTheme("p_aurora", "Aurora Live", Color(0xFF0F766E) to Color.White, Color(0xFF5EEAD4) to Color(0xFF042F2E)),
    ChatTheme("p_neon", "Neon Nights", Color(0xFFA21CAF) to Color.White, Color(0xFFF0ABFC) to Color(0xFF2E0A33)),
    ChatTheme("p_golden", "Golden Hour", Color(0xFFB45309) to Color.White, Color(0xFFFCD34D) to Color(0xFF3A2A03)),
    ChatTheme("p_stars", "Starfield", Color(0xFF4338CA) to Color.White, Color(0xFFA5B4FC) to Color(0xFF14173A)),
    ChatTheme("p_rain", "Rainy Window", Color(0xFF0E7490) to Color.White, Color(0xFF67E8F9) to Color(0xFF06303A)),
    ChatTheme("p_sakura", "Sakura", Color(0xFFBE185D) to Color.White, Color(0xFFF9A8D4) to Color(0xFF3B0A22)),
)

fun isPremiumLook(id: String) = id.startsWith("p_")

/**
 * What to actually show. A Premium look that is stored but not currently unlocked (the plan ended, or the server has
 * not answered) shows the free default. The stored choice is kept, so it comes back the moment Premium does.
 */
fun themeShown(id: String, entitled: Boolean) = if (isPremiumLook(id) && !entitled) "default" else id
fun wallpaperShown(id: String, entitled: Boolean) = if (isPremiumLook(id) && !entitled) "none" else id
fun premiumLookPaused(themeId: String, wallpaperId: String, entitled: Boolean) = !entitled && (isPremiumLook(themeId) || isPremiumLook(wallpaperId))

// ------------------------------------------------------------------ animated wallpapers

private const val TAU = (2.0 * PI).toFloat()
private const val STILL = 0.3f

private class Particle(val x: Float, val y: Float, val size: Float, val speed: Int, val phase: Float)

private fun particles(seed: Int, count: Int, minSpeed: Int, maxSpeed: Int): List<Particle> {
    val r = Random(seed)
    return List(count) { Particle(r.nextFloat(), r.nextFloat(), r.nextFloat(), minSpeed + r.nextInt(maxSpeed - minSpeed + 1), r.nextFloat()) }
}

/**
 * Draws behind the messages and never takes touches. Plain Canvas drawing, no images and no downloads. It holds still
 * (one fixed frame) when [animate] is false, when Reduce motion is on, or when glass effects are off on a low-memory phone.
 * Time is read only while drawing, so the chat itself does not recompose every frame. Every motion repeats exactly
 * every 24 seconds, so the loop has no visible jump.
 */
@Composable
fun PremiumWallpaperLayer(id: String, modifier: Modifier = Modifier, animate: Boolean = true) {
    val still = !animate || !LocalGlassEnabled.current || rememberReducedMotion()
    val phase: State<Float> = if (still) remember { mutableStateOf(STILL) } else {
        rememberInfiniteTransition(label = "premium-wallpaper").animateFloat(0f, 1f, infiniteRepeatable(tween(24_000, easing = LinearEasing)), label = "t")
    }
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val ink = MaterialTheme.colorScheme.onSurface
    val dots = remember(id) {
        when (id) {
            "p_golden" -> particles(11, 14, 1, 2)
            "p_stars" -> particles(23, 60, 1, 3)
            "p_rain" -> particles(37, 50, 2, 4)
            "p_sakura" -> particles(41, 22, 1, 2)
            else -> emptyList()
        }
    }
    Canvas(modifier) {
        val t = phase.value
        when (id) {
            "p_aurora" -> aurora(t, dark)
            "p_neon" -> neon(t)
            "p_golden" -> golden(t, dots)
            "p_stars" -> stars(t, dots, dark)
            "p_rain" -> rain(t, dots, ink)
            "p_sakura" -> sakura(t, dots)
        }
    }
}

private fun DrawScope.glow(color: Color, alpha: Float, center: Offset, radius: Float) =
    drawCircle(Brush.radialGradient(listOf(color.copy(alpha = alpha), Color.Transparent), center = center, radius = radius), radius = radius, center = center)

private fun DrawScope.aurora(t: Float, dark: Boolean) {
    drawRect(Brush.verticalGradient(listOf(Color(0xFF14B8A6).copy(alpha = 0.16f), Color(0xFF3B82F6).copy(alpha = 0.10f))))
    val blobs = listOf(Triple(Color(0xFF2DD4BF), 1, 0f), Triple(Color(0xFF60A5FA), 1, 0.33f), Triple(Color(0xFFA78BFA), 2, 0.66f))
    for ((c, k, ph) in blobs) {
        val a = TAU * (k * t + ph)
        glow(c, if (dark) 0.30f else 0.22f, Offset(size.width * (0.5f + 0.35f * sin(a)), size.height * (0.35f + 0.25f * cos(a))), size.maxDimension * 0.55f)
    }
}

private fun DrawScope.neon(t: Float) {
    drawRect(Brush.verticalGradient(listOf(Color(0xFFD946EF).copy(alpha = 0.14f), Color(0xFF22D3EE).copy(alpha = 0.12f))))
    val step = 40.dp.toPx()
    val line = Color(0xFF22D3EE).copy(alpha = 0.10f)
    val shift = (t * step * 2f) % step
    var y = shift - step
    while (y < size.height + step) { drawLine(line, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx()); y += step }
    var x = 0f
    while (x < size.width + step) { drawLine(line, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.dp.toPx()); x += step }
    val pulse = 0.5f + 0.5f * sin(TAU * t * 2f)
    glow(Color(0xFFE879F9), 0.16f + 0.12f * pulse, Offset(size.width / 2f, size.height), size.width * (0.8f + 0.1f * pulse))
}

private fun DrawScope.golden(t: Float, dots: List<Particle>) {
    drawRect(Brush.verticalGradient(listOf(Color(0xFFF59E0B).copy(alpha = 0.18f), Color(0xFFFB7185).copy(alpha = 0.08f))))
    val a = TAU * t
    glow(Color(0xFFFBBF24), 0.30f, Offset(size.width * (0.5f + 0.3f * sin(a)), size.height * (0.15f + 0.05f * cos(a))), size.maxDimension * 0.6f)
    for (d in dots) {
        val p = (t * d.speed + d.phase) % 1f
        val fade = sin(PI.toFloat() * p)
        drawCircle(Color(0xFFFDE68A).copy(alpha = 0.40f * fade), radius = (3f + 7f * d.size).dp.toPx(), center = Offset(size.width * d.x, size.height * (1f - p)))
    }
}

private fun DrawScope.stars(t: Float, dots: List<Particle>, dark: Boolean) {
    drawRect(Brush.verticalGradient(listOf(Color(0xFF4338CA).copy(alpha = 0.14f), Color(0xFF0F172A).copy(alpha = if (dark) 0.10f else 0.04f))))
    val star = if (dark) Color.White else Color(0xFF4338CA)
    for (d in dots) {
        val twinkle = 0.25f + 0.75f * abs(sin(TAU * (t * d.speed + d.phase)))
        drawCircle(star.copy(alpha = 0.55f * twinkle), radius = (0.8f + 1.6f * d.size).dp.toPx(), center = Offset(size.width * d.x, size.height * d.y))
    }
}

private fun DrawScope.rain(t: Float, dots: List<Particle>, ink: Color) {
    drawRect(Brush.verticalGradient(listOf(Color(0xFF0E7490).copy(alpha = 0.14f), Color(0xFF475569).copy(alpha = 0.10f))))
    val len = 26.dp.toPx()
    for (d in dots) {
        val p = (t * d.speed + d.phase) % 1f
        val y = p * (size.height + len) - len
        val x = size.width * d.x
        drawLine(ink.copy(alpha = 0.14f), Offset(x, y), Offset(x - len * 0.2f, y + len), strokeWidth = 1.2.dp.toPx())
    }
}

private fun DrawScope.sakura(t: Float, dots: List<Particle>) {
    drawRect(Brush.verticalGradient(listOf(Color(0xFFF472B6).copy(alpha = 0.14f), Color(0xFFFDA4AF).copy(alpha = 0.06f))))
    for (d in dots) {
        val p = (t * d.speed + d.phase) % 1f
        val y = p * (size.height + 40.dp.toPx()) - 20.dp.toPx()
        val x = size.width * d.x + 22.dp.toPx() * sin(TAU * (t * 2f + d.phase))
        val w = (6f + 5f * d.size).dp.toPx()
        val h = w * 0.62f
        rotate(degrees = 360f * (t * d.speed + d.phase), pivot = Offset(x, y)) {
            drawOval(Color(0xFFF472B6).copy(alpha = 0.55f), topLeft = Offset(x - w / 2f, y - h / 2f), size = Size(w, h))
        }
    }
}
