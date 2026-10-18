package com.univibe.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Top-bar bell with an unread badge. The count is announced to screen readers by the button. */
@Composable
fun NotificationBell(unread: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier) {
        VibeIconButton(
            if (unread > 0) VibeIcons.BellFilled else VibeIcons.Bell,
            if (unread > 0) "Notifications, $unread unread" else "Notifications",
            onClick,
        )
        AnimatedVisibility(
            visible = unread > 0,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 2.dp, end = 0.dp),
            enter = scaleIn(initialScale = 0.6f) + fadeIn(),
            exit = scaleOut(targetScale = 0.6f) + fadeOut(),
        ) {
            NotificationBadge(unread)
        }
    }
}
