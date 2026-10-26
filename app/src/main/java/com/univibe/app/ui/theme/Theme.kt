package com.univibe.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.univibe.app.data.settings.AppFont
import androidx.compose.runtime.remember

private val LightColors = lightColorScheme(
    primary = LightPrimary,
    onPrimary = Color.White,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    secondary = LightTextMuted,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE2E8F0),
    onSecondaryContainer = Color(0xFF0F172A),
    tertiary = Color(0xFFC2410C),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFEDD5),
    onTertiaryContainer = Color(0xFF431407),
    background = LightBackground,
    onBackground = LightText,
    surface = LightSurface,
    onSurface = LightText,
    surfaceVariant = Color(0xFFEEF2F6),
    onSurfaceVariant = LightTextMuted,
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
    error = Color(0xFFB91C1C),
    onError = Color.White,
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF8FAFC),
    surfaceContainer = Color(0xFFF1F5F9),
    surfaceContainerHigh = Color(0xFFE9EEF4),
    surfaceContainerHighest = Color(0xFFE2E8F0),
)

// Pure black dark theme (X "Lights out" style).
private val PureBlack = Color(0xFF000000)
private val PureBlackText = Color(0xFFE7E9EA)
private val PureBlackTextMuted = Color(0xFF71767B)
private val PureBlackDivider = Color(0xFF2F3336)

private val DarkColors = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = Color(0xFF042F2E),
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    secondary = Color(0xFF94A3B8),
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF1E293B),
    onSecondaryContainer = Color(0xFFE2E8F0),
    tertiary = Color(0xFFFDBA74),
    onTertiary = Color(0xFF431407),
    tertiaryContainer = Color(0xFF7C2D12),
    onTertiaryContainer = Color(0xFFFFEDD5),
    background = PureBlack,
    onBackground = PureBlackText,
    surface = PureBlack,
    onSurface = PureBlackText,
    surfaceVariant = Color(0xFF16181C),
    onSurfaceVariant = PureBlackTextMuted,
    outline = DarkOutline,
    outlineVariant = PureBlackDivider,
    error = Color(0xFFF87171),
    onError = Color(0xFF450A0A),
    errorContainer = Color(0xFF7F1D1D),
    onErrorContainer = Color(0xFFFEE2E2),
    surfaceContainerLowest = PureBlack,
    surfaceContainerLow = Color(0xFF080808),
    surfaceContainer = Color(0xFF0F1114),
    surfaceContainerHigh = Color(0xFF16181C),
    surfaceContainerHighest = Color(0xFF202327),
)

/**
 * Settings → Accessibility → High contrast. Secondary text and borders get stronger; the brand teal, backgrounds and
 * surfaces are untouched. Every pair below clears WCAG AAA (7:1) for text and 3:1 for borders on its surfaces.
 */
fun ColorScheme.withHighContrast(darkTheme: Boolean): ColorScheme =
    if (darkTheme) copy(onSurfaceVariant = Color(0xFFCBD5E1), outline = Color(0xFFA8B3C2), outlineVariant = Color(0xFF7B8798))
    else copy(onSurfaceVariant = Color(0xFF334155), outline = Color(0xFF475569), outlineVariant = Color(0xFF64748B))

@Composable
fun UniVibeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    appFont: AppFont = AppFont.UniVibe,
    boldText: Boolean = false,
    highContrast: Boolean = false,
    content: @Composable () -> Unit,
) {
    val base = if (darkTheme) DarkColors else LightColors
    MaterialTheme(
        colorScheme = if (highContrast) remember(darkTheme) { base.withHighContrast(darkTheme) } else base,
        typography = remember(appFont, boldText) { uniVibeTypography(appFont.family(), boldText) },
        shapes = UniVibeShapes,
        content = content,
    )
}
