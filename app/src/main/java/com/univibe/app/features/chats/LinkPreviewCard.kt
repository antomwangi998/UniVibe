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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.univibe.app.data.model.LinkPreview

/**
 * The card under a message that contains a link: the page's picture when it has one, then site, title and a short description. Tapping opens the page; holding
 * still opens the message menu like anywhere else on the bubble. Text uses the theme styles, so it follows the person's
 * font and text size, and nothing has a fixed height. Colors come from the bubble, so it suits both sent and received.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LinkPreviewCard(preview: LinkPreview, onLongPress: () -> Unit, modifier: Modifier = Modifier) {
    val uriHandler = LocalUriHandler.current
    val tint = LocalContentColor.current
    val site = if (preview.siteName != null && !preview.siteName.equals(preview.host, ignoreCase = true)) "${preview.siteName} · ${preview.host}" else preview.host
    // A picture that fails to load (an expired link, no network) is simply left out, never an empty box.
    var imageFailed by remember(preview.imageUrl) { mutableStateOf(false) }
    val showImage = preview.imageUrl != null && !imageFailed
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(tint.copy(alpha = 0.10f))
            .combinedClickable(
                role = Role.Button, onClickLabel = "Open link",
                onClick = { runCatching { uriHandler.openUri(preview.url) } }, // no browser installed: ignored, never a crash
                onLongClick = onLongPress,
            )
            .semantics(mergeDescendants = true) {
                contentDescription = "Link preview from $site. ${preview.title.orEmpty()} ${preview.description.orEmpty()}".trim()
            },
    ) {
        if (showImage) {
            // Decorative: the title below says what the page is. Wide pictures keep their shape; tall ones are cropped to fit.
            AsyncImage(
                model = preview.imageUrl, contentDescription = null, contentScale = ContentScale.Crop,
                onError = { imageFailed = true },
                modifier = Modifier.fillMaxWidth().aspectRatio((preview.imageRatio ?: 1.91f).coerceIn(1.2f, 2f)).background(tint.copy(alpha = 0.08f)),
            )
        }
        Row(Modifier.height(IntrinsicSize.Min)) {
            if (!showImage) Box(Modifier.width(3.dp).fillMaxHeight().background(tint.copy(alpha = 0.6f)))
            Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (site.isNotBlank()) Text(site, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.alpha(0.8f))
                preview.title?.let { Text(it, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                preview.description?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.alpha(0.9f)) }
            }
        }
    }
}
