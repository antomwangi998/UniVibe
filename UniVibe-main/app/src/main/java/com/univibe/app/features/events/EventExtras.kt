package com.univibe.app.features.events

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PersonOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import com.univibe.app.ui.components.PhotoImage
import com.univibe.app.ui.components.accentGradient
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.produceState
import androidx.compose.ui.semantics.Role
import com.univibe.app.app.AppContainer
import com.univibe.app.data.model.EventDetails
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.univibe.app.core.formatDateTime
import com.univibe.app.data.model.CampusEvent
import com.univibe.app.data.model.EventActivity
import com.univibe.app.data.model.EventUpdate
import com.univibe.app.data.model.ShareTarget
import com.univibe.app.features.chats.SheetRow
import com.univibe.app.ui.components.VibeBottomSheet
import com.univibe.app.ui.components.VibeIcons
import com.univibe.app.ui.components.VibeTextField
import com.univibe.app.ui.theme.Spacing

/** What a reporter can pick. These are the reasons the server accepts. */
private val REPORT_REASONS = listOf(
    "spam" to "Spam or scam", "harassment" to "Harassment", "hate" to "Hate", "sexual" to "Sexual content",
    "violence" to "Violence", "misinformation" to "False information", "other" to "Something else",
)

@Composable
internal fun ReportReasonSheet(target: ReportTarget, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    VibeBottomSheet(onDismiss = onDismiss) {
        Text(
            if (target == ReportTarget.Event) "Report this event" else "Report the organizer",
            style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
        )
        REPORT_REASONS.forEach { (code, label) -> SheetRow(Icons.Outlined.Flag, label, false) { onPick(code) } }
    }
}

