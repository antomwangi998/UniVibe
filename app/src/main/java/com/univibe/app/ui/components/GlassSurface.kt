package com.univibe.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.univibe.app.ui.theme.Spacing

/**
 * Glass look for the conversation screen: a translucent gradient fill, a faint top sheen and a thin gradient border.
 *
 * Deliberately NOT a real backdrop blur. Compose's Modifier.blur() blurs a composable's own content, not what is behind
 * it, and faking a backdrop needs an offscreen layer per surface, which is too costly inside a LazyColumn on low-end
 * phones. Everything here is plain brush drawing: no layers, no RenderEffect, no new dependency, same on every API level.
 */
object GlassTokens {
    val BorderWidth = 1.dp
    val BubbleCorner = 20.dp
    val BubbleJoinCorner = 6.dp
    val QuoteCorner = 12.dp
    val ControlCorner = 14.dp
    val ControlSize = 40.dp
    val HeaderCorner = 30.dp
    val ComposerCorner = 28.dp
    const val PressedScale = 0.94f
    const val PressMillis = 90
    /** Timestamps and ticks sit quietly beside the text. */
    const val MetaAlpha = 0.66f
    /** Real backdrop blur behind the bottom tab bar on Android 12+. Set false to turn it off everywhere. */
    const val BackdropBlur = true
    val BlurRadius = 20.dp
}

/**
 * False on low-RAM phones and in Settings > Accessibility > High contrast. Glass then falls back to flat, opaque
 * surfaces with a plain border: same shapes and layout, no gradients, nothing extra to draw. Provided once in UniVibeApp.
 */
val LocalGlassEnabled = compositionLocalOf { true }

/** True inside [GlassHeaderBar], so every [VibeIconButton] in a screen's top bar turns into a [GlassIconButton] by itself. */
val LocalInGlassBar = compositionLocalOf { false }

enum class GlassTone {
    /** Messages from the other person: charcoal / navy glass. */
    Incoming,
    /** My messages: teal-tinted glass. */
    Outgoing,
    /** Larger containers: the header and the message box. */
    Chrome,
    /** Small controls: icon buttons, date chips, the jump-to-latest button. */
    Control,
    /** Feed and list cards: FLAT glass (solid tonal fill + thin border, no gradients), cheap enough for scrolling lists. */
    Card,
}

@Immutable
class GlassStyle(
    val fill: Brush,
    val sheen: Brush,
    val border: Brush,
    /** Text and icons on this glass. Always the theme's onSurface, so contrast follows the theme. */
    val content: Color,
    /** The quoted-reply panel inside a bubble, a step away from the bubble itself. */
    val quoteFill: Color,
    val quoteBar: Color,
)

private fun vertical(top: Color, bottom: Color) = Brush.verticalGradient(listOf(top, bottom))

