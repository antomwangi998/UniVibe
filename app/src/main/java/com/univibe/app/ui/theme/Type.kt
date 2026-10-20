package com.univibe.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.univibe.app.R
import com.univibe.app.data.settings.AppFont

/** Poppins (SIL Open Font License) bundled in res/font, so it looks the same on every phone. */
val UniVibeFont: FontFamily = FontFamily(
    Font(R.font.poppins_regular, FontWeight.Normal),
    Font(R.font.poppins_medium, FontWeight.Medium),
    Font(R.font.poppins_bold, FontWeight.SemiBold),
    Font(R.font.poppins_bold, FontWeight.Bold),
    Font(R.font.poppins_bold, FontWeight.ExtraBold),
)

/** The typeface behind each choice in Settings → Appearance → Font. */
fun AppFont.family(): FontFamily = when (this) {
    AppFont.UniVibe -> UniVibeFont
    AppFont.System -> FontFamily.Default
    AppFont.Serif -> FontFamily.Serif
    AppFont.Mono -> FontFamily.Monospace
}

private fun style(family: FontFamily, weight: FontWeight, size: Int, line: Int) =
    TextStyle(fontFamily = family, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp)

fun uniVibeTypography(family: FontFamily): Typography = Typography(
    displaySmall = style(family, FontWeight.Bold, 34, 42),
    headlineMedium = style(family, FontWeight.Bold, 30, 38),
    headlineSmall = style(family, FontWeight.SemiBold, 24, 32),
    titleLarge = style(family, FontWeight.SemiBold, 21, 28),
    titleMedium = style(family, FontWeight.SemiBold, 17, 24),
    titleSmall = style(family, FontWeight.SemiBold, 15, 22),
    bodyLarge = style(family, FontWeight.Normal, 17, 25),
    bodyMedium = style(family, FontWeight.Normal, 15, 22),
    bodySmall = style(family, FontWeight.Normal, 13, 18),
    labelLarge = style(family, FontWeight.Medium, 15, 22),
    labelMedium = style(family, FontWeight.Medium, 13, 18),
    labelSmall = style(family, FontWeight.Medium, 12, 16),
)

val UniVibeTypography = uniVibeTypography(UniVibeFont)