/** Organizer-only: post a short update to everyone going or interested. */
@Composable
internal fun PostUpdateDialog(posting: Boolean, onPost: (String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Post an update") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text("Everyone going or interested is notified. Up to 5 updates a day.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                VibeTextField(text, { if (it.length <= 500) text = it }, label = "What's new?", singleLine = false, minLines = 3, supportingText = "${text.trim().length}/500")
            }
        },
        confirmButton = { TextButton(onClick = { onPost(text) }, enabled = text.isNotBlank() && !posting) { Text(if (posting) "Sending…" else "Send") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun UpdatesSection(updates: List<EventUpdate>) {
    if (updates.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text("Updates from the organizer", style = MaterialTheme.typography.titleMedium)
        updates.take(3).forEach { u ->
            Column(Modifier.fillMaxWidth()) {
                Text(u.body, style = MaterialTheme.typography.bodyMedium)
                Text(formatDateTime(u.createdAtMillis), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun activityLabel(a: EventActivity): String = when (a.action) {
    "create" -> "Event created"
    "publish" -> "Event published"
    "update" -> if (a.fields.isEmpty()) "Event edited" else "Edited: " + a.fields.joinToString(", ") { it.replace(Regex("([A-Z])"), " $1").lowercase() }
    "cancel" -> "Event cancelled"
    "complete" -> "Marked completed"
    "announce_update" -> "Posted an update"
    "hide" -> "Hidden by moderators"
    "restore" -> "Restored by moderators"
    else -> "Change"
}

@Composable
internal fun ActivitySheet(items: List<EventActivity>, onDismiss: () -> Unit) {
    VibeBottomSheet(onDismiss = onDismiss) {
        Text("Activity", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm))
        if (items.isEmpty()) Text("Nothing yet.", modifier = Modifier.padding(Spacing.md), color = MaterialTheme.colorScheme.onSurfaceVariant)
        items.forEach { a ->
            Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = 6.dp)) {
                Text(activityLabel(a), style = MaterialTheme.typography.bodyMedium)
                Text(listOfNotNull(a.actorName, formatDateTime(a.atMillis)).joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun ShareSheet(state: ShareState, onSystemShare: () -> Unit, onPage: (SharePage) -> Unit, onSend: (ShareTarget, String) -> Unit, onDismiss: () -> Unit) {
    VibeBottomSheet(onDismiss = onDismiss) {
        when (state.page) {
            SharePage.Main -> {
                SheetRow(VibeIcons.Share, "Share link…", false, onSystemShare)
                SheetRow(Icons.Outlined.ChatBubbleOutline, "Send in a chat", false) { onPage(SharePage.Chats) }
                SheetRow(Icons.Outlined.Groups, "Post in a community", false) { onPage(SharePage.Communities) }
            }
            SharePage.Chats -> {
                Text("Send to", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm))
                if (state.chats.isEmpty()) Text("No chats yet.", modifier = Modifier.padding(Spacing.md), color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.chats.take(20).forEach { c -> SheetRow(Icons.Outlined.ChatBubbleOutline, c.title, false) { if (!state.sending) onSend(ShareTarget.Chat, c.id) } }
            }
            SharePage.Communities -> {
                Text("Post in", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm))
                if (state.communities.isEmpty()) Text("You can't post in any community right now.", modifier = Modifier.padding(Spacing.md), color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.communities.forEach { c -> SheetRow(Icons.Outlined.Groups, c.name, false) { if (!state.sending) onSend(ShareTarget.Community, c.id) } }
            }
        }
    }
}

/** The text the system share sheet sends. Uses the web link when the server has one, otherwise just the details. */
internal fun systemShare(context: Context, event: CampusEvent, webUrl: String?) {
    val text = listOfNotNull(event.title, event.timeLabel, event.location.ifBlank { null }, webUrl).joinToString("\n")
    val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }
    context.startActivity(Intent.createChooser(send, "Share event"))
}

// ---- Calendar: which version of an event the person added. Android can't edit a calendar entry for us, so we say so honestly.
private const val CAL_PREFS = "event_calendar"
internal fun calendarRevisionAdded(context: Context, eventId: String): Int? =
    context.getSharedPreferences(CAL_PREFS, Context.MODE_PRIVATE).takeIf { it.contains(eventId) }?.getInt(eventId, 0)
internal fun rememberCalendarRevision(context: Context, eventId: String, revision: Int) =
    context.getSharedPreferences(CAL_PREFS, Context.MODE_PRIVATE).edit().putInt(eventId, revision).apply()

@Composable
internal fun CalendarChangedNote(onAddAgain: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("This event changed after you added it to your calendar.", style = MaterialTheme.typography.bodyMedium)
        Text("We can't edit your calendar for you. Add the new version, then delete the old entry.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = onAddAgain) { Text("Add updated event") }
    }
}

internal val ReportIcon = Icons.Outlined.Flag
internal val UpdateIcon = Icons.Outlined.Campaign
internal val ActivityIcon = Icons.Outlined.History
internal val OrganizerReportIcon = Icons.Outlined.PersonOff

/** Opens an event from deep inside a screen (a chat bubble) without threading a callback through every layer. */
val LocalOpenEvent = compositionLocalOf<(String) -> Unit> { {} }

private sealed interface SharedEventState {
    data object Loading : SharedEventState
    data object Unavailable : SharedEventState
    data class Ready(val details: EventDetails) : SharedEventState
}

/**
 * The card for an event someone shared in a chat. It asks for the event as THIS reader: if they may not see it (private community,
 * hidden by moderators, deleted), they get "not available" and nothing about it.
 */
@Composable
fun SharedEventCard(eventId: String) {
    val open = LocalOpenEvent.current
    val state by produceState<SharedEventState>(SharedEventState.Loading, eventId) {
        value = try { AppContainer.eventRepository.getEvent(eventId)?.let { SharedEventState.Ready(it) } ?: SharedEventState.Unavailable }
        catch (e: Exception) { SharedEventState.Loading }
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        when (val st = state) {
            SharedEventState.Loading -> Text("Event…", style = MaterialTheme.typography.bodyMedium)
            SharedEventState.Unavailable -> Text("This event isn't available.", style = MaterialTheme.typography.bodyMedium)
            is SharedEventState.Ready -> Column(Modifier.clickable(role = Role.Button, onClickLabel = "Open event") { open(eventId) }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val e = st.details.event
                CoverStrip(e)
                Text(e.title, style = MaterialTheme.typography.titleSmall)
                Text(listOf(e.timeLabel, e.location).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                if (e.cancelled) Text("Cancelled", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/**
 * The event's cover. Optional: a cover helps people recognise the event in lists, chats and shared cards. Without one every
 * card shows the event's colour instead, so nothing looks broken.
 */
@Composable
internal fun CoverPicker(shown: String?, accent: Int, onPick: () -> Unit, onRemove: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text("Cover photo (optional)", style = MaterialTheme.typography.titleSmall)
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(16.dp)).background(Brush.linearGradient(accentGradient(accent)))
                .clickable(role = Role.Button, onClickLabel = "Choose a cover photo", onClick = onPick),
            contentAlignment = Alignment.Center,
        ) {
            if (shown != null) PhotoImage(shown, contentDescription = "Event cover")
            else Text("Tap to add a photo", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimary)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            TextButton(onClick = onPick) { Text(if (shown == null) "Add photo" else "Change photo") }
            if (shown != null) TextButton(onClick = onRemove) { Text("Remove") }
        }
    }
}

/** A small cover, or the event's colour when it has none, for cards that show an event inside something else. */
@Composable
fun CoverStrip(event: CampusEvent, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().aspectRatio(16f / 7f).clip(RoundedCornerShape(12.dp)).background(Brush.linearGradient(accentGradient(event.accent)))) {
        event.coverUrl?.let { PhotoImage(it, contentDescription = null) }
    }
}