private fun glassStyle(tone: GlassTone, dark: Boolean, primary: Color, onSurface: Color): GlassStyle {
    val slate = Color(0xFF0F172A)
    val sheenDark = Brush.verticalGradient(0f to Color.White.copy(alpha = 0.07f), 0.45f to Color.Transparent)
    val sheenLight = Brush.verticalGradient(0f to Color.White.copy(alpha = 0.55f), 0.45f to Color.Transparent)
    return if (dark) when (tone) {
        GlassTone.Incoming -> GlassStyle(
            fill = vertical(Color(0xFF1B2735).copy(alpha = 0.80f), Color(0xFF0F1923).copy(alpha = 0.84f)),
            sheen = sheenDark,
            border = vertical(Color.White.copy(alpha = 0.20f), Color.White.copy(alpha = 0.06f)),
            content = onSurface, quoteFill = Color.White.copy(alpha = 0.07f), quoteBar = primary,
        )
        GlassTone.Outgoing -> GlassStyle(
            fill = vertical(primary.copy(alpha = 0.28f), primary.copy(alpha = 0.14f)),
            sheen = sheenDark,
            border = vertical(primary.copy(alpha = 0.65f), primary.copy(alpha = 0.26f)),
            content = onSurface, quoteFill = Color.Black.copy(alpha = 0.30f), quoteBar = primary,
        )
        GlassTone.Chrome -> GlassStyle(
            fill = vertical(Color(0xFF14202C).copy(alpha = 0.62f), Color(0xFF0B141D).copy(alpha = 0.58f)),
            sheen = sheenDark,
            border = vertical(primary.copy(alpha = 0.45f), Color.White.copy(alpha = 0.07f)),
            content = onSurface, quoteFill = Color.White.copy(alpha = 0.07f), quoteBar = primary,
        )
        GlassTone.Card -> GlassStyle(
            fill = SolidColor(Color(0xFF111A24).copy(alpha = 0.78f)), sheen = SolidColor(Color.Transparent),
            border = SolidColor(Color.White.copy(alpha = 0.10f)),
            content = onSurface, quoteFill = Color.White.copy(alpha = 0.07f), quoteBar = primary,
        )
        GlassTone.Control -> GlassStyle(
            fill = vertical(Color.White.copy(alpha = 0.10f), Color.White.copy(alpha = 0.04f)),
            sheen = sheenDark,
            border = vertical(Color.White.copy(alpha = 0.24f), Color.White.copy(alpha = 0.08f)),
            content = onSurface, quoteFill = Color.White.copy(alpha = 0.07f), quoteBar = primary,
        )
    } else when (tone) {
        GlassTone.Incoming -> GlassStyle(
            fill = vertical(Color.White.copy(alpha = 0.94f), Color(0xFFF1F5F9).copy(alpha = 0.90f)),
            sheen = sheenLight,
            border = vertical(slate.copy(alpha = 0.14f), slate.copy(alpha = 0.06f)),
            content = onSurface, quoteFill = slate.copy(alpha = 0.06f), quoteBar = primary,
        )
        GlassTone.Outgoing -> GlassStyle(
            fill = vertical(primary.copy(alpha = 0.20f), primary.copy(alpha = 0.11f)),
            sheen = sheenLight,
            border = vertical(primary.copy(alpha = 0.50f), primary.copy(alpha = 0.24f)),
            content = onSurface, quoteFill = primary.copy(alpha = 0.12f), quoteBar = primary,
        )
        GlassTone.Chrome -> GlassStyle(
            fill = vertical(Color.White.copy(alpha = 0.78f), Color.White.copy(alpha = 0.66f)),
            sheen = sheenLight,
            border = vertical(primary.copy(alpha = 0.35f), slate.copy(alpha = 0.08f)),
            content = onSurface, quoteFill = slate.copy(alpha = 0.06f), quoteBar = primary,
        )
        GlassTone.Card -> GlassStyle(
            fill = SolidColor(Color.White.copy(alpha = 0.90f)), sheen = SolidColor(Color.Transparent),
            border = SolidColor(slate.copy(alpha = 0.10f)),
            content = onSurface, quoteFill = slate.copy(alpha = 0.06f), quoteBar = primary,
        )
        GlassTone.Control -> GlassStyle(
            fill = vertical(Color.White.copy(alpha = 0.85f), Color.White.copy(alpha = 0.60f)),
            sheen = sheenLight,
            border = vertical(slate.copy(alpha = 0.16f), slate.copy(alpha = 0.07f)),
            content = onSurface, quoteFill = slate.copy(alpha = 0.06f), quoteBar = primary,
        )
    }
}

/** The style for [tone] in the current theme (and chat accent). Remembered, so scrolling does not rebuild brushes. */
@Composable
fun rememberGlassStyle(tone: GlassTone): GlassStyle {
    val colors = MaterialTheme.colorScheme
    val dark = colors.background.luminance() < 0.5f
    val primary = colors.primary
    val onSurface = colors.onSurface
    val enabled = LocalGlassEnabled.current
    val flatBase = when (tone) {
        GlassTone.Incoming, GlassTone.Chrome -> colors.surfaceContainerHigh
        GlassTone.Control -> colors.surfaceContainerHighest
        GlassTone.Card -> colors.surface
        GlassTone.Outgoing -> primary.copy(alpha = if (dark) 0.28f else 0.18f).compositeOver(colors.background)
    }
    val outline = colors.outlineVariant
    return remember(tone, dark, primary, onSurface, enabled, flatBase, outline) {
        val glass = glassStyle(tone, dark, primary, onSurface)
        if (enabled) glass else GlassStyle(
            fill = SolidColor(flatBase), sheen = SolidColor(Color.Transparent), border = SolidColor(outline),
            content = onSurface, quoteFill = glass.quoteFill, quoteBar = glass.quoteBar,
        )
    }
}

/** Nearly opaque colour for popups and sheets (they live in their own window, so they cannot show the wallpaper). */
@Composable
fun glassPopupColor(): Color {
    val colors = MaterialTheme.colorScheme
    if (!LocalGlassEnabled.current) return colors.surface
    val dark = colors.background.luminance() < 0.5f
    return if (dark) Color(0xFF111B26).copy(alpha = 0.96f) else Color.White.copy(alpha = 0.97f)
}

/** Fill, sheen and border in one modifier. Draw it before any clip so the border is not cut. [borderOverride] is for error / focus. */
fun Modifier.glass(shape: Shape, style: GlassStyle, borderOverride: Color? = null): Modifier =
    this.background(style.fill, shape)
        .background(style.sheen, shape)
        .border(GlassTokens.BorderWidth, if (borderOverride != null) SolidColor(borderOverride) else style.border, shape)

