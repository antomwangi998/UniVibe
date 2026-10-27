package com.univibe.app.features.profile

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

/**
 * Design tokens for the Profile screen. Dark values are the approved palette; light values come straight from the app's
 * colour scheme so the light theme keeps working. Nothing here changes the global theme, so other screens are untouched.
 */
@Immutable
class ProfileTokens(
    val dark: Boolean,
    val background: Color,
    val card: Color,
    val cardElevated: Color,
    val border: Color,
    val primary: Color,
    val secondary: Color,
    val onPrimary: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
)

private val DarkTokens = ProfileTokens(
    dark = true,
    background = Color(0xFF05080B),
    card = Color(0xFF0B1117),
    cardElevated = Color(0xFF101820),
    border = Color(0xFF202B34),
    primary = Color(0xFF00D6C9),
    secondary = Color(0xFF00AAA6),
    onPrimary = Color(0xFF031412),
    textPrimary = Color(0xFFF5F7FA),
    textSecondary = Color(0xFFA4AFBA),
    textMuted = Color(0xFF75818D),
)

@Composable
fun rememberProfileTokens(): ProfileTokens {
    val c = MaterialTheme.colorScheme
    val dark = c.background.luminance() < 0.5f
    return remember(dark, c) {
        if (dark) DarkTokens
        else ProfileTokens(
            dark = false,
            background = c.background,
            card = c.surface,
            cardElevated = c.surfaceContainerLow,
            border = c.outlineVariant,
            primary = c.primary,
            secondary = Color(0xFF0B5F59),
            onPrimary = c.onPrimary,
            textPrimary = c.onBackground,
            textSecondary = c.onSurfaceVariant,
            textMuted = c.onSurfaceVariant,
        )
    }
}

/** Corner radii from the redesign spec. */
object ProfileShapes {
    val chip = RoundedCornerShape(18.dp)
    val button = RoundedCornerShape(20.dp)
    val card = RoundedCornerShape(24.dp)
    val large = RoundedCornerShape(28.dp)
    val thumb = RoundedCornerShape(16.dp)
}

/** Horizontal screen padding for the profile content. */
val ProfileHorizontalPadding = 20.dp
