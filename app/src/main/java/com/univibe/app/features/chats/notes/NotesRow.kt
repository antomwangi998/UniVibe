package com.univibe.app.features.chats.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.univibe.app.data.model.Note
import com.univibe.app.data.model.NoteAudience
import com.univibe.app.data.model.NoteDraftCheck
import com.univibe.app.data.model.noteLength
import com.univibe.app.ui.components.InterestChip
import com.univibe.app.ui.components.StudentAvatar
import com.univibe.app.ui.components.VibeBottomSheet
import com.univibe.app.ui.components.VibeButton
import com.univibe.app.ui.components.VibeButtonStyle
import com.univibe.app.ui.components.VibeIcons
import com.univibe.app.ui.components.VibeTextField
import com.univibe.app.ui.theme.Spacing

private val ItemWidth = 76.dp
private val AvatarSize = 56.dp
private val AvatarBox = 60.dp

/** Space kept above every avatar for a Note bubble, so avatars line up. Zero when nobody has a Note (the row stays compact). */
private val BubbleSlot = 40.dp

/**
 * The compact, horizontally scrolling row between the search field and the conversation list.
 * The first item is always the signed-in student's own real avatar with a small plain "+" at its bottom-right corner.
 */
@Composable
fun NotesRow(
    state: NotesUiState,
    onAddNote: () -> Unit,
    onOpenNote: (authorId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val me = state.me ?: return
    val slot = if (state.hasAnyNote) BubbleSlot else 0.dp
    LazyRow(
        state = rememberLazyListState(),
        modifier = modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = Spacing.md, vertical = Spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.Bottom,
    ) {
        item(key = "me", contentType = "me") {
            YourNoteItem(me = me, note = state.myActive, slot = slot, onClick = onAddNote)
        }
        items(state.visibleOthers, key = { it.authorId }, contentType = { "note" }) { note ->
            PersonNoteItem(note = note, slot = slot, onClick = { onOpenNote(note.authorId) })
        }
    }
}

@Composable
private fun YourNoteItem(me: NotesMe, note: Note?, slot: Dp, onClick: () -> Unit) {
    val description = if (note == null) "Add a note" else "Your note: ${note.text}. Opens your note."
    Column(
        Modifier
            .width(ItemWidth)
            .clickable(role = Role.Button, onClickLabel = if (note == null) "Add a note" else "View your note", onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BubbleSlot(note?.text, slot)
        Box(Modifier.size(AvatarBox)) {
            // The person's real profile photo (initials only if they have not set one) is the item. The plus is a badge on it.
            StudentAvatar(me.name, size = AvatarSize, accent = me.id.hashCode(), imagePath = me.avatarUrl, modifier = Modifier.align(Alignment.Center))
            PlusBadge(Modifier.align(Alignment.BottomEnd))
        }
        ItemLabel("Your note")
    }
}

/** A small, plain teal "+" attached to the avatar's bottom-right edge. Deliberately not a button and not a large circle. */
@Composable
private fun PlusBadge(modifier: Modifier = Modifier) {
    Box(
        modifier.size(18.dp).background(Color.Black.copy(alpha = 0.55f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(VibeIcons.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
    }
}

@Composable
private fun PersonNoteItem(note: Note, slot: Dp, onClick: () -> Unit) {
    Column(
        Modifier
            .width(ItemWidth)
            .clickable(role = Role.Button, onClickLabel = "View note", onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "${note.authorName}'s note: ${note.text}" },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BubbleSlot(note.text, slot)
        Box(Modifier.size(AvatarBox), contentAlignment = Alignment.Center) {
            StudentAvatar(note.authorName, size = AvatarSize, accent = note.authorId.hashCode(), imagePath = note.avatarUrl)
        }
        ItemLabel(note.authorName.trim().substringBefore(' '))
    }
}

@Composable
private fun BubbleSlot(text: String?, slot: Dp) {
    // heightIn, not height: with a large font the bubble grows instead of being clipped.
    Box(Modifier.fillMaxWidth().heightIn(min = slot).padding(bottom = 4.dp), contentAlignment = Alignment.BottomCenter) {
        if (text != null) NoteBubble(text)
    }
}

@Composable
private fun NoteBubble(text: String) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.widthIn(max = ItemWidth)) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, lineHeight = 13.sp),
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun ItemLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 4.dp),
    )
}

// ---------------------------------------------------------------------------------------------------------------------
// Sheets
// ---------------------------------------------------------------------------------------------------------------------

