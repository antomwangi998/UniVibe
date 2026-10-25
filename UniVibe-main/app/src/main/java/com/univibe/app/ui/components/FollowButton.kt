package com.univibe.app.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier

/**
 * Follow / Following toggle. Cross-fades between the filled "Follow" and the outlined
 * "Following" (with a check) in 180 ms; width changes are animated rather than snapped.
 * Visual only: [following] and [onToggle] come from the caller's state.
 */
@Composable
fun FollowButton(
    following: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val toggle by rememberUpdatedState(onToggle)
    Crossfade(
        targetState = following,
        animationSpec = tween(durationMillis = 180),
        modifier = modifier.animateContentSize(tween(180)),
        label = "followToggle",
    ) { isFollowing ->
        VibeButton(
            text = if (isFollowing) "Following" else "Follow",
            onClick = { toggle() },
            modifier = Modifier,
            style = if (isFollowing) VibeButtonStyle.Secondary else VibeButtonStyle.Primary,
            compact = compact,
            leadingIcon = if (isFollowing) VibeIcons.Check else VibeIcons.PersonAdd,
        )
    }
}
