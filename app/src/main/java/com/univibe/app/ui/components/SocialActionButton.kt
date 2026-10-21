package com.univibe.app.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.univibe.app.ui.theme.Spacing

/**
 * An icon that is itself the button: no box, no ripple rectangle, no circle behind it.
 * Press feedback is a small scale-down on the icon (and its count); the touch target stays 48dp.
 * Purely visual: all state and actions come from the caller.
 *
 * [popOnSelect] plays a short scale-up when [selected] turns true (like, save).
 */
@Composable
fun SocialActionButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    count: Int? = null,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    iconSize: Dp = 22.dp,
    enabled: Boolean = true,
    selected: Boolean = false,
    popOnSelect: Boolean = false,
) {
    val reduced = rememberReducedMotion()
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed && !reduced) 0.86f else 1f,
        animationSpec = tween(durationMillis = 120),
        label = "actionPress",
    )
    val pop = remember { Animatable(1f) }
    LaunchedEffect(selected) {
        if (selected && popOnSelect && !reduced) {
            pop.snapTo(0.78f)
            pop.animateTo(1f, spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMedium))
        }
    }
    val color by animateColorAsState(
        targetValue = if (enabled) tint else tint.copy(alpha = 0.38f),
        animationSpec = tween(durationMillis = 180),
        label = "actionTint",
    )
    Row(
        modifier
            .heightIn(min = 48.dp)
            .widthIn(min = 48.dp)
            .clickable(interactionSource = source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = if (count != null) Spacing.sm else 0.dp)
            .semantics { this.role = Role.Button; this.contentDescription = contentDescription }
            .graphicsLayer { scaleX = pressScale; scaleY = pressScale },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(iconSize).graphicsLayer { scaleX = pop.value; scaleY = pop.value },
        )
        if (count != null) {
            Spacer(Modifier.width(6.dp))
            AnimatedActionCount(count, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AnimatedActionCount(count: Int, color: Color) {
    AnimatedContent(
        targetState = count,
        transitionSpec = {
            if (targetState > initialState) {
                (slideInVertically { it } + fadeIn()) togetherWith (slideOutVertically { -it } + fadeOut())
            } else {
                (slideInVertically { -it } + fadeIn()) togetherWith (slideOutVertically { it } + fadeOut())
            }
        },
        label = "actionCount",
    ) { value ->
        Text(compactCount(value), style = MaterialTheme.typography.labelLarge, color = color)
    }
}

/** Heart toggle. Outline when off, solid error-colored heart when on; same geometry in both states. */
@Composable
fun AnimatedLikeButton(liked: Boolean, count: Int, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    SocialActionButton(
        icon = if (liked) VibeIcons.HeartFilled else VibeIcons.Heart,
        contentDescription = if (liked) "Unlike, $count likes" else "Like, $count likes",
        onClick = onClick,
        modifier = modifier,
        count = count,
        tint = if (liked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        enabled = enabled,
        selected = liked,
        popOnSelect = true,
    )
}

/** Repost toggle. Outline when off, brand teal when on. */
@Composable
fun AnimatedRepostButton(reposted: Boolean, count: Int, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    SocialActionButton(
        icon = VibeIcons.Repost,
        contentDescription = if (reposted) "Undo repost, $count reposts" else "Repost, $count reposts",
        onClick = onClick,
        modifier = modifier,
        count = count,
        tint = if (reposted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        enabled = enabled,
        selected = reposted,
        popOnSelect = true,
    )
}

/** 1,200 shows as 1.2K, 3,400,000 as 3.4M, so a count never pushes its neighbours around. */
fun compactCount(n: Int): String = when {
    n < 1_000 -> n.toString()
    n < 10_000 -> "${n / 1_000}.${(n % 1_000) / 100}K".replace(".0K", "K")
    n < 1_000_000 -> "${n / 1_000}K"
    else -> "${n / 1_000_000}.${(n % 1_000_000) / 100_000}M".replace(".0M", "M")
}

/** Bookmark toggle. Outline when off, solid brand-colored when saved. */
@Composable
fun AnimatedBookmarkButton(saved: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    SocialActionButton(
        icon = if (saved) VibeIcons.BookmarkFilled else VibeIcons.Bookmark,
        contentDescription = if (saved) "Remove from saved" else "Save post",
        onClick = onClick,
        modifier = modifier,
        tint = if (saved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        enabled = enabled,
        selected = saved,
        popOnSelect = true,
    )
}