/** Shows whichever Notes sheet the state says is open. Sheets close themselves if their Note has expired. */
@Composable
fun NotesSheets(state: NotesUiState, vm: NotesViewModel) {
    when (val sheet = state.sheet) {
        NotesSheet.None -> Unit
        NotesSheet.Compose -> ComposeNoteSheet(state, vm)
        NotesSheet.Mine -> {
            val note = state.myActive
            if (note == null) LaunchedEffect(Unit) { vm.closeSheet() } else MyNoteSheet(note, state, vm)
        }
        is NotesSheet.Theirs -> {
            val note = state.theirs
            if (note == null) LaunchedEffect(sheet) { vm.closeSheet() } else TheirNoteSheet(note, state, vm)
        }
    }
}

@Composable
private fun ComposeNoteSheet(state: NotesUiState, vm: NotesViewModel) {
    val me = state.me
    val check = state.draftCheck
    val length = noteLength(state.draft.trim())
    val editing = state.myActive != null
    VibeBottomSheet(onDismiss = vm::closeSheet) {
        Column(Modifier.imePadding(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            if (me != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StudentAvatar(me.name, size = 40.dp, accent = me.id.hashCode(), imagePath = me.avatarUrl)
                    Text(me.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = Spacing.md))
                }
            }
            val message = when {
                check is NoteDraftCheck.TooLong -> "A Note can be at most ${state.maxChars} characters."
                check is NoteDraftCheck.Empty && state.draft.isNotEmpty() -> "Write something first."
                else -> null
            }
            VibeTextField(
                value = state.draft,
                onValueChange = vm::setDraft,
                placeholder = "Share a quick thought",
                singleLine = false, minLines = 2, maxLines = 4,
                isError = message != null,
                supportingText = message,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "$length/${state.maxChars}",
                style = MaterialTheme.typography.labelMedium,
                color = if (check is NoteDraftCheck.TooLong) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.End),
            )
            Text("Who can see it", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                NoteAudience.entries.forEach { a -> InterestChip(a.label, selected = state.draftAudience == a, onClick = { vm.setAudience(a) }) }
            }
            Text(
                "Disappears after ${state.ttlHours} hours. Editing a Note starts the ${state.ttlHours} hours again.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.publishError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            VibeButton(
                text = if (editing) "Update note" else "Share note",
                onClick = vm::publish,
                enabled = state.canPublish,
                loading = state.publishing,
                modifier = Modifier.fillMaxWidth(),
            )
            VibeButton(text = "Cancel", onClick = vm::closeSheet, style = VibeButtonStyle.Text, enabled = !state.publishing, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun MyNoteSheet(note: Note, state: NotesUiState, vm: NotesViewModel) {
    VibeBottomSheet(onDismiss = vm::closeSheet) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text("Your note", style = MaterialTheme.typography.titleMedium)
            NoteBody(note, state.nowMs)
            VibeButton(text = "Edit note", onClick = vm::editMine, leadingIcon = VibeIcons.Edit, modifier = Modifier.fillMaxWidth())
            VibeButton(text = "Delete note", onClick = vm::deleteMine, style = VibeButtonStyle.Secondary, leadingIcon = VibeIcons.Delete, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun TheirNoteSheet(note: Note, state: NotesUiState, vm: NotesViewModel) {
    VibeBottomSheet(onDismiss = vm::closeSheet) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StudentAvatar(note.authorName, size = 40.dp, accent = note.authorId.hashCode(), imagePath = note.avatarUrl)
                Column(Modifier.padding(start = Spacing.md)) {
                    Text(note.authorName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (note.authorHandle.isNotBlank()) Text("@${note.authorHandle}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            NoteBody(note, state.nowMs)
        }
    }
}

@Composable
private fun NoteBody(note: Note, nowMs: Long) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Text(note.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(Spacing.md))
    }
    Text(
        "${ageLabel(nowMs - note.createdAtMs)} · ${remainingLabel(note.expiresAtMs - nowMs)} · ${note.audience.label}",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** "Just now", "12m ago", "5h ago". Judged on the server-corrected clock. */
internal fun ageLabel(ageMs: Long): String {
    val minutes = (ageMs / 60_000).coerceAtLeast(0)
    return when {
        minutes < 1 -> "Just now"
        minutes < 60 -> "${minutes}m ago"
        else -> "${minutes / 60}h ago"
    }
}

/** "Expired", "Less than an hour left", "5h left". */
internal fun remainingLabel(remainingMs: Long): String {
    if (remainingMs <= 0) return "Expired"
    val hours = remainingMs / 3_600_000
    return if (hours < 1) "Less than an hour left" else "${hours}h left"
}
