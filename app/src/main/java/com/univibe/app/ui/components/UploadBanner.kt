package com.univibe.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.univibe.app.data.net.UploadTracker

/**
 * A slim progress card at the top of the app while any media is uploading (post photos and videos, profile and group
 * photos). Chat photos and voice messages show their percentage on the message itself, so they are left out here.
 */
@Composable
fun UploadBanner(modifier: Modifier = Modifier) {
    val all by UploadTracker.uploads.collectAsStateWithLifecycle()
    val shown = all.filter { it.tag == null }
    if (shown.isEmpty()) return
    val percent = (shown.map { it.fraction }.average() * 100).toInt().coerceIn(0, 100)
    val label = if (shown.size == 1) when (shown[0].kind) { "video" -> "Uploading video"; "audio" -> "Uploading voice message"; else -> "Uploading photo" } else "Uploading ${shown.size} files"
    Surface(
        shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 6.dp,
        modifier = modifier.statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth()
            .semantics { contentDescription = "$label, $percent percent" },
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(label, style = MaterialTheme.typography.labelLarge)
                Text("$percent%", style = MaterialTheme.typography.labelLarge)
            }
            LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
        }
    }
}
