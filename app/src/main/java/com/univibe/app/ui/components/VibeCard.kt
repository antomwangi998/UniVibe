package com.univibe.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.univibe.app.ui.theme.Spacing

/** 24dp-corner surface with a subtle border and no heavy elevation. */
@Composable
fun VibeCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(Spacing.md),
    elevation: androidx.compose.ui.unit.Dp = 0.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    // Flat glass: one solid tonal fill and a thin border, drawn once. No gradient, no layer, no shadow (a shadow would show
    // through the translucent fill), so a feed of these scrolls as smoothly as before. [elevation] is kept for callers.
    val shape = MaterialTheme.shapes.large
    val glassModifier = modifier.glass(shape, rememberGlassStyle(GlassTone.Card))
    if (onClick != null) {
        Surface(onClick = onClick, modifier = glassModifier, shape = shape, color = Color.Transparent) {
            Column(Modifier.padding(contentPadding), content = content)
        }
    } else {
        Surface(modifier = glassModifier, shape = shape, color = Color.Transparent) {
            Column(Modifier.padding(contentPadding), content = content)
        }
    }
}
