package com.univibe.app.ui.components

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import com.univibe.app.data.model.CampusEvent
import com.univibe.app.data.model.Post
import com.univibe.app.ui.theme.Spacing
import androidx.compose.ui.unit.dp

/**
 * A Home Feed announcement of an event. It only references the event: everything on the card, including cancelled, ended and
 * the person's own RSVP, comes from the one event record, drawn by the same [EventCard] as the Events tab.
 * [onSave] is null when the server can't store bookmarks yet, so no dead button is shown.
 */
@Composable
fun EventFeedCard(
    post: Post,
    event: CampusEvent,
    onOpen: () -> Unit,
    onRsvp: () -> Unit,
    onShare: () -> Unit,
    onMore: () -> Unit,
    onAuthorClick: () -> Unit,
    modifier: Modifier = Modifier,
    onSave: (() -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth().padding(vertical = Spacing.sm)) {
        Column(Modifier.padding(start = Spacing.sm + 4.dp, end = Spacing.xs)) {
            post.community?.let { PostCommunityHeader(it, post.announcement) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                        .clickable(role = Role.Button, onClickLabel = "Open profile", onClick = onAuthorClick),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StudentAvatar(post.author.name, size = 40.dp, accent = post.author.accent, imagePath = post.author.avatarUrl)
                    Spacer(Modifier.width(Spacing.sm + 2.dp))
                    Column(Modifier.weight(1f)) {
                        Text(post.author.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "Hosting an event · ${post.timeAgo}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                IconButton(onClick = onMore) { Icon(Icons.Filled.MoreVert, contentDescription = "More options") }
            }
        }
        EventCard(
            event = event, onRsvpClick = onRsvp, onClick = onOpen,
            modifier = Modifier.padding(horizontal = Spacing.sm + 4.dp, vertical = Spacing.sm),
        )
        Row(Modifier.padding(horizontal = Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
            if (onSave != null) {
                IconButton(onClick = onSave) {
                    Icon(
                        if (event.saved) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                        contentDescription = if (event.saved) "Remove from saved events" else "Save event",
                        tint = if (event.saved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = onShare) { Icon(Icons.Filled.Share, contentDescription = "Share event", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/** Shares the event as plain text through the system share sheet. The text carries no private details beyond what the event page shows. */
fun shareEvent(context: Context, event: CampusEvent) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, "${event.title}\n${event.timeLabel}\n${event.location}")
    }
    context.startActivity(Intent.createChooser(send, "Share event").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
