package com.univibe.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Pill chip. Pass [onClick] for a selectable filter, or null for a read-only tag. */
@Composable
fun InterestChip(
    label: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val container by animateColorAsState(if (selected) colors.primary else colors.surface, label = "chipBg")
    val content by animateColorAsState(if (selected) colors.onPrimary else colors.onSurface, label = "chipFg")
    val border = BorderStroke(1.dp, if (selected) colors.primary else colors.outlineVariant)
    val text: @Composable () -> Unit = {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
    if (onClick != null) {
        Surface(
            onClick = onClick, modifier = modifier.semantics { this.selected = selected },
            shape = CircleShape, color = container, contentColor = content, border = border, content = text,
        )
    } else {
        Surface(modifier = modifier, shape = CircleShape, color = container, contentColor = content, border = border, content = text)
    }
}
