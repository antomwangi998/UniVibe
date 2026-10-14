package com.univibe.app.features.chats

import com.univibe.app.ui.components.rememberBottomClearance
import androidx.compose.animation.AnimatedVisibility
import androidx.lifecycle.compose.LifecycleEventEffect
import com.univibe.app.ui.components.VibeIconButton
import androidx.compose.material.icons.outlined.GroupAdd
import androidx.lifecycle.Lifecycle
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.univibe.app.data.model.Chat
import com.univibe.app.ui.components.EmptyState
import com.univibe.app.ui.components.ErrorState
import com.univibe.app.ui.components.InterestChip
import com.univibe.app.ui.components.LoadingSkeleton
import com.univibe.app.ui.components.StudentAvatar
import com.univibe.app.ui.components.VibeTextField
import com.univibe.app.ui.components.VibeTopBar
import com.univibe.app.ui.theme.Spacing

@Composable
fun ChatsScreen(onOpenChat: (String) -> Unit = {}, onNewGroup: () -> Unit = {}, viewModel: ChatsViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    Column(Modifier.fillMaxSize()) {
        VibeTopBar("Chats", actions = { VibeIconButton(Icons.Outlined.GroupAdd, "New group", onNewGroup) })
        VibeTextField(
            value = state.query,
            onValueChange = viewModel::setQuery,
            placeholder = "Search messages",
            leadingIcon = Icons.Outlined.Search,
            trailingIcon = {
                IconButton(onClick = viewModel::toggleFilters) {
                    Icon(
                        Icons.Outlined.FilterList,
                        contentDescription = "Filter chats",
                        tint = if (state.filter != ChatFilter.All) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            modifier = Modifier.padding(horizontal = Spacing.md),
        )
        AnimatedVisibility(visible = state.showFilters) {
            Row(
                Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                ChatFilter.entries.forEach { f ->
                    InterestChip(f.label, selected = state.filter == f, onClick = { viewModel.setFilter(f) })
                }
            }
        }
        when {
            state.loading -> LoadingSkeleton(Modifier.padding(Spacing.md), itemCount = 2)
            state.error != null -> ErrorState(state.error!!, onRetry = viewModel::load)
            state.visible.isEmpty() -> EmptyState(
                icon = Icons.Outlined.ChatBubbleOutline,
                title = if (state.query.isBlank() && state.filter == ChatFilter.All) "No conversations yet" else "No matches",
                message = if (state.query.isBlank() && state.filter == ChatFilter.All) "Start a chat from a student's profile."
                else "Try a different name, word or filter.",
            )
            else -> LazyColumn(
                contentPadding = PaddingValues(start = Spacing.md, end = Spacing.md, top = Spacing.sm, bottom = rememberBottomClearance()),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                if (state.pinned.isNotEmpty()) {
                    item(key = "hdr-pinned") { SectionLabel("Pinned") }
                    items(state.pinned, key = { it.id }) { chat ->
                        SwipeableChatRow(chat, { onOpenChat(chat.id) }, { viewModel.toggleMute(chat.id) }, { viewModel.archive(chat.id) })
                    }
                }
                if (state.others.isNotEmpty()) {
                    item(key = "hdr-all") { SectionLabel("All chats") }
                    items(state.others, key = { it.id }) { chat ->
                        SwipeableChatRow(chat, { onOpenChat(chat.id) }, { viewModel.toggleMute(chat.id) }, { viewModel.archive(chat.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = Spacing.sm, bottom = Spacing.xs),
    )
}

/** Swipe right to mute/unmute (snaps back), swipe left to archive. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableChatRow(chat: Chat, onOpen: () -> Unit, onMute: () -> Unit, onArchive: () -> Unit) {
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> { onMute(); false }
                SwipeToDismissBoxValue.EndToStart -> { onArchive(); true }
                SwipeToDismissBoxValue.Settled -> false
            }
        },
    )
    SwipeToDismissBox(
        state = state,
        backgroundContent = {
            val direction = state.dismissDirection
            val muteSide = direction == SwipeToDismissBoxValue.StartToEnd
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        if (muteSide) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.tertiaryContainer,
                        MaterialTheme.shapes.medium,
                    )
                    .padding(horizontal = Spacing.lg),
                contentAlignment = if (muteSide) Alignment.CenterStart else Alignment.CenterEnd,
            ) {
                Icon(
                    if (muteSide) Icons.Outlined.NotificationsOff else Icons.Outlined.Archive,
                    contentDescription = if (muteSide) "Mute" else "Archive",
                )
            }
        },
    ) {
        Surface(color = MaterialTheme.colorScheme.background, onClick = onOpen) { ChatRow(chat) }
    }
}

@Composable
private fun ChatRow(chat: Chat) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 76.dp)
            .padding(vertical = Spacing.sm)
            .semantics(mergeDescendants = true) {
                contentDescription = buildString {
                    append(chat.title); append(". "); append(chat.lastMessage)
                    if (chat.unread > 0) append(". ${chat.unread} unread")
                    if (chat.muted) append(". Muted")
                }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (chat.isGroup) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(56.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Groups, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        } else {
            StudentAvatar(chat.title, size = 56.dp, accent = chat.id.hashCode(), online = chat.online)
        }
        Column(Modifier.weight(1f).padding(horizontal = Spacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    chat.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (chat.unread > 0) FontWeight.Bold else FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (chat.pinned) {
                    Icon(Icons.Outlined.PushPin, contentDescription = null, modifier = Modifier.padding(start = 6.dp).size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (chat.muted) {
                    Icon(Icons.Outlined.NotificationsOff, contentDescription = null, modifier = Modifier.padding(start = 6.dp).size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(
                chat.lastMessage.ifBlank { "Say hello" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(chat.timeLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (chat.unread > 0) {
                Surface(
                    shape = CircleShape,
                    color = if (chat.muted) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 6.dp),
                ) {
                    Text(
                        chat.unread.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}
