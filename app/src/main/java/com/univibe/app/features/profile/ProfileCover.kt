package com.univibe.app.features.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.univibe.app.ui.components.PhotoImage
import com.univibe.app.ui.theme.Spacing

private val CoverBodyHeight = 150.dp
private val CoverCorner = 36.dp
private val AvatarRing = 116.dp

/**
 * Full-bleed profile cover: runs under the status bar, big rounded bottom corners, a soft teal sheen,
 * icon buttons on top and the avatar overlapping the bottom edge.
 *
 * The avatar hangs [AvatarRing]/2 below the cover without taking layout space, so put a
 * Spacer(64.dp) under this composable (the existing Header already does).
 * [leading] sits top-left, [actions] top-right. Use [CoverIconButton] inside them.
 */
@Composable
fun ProfileCover(
    accent: Int,
    coverPath: String?,
    modifier: Modifier = Modifier,
    leading: @Composable RowScope.() -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    avatar: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val base = colors.primary
    val gradient = Brush.linearGradient(
        listOf(lerp(base, Color.Black, 0.22f), base, lerp(base, Color.White, 0.30f)),
    )

    Box(modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(CoverBodyHeight + statusBar)
                .clip(RoundedCornerShape(bottomStart = CoverCorner, bottomEnd = CoverCorner))
                .background(gradient)
                .drawBehind {
                    // Soft highlight in the top-right, like the glossy reference.
                    drawRect(
                        Brush.radialGradient(
                            listOf(Color.White.copy(alpha = 0.30f), Color.Transparent),
                            center = Offset(size.width * 0.85f, size.height * 0.05f),
                            radius = size.width * 0.75f,
                        ),
                    )
                },
        ) {
            if (coverPath != null) {
                PhotoImage(coverPath, contentDescription = "Cover photo", modifier = Modifier.fillMaxSize())
                // Keeps the white icons legible on any photo.
                Box(
                    Modifier.fillMaxWidth().height(96.dp + statusBar).background(
                        Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.35f), Color.Transparent)),
                    ),
                )
            }
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = Spacing.md, vertical = Spacing.sm),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, content = leading)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm), content = actions)
            }
        }
        Box(Modifier.align(Alignment.BottomCenter).offset(y = AvatarRing / 2)) { avatar() }
    }
}

/** Translucent dark circle with a white icon: readable on teal and on photos, 48dp touch target. */
@Composable
fun CoverIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(shape = CircleShape, color = Color.Black.copy(alpha = 0.28f), modifier = modifier) {
        IconButton(onClick = onClick) {
            Icon(icon, contentDescription = contentDescription, tint = Color.White, modifier = Modifier.size(22.dp))
        }
    }
}

/** Bell for the cover's leading slot, with a dot when something is unread. */
@Composable
fun CoverBellButton(unread: Int, onClick: () -> Unit) {
    Box {
        CoverIconButton(
            Icons.Outlined.Notifications,
            if (unread > 0) "Notifications, $unread unread" else "Notifications",
            onClick,
        )
        if (unread > 0) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 8.dp).size(10.dp)
                    .background(MaterialTheme.colorScheme.error, CircleShape),
            )
        }
    }
}

/** Lets a child ignore the parent's horizontal content padding (used to run the cover edge to edge). */
fun Modifier.fullBleed(horizontal: Dp): Modifier = layout { measurable, constraints ->
    val pad = horizontal.roundToPx()
    val width = constraints.maxWidth + pad * 2
    val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-pad, 0) }
}
