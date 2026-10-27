package com.univibe.app.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColor
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.univibe.app.guide.guideTarget
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.univibe.app.ui.theme.Spacing

data class VibeBarItem(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
)

/**
 * Floating rounded navigation card. It pads itself with the navigation-bar inset so it
 * always sits above the system gesture area; the host Scaffold uses its measured height
 * as bottom content padding so it never covers content.
 */
@Composable
fun VibeBottomBar(
    items: List<VibeBarItem>,
    selectedRoute: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    backdrop: GlassBackdropState? = null,
    /** Unread count per route (only routes with a real, positive count show a badge). Null or absent means no badge. */
    badges: Map<String, Int?> = emptyMap(),
) {
    Box(
        modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
    ) {
        val barShape = RoundedCornerShape(GlassTokens.ComposerCorner)
        val barStyle = rememberGlassStyle(GlassTone.Chrome)
        // Glass: translucent gradient, thin border, no drop shadow. The bar sits still, so it never re-draws while scrolling.
        Surface(
            modifier = Modifier.fillMaxWidth().glassBackdropTarget(backdrop).glass(barShape, barStyle),
            shape = barShape,
            color = Color.Transparent,
        ) {
            Row(
                Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.sm).selectableGroup(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                items.forEach { item ->
                    BarItem(item, item.route == selectedRoute, badges[item.route] ?: 0, { onSelect(item.route) }, Modifier.weight(1f).guideTarget(com.univibe.app.guide.tabTargetId(item.route)))
                }
            }
        }
    }
}

@Composable
private fun BarItem(item: VibeBarItem, selected: Boolean, badge: Int, onClick: () -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val transition = updateTransition(selected, label = "barItem")
    val indicator by transition.animateFloat(
        label = "indicator",
        transitionSpec = { spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow) },
    ) { if (it) 1f else 0f }
    val tint by transition.animateColor(label = "tint", transitionSpec = { tween(180) }) {
        if (it) colors.primary else colors.onSurface.copy(alpha = 0.68f)
    }
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = tween(durationMillis = 120),
        label = "pressScale",
    )
    Column(
        modifier = modifier
            .selectable(
                selected = selected,
                interactionSource = interaction,
                indication = null,
                role = Role.Tab,
                onClick = onClick,
            )
            .padding(vertical = 4.dp)
            .graphicsLayer { scaleX = pressScale; scaleY = pressScale }
            // With a badge the whole item reads "Chats, 3 unread messages"; without one the label alone says "Chats".
            .then(if (badge > 0) Modifier.semantics { contentDescription = unreadDescription(item.label, badge) } else Modifier),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.width(56.dp).height(32.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = indicator; scaleX = 0.5f + 0.5f * indicator }
                    .background(colors.primaryContainer, CircleShape),
            )
            Icon(
                imageVector = if (selected) item.selectedIcon else item.icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(VibeIconSize.Navigation).graphicsLayer {
                    val emphasis = 1f + 0.06f * indicator
                    scaleX = emphasis; scaleY = emphasis
                },
            )
            // Positioned by alignment + offset only, so it never moves the icon, the label or the other items.
            UnreadBadge(badge, Modifier.align(Alignment.TopEnd).offset(x = (-2).dp, y = (-2).dp))
        }
        Text(
            item.label, style = MaterialTheme.typography.labelSmall, color = tint, maxLines = 1, overflow = TextOverflow.Clip,
            modifier = if (badge > 0) Modifier.clearAndSetSemantics { } else Modifier,
        )
    }
}

/** "Chats, 3 unread messages" / "Chats, 1 unread message"; 100+ is announced as "more than 99". */
internal fun unreadDescription(label: String, count: Int): String = when {
    count <= 0 -> label
    count == 1 -> "$label, 1 unread message"
    count > 99 -> "$label, more than 99 unread messages"
    else -> "$label, $count unread messages"
}

/** Display text for a count: nothing at 0 or below, the number up to 99, then "99+". */
internal fun unreadBadgeText(count: Int): String? = when {
    count <= 0 -> null
    count > 99 -> "99+"
    else -> count.toString()
}

/**
 * Small turquoise count bubble for the Chats icon. Appears and disappears with a gentle scale/fade, and the number
 * cross-fades when it changes. Semantics are cleared: the parent item carries the spoken description.
 */
@Composable
private fun UnreadBadge(count: Int, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    AnimatedVisibility(
        visible = count > 0,
        modifier = modifier,
        enter = scaleIn(tween(160), initialScale = 0.6f) + fadeIn(tween(160)),
        exit = scaleOut(tween(120), targetScale = 0.6f) + fadeOut(tween(120)),
    ) {
        Box(
            Modifier
                .defaultMinSize(minWidth = 18.dp, minHeight = 18.dp)
                .border(1.5.dp, colors.background, CircleShape)
                .background(colors.primary, CircleShape)
                .padding(horizontal = 4.dp)
                .clearAndSetSemantics { },
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = unreadBadgeText(count) ?: "",
                transitionSpec = { fadeIn(tween(120)) togetherWith fadeOut(tween(120)) },
                label = "unreadCount",
            ) { text ->
                Text(text, color = colors.onPrimary, fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 1)
            }
        }
    }
}
