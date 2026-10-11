package com.univibe.app.features.chats

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
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import com.univibe.app.ui.components.LoadingSkeleton
import com.univibe.app.ui.components.StudentAvatar
import com.univibe.app.ui.components.VibeTextField
import com.univibe.app.ui.components.VibeTopBar
import com.univibe.app.ui.theme.Spacing

@Composable
fun ChatsScreen(viewModel: ChatsViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        VibeTopBar("Chats")
        VibeTextField(
            value = state.query,
            onValueChange = viewModel::setQuery,
            placeholder = "Search messages",
            leadingIcon = Icons.Outlined.Search,
            modifier = Modifier.padding(horizontal = Spacing.md),
        )
        when {
            state.loading -> LoadingSkeleton(Modifier.padding(Spacing.md), itemCount = 2)
            state.error != null -> ErrorState(state.error!!, onRetry = viewModel::load)
            state.visible.isEmpty() -> EmptyState(
                icon = Icons.Outlined.ChatBubbleOutline,
                title = if (state.query.isBlank()) "No conversations yet" else "No matches",
                message = if (state.query.isBlank()) "Start a chat from a student's profile." else "Try a different name or word.",
            )
            else -> LazyColumn(
                contentPadding = PaddingValues(horizontal = Spacing.md, vertical = Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                items(state.visible, key = { it.id }) { ChatRow(it) }
            }
        }
    }
}

@Composable
private fun ChatRow(chat: Chat) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .padding(vertical = Spacing.sm)
            .semantics(mergeDescendants = true) {
                contentDescription = buildString {
                    append(chat.title); append(". "); append(chat.lastMessage)
                    if (chat.unread > 0) append(". ${chat.unread} unread")
                }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (chat.isGroup) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(52.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Groups, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        } else {
            StudentAvatar(chat.title, size = 52.dp, accent = chat.id.hashCode(), online = chat.online)
        }
        Column(Modifier.weight(1f).padding(horizontal = Spacing.md)) {
            Text(
                chat.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (chat.unread > 0) FontWeight.Bold else FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                chat.lastMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(chat.timeLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (chat.unread > 0) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp)) {
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

