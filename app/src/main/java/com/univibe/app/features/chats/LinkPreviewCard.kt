package com.univibe.app.features.chats

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.univibe.app.data.model.LinkPreview

/**
 * The card under a message that contains a link: site, title and a short description. Tapping opens the page; holding
 * still opens the message menu like anywhere else on the bubble. Text uses the theme styles, so it follows the person's
 * font and text size, and nothing has a fixed height. Colors come from the bubble, so it suits both sent and received.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LinkPreviewCard(preview: LinkPreview, onLongPress: () -> Unit, modifier: Modifier = Modifier) {
    val uriHandler = LocalUriHandler.current
    val tint = LocalContentColor.current
    val site = if (preview.siteName != null && !preview.siteName.equals(preview.host, ignoreCase = true)) "${preview.siteName} · ${preview.host}" else preview.host
    Row(
        modifier.fillMaxWidth().height(IntrinsicSize.Min).clip(RoundedCornerShape(12.dp)).background(tint.copy(alpha = 0.10f))
            .combinedClickable(
                role = Role.Button, onClickLabel = "Open link",
                onClick = { runCatching { uriHandler.openUri(preview.url) } }, // no browser installed: ignored, never a crash
                onLongClick = onLongPress,
            )
            .semantics(mergeDescendants = true) {
                contentDescription = "Link preview from $site. ${preview.title.orEmpty()} ${preview.description.orEmpty()}".trim()
            },
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(tint.copy(alpha = 0.6f)))
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (site.isNotBlank()) Text(site, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.alpha(0.8f))
            preview.title?.let { Text(it, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            preview.description?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.alpha(0.9f)) }
        }
    }
}
