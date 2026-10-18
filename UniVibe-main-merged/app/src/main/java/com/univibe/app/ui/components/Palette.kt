package com.univibe.app.ui.components

import androidx.compose.ui.graphics.Color

/**
 * Placeholder "photo" colours used until real image loading (Coil) is added.
 * Dark -> light so white overlay text stays legible on the darker end.
 */
private val AccentPalette = listOf(
    listOf(Color(0xFF0F766E), Color(0xFF2DD4BF)),
    listOf(Color(0xFF1D4ED8), Color(0xFF60A5FA)),
    listOf(Color(0xFFC2410C), Color(0xFFFDBA74)),
    listOf(Color(0xFFB45309), Color(0xFFFCD34D)),
    listOf(Color(0xFF334155), Color(0xFF94A3B8)),
    listOf(Color(0xFF15803D), Color(0xFF86EFAC)),
)

fun accentGradient(index: Int): List<Color> = AccentPalette[index.mod(AccentPalette.size)]
fun accentSolid(index: Int): Color = accentGradient(index).first()
