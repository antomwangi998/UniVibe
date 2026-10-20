package com.univibe.app.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** "Deleted · Undo" strip shown for a few seconds after something is deleted. TalkBack announces it. */
@Composable
fun UndoBar(message: String, onUndo: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.large,
        color = colors.inverseSurface,
        contentColor = colors.inverseOnSurface,
        shadowElevation = 6.dp,
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(vertical = 12.dp))
            TextButton(onClick = onUndo) { Text("Undo", color = colors.inversePrimary, style = MaterialTheme.typography.labelLarge) }
        }
    }
}

/** How long a deleted post, comment or message can still be brought back before the server is told. */
const val UNDO_WINDOW_MS = 5_000L
