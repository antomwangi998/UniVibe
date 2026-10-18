package com.univibe.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Top-bar bell with a small dot when [unread] > 0. The count is announced to screen readers. */
@Composable
fun NotificationBell(unread: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Box(modifier) {
        VibeIconButton(
            Icons.Outlined.Notifications,
            if (unread > 0) "Notifications, $unread unread" else "Notifications",
            onClick,
        )
        if (unread > 0) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 6.dp).size(12.dp)
                    .background(colors.error, CircleShape)
                    .border(2.dp, colors.background, CircleShape),
            )
        }
    }
}
