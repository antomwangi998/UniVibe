package com.univibe.app.features.chats

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.univibe.app.data.model.Message
import com.univibe.app.ui.components.InterestChip
import com.univibe.app.ui.components.PhotoImage
import com.univibe.app.ui.components.VibeBottomSheet
import com.univibe.app.ui.components.VibeIcons
import com.univibe.app.ui.theme.Spacing

private val Tabs = listOf("Media", "Links", "Docs")

/**
 * Everything shared in this chat, from the messages already loaded (scroll up in the chat to load older ones).
 * A photo opens full screen; a link opens in the browser; a document takes you to its message.
 */
@Composable
fun ChatMediaSheet(
    messages: List<Message>,
    onOpenPhoto: (String) -> Unit,
    onGoToMessage: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    // Newest first, like the rest of the app.
    val newest = remember(messages) { messages.sortedByDescending { it.timeMillis } }
    val photos = remember(newest) { newest.flatMap { m -> m.photos.map { path -> path to m.id } } }
    val links = remember(newest) { newest.flatMap { m -> findLinks(m.text).map { it.url to m.id } }.distinctBy { it.first } }
    val docs = remember(newest) { newest.filter { it.file != null } }

    VibeBottomSheet(onDismiss = onDismiss) {
        Text("Media, links and docs", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = Spacing.sm))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = Modifier.padding(bottom = Spacing.sm)) {
            Tabs.forEachIndexed { i, label -> InterestChip(label, selected = tab == i, onClick = { tab = i }) }
        }
        val empty = when (tab) { 0 -> photos.isEmpty(); 1 -> links.isEmpty(); else -> docs.isEmpty() }
        if (empty) {
            Text(
                when (tab) { 0 -> "No photos yet."; 1 -> "No links yet."; else -> "No documents yet." },
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = Spacing.lg),
            )
            return@VibeBottomSheet
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            when (tab) {
                0 -> items(photos.chunked(3)) { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        row.forEach { (path, _) ->
                            PhotoImage(
                                path, contentDescription = "Shared photo",
                                modifier = Modifier.weight(1f).aspectRatio(1f).clickable(role = Role.Button, onClickLabel = "Open photo") { onDismiss(); onOpenPhoto(path) },
                            )
                        }
                        // Keep the last row's tiles the same size as the others.
                        repeat(3 - row.size) { Box(Modifier.weight(1f)) }
                    }
                }
                1 -> items(links) { (url, id) ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(role = Role.Button, onClickLabel = "Open link") {
                            try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                            catch (e: Exception) { Toast.makeText(context, "No app can open this link.", Toast.LENGTH_SHORT).show() }
                        },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(VibeIcons.Link, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(url, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = Spacing.md))
                    }
                }
                else -> items(docs) { m ->
                    val f = m.file ?: return@items
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(role = Role.Button, onClickLabel = "Go to message") { onDismiss(); onGoToMessage(m.id) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(VibeIcons.File, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Column(Modifier.padding(start = Spacing.md)) {
                            Text(f.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (f.sizeBytes > 0) Text(formatBytes(f.sizeBytes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
