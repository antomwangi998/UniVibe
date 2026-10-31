@file:OptIn(ExperimentalFoundationApi::class)

package com.univibe.app.features.comments

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.univibe.app.core.keyedViewModel
import com.univibe.app.data.model.Comment
import com.univibe.app.data.model.Post
import com.univibe.app.features.chats.SheetRow
import com.univibe.app.ui.components.AnimatedBookmarkButton
import com.univibe.app.ui.components.AnimatedLikeButton
import com.univibe.app.ui.components.AnimatedRepostButton
import com.univibe.app.ui.components.ConfirmDialog
import com.univibe.app.ui.components.DoubleTapHeart
import com.univibe.app.ui.components.EmptyState
import com.univibe.app.ui.components.ErrorState
import com.univibe.app.ui.components.LoadingSkeleton
import com.univibe.app.ui.components.PhotoGalleryViewerDialog
import com.univibe.app.ui.components.PostGallery
import com.univibe.app.ui.components.PostVideo
import com.univibe.app.ui.components.SocialActionButton
import com.univibe.app.ui.components.StudentAvatar
import com.univibe.app.ui.components.UndoBar
import com.univibe.app.ui.components.VerifiedName
import com.univibe.app.ui.components.VibeBottomSheet
import com.univibe.app.ui.components.VibeIconButton
import com.univibe.app.ui.components.VibeIcons
import com.univibe.app.ui.components.VibePullToRefresh
import com.univibe.app.ui.components.VibeTopBar
import com.univibe.app.ui.components.compactCount
import com.univibe.app.ui.components.sharePost
import com.univibe.app.ui.theme.Spacing

private val ReportReasons = listOf("Spam", "Harassment or bullying", "Hate speech", "Misinformation", "Something else")
private val MentionRegex = Regex("@[A-Za-z0-9._]+")

/** Width of the avatar column of a top-level comment: 40dp avatar + 12dp gap. Replies and the thread rail line up with it. */
private val AvatarColumn = 52.dp

@Composable
fun CommentsScreen(postId: String, onBack: () -> Unit, onOpenStudent: (String) -> Unit) {
    val viewModel = keyedViewModel("comments-$postId") { CommentsViewModel(postId) }
    val s by viewModel.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val colors = MaterialTheme.colorScheme
    val composerFocus = remember { FocusRequester() }

    LaunchedEffect(s.notice) { s.notice?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show(); viewModel.consumeNotice() } }
    // Tapping Reply on a comment puts the cursor straight into the reply box.
    LaunchedEffect(s.replyingTo?.id) { if (s.replyingTo != null) runCatching { composerFocus.requestFocus() } }

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
        VibeTopBar("Post", compactTitle = true, navigationIcon = { VibeIconButton(VibeIcons.Back, "Back", onBack) })
        Column(Modifier.weight(1f)) {
            when {
                s.loading -> LoadingSkeleton(Modifier.padding(Spacing.md), itemCount = 2)
                s.error != null -> ErrorState(s.error!!, viewModel::load)
                else -> VibePullToRefresh(refreshing = s.refreshing, onRefresh = viewModel::pullRefresh) { LazyColumn(
                    state = listState, modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = Spacing.lg),
                ) {
                    s.post?.let { post ->
                        item(key = "post") {
                            PostDetail(
                                post = post,
                                onAuthor = { onOpenStudent(post.author.id) },
                                onLike = viewModel::togglePostLike,
                                onRepost = viewModel::togglePostRepost,
                                onSave = viewModel::togglePostSave,
                                onComment = { runCatching { composerFocus.requestFocus() } },
                                onShare = {
                                    if (!sharePost(context, post)) Toast.makeText(context, "Only campus-wide posts can be shared outside UniVibe.", Toast.LENGTH_SHORT).show()
                                },
                            )
                        }
                    }
                    item(key = "sort") {
                        SortRow(s.total, s.sort, viewModel::setSort)
                    }
                    if (threads.isEmpty()) item(key = "empty") {
                        EmptyState(VibeIcons.Comment, "No comments yet", "Start the conversation.")
                    }
                    threads.forEach { thread ->
                        item(key = thread.parent.id) {
                            val open = thread.parent.id in s.expanded
                            Column {
                                CommentRow(
                                    thread.parent, isReply = false, lineBelow = thread.replies.isNotEmpty(), replyCount = thread.replies.size,
                                    onLike = { viewModel.toggleLike(thread.parent.id) }, onReply = { viewModel.startReply(thread.parent) },
                                    onMore = { viewModel.openMenu(thread.parent) }, onAuthor = { onOpenStudent(thread.parent.author.id) },
                                )
                                if (thread.replies.isNotEmpty()) {
                                    RepliesToggle(
                                        open = open, count = thread.replies.size,
                                        onClick = { viewModel.toggleExpanded(thread.parent.id) },
                                    )
                                    if (open) thread.replies.forEach { r ->
                                        Box(Modifier.padding(start = 40.dp)) {
                                            CommentRow(
                                                r, isReply = true, lineBelow = false, replyCount = 0,
                                                onLike = { viewModel.toggleLike(r.id) }, onReply = { viewModel.startReply(r) },
                                                onMore = { viewModel.openMenu(r) }, onAuthor = { onOpenStudent(r.author.id) },
                                            )
                                        }
                                    }
                                }
                                HorizontalDivider(Modifier.padding(top = Spacing.xs), color = colors.outlineVariant.copy(alpha = 0.6f), thickness = 0.5.dp)
                            }
                        }
                    }
                } }
            }
        }
        Composer(s, viewModel, composerFocus)
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
            message = if (replies > 0) "Its $replies ${if (replies == 1) "reply" else "replies"} will be deleted too." else "You can undo for a few seconds.",
            confirmLabel = "Delete", dismissLabel = "Cancel", destructive = true,
            onConfirm = viewModel::confirmDelete, onDismiss = viewModel::dismissDelete,
        )
    }
}

