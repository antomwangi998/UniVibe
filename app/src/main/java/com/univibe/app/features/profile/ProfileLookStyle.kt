package com.univibe.app.features.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.univibe.app.data.model.ProfileLook
import com.univibe.app.ui.theme.Spacing
import kotlin.random.Random

/** The ids below must match the server's lists (backend/src/modules/monetization/profile_look.ts); a test keeps them in step. */
class ThemeInfo(val id: String, val name: String)
class BadgeInfo(val id: String, val glyph: String, val label: String)
class FrameInfo(val id: String, val name: String)

val ProfileThemes = listOf(
    ThemeInfo("aurora", "Aurora"), ThemeInfo("ember", "Ember"), ThemeInfo("tide", "Tide"),
    ThemeInfo("noir", "Noir"), ThemeInfo("bloom", "Bloom"), ThemeInfo("gold", "Gold"),
    ThemeInfo("nature", "Nature"), ThemeInfo("midnight", "Midnight"), ThemeInfo("galaxy", "Galaxy"), ThemeInfo("minimal", "Minimal"),
)
val ProfileFrames = listOf(FrameInfo("ring", "Ring"), FrameInfo("double", "Double"), FrameInfo("dashed", "Dashed"), FrameInfo("halo", "Halo"))
/** Badges say what they are in words, so none can be mistaken for the verified student mark. */
val ProfileBadges = listOf(
    BadgeInfo("spark", "\u2728", "Bright spark"), BadgeInfo("book", "\uD83D\uDCDA", "Bookworm"), BadgeInfo("moon", "\uD83C\uDF19", "Night owl"),
    BadgeInfo("coffee", "\u2615", "Coffee powered"), BadgeInfo("flame", "\uD83D\uDD25", "On fire"), BadgeInfo("bolt", "\u26A1", "Quick thinker"),
)
val AccentPresets = listOf("#14B8A6", "#3B82F6", "#EF4444", "#F59E0B", "#EC4899", "#8B5CF6", "#22C55E", "#F97316", "#06B6D4", "#E11D48", "#EAB308", "#64748B")

private val palettes: Map<String, List<Color>> = mapOf(
    "aurora" to listOf(Color(0xFF042F2E), Color(0xFF0F766E), Color(0xFF2DD4BF)),
    "ember" to listOf(Color(0xFF2B0A03), Color(0xFFC2410C), Color(0xFFFBBF24)),
    "tide" to listOf(Color(0xFF04122B), Color(0xFF1D4ED8), Color(0xFF38BDF8)),
    "noir" to listOf(Color(0xFF050505), Color(0xFF1F2937), Color(0xFF6B7280)),
    "bloom" to listOf(Color(0xFF3B0A22), Color(0xFFBE185D), Color(0xFFF9A8D4)),
    "gold" to listOf(Color(0xFF2A1D02), Color(0xFFB45309), Color(0xFFFCD34D)),
    "nature" to listOf(Color(0xFF052E16), Color(0xFF15803D), Color(0xFF86EFAC)),
    "midnight" to listOf(Color(0xFF020617), Color(0xFF1E1B4B), Color(0xFF818CF8)),
    "galaxy" to listOf(Color(0xFF0B0326), Color(0xFF5B21B6), Color(0xFFE879F9)),
    "minimal" to listOf(Color(0xFF111827), Color(0xFF374151), Color(0xFFE5E7EB)),
)

/** Three colours for the cover gradient, or null for an unknown id (an old app meeting a newer theme just shows the default). */
fun themeColors(id: String?): List<Color>? = id?.let { palettes[it] }

fun badgeInfo(id: String?): BadgeInfo? = ProfileBadges.firstOrNull { it.id == id }

/** "#1A9E8F" to a colour; anything else is ignored, never trusted. */
fun parseAccent(hex: String?): Color? {
    if (hex == null || !Regex("^#[0-9A-Fa-f]{6}$").matches(hex)) return null
    return Color(("FF" + hex.substring(1)).toLong(16))
}

/** The accent to draw with: the chosen colour, else the theme's light colour, else the fallback. */
fun lookAccent(look: ProfileLook?, fallback: Color): Color = parseAccent(look?.accent) ?: themeColors(look?.theme)?.last() ?: fallback

/** Seal colour for a person with a look: their accent or theme colour. Unspecified (the brand colour) for everyone else. */
fun badgeTint(look: ProfileLook?): Color =
    if (look?.theme != null || look?.accent != null) lookAccent(look, Color.Unspecified) else Color.Unspecified

fun readableOn(c: Color): Color = if (c.luminance() > 0.5f) Color(0xFF111111) else Color.White

/** Saving needs the parts it uses to be unlocked: theme, accent and badge need themes; a frame needs frames. */
fun canSaveLook(look: ProfileLook, themesUnlocked: Boolean, framesUnlocked: Boolean): Boolean {
    val needsThemes = look.theme != null || look.accent != null || look.badge != null
    return (!needsThemes || themesUnlocked) && (look.frame == null || framesUnlocked)
}

