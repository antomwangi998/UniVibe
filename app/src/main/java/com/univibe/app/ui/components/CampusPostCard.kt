package com.univibe.app.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.univibe.app.data.model.Post
import com.univibe.app.data.model.PostAudience
import com.univibe.app.ui.theme.Spacing

@Composable
fun CampusPostCard(
    post: Post,
    onLike: () -> Unit,
    onComment: () -> Unit,
    modifier: Modifier = Modifier,
    onSave: () -> Unit = {},
    onMore: () -> Unit = {},
    onAuthorClick: () -> Unit = {},
) {
    VibeCard(modifier = modifier, elevation = 2.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onAuthorClick),
                verticalAlignment = Alignment.CenterVertically,
            ) {
            StudentAvatar(post.author.name, size = 44.dp, accent = post.author.accent)
            Spacer(Modifier.width(Spacing.sm + 4.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(post.author.name, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                    if (post.author.verified) {
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Filled.Verified, contentDescription = "Verified", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                    }
                }
                val audience = when (val a = post.audience) {
                    PostAudience.Campus -> ""
                    PostAudience.Followers -> " · Followers"
                    is PostAudience.Community -> " · ${a.name}"
                }
                Text(
                    "${post.author.handle} · ${post.timeAgo}$audience",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            }
            IconButton(onClick = onMore) {
                Icon(Icons.Outlined.MoreHoriz, contentDescription = "More options")
            }
        }
        Spacer(Modifier.size(Spacing.sm))
        if (post.text.isNotBlank()) Text(post.text, style = MaterialTheme.typography.bodyLarge)
        if (post.imagePaths.size > 1) {
            Spacer(Modifier.size(Spacing.sm + 4.dp))
            PostImageGrid(post.imagePaths, post.author.name)
        } else if (post.imagePath != null) {
            Spacer(Modifier.size(Spacing.sm + 4.dp))
            Box(Modifier.fillMaxWidth().aspectRatio(4f / 3f).clip(RoundedCornerShape(16.dp))) {
                PhotoImage(post.imagePath, contentDescription = "Photo attached to ${post.author.name}'s post")
            }
        } else if (post.mediaAccent != null) {
            Spacer(Modifier.size(Spacing.sm + 4.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(if (post.mediaPortrait) 4f / 5f else 16f / 9f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Brush.linearGradient(accentGradient(post.mediaAccent))),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Image, contentDescription = null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(40.dp))
            }
        }
        Spacer(Modifier.size(Spacing.sm))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            LikeAction(post.likedByMe, post.likeCount, onLike)
            CountAction(
                icon = { Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = null, modifier = Modifier.size(22.dp)) },
                count = post.commentCount,
                description = "Comments, ${post.commentCount}",
                onClick = onComment,
            )
            CountAction(
                icon = { Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(22.dp)) },
                count = post.shareCount,
                description = "Share, ${post.shareCount}",
                onClick = { /* TODO: share sheet */ },
            )
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onSave) {
                Icon(
                    imageVector = if (post.savedByMe) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                    contentDescription = if (post.savedByMe) "Remove from saved" else "Save post",
                    tint = if (post.savedByMe) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun LikeAction(liked: Boolean, count: Int, onClick: () -> Unit) {
    val scale = remember { Animatable(1f) }
    LaunchedEffect(liked) {
        if (liked) {
            scale.snapTo(0.7f)
            scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
        }
    }
    Row(
        Modifier
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.sm)
            .semantics {
                role = Role.Button
                contentDescription = if (liked) "Unlike, $count likes" else "Like, $count likes"
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (liked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
            contentDescription = null,
            tint = if (liked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp).graphicsLayer { scaleX = scale.value; scaleY = scale.value },
        )
        Spacer(Modifier.width(6.dp))
        AnimatedCount(count)
    }
}

@Composable
private fun CountAction(icon: @Composable () -> Unit, count: Int?, description: String, onClick: () -> Unit) {
    Row(
        Modifier
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.sm)
            .semantics { role = Role.Button; contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon()
        if (count != null) {
            Spacer(Modifier.width(6.dp))
            Text(count.toString(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AnimatedCount(count: Int) {
    AnimatedContent(
        targetState = count,
        transitionSpec = {
            if (targetState > initialState) {
                (slideInVertically { it } + fadeIn()) togetherWith (slideOutVertically { -it } + fadeOut())
            } else {
                (slideInVertically { -it } + fadeIn()) togetherWith (slideOutVertically { it } + fadeOut())
            }
        },
        label = "likeCount",
    ) { value ->
        Text(value.toString(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 2 to 4 photos in one 4:3 frame: side by side, one tall + two stacked, or a 2x2 grid. */
@Composable
private fun PostImageGrid(paths: List<String>, authorName: String) {
    val gap = 2.dp
    val shown = paths.take(4)
    Row(
        Modifier.fillMaxWidth().aspectRatio(4f / 3f).clip(RoundedCornerShape(16.dp)),
        horizontalArrangement = Arrangement.spacedBy(gap),
    ) {
        when (shown.size) {
            2 -> shown.forEachIndexed { i, p -> GridPhoto(p, "Photo ${i + 1} of 2 attached to $authorName's post", Modifier.weight(1f).fillMaxHeight()) }
            3 -> {
                GridPhoto(shown[0], "Photo 1 of 3 attached to $authorName's post", Modifier.weight(1f).fillMaxHeight())
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(gap)) {
                    GridPhoto(shown[1], "Photo 2 of 3 attached to $authorName's post", Modifier.weight(1f).fillMaxWidth())
                    GridPhoto(shown[2], "Photo 3 of 3 attached to $authorName's post", Modifier.weight(1f).fillMaxWidth())
                }
            }
            else -> {
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(gap)) {
                    GridPhoto(shown[0], "Photo 1 of 4 attached to $authorName's post", Modifier.weight(1f).fillMaxWidth())
                    GridPhoto(shown[1], "Photo 2 of 4 attached to $authorName's post", Modifier.weight(1f).fillMaxWidth())
                }
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(gap)) {
                    GridPhoto(shown[2], "Photo 3 of 4 attached to $authorName's post", Modifier.weight(1f).fillMaxWidth())
                    GridPhoto(shown[3], "Photo 4 of 4 attached to $authorName's post", Modifier.weight(1f).fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun GridPhoto(path: String, description: String, modifier: Modifier) {
    Box(modifier) { PhotoImage(path, contentDescription = description, modifier = Modifier.fillMaxSize()) }
}
