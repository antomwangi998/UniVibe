@file:OptIn(ExperimentalFoundationApi::class)

package com.univibe.app.features.comments

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import com.univibe.app.ui.components.VibeIcons
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.univibe.app.core.keyedViewModel
import com.univibe.app.data.model.Comment
import com.univibe.app.features.chats.SheetRow
import com.univibe.app.ui.components.ConfirmDialog
import com.univibe.app.ui.components.EmptyState
import com.univibe.app.ui.components.ErrorState
import com.univibe.app.ui.components.InterestChip
import com.univibe.app.ui.components.LoadingSkeleton
import com.univibe.app.ui.components.StudentAvatar
import com.univibe.app.ui.components.VibeBottomSheet
import com.univibe.app.ui.components.VibeIconButton
import com.univibe.app.ui.components.VibeTextField
import com.univibe.app.ui.components.VibeTopBar
import com.univibe.app.ui.theme.Spacing

private val ReportReasons = listOf("Spam", "Harassment or bullying", "Hate speech", "Misinformation", "Something else")
private val MentionRegex = Regex("@[A-Za-z0-9._]+")

@Composable
fun CommentsScreen(postId: String, onBack: () -> Unit, onOpenStudent: (String) -> Unit) {
    val viewModel = keyedViewModel("comments-$postId") { CommentsViewModel(postId) }
    val s by viewModel.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val colors = MaterialTheme.colorScheme

    LaunchedEffect(s.notice) { s.notice?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show(); viewModel.consumeNotice() } }

    val threads = s.threads
    // After you post, scroll to your comment once. Header and sort row come first, so offset by 2 (or 1 without a post).
    LaunchedEffect(s.scrollToId) {
        val id = s.scrollToId ?: return@LaunchedEffect
        val head = if (s.post != null) 2 else 1
        val index = threads.indexOfFirst { t -> t.parent.id == id || t.replies.any { it.id == id } }
        if (index >= 0) listState.animateScrollToItem(head + index)
        viewModel.consumeScroll()
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        VibeTopBar("Comments", compactTitle = true, navigationIcon = { VibeIconButton(VibeIcons.Back, "Back", onBack) })
        Column(Modifier.weight(1f)) {
            when {
                s.loading -> LoadingSkeleton(Modifier.padding(Spacing.md), itemCount = 2)
                s.error != null -> ErrorState(s.error!!, viewModel::load)
                else -> LazyColumn(
                    state = listState, modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    s.post?.let { post ->
                        item(key = "post") {
                            Column {
                                Row(verticalAlignment = Alignment.Top) {
                                    StudentAvatar(post.author.name, size = 40.dp, accent = post.author.accent, imagePath = post.author.avatarUrl, modifier = Modifier.clickable(role = Role.Button, onClickLabel = "Open profile") { onOpenStudent(post.author.id) })
                                    Column(Modifier.weight(1f).padding(start = Spacing.sm + 4.dp)) {
                                        Text(post.author.name, style = MaterialTheme.typography.titleSmall)
                                        Text(post.text, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                                HorizontalDivider(Modifier.padding(top = Spacing.md), color = colors.outlineVariant)
                            }
                        }
                    }
                    item(key = "sort") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${s.total} ${if (s.total == 1) "comment" else "comments"}", style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f),
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                                InterestChip("Top", selected = s.sort == CommentSort.Top, onClick = { viewModel.setSort(CommentSort.Top) })
                                InterestChip("Newest", selected = s.sort == CommentSort.Newest, onClick = { viewModel.setSort(CommentSort.Newest) })
                            }
                        }
                    }
                    if (threads.isEmpty()) item(key = "empty") {
                        EmptyState(VibeIcons.Comment, "No comments yet", "Start the conversation.")
                    }
                    threads.forEach { thread ->
                        item(key = thread.parent.id) {
                            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                                CommentRow(
                                    thread.parent, isReply = false,
                                    onLike = { viewModel.toggleLike(thread.parent.id) }, onReply = { viewModel.startReply(thread.parent) },
                                    onMore = { viewModel.openMenu(thread.parent) }, onAuthor = { onOpenStudent(thread.parent.author.id) },
                                )
                                if (thread.replies.isNotEmpty()) {
                                    val open = thread.parent.id in s.expanded
                                    Row(
                                        Modifier.padding(start = 52.dp).heightIn(min = 44.dp)
                                            .clickable(role = Role.Button, onClickLabel = if (open) "Hide replies" else "Show replies") { viewModel.toggleExpanded(thread.parent.id) },
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        HorizontalDivider(Modifier.width(24.dp), color = colors.outlineVariant)
                                        Text(
                                            if (open) "  Hide replies" else "  View ${thread.replies.size} ${if (thread.replies.size == 1) "reply" else "replies"}",
                                            style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant,
                                        )
                                    }
                                    if (open) thread.replies.forEach { r ->
                                        Box(Modifier.padding(start = 52.dp)) {
                                            CommentRow(
                                                r, isReply = true,
                                                onLike = { viewModel.toggleLike(r.id) }, onReply = { viewModel.startReply(r) },
                                                onMore = { viewModel.openMenu(r) }, onAuthor = { onOpenStudent(r.author.id) },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        Surface(color = colors.surface, shadowElevation = 6.dp) {
            Column(Modifier.navigationBarsPadding()) {
                AnimatedVisibility(s.replyingTo != null) {
                    s.replyingTo?.let { target ->
                        Row(Modifier.fillMaxWidth().padding(start = Spacing.md, end = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                            Text("Replying to ${target.author.name}", style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant, modifier = Modifier.weight(1f))
                            IconButton(onClick = viewModel::cancelReply) { Icon(VibeIcons.Close, contentDescription = "Cancel reply") }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(Spacing.sm), verticalAlignment = Alignment.Top) {
                    VibeTextField(
                        value = s.input, onValueChange = viewModel::onInput, modifier = Modifier.weight(1f),
                        placeholder = if (s.replyingTo != null) "Write a reply" else "Add a comment", singleLine = false,
                        isError = s.input.length > MAX_COMMENT,
                        supportingText = if (s.input.length > MAX_COMMENT - 40) "${s.input.length}/$MAX_COMMENT" else null,
                    )
                    Spacer(Modifier.width(Spacing.sm))
                    Surface(
                        shape = CircleShape,
                        color = if (s.canSend) colors.primary else colors.surfaceContainerHighest,
                        contentColor = if (s.canSend) colors.onPrimary else colors.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    ) {
                        IconButton(onClick = viewModel::send, enabled = s.canSend, modifier = Modifier.size(52.dp)) {
                            Icon(VibeIcons.Send, contentDescription = if (s.replyingTo != null) "Post reply" else "Post comment")
                        }
                    }
                }
            }
        }
    }

    s.menuComment?.let { c ->
        VibeBottomSheet(onDismiss = { viewModel.openMenu(null) }) {
            SheetRow(VibeIcons.Reply, "Reply", false) { viewModel.startReply(c) }
            SheetRow(VibeIcons.Copy, "Copy text", false) { clipboard.setText(AnnotatedString(c.text)); viewModel.openMenu(null) }
            if (!c.isMine) SheetRow(VibeIcons.Profile, "View ${c.author.name}'s profile", false) { viewModel.openMenu(null); onOpenStudent(c.author.id) }
            if (c.isMine) SheetRow(VibeIcons.Delete, "Delete comment", true) { viewModel.askDelete(c) }
            else SheetRow(VibeIcons.Report, "Report comment", true) { viewModel.startReport(c) }
        }
    }

    s.reporting?.let {
        VibeBottomSheet(onDismiss = viewModel::dismissReport) {
            Text("Why are you reporting this comment?", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm))
            ReportReasons.forEach { r -> SheetRow(VibeIcons.Report, r, false) { viewModel.report(r) } }
        }
    }

    s.confirmDelete?.let { c ->
        val replies = s.comments.count { it.parentId == c.id }
        ConfirmDialog(
            title = "Delete this comment?",
            message = if (replies > 0) "Its $replies ${if (replies == 1) "reply" else "replies"} will be deleted too." else "This can't be undone.",
            confirmLabel = "Delete", dismissLabel = "Cancel", destructive = true,
            onConfirm = viewModel::confirmDelete, onDismiss = viewModel::dismissDelete,
        )
    }
}

@Composable
private fun mentionText(text: String): AnnotatedString {
    val primary = MaterialTheme.colorScheme.primary
    return buildAnnotatedString {
        var last = 0
        for (m in MentionRegex.findAll(text)) {
            append(text.substring(last, m.range.first))
            withStyle(SpanStyle(color = primary, fontWeight = FontWeight.SemiBold)) { append(m.value) }
            last = m.range.last + 1
        }
        append(text.substring(last))
    }
}

@Composable
private fun CommentRow(c: Comment, isReply: Boolean, onLike: () -> Unit, onReply: () -> Unit, onMore: () -> Unit, onAuthor: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val avatar: Dp = if (isReply) 28.dp else 40.dp
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        StudentAvatar(c.author.name, size = avatar, accent = c.author.accent, imagePath = c.author.avatarUrl, modifier = Modifier.clickable(role = Role.Button, onClickLabel = "Open profile", onClick = onAuthor))
        Column(Modifier.weight(1f).padding(start = Spacing.sm + 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(c.author.name, style = MaterialTheme.typography.titleSmall)
                if (c.isPostAuthor) {
                    Surface(shape = CircleShape, color = colors.primaryContainer, contentColor = colors.onPrimaryContainer, modifier = Modifier.padding(start = 6.dp)) {
                        Text("Author", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                    }
                }
                Text("  ${c.timeAgo}", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            Text(
                mentionText(c.text), style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 2.dp).combinedClickable(onClick = {}, onDoubleClick = onLike, onLongClick = onMore),
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Box(
                    Modifier.heightIn(min = 44.dp).clickable(role = Role.Button, onClick = onReply).padding(end = Spacing.sm),
                    contentAlignment = Alignment.CenterStart,
                ) { Text("Reply", style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant) }
                IconButton(onClick = onMore, modifier = Modifier.size(44.dp)) {
                    Icon(VibeIcons.More, contentDescription = "More options for this comment", tint = colors.onSurfaceVariant, modifier = Modifier.size(18.dp))
                }
            }
        }
        Column(
            Modifier.heightIn(min = 44.dp).widthIn(min = 44.dp).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onLike)
                .semantics { contentDescription = if (c.likedByMe) "Unlike comment, ${c.likeCount} likes" else "Like comment, ${c.likeCount} likes" },
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                if (c.likedByMe) VibeIcons.HeartFilled else VibeIcons.Heart, contentDescription = null,
                tint = if (c.likedByMe) colors.error else colors.onSurfaceVariant, modifier = Modifier.size(18.dp),
            )
            if (c.likeCount > 0) Text("${c.likeCount}", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
        }
    }
}
