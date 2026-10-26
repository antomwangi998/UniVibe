package com.univibe.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.univibe.app.ui.theme.Spacing

/**
 * Screen top bar. By default it is the glass header (one rounded bar; icon buttons inside it become glass buttons on
 * their own). Pass [glass] = false for the old flat bar.
 */
@Composable
fun VibeTopBar(
    title: String,
    modifier: Modifier = Modifier,
    navigationIcon: (@Composable () -> Unit)? = null,
    titleContent: (@Composable () -> Unit)? = null,
    compactTitle: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {},
    glass: Boolean = true,
) {
    if (glass) {
        GlassHeaderBar(modifier) {
            navigationIcon?.invoke()
            if (titleContent != null) {
                Row(Modifier.weight(1f).padding(horizontal = Spacing.xs), verticalAlignment = Alignment.CenterVertically) { titleContent() }
            } else {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(horizontal = Spacing.sm).semantics { heading() },
                )
            }
            actions()
        }
        return
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = Spacing.md, vertical = Spacing.sm)
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (navigationIcon != null) {
            navigationIcon()
            Spacer(Modifier.width(Spacing.sm))
        }
        if (titleContent != null) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) { titleContent() }
        } else {
            Text(
                text = title,
                style = if (compactTitle) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
        }
        actions()
    }
}

/** Top-bar icon button: the icon itself is the button (no circle or box), with a 48dp touch target. */
@Composable
fun VibeIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (LocalInGlassBar.current) {
        GlassIconButton(icon, contentDescription, onClick, modifier)
        return
    }
    SocialActionButton(
        icon = icon,
        contentDescription = contentDescription,
        onClick = onClick,
        modifier = modifier,
        tint = MaterialTheme.colorScheme.onSurface,
        iconSize = VibeIconSize.Navigation,
    )
}
