package com.univibe.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.univibe.app.R

/** Poppins (SIL Open Font License) bundled in res/font, so it looks the same on every phone. */
val UniVibeFont: FontFamily = FontFamily(
    Font(R.font.poppins_regular, FontWeight.Normal),
    Font(R.font.poppins_medium, FontWeight.Medium),
    Font(R.font.poppins_bold, FontWeight.SemiBold),
    Font(R.font.poppins_bold, FontWeight.Bold),
    Font(R.font.poppins_bold, FontWeight.ExtraBold),
)

private fun style(weight: FontWeight, size: Int, line: Int) =
    TextStyle(fontFamily = UniVibeFont, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp)

val UniVibeTypography = Typography(
    displaySmall = style(FontWeight.Bold, 34, 42),
    headlineMedium = style(FontWeight.Bold, 30, 38),
    headlineSmall = style(FontWeight.SemiBold, 24, 32),
    titleLarge = style(FontWeight.SemiBold, 21, 28),
    titleMedium = style(FontWeight.SemiBold, 17, 24),
    titleSmall = style(FontWeight.SemiBold, 15, 22),
    bodyLarge = style(FontWeight.Normal, 17, 25),
    bodyMedium = style(FontWeight.Normal, 15, 22),
    bodySmall = style(FontWeight.Normal, 13, 18),
    labelLarge = style(FontWeight.Medium, 15, 22),
    labelMedium = style(FontWeight.Medium, 13, 18),
    labelSmall = style(FontWeight.Medium, 12, 16),
)