/** The post itself, full length with its media, counts and the same action bar as the feed. Double-tap likes it. */
@Composable
private fun PostDetail(
    post: Post,
    onAuthor: () -> Unit,
    onLike: () -> Unit,
    onRepost: () -> Unit,
    onSave: () -> Unit,
    onComment: () -> Unit,
    onShare: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    var viewerIndex by remember(post.id) { mutableStateOf<Int?>(null) }
    var burst by remember(post.id) { mutableIntStateOf(0) }
    val photos = remember(post.imagePath, post.imagePaths) { post.imagePaths.ifEmpty { listOfNotNull(post.imagePath) } }
    val shownPhotos = remember(photos, post.thumbPaths) { if (post.thumbPaths.size == photos.size) post.thumbPaths else photos }
    val doubleTapLike = {
        if (!post.likedByMe) onLike()
        burst++
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }
    val side = Modifier.padding(horizontal = Spacing.md)
    Column(Modifier.fillMaxWidth()) {
        Row(side.padding(top = Spacing.sm + 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClickLabel = "Open profile", onClick = onAuthor),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StudentAvatar(post.author.name, size = 44.dp, accent = post.author.accent, imagePath = post.author.avatarUrl, look = post.author.look)
                Column(Modifier.weight(1f).padding(start = Spacing.sm + 4.dp)) {
                    VerifiedName(post.author.name, post.author.verified, look = post.author.look, style = MaterialTheme.typography.titleSmall)
                    Text(post.author.handle, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Box(Modifier.fillMaxWidth().pointerInput(Unit) { detectTapGestures(onDoubleTap = { doubleTapLike() }) }) {
            Column {
                if (post.text.isNotBlank()) {
                    Text(mentionText(post.text), style = MaterialTheme.typography.bodyLarge, modifier = side.padding(top = Spacing.sm + 4.dp))
                }
                val mediaPadding = side.padding(top = Spacing.sm + 4.dp)
                when {
                    post.videoPath != null -> PostVideo(post.videoPath, post.author.name, mediaPadding, knownRatio = post.videoRatio)
                    photos.isNotEmpty() -> PostGallery(
                        shownPhotos, post.author.name, onOpen = { viewerIndex = it }, modifier = mediaPadding,
                        ratios = post.mediaRatios, onDoubleTap = { doubleTapLike() },
                    )
                }
            }
            DoubleTapHeart(burst, Modifier.align(Alignment.Center))
        }
        Text(
            "${post.timeAgo}${if (post.edited) " · edited" else ""}",
            style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant,
            modifier = side.padding(top = Spacing.sm + 4.dp),
        )
        if (post.repostCount > 0 || post.likeCount > 0 || post.viewCount > 0) {
            HorizontalDivider(Modifier.padding(top = Spacing.sm + 4.dp), color = colors.outlineVariant.copy(alpha = 0.6f), thickness = 0.5.dp)
            Row(side.padding(vertical = Spacing.sm + 4.dp), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                if (post.repostCount > 0) Stat(post.repostCount, if (post.repostCount == 1) "Repost" else "Reposts")
                if (post.likeCount > 0) Stat(post.likeCount, if (post.likeCount == 1) "Like" else "Likes")
                if (post.viewCount > 0) Stat(post.viewCount, if (post.viewCount == 1) "View" else "Views")
            }
        }
        HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.6f), thickness = 0.5.dp)
        Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                SocialActionButton(VibeIcons.Comment, "Reply to this post, ${post.commentCount} comments", onComment, count = post.commentCount)
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) { AnimatedRepostButton(post.repostedByMe, post.repostCount, onRepost) }
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) { AnimatedLikeButton(post.likedByMe, post.likeCount, onLike) }
            AnimatedBookmarkButton(post.savedByMe, onSave)
            SocialActionButton(VibeIcons.Share, "Share", onShare)
        }
        HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.6f), thickness = 0.5.dp)
    }
    viewerIndex?.let { PhotoGalleryViewerDialog(photos, it, onDismiss = { viewerIndex = null }) }
}

