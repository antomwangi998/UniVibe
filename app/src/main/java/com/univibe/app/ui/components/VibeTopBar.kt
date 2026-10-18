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

@Composable
fun VibeTopBar(
    title: String,
    modifier: Modifier = Modifier,
    navigationIcon: (@Composable () -> Unit)? = null,
    titleContent: (@Composable () -> Unit)? = null,
    compactTitle: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {},
) {
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
    SocialActionButton(
        icon = icon,
        contentDescription = contentDescription,
        onClick = onClick,
        modifier = modifier,
        tint = MaterialTheme.colorScheme.onSurface,
        iconSize = VibeIconSize.Navigation,
    )
}
