package com.univibe.app.features.notifications

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChatBubble
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.univibe.app.data.model.AppNotification
import com.univibe.app.data.model.NotificationTarget
import com.univibe.app.data.model.NotificationType
import com.univibe.app.ui.components.EmptyState
import com.univibe.app.ui.components.ErrorState
import com.univibe.app.ui.components.LoadingSkeleton
import com.univibe.app.ui.components.StudentAvatar
import com.univibe.app.ui.components.VibeIconButton
import com.univibe.app.ui.components.VibeTopBar
import com.univibe.app.ui.theme.Spacing

@Composable
fun NotificationCenterScreen(
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTarget: (NotificationTarget) -> Unit,
    viewModel: NotificationsViewModel = viewModel(),
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        VibeTopBar(
            "Notifications", compactTitle = true,
            navigationIcon = { VibeIconButton(Icons.AutoMirrored.Outlined.ArrowBack, "Back", onBack) },
            actions = { VibeIconButton(Icons.Outlined.Settings, "Notification settings", onOpenSettings) },
        )
        when {
            s.loading -> LoadingSkeleton(Modifier.padding(Spacing.md), itemCount = 4)
            s.error != null -> ErrorState(s.error!!, onRetry = viewModel::load)
            s.visible.isEmpty() -> EmptyState(
                Icons.Outlined.Notifications, "You're all caught up",
                "Likes, comments, follows and event reminders will show up here.",
            )
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = Spacing.md, end = Spacing.md, bottom = Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                if (s.unread.isNotEmpty()) {
                    item(key = "new-header") {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("New", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            TextButton(onClick = viewModel::markAllRead) { Text("Mark all read") }
                        }
                    }
                    items(s.unread, key = { it.id }) { n -> NotificationRow(n) { viewModel.open(n, onOpenTarget) } }
                }
                if (s.earlier.isNotEmpty()) {
                    item(key = "earlier-header") {
                        Text("Earlier", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = Spacing.sm, bottom = Spacing.xs))
                    }
                    items(s.earlier, key = { it.id }) { n -> NotificationRow(n) { viewModel.open(n, onOpenTarget) } }
                }
                item(key = "end") { Spacer(Modifier.navigationBarsPadding()) }
            }
        }
    }
}

private fun NotificationType.icon(): ImageVector = when (this) {
    NotificationType.Like -> Icons.Outlined.Favorite
    NotificationType.Comment -> Icons.Outlined.ChatBubbleOutline
    NotificationType.Follow -> Icons.Outlined.PersonAdd
    NotificationType.Connection -> Icons.Outlined.Favorite
    NotificationType.Event -> Icons.Outlined.Event
    NotificationType.Community -> Icons.Outlined.Groups
    NotificationType.Message -> Icons.Outlined.ChatBubble
    NotificationType.System -> Icons.Outlined.Notifications
}

@Composable
private fun NotificationRow(n: AppNotification, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val tappable = n.target != NotificationTarget.None || !n.read
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clip20()
            .background(if (n.read) Color.Transparent else colors.primaryContainer.copy(alpha = 0.35f))
            .clickable(enabled = tappable, role = Role.Button, onClickLabel = "Open", onClick = onClick)
            .padding(horizontal = Spacing.sm + 4.dp, vertical = Spacing.sm + 4.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "${n.title} ${n.body}, ${n.timeAgo} ago${if (n.read) "" else ", unread"}"
            },
        verticalAlignment = Alignment.Top,
    ) {
        Box {
            val actor = n.actor
            if (actor != null) {
                StudentAvatar(actor.name, size = 48.dp, accent = actor.accent, imagePath = actor.avatarUrl)
            } else {
                Surface(shape = CircleShape, color = colors.surfaceVariant, modifier = Modifier.size(48.dp)) {
                    Box(contentAlignment = Alignment.Center) { Icon(n.type.icon(), contentDescription = null, tint = colors.primary) }
                }
            }
            if (actor != null) {
                Surface(
                    shape = CircleShape, color = colors.primary, contentColor = colors.onPrimary,
                    modifier = Modifier.align(Alignment.BottomEnd).size(22.dp),
                ) { Box(contentAlignment = Alignment.Center) { Icon(n.type.icon(), contentDescription = null, modifier = Modifier.size(12.dp)) } }
            }
        }
        Spacer(Modifier.width(Spacing.md))
        Column(Modifier.weight(1f)) {
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(n.title) }
                    append(" ")
                    append(n.body)
                },
                style = MaterialTheme.typography.bodyMedium, maxLines = 3,
            )
            Text(n.timeAgo, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
        }
        if (!n.read) {
            Box(Modifier.padding(start = Spacing.sm, top = 6.dp).size(10.dp).background(colors.primary, CircleShape))
        }
    }
}

private fun Modifier.clip20(): Modifier = this.then(Modifier.clip(RoundedCornerShape(20.dp)))
