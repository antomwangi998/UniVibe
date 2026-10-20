package com.univibe.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.univibe.app.data.model.Post
import com.univibe.app.data.model.PostAudience
import com.univibe.app.ui.theme.Spacing

/**
 * One post in the feed. The height comes entirely from the content: author row, text, poll, media, then the
 * action bar underneath. Nothing is given a fixed height, so text-only posts stay compact while photo, gallery,
 * video and poll posts grow to fit. Posts run edge to edge and are separated by a hairline, which leaves the
 * media almost the full screen width instead of boxing it inside a padded card.
 */
@Composable
fun CampusPostCard(
    post: Post,
    onLike: () -> Unit,
    onComment: () -> Unit,
    modifier: Modifier = Modifier,
    onSave: () -> Unit = {},
    onMore: () -> Unit = {},
    onAuthorClick: () -> Unit = {},
    onShare: () -> Unit = {},
    /** Null makes any poll read-only (results shown, no voting). */
    onVote: ((Int) -> Unit)? = null,
) {
    var viewerIndex by remember(post.id) { mutableStateOf<Int?>(null) }
    val photos = remember(post.imagePath, post.imagePaths) {
        post.imagePaths.ifEmpty { listOfNotNull(post.imagePath) }
    }
    // The feed shows the small copies; the full-screen viewer below opens the full-size photos.
    val feedPhotos = remember(photos, post.thumbPaths) { if (post.thumbPaths.size == photos.size) post.thumbPaths else photos }
    // Double-tapping the text or media likes the post (never unlikes it) and pops a heart over it.
    val haptics = LocalHapticFeedback.current
    var burst by remember(post.id) { mutableIntStateOf(0) }
    val doubleTapLike = rememberUpdatedState<() -> Unit> {
        if (!post.likedByMe) onLike()
        burst++
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }
    Column(modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = Spacing.sm + 4.dp, end = Spacing.xs, top = Spacing.sm + 4.dp)) {
            post.community?.let { PostCommunityHeader(it, post.announcement) }
            AuthorRow(post, onAuthorClick, onMore)
        }
        Box(Modifier.fillMaxWidth().pointerInput(Unit) { detectTapGestures(onDoubleTap = { doubleTapLike.value() }) }) {
        Column {
        if (post.text.isNotBlank()) {
            Text(
                post.text,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(start = Spacing.sm + 4.dp, end = Spacing.xs + Spacing.sm, top = 6.dp),
            )
        }
        val mediaPadding = Modifier.padding(start = Spacing.sm + 4.dp, end = Spacing.sm + 4.dp, top = Spacing.sm + 2.dp)
        if (post.poll != null) PostPoll(post.poll, onVote, mediaPadding)
        when {
            post.videoPath != null -> PostVideo(post.videoPath, post.author.name, mediaPadding, knownRatio = post.videoRatio)
            photos.isNotEmpty() -> PostGallery(feedPhotos, post.author.name, onOpen = { viewerIndex = it }, modifier = mediaPadding, ratios = post.mediaRatios, onDoubleTap = { doubleTapLike.value() })
            post.mediaAccent != null -> Box(
                mediaPadding
                    .fillMaxWidth()
                    .aspectRatio(if (post.mediaPortrait) 4f / 5f else 16f / 9f)
                    .clip(PostMediaShape)
                    .background(Brush.linearGradient(accentGradient(post.mediaAccent))),
                contentAlignment = Alignment.Center,
            ) {
                Icon(VibeIcons.Photo, contentDescription = null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(40.dp))
            }
        }
        }
        DoubleTapHeart(burst, Modifier.align(Alignment.Center))
        }
        // The action bar always sits below the content, in its own row, so nothing can clip or overlap it.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            AnimatedLikeButton(post.likedByMe, post.likeCount, onLike)
            SocialActionButton(
                icon = VibeIcons.Comment,
                contentDescription = "Comments, ${post.commentCount}",
                onClick = onComment,
                count = post.commentCount,
            )
            SocialActionButton(
                icon = VibeIcons.Share,
                contentDescription = "Share, ${post.shareCount}",
                onClick = onShare,
                count = post.shareCount,
            )
            Spacer(Modifier.weight(1f))
            AnimatedBookmarkButton(post.savedByMe, onSave)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), thickness = 0.5.dp)
    }
    viewerIndex?.let { PhotoGalleryViewerDialog(photos, it, onDismiss = { viewerIndex = null }) }
}

@Composable
private fun AuthorRow(post: Post, onAuthorClick: () -> Unit, onMore: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClickLabel = "Open profile", onClick = onAuthorClick),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StudentAvatar(post.author.name, size = 40.dp, accent = post.author.accent, imagePath = post.author.avatarUrl)
            Spacer(Modifier.width(Spacing.sm + 2.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(post.author.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (post.author.verified) {
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Filled.Verified, contentDescription = "Verified", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                    }
                }
                val audience = when (val a = post.audience) {
                    PostAudience.Campus -> ""
                    PostAudience.Followers -> " · Followers"
                    is PostAudience.Community -> if (post.community != null) "" else " · ${a.name}"
                }
                Text(
                    "${post.author.handle} · ${post.timeAgo}${if (post.edited) " · edited" else ""}$audience",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        SocialActionButton(
            icon = VibeIcons.More,
            contentDescription = "More options",
            onClick = onMore,
        )
    }
}