/** A glass panel. Content gets the tone's text colour. */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(GlassTokens.BubbleCorner),
    tone: GlassTone = GlassTone.Control,
    content: @Composable () -> Unit,
) {
    val style = rememberGlassStyle(tone)
    CompositionLocalProvider(LocalContentColor provides style.content) {
        Box(modifier.glass(shape, style).clip(shape)) { content() }
    }
}

/**
 * A rounded-square glass icon button. The visible control is 40dp; the touch target stays 48dp. Pressed shrinks it a
 * little and tints it teal (no shrink with reduced motion); keyboard / D-pad focus turns the border teal.
 */
@Composable
fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val style = rememberGlassStyle(GlassTone.Control)
    val colors = MaterialTheme.colorScheme
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val focused by source.collectIsFocusedAsState()
    val reduced = rememberReducedMotion()
    val scale by animateFloatAsState(
        if (pressed && !reduced) GlassTokens.PressedScale else 1f,
        tween(if (reduced) 0 else GlassTokens.PressMillis), label = "glassPress",
    )
    val shape = RoundedCornerShape(GlassTokens.ControlCorner)
    Box(
        modifier.size(VibeIconSize.TouchTarget)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(GlassTokens.ControlSize)
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .glass(shape, style, borderOverride = if (focused) colors.primary else null)
                .background(if (pressed) colors.primary.copy(alpha = 0.16f) else Color.Transparent, shape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = style.content, modifier = Modifier.size(VibeIconSize.Navigation))
        }
    }
}

/**
 * The conversation header: one wide rounded glass bar that groups the back button, the person and the actions. It
 * respects the status bar, and everything inside is flexible so a long name truncates instead of pushing controls off.
 */
@Composable
fun GlassHeaderBar(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    val style = rememberGlassStyle(GlassTone.Chrome)
    val shape = RoundedCornerShape(GlassTokens.HeaderCorner)
    CompositionLocalProvider(LocalContentColor provides style.content, LocalInGlassBar provides true) {
        Row(
            modifier.fillMaxWidth().statusBarsPadding()
                .padding(horizontal = Spacing.sm + 4.dp, vertical = Spacing.sm)
                .glass(shape, style)
                .padding(6.dp)
                .heightIn(min = VibeIconSize.TouchTarget),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            content = content,
        )
    }
}

/**
 * The quoted message inside a reply bubble: a tonal panel (darker teal inside my bubbles, lighter charcoal inside
 * theirs), a narrow accent bar, the sender and at most two lines of preview. One level only: the quote never renders a
 * quote of its own. A blank preview reads "Message unavailable" instead of showing nothing.
 */
@Composable
fun GlassQuotedMessage(
    senderName: String,
    preview: String,
    outgoing: Boolean,
    modifier: Modifier = Modifier,
    fillWidth: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val style = rememberGlassStyle(if (outgoing) GlassTone.Outgoing else GlassTone.Incoming)
    val shape = RoundedCornerShape(GlassTokens.QuoteCorner)
    Row(
        modifier.then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
            .clip(shape)
            .background(style.quoteFill)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClickLabel = "Jump to original message", onClick = onClick) else Modifier)
            .height(IntrinsicSize.Min),
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(style.quoteBar))
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Text(
                senderName, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                color = style.quoteBar, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                preview.ifBlank { "Message unavailable" }, style = MaterialTheme.typography.bodySmall,
                color = style.content.copy(alpha = 0.85f), maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Quiet ambient light behind the glass: deep navy-black with a faint teal glow in dark mode, a whisper of teal in
 * light mode. Drawn once per size (drawWithCache), no animation, no layers.
 */
@Composable
fun GlassAmbientLayer(modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val dark = colors.background.luminance() < 0.5f
    val primary = colors.primary
    Box(
        modifier.drawWithCache {
            val base = if (dark) Brush.verticalGradient(listOf(Color(0xFF06101A), Color(0xFF020609), Color.Black)) else null
            val glow = Brush.radialGradient(
                listOf(primary.copy(alpha = if (dark) 0.14f else 0.07f), Color.Transparent),
                center = Offset(size.width * 0.25f, 0f), radius = size.maxDimension * 0.6f,
            )
            onDrawBehind {
                base?.let { drawRect(it) }
                drawRect(glow)
            }
        },
    )
}

/**
 * The "…" menu in glass. A menu lives in its own popup window, so it cannot show the wallpaper through itself; it uses
 * the same border, corner and tint language, kept mostly opaque so the items stay easy to read over the chat behind.
 */
@Composable
fun GlassDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val style = rememberGlassStyle(GlassTone.Chrome)
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        shape = RoundedCornerShape(GlassTokens.BubbleCorner),
        containerColor = glassPopupColor(),
        tonalElevation = 0.dp,
        shadowElevation = 8.dp,
        border = BorderStroke(GlassTokens.BorderWidth, style.border),
        content = content,
    )
}