/** "117 Likes": the number in bold, the word in the quiet colour. */
@Composable
private fun Stat(count: Int, label: String) {
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)) { append(compactCount(count)) }
            append(" $label")
        },
        style = MaterialTheme.typography.bodyMedium, color = quiet,
    )
}

/** "12 comments" on the left, a small Top / Newest menu on the right. */
@Composable
private fun SortRow(total: Int, sort: CommentSort, onSort: (CommentSort) -> Unit) {
    val colors = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(start = Spacing.md, end = Spacing.sm, top = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "$total ${if (total == 1) "comment" else "comments"}", style = MaterialTheme.typography.titleSmall,
            color = colors.onSurfaceVariant, modifier = Modifier.weight(1f),
        )
        Box {
            Row(
                Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
                    .clickable(role = Role.Button, onClickLabel = "Change sort order") { menu = true }
                    .padding(horizontal = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(if (sort == CommentSort.Top) "Top" else "Newest", style = MaterialTheme.typography.labelLarge, color = colors.primary)
                Icon(VibeIcons.ChevronDown, contentDescription = null, tint = colors.primary, modifier = Modifier.padding(start = 2.dp).size(18.dp))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Top") }, onClick = { onSort(CommentSort.Top); menu = false })
                DropdownMenuItem(text = { Text("Newest") }, onClick = { onSort(CommentSort.Newest); menu = false })
            }
        }
    }
    HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.6f), thickness = 0.5.dp)
}

/** "Show 3 replies" hanging off the thread line under a comment. */
@Composable
private fun RepliesToggle(open: Boolean, count: Int, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val rail = colors.outlineVariant
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp)
            .drawBehind {
                // The line from the comment above carries on through this row; closed threads end it halfway.
                val x = (Spacing.md + 20.dp).toPx()
                drawLine(rail, Offset(x, 0f), Offset(x, if (open) size.height else size.height / 2f), strokeWidth = 2.dp.toPx())
            }
            .clickable(role = Role.Button, onClickLabel = if (open) "Hide replies" else "Show replies", onClick = onClick)
            .padding(start = Spacing.md + AvatarColumn),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (open) "Hide replies" else "Show $count ${if (count == 1) "reply" else "replies"}",
            style = MaterialTheme.typography.labelLarge, color = colors.primary,
        )
    }
}

