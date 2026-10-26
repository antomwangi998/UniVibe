package com.univibe.app.features.chats

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.univibe.app.data.model.FileAttachment
import com.univibe.app.ui.components.VibeIcons

/** "1.2 MB", "340 KB", "812 B". */
fun formatBytes(bytes: Long): String = when {
    bytes >= 1_048_576 -> String.format(java.util.Locale.getDefault(), "%.1f MB", bytes / 1_048_576.0)
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}

/**
 * A shared file in a bubble: its kind (PDF, DOCX, ...), name and size. Tap opens it; hold opens the message menu like
 * anywhere else on the bubble. While it uploads it shows progress, and while it downloads to be opened, a spinner.
 * Text uses the theme styles and nothing has a fixed height, so it follows the person's font and text size.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileCard(
    file: FileAttachment,
    sending: Boolean,
    progress: Float?,
    busy: Boolean,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tint = LocalContentColor.current
    val kind = file.name.substringAfterLast('.', "").uppercase().take(4)
    val details = listOfNotNull(kind.ifBlank { null }, formatBytes(file.sizeBytes).takeIf { file.sizeBytes > 0 }).joinToString(" · ")
    Row(
        modifier.widthIn(min = 220.dp).clip(RoundedCornerShape(12.dp)).background(tint.copy(alpha = 0.10f))
            .combinedClickable(enabled = !sending, role = Role.Button, onClickLabel = "Open file", onClick = onOpen, onLongClick = onLongPress)
            .semantics(mergeDescendants = true) { contentDescription = "File ${file.name}, $details" + if (sending) ", sending" else "" }
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.sizeIn(minWidth = 44.dp, minHeight = 44.dp).clip(RoundedCornerShape(10.dp)).background(tint.copy(alpha = 0.18f)).padding(horizontal = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (kind.isBlank()) Icon(VibeIcons.File, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
            else Text(kind, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
        }
        Column(Modifier.weight(1f)) {
            Text(file.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (details.isNotBlank()) Text(details, style = MaterialTheme.typography.labelSmall, modifier = Modifier.alpha(0.8f))
        }
        if (sending && progress != null) CircularProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.size(24.dp), strokeWidth = 2.5.dp, color = tint, trackColor = tint.copy(alpha = 0.25f))
        else if (sending || busy) CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.5.dp, color = tint)
    }
}
