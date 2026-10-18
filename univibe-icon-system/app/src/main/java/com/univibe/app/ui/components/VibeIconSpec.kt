package com.univibe.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Size tokens for the icon system. Icons are drawn on a 24dp grid; these are display sizes. */
object VibeIconSize {
    val Compact = 18.dp      // secondary actions, inline status (receipts use 14dp)
    val Action = 22.dp       // post and chat actions
    val Navigation = 24.dp   // bottom bar, top bar
    val Prominent = 28.dp    // primary calls to action
    val TouchTarget = 48.dp  // minimum tappable area, whatever the visible icon size
}

/** A plain, non-interactive icon. Decorative unless [contentDescription] is given. */
@Composable
fun VibeIcon(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    size: Dp = VibeIconSize.Action,
) {
    Icon(icon, contentDescription, modifier.size(size), tint = tint)
}

/**
 * Small count pill for unread totals; shows a dot when [count] is 0 and [showDotWhenZero] is true.
 * Pure visuals: the caller owns the number. Caps at 99+. Announced by the parent's description,
 * so it clears its own semantics.
 */
@Composable
fun NotificationBadge(count: Int, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    if (count <= 0) return
    val label = if (count > 99) "99+" else count.toString()
    Box(
        modifier
            .defaultMinSize(minWidth = 18.dp, minHeight = 18.dp)
            .border(2.dp, colors.background, RoundedCornerShape(50))
            .background(colors.error, CircleShape)
            .padding(horizontal = 4.dp)
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = colors.onError, fontSize = 10.sp, lineHeight = 12.sp, textAlign = TextAlign.Center, maxLines = 1)
    }
}