/** A ring around the avatar. Drawn over the content, so it sits on the avatar's own border. */
fun Modifier.profileFrame(frame: String?, accent: Color): Modifier {
    if (frame == null) return this
    val second = lerp(accent, Color.White, 0.45f)
    return drawWithContent {
        drawContent()
        val r = size.minDimension / 2f
        // Strokes are designed for the 116dp profile photo; smaller avatars (feed, lists) get proportionally thinner ones.
        val k = (size.minDimension / 116.dp.toPx()).coerceIn(0.35f, 1f)
        when (frame) {
            "ring" -> { val w = 4.dp.toPx() * k; drawCircle(Brush.sweepGradient(listOf(accent, second, accent), center), radius = r - w / 2f, style = Stroke(w)) }
            "double" -> {
                drawCircle(accent, radius = r - 1.dp.toPx() * k, style = Stroke(2.dp.toPx() * k))
                drawCircle(second, radius = r - 5.dp.toPx() * k, style = Stroke(2.dp.toPx() * k))
            }
            "dashed" -> drawCircle(accent, radius = r - 2.dp.toPx() * k, style = Stroke(3.dp.toPx() * k, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx() * k, 7.dp.toPx() * k))))
            "halo" -> {
                drawCircle(Brush.radialGradient(0.7f to Color.Transparent, 0.85f to accent.copy(alpha = 0.5f), 1f to Color.Transparent, center = center, radius = r * 1.4f), radius = r * 1.4f)
                drawCircle(accent, radius = r - 1.5.dp.toPx() * k, style = Stroke(3.dp.toPx() * k))
            }
        }
    }
}

/** Faint artwork on the cover for each theme. Drawn once, still, from fixed positions: cheap on a long scrolling profile. */
fun DrawScope.drawThemeMotif(theme: String) {
    val w = size.width
    val h = size.height
    when (theme) {
        "aurora" -> {
            drawCircle(Brush.radialGradient(listOf(Color(0xFF60A5FA).copy(alpha = 0.35f), Color.Transparent), Offset(w * 0.2f, h * 0.3f), w * 0.5f), radius = w * 0.5f, center = Offset(w * 0.2f, h * 0.3f))
            drawCircle(Brush.radialGradient(listOf(Color(0xFF5EEAD4).copy(alpha = 0.30f), Color.Transparent), Offset(w * 0.75f, h * 0.55f), w * 0.55f), radius = w * 0.55f, center = Offset(w * 0.75f, h * 0.55f))
        }
        "ember" -> { val r = Random(3); repeat(16) { drawCircle(Color(0xFFFDBA74).copy(alpha = 0.10f + 0.25f * r.nextFloat()), radius = (1.5f + 3.5f * r.nextFloat()).dp.toPx(), center = Offset(w * r.nextFloat(), h * r.nextFloat())) } }
        "tide" -> {
            for (i in 0 until 3) {
                val y = h * (0.45f + 0.17f * i)
                val p = androidx.compose.ui.graphics.Path().apply { moveTo(0f, y); cubicTo(w * 0.25f, y - 14.dp.toPx(), w * 0.5f, y + 14.dp.toPx(), w * 0.75f, y - 8.dp.toPx()); cubicTo(w * 0.88f, y - 14.dp.toPx(), w * 0.95f, y, w, y - 4.dp.toPx()) }
                drawPath(p, Color.White.copy(alpha = 0.14f - 0.03f * i), style = Stroke(1.5.dp.toPx()))
            }
        }
        "noir" -> { var x = -h; while (x < w) { drawLine(Color.White.copy(alpha = 0.06f), Offset(x, h), Offset(x + h, 0f), strokeWidth = 1.dp.toPx()); x += 16.dp.toPx() } }
        "bloom" -> { val r = Random(7); repeat(12) { val c = Offset(w * r.nextFloat(), h * r.nextFloat()); val pw = (8f + 8f * r.nextFloat()).dp.toPx(); rotate(360f * r.nextFloat(), c) { drawOval(Color(0xFFFBCFE8).copy(alpha = 0.28f), Offset(c.x - pw / 2f, c.y - pw * 0.31f), androidx.compose.ui.geometry.Size(pw, pw * 0.62f)) } } }
        "gold" -> { val r = Random(5); repeat(10) { val c = Offset(w * r.nextFloat(), h * r.nextFloat()); val s = (3f + 5f * r.nextFloat()).dp.toPx(); val col = Color(0xFFFDE68A).copy(alpha = 0.35f); drawLine(col, Offset(c.x - s, c.y), Offset(c.x + s, c.y), 1.2.dp.toPx()); drawLine(col, Offset(c.x, c.y - s), Offset(c.x, c.y + s), 1.2.dp.toPx()) } }
    }
}

/** The badge as a small chip next to the name. Always carries its words, and never looks like the verified mark. */
@Composable
fun ProfileBadgeChip(badge: String?, accent: Color, modifier: Modifier = Modifier) {
    val info = badgeInfo(badge) ?: return
    Row(
        modifier.clip(RoundedCornerShape(50)).background(accent.copy(alpha = 0.18f)).padding(horizontal = Spacing.sm, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(info.glyph, style = androidx.compose.material3.MaterialTheme.typography.labelMedium)
        Text("  " + info.label, style = androidx.compose.material3.MaterialTheme.typography.labelMedium, color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface)
    }
}

/** Just the badge glyph, for tight rows (feed, comments). The words are in the accessibility description. */
@Composable
fun BadgeGlyph(badge: String?, modifier: Modifier = Modifier) {
    val info = badgeInfo(badge) ?: return
    Text(info.glyph, style = androidx.compose.material3.MaterialTheme.typography.labelMedium, modifier = modifier.semantics { contentDescription = info.label })
}
