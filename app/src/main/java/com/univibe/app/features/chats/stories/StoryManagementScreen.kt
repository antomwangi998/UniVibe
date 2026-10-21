package com.univibe.app.features.chats.stories

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.univibe.app.core.keyedViewModel
import com.univibe.app.data.model.MyStory
import com.univibe.app.data.model.StoryKind
import com.univibe.app.data.model.StoryViewer
import com.univibe.app.data.net.timeAgo
import com.univibe.app.ui.components.StudentAvatar
import com.univibe.app.ui.components.VibeButton
import com.univibe.app.ui.components.VibeIconButton
import com.univibe.app.ui.components.VibeIcons
import com.univibe.app.ui.components.VibeTopBar
import com.univibe.app.ui.theme.Spacing

private val ReactionEmoji = mapOf("heart" to "❤️", "like" to "👍", "laugh" to "😂", "fire" to "🔥", "sad" to "😢")

private fun remaining(expiresAtMs: Long): String {
    val minutes = ((expiresAtMs - System.currentTimeMillis()) / 60_000).coerceAtLeast(0)
    return when {
        minutes >= 60 -> "${minutes / 60}h left"
        minutes >= 1 -> "${minutes}m left"
        else -> "Expiring"
    }
}

/** Review my active Stories: who watched, how people reacted, who can see each one, and delete. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoryManagementScreen(onBack: () -> Unit, onOpenMyStory: () -> Unit, onAddStory: () -> Unit) {
    val vm = keyedViewModel("story-management") { StoryManagementViewModel() }
    val s by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }
    LaunchedEffect(s.notice) { s.notice?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show(); vm.consumeNotice() } }

    Column(Modifier.fillMaxSize()) {
        VibeTopBar(
            "Your stories", compactTitle = true,
            navigationIcon = { VibeIconButton(VibeIcons.Back, "Back", onBack) },
            actions = { VibeIconButton(VibeIcons.Camera, "Add to your story", onAddStory) },
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                s.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                s.error != null -> Column(Modifier.align(Alignment.Center).padding(Spacing.lg), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    Text(s.error.orEmpty(), textAlign = TextAlign.Center, color = colors.onSurfaceVariant)
                    VibeButton("Try again", vm::retry, compact = true)
                }
                s.stories.isEmpty() -> Column(Modifier.align(Alignment.Center).padding(Spacing.lg), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    Text("No active stories", style = MaterialTheme.typography.titleMedium)
                    Text("Stories disappear after 24 hours.", color = colors.onSurfaceVariant, textAlign = TextAlign.Center)
                    VibeButton("Add to story", onAddStory, compact = true, leadingIcon = VibeIcons.Camera)
                }
                else -> LazyColumn(
                    Modifier.fillMaxSize().navigationBarsPadding(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(Spacing.md),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    items(s.stories, key = { it.item.id }) { story ->
                        StoryCard(
                            story, onOpen = onOpenMyStory, onViewers = { vm.openViewers(story.item.id) },
                            onAudience = { vm.setAudience(story.item.id, it) }, onDelete = { vm.askDelete(story.item.id) },
                        )
                    }
                }
            }
        }
    }

    if (s.confirmDeleteId != null) AlertDialog(
        onDismissRequest = vm::cancelDelete,
        title = { Text("Delete this story?") },
        text = { Text("It disappears for everyone right away.") },
        confirmButton = { TextButton(onClick = vm::delete) { Text("Delete") } },
        dismissButton = { TextButton(onClick = vm::cancelDelete) { Text("Cancel") } },
    )

    if (s.viewersFor != null) ModalBottomSheet(onDismissRequest = vm::closeViewers) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = Spacing.md)) {
            Text(
                if (s.viewersTotal == 1) "1 viewer" else "${s.viewersTotal} viewers",
                style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = Spacing.sm),
            )
            when {
                s.viewersError != null && s.viewers.isEmpty() -> Column(Modifier.fillMaxWidth().padding(Spacing.lg), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(s.viewersError.orEmpty(), color = colors.onSurfaceVariant, textAlign = TextAlign.Center)
                    TextButton(onClick = vm::loadMoreViewers) { Text("Try again") }
                }
                s.viewers.isEmpty() && s.viewersLoading -> Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                s.viewers.isEmpty() -> Text("No one has watched this story yet.", color = colors.onSurfaceVariant, modifier = Modifier.padding(vertical = Spacing.lg))
                else -> LazyColumn(Modifier.height(380.dp)) {
                    items(s.viewers, key = { it.id }) { ViewerRow(it) }
                    item {
                        if (s.viewersCursor != null) {
                            LaunchedEffect(s.viewersCursor) { vm.loadMoreViewers() }
                            Box(Modifier.fillMaxWidth().padding(Spacing.md), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StoryCard(story: MyStory, onOpen: () -> Unit, onViewers: () -> Unit, onAudience: (String) -> Unit, onDelete: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val item = story.item
    var menu by remember { mutableStateOf(false) }
    val label = when (item.kind) { StoryKind.Photo -> "Photo"; StoryKind.Video -> "Video"; StoryKind.Text -> "Text story" }
    val snippet = (if (item.kind == StoryKind.Text) item.text else item.caption).orEmpty()
    Surface(shape = RoundedCornerShape(16.dp), color = colors.surfaceVariant.copy(alpha = 0.5f), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(Spacing.sm), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Box(
                Modifier.size(width = 64.dp, height = 96.dp).clip(RoundedCornerShape(12.dp)).background(Color.Black)
                    .clickable(role = Role.Button, onClickLabel = "Open story", onClick = onOpen),
                contentAlignment = Alignment.Center,
            ) {
                when (item.kind) {
                    StoryKind.Photo -> AsyncImage(item.mediaUrl, contentDescription = "$label thumbnail", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    StoryKind.Video -> Icon(VibeIcons.Play, contentDescription = "Video story", tint = Color.White, modifier = Modifier.size(28.dp))
                    StoryKind.Text -> Box(Modifier.fillMaxSize().background(backgroundBrush(item.background ?: "teal")), contentAlignment = Alignment.Center) {
                        Text(snippet, color = Color.White, style = MaterialTheme.typography.labelSmall, maxLines = 5, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.padding(4.dp))
                    }
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(label, style = MaterialTheme.typography.titleSmall)
                if (item.kind != StoryKind.Text && snippet.isNotEmpty()) Text(snippet, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "Posted ${timeAgo(((System.currentTimeMillis() - item.createdAtMs) / 1000).coerceAtLeast(0))} ago · ${remaining(story.expiresAtMs)}",
                    style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant,
                )
                Text(
                    if (story.audience == "followers") "Followers only" else "Campus",
                    style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (story.viewCount == 1) "1 view" else "${story.viewCount} views",
                        style = MaterialTheme.typography.labelLarge, color = colors.primary,
                        modifier = Modifier.clickable(role = Role.Button, onClickLabel = "See who viewed", onClick = onViewers).padding(vertical = 6.dp),
                    )
                    val summary = story.reactions.entries.sortedByDescending { it.value }.joinToString("  ") { "${ReactionEmoji[it.key] ?: it.key} ${it.value}" }
                    if (summary.isNotEmpty()) Text(summary, style = MaterialTheme.typography.labelMedium)
                }
            }
            Box {
                VibeIconButton(VibeIcons.More, "More options for this story") { menu = true }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Open") }, onClick = { menu = false; onOpen() })
                    DropdownMenuItem(text = { Text("See viewers") }, onClick = { menu = false; onViewers() })
                    if (story.audience == "campus") DropdownMenuItem(text = { Text("Show to followers only") }, onClick = { menu = false; onAudience("followers") })
                    else DropdownMenuItem(text = { Text("Show to campus") }, onClick = { menu = false; onAudience("campus") })
                    DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

@Composable
private fun ViewerRow(v: StoryViewer) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
        StudentAvatar(name = v.name, size = 40.dp, imagePath = v.avatarUrl)
        Column(Modifier.weight(1f)) {
            Text(v.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                (if (v.handle.isNotEmpty()) "@${v.handle} · " else "") + timeAgo(((System.currentTimeMillis() - v.viewedAtMs) / 1000).coerceAtLeast(0)) + " ago",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        v.reaction?.let { Text(ReactionEmoji[it] ?: "", style = MaterialTheme.typography.titleMedium) }
    }
}
