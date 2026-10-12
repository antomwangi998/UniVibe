package com.univibe.app.features.comments

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.univibe.app.core.keyedViewModel
import com.univibe.app.data.model.Comment
import com.univibe.app.ui.components.EmptyState
import com.univibe.app.ui.components.ErrorState
import com.univibe.app.ui.components.LoadingSkeleton
import com.univibe.app.ui.components.StudentAvatar
import com.univibe.app.ui.components.VibeIconButton
import com.univibe.app.ui.components.VibeTextField
import com.univibe.app.ui.components.VibeTopBar
import com.univibe.app.ui.theme.Spacing

@Composable
fun CommentsScreen(postId: String, onBack: () -> Unit, onOpenStudent: (String) -> Unit) {
    val viewModel = keyedViewModel("comments-$postId") { CommentsViewModel(postId) }
    val s by viewModel.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val context = LocalContext.current

    LaunchedEffect(s.notice) { s.notice?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show(); viewModel.consumeNotice() } }
    LaunchedEffect(s.comments.size) { if (s.comments.isNotEmpty()) listState.animateScrollToItem(s.comments.lastIndex) }

    Column(Modifier.fillMaxSize().imePadding()) {
        VibeTopBar("Comments", compactTitle = true, navigationIcon = { VibeIconButton(Icons.AutoMirrored.Outlined.ArrowBack, "Back", onBack) })
        Column(Modifier.weight(1f)) {
            when {
                s.loading -> LoadingSkeleton(Modifier.padding(Spacing.md), itemCount = 2)
                s.error != null -> ErrorState(s.error!!, viewModel::load)
                s.comments.isEmpty() -> EmptyState(Icons.Outlined.ChatBubbleOutline, "No comments yet", "Start the conversation.")
                else -> LazyColumn(
                    state = listState, modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    items(s.comments, key = { it.id }) { c ->
                        CommentRow(
                            c, onLike = { viewModel.toggleLike(c.id) }, onDelete = { viewModel.delete(c.id) },
                            onReport = { viewModel.report(c.id) }, onAuthor = { onOpenStudent(c.author.id) },
                        )
                    }
                }
            }
        }
        Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 6.dp) {
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(Spacing.sm),
                verticalAlignment = Alignment.Top,
            ) {
                VibeTextField(
                    value = s.input, onValueChange = viewModel::onInput, modifier = Modifier.weight(1f),
                    placeholder = "Add a comment", singleLine = false,
                    isError = s.input.length > MAX_COMMENT,
                    supportingText = if (s.input.length > MAX_COMMENT - 40) "${s.input.length}/$MAX_COMMENT" else null,
                )
                Spacer(Modifier.width(Spacing.sm))
                Surface(
                    shape = CircleShape,
                    color = if (s.canSend) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = if (s.canSend) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    IconButton(onClick = viewModel::send, enabled = s.canSend, modifier = Modifier.size(52.dp)) {
                        Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = "Post comment")
                    }
                }
            }
        }
    }
}

@Composable
private fun CommentRow(c: Comment, onLike: () -> Unit, onDelete: () -> Unit, onReport: () -> Unit, onAuthor: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        StudentAvatar(c.author.name, size = 40.dp, accent = c.author.accent, modifier = Modifier.clickable(role = Role.Button, onClick = onAuthor))
        Column(Modifier.weight(1f).padding(start = Spacing.sm + 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(c.author.name, style = MaterialTheme.typography.titleSmall)
                Text("  ${c.timeAgo}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(c.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 2.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Row(
                    Modifier.heightIn(min = 44.dp).clickable(role = Role.Button, onClick = onLike)
                        .semantics { contentDescription = if (c.likedByMe) "Unlike comment, ${c.likeCount} likes" else "Like comment, ${c.likeCount} likes" },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (c.likedByMe) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder, contentDescription = null,
                        tint = if (c.likedByMe) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                    Text("  ${c.likeCount}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = if (c.isMine) onDelete else onReport, modifier = Modifier.size(44.dp)) {
                    Icon(
                        if (c.isMine) Icons.Outlined.DeleteOutline else Icons.Outlined.Flag,
                        contentDescription = if (c.isMine) "Delete comment" else "Report comment",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}