/** The reply bar: your avatar, a pill-shaped field and a Reply button, with a "Replying to" line above when answering someone. */
@Composable
private fun Composer(s: CommentsUiState, viewModel: CommentsViewModel, focus: FocusRequester) {
    val colors = MaterialTheme.colorScheme
    Surface(color = colors.surface) {
        Column(Modifier.navigationBarsPadding()) {
            HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.6f), thickness = 0.5.dp)
            AnimatedVisibility(s.replyingTo != null) {
                s.replyingTo?.let { target ->
                    Row(Modifier.fillMaxWidth().padding(start = Spacing.md, end = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            buildAnnotatedString {
                                append("Replying to ")
                                withStyle(SpanStyle(color = colors.primary)) { append(target.author.handle) }
                            },
                            style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant, modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = viewModel::cancelReply) { Icon(VibeIcons.Close, contentDescription = "Cancel reply", modifier = Modifier.size(18.dp)) }
                    }
                }
            }
            if (s.undoComment != null) UndoBar("Comment deleted", viewModel::undoDelete)
            if (s.input.length > MAX_COMMENT - 40) {
                Text(
                    "${s.input.length}/$MAX_COMMENT", style = MaterialTheme.typography.labelSmall,
                    color = if (s.input.length > MAX_COMMENT) colors.error else colors.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.End).padding(end = Spacing.md, top = Spacing.xs),
                )
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.sm), verticalAlignment = Alignment.Bottom) {
                StudentAvatar(
                    s.me?.name ?: "", size = 36.dp, accent = s.me?.accent ?: 0, imagePath = s.me?.avatarUrl, look = s.me?.look,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                Spacer(Modifier.width(Spacing.sm + 2.dp))
                Box(
                    Modifier.weight(1f).heightIn(min = 44.dp).clip(RoundedCornerShape(22.dp))
                        .background(colors.surfaceContainerHighest).padding(horizontal = Spacing.md, vertical = 10.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    BasicTextField(
                        value = s.input, onValueChange = viewModel::onInput,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
                        cursorBrush = SolidColor(colors.primary), maxLines = 5,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                        decorationBox = { inner ->
                            Box {
                                if (s.input.isEmpty()) {
                                    Text(
                                        if (s.replyingTo != null) "Post your reply" else "Add a comment",
                                        style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant,
                                    )
                                }
                                inner()
                            }
                        },
                    )
                }
                Spacer(Modifier.width(Spacing.sm))
                Box(
                    Modifier.heightIn(min = 44.dp).clip(CircleShape)
                        .background(if (s.canSend) colors.primary else colors.surfaceContainerHighest)
                        .clickable(enabled = s.canSend, role = Role.Button, onClickLabel = if (s.replyingTo != null) "Post reply" else "Post comment", onClick = viewModel::send)
                        .padding(horizontal = 18.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Reply", style = MaterialTheme.typography.labelLarge,
                        color = if (s.canSend) colors.onPrimary else colors.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                }
            }
        }
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
private fun CommentRow(
    c: Comment, isReply: Boolean, lineBelow: Boolean, replyCount: Int,
    onLike: () -> Unit, onReply: () -> Unit, onMore: () -> Unit, onAuthor: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val avatar: Dp = if (isReply) 32.dp else 40.dp
    val rail = colors.outlineVariant
    Row(
        Modifier.fillMaxWidth()
            .drawBehind {
                // Thread line from under the avatar down to the replies.
                if (lineBelow) {
                    val x = (Spacing.md + avatar / 2).toPx()
                    drawLine(rail, Offset(x, (12.dp + avatar + 6.dp).toPx()), Offset(x, size.height), strokeWidth = 2.dp.toPx())
                }
            }
            .padding(start = Spacing.md, end = Spacing.xs, top = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        StudentAvatar(c.author.name, size = avatar, accent = c.author.accent, imagePath = c.author.avatarUrl, look = c.author.look, modifier = Modifier.clickable(role = Role.Button, onClickLabel = "Open profile", onClick = onAuthor))
        Column(Modifier.weight(1f).padding(start = Spacing.sm + 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                VerifiedName(c.author.name, c.author.verified, look = c.author.look, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f, fill = false))
                com.univibe.app.features.profile.BadgeGlyph(c.author.look?.badge, Modifier.padding(start = 4.dp))
                if (c.isPostAuthor) {
                    Surface(shape = CircleShape, color = colors.primaryContainer, contentColor = colors.onPrimaryContainer, modifier = Modifier.padding(start = 6.dp)) {
                        Text("Author", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                    }
                }
                Text(
                    "  ${c.author.handle} · ${c.timeAgo}", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                )
            }
            Text(
                mentionText(c.text), style = MaterialTheme.typography.bodyMedium,
                // A double-tap only likes, so an accidental second double-tap never takes a like back.
                modifier = Modifier.padding(top = 2.dp).combinedClickable(onClick = {}, onDoubleClick = { if (!c.likedByMe) onLike() }, onLongClick = onMore),
            )
            Row(Modifier.offset(x = -Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                SocialActionButton(
                    icon = VibeIcons.Comment, contentDescription = "Reply to ${c.author.name}", onClick = onReply,
                    count = replyCount.takeIf { it > 0 }, iconSize = 18.dp, keepCountPadding = true,
                )
                Spacer(Modifier.width(Spacing.md))
                AnimatedLikeButton(c.likedByMe, c.likeCount, onLike, iconSize = 18.dp, hideZero = true)
            }
        }
        IconButton(onClick = onMore, modifier = Modifier.size(40.dp)) {
            Icon(VibeIcons.More, contentDescription = "More options for this comment", tint = colors.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
    }
}
