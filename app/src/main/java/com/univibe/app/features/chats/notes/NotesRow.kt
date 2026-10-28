package com.univibe.app.features.chats.notes

import com.univibe.app.ui.components.VerifiedName
import com.univibe.app.guide.guideTarget
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
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
import com.univibe.app.data.model.activeLabel
import com.univibe.app.data.model.noteLength
import com.univibe.app.ui.components.ActiveDot
import com.univibe.app.ui.components.InterestChip
import com.univibe.app.ui.components.StoryStatusRing
import com.univibe.app.ui.components.StudentAvatar
import com.univibe.app.ui.components.VibeBottomSheet
import com.univibe.app.ui.components.VibeButton
import com.univibe.app.ui.components.VibeButtonStyle
import com.univibe.app.ui.components.VibeIcons
import com.univibe.app.ui.components.VibeTextField
import com.univibe.app.ui.theme.Spacing

private val ItemWidth = 76.dp
private val AvatarSize = 56.dp
private val AvatarBox = 64.dp

/** Space kept above every avatar for a thought bubble and its tail, so avatars line up. Zero when nobody has a Note. */
private val BubbleSlot = 56.dp

/**
 * The compact, horizontally scrolling row between the search field and the conversation list.
 *
 * Every item has two separate tap targets: the avatar (which will open a Story when its teal ring is showing) and the
 * Note bubble above it (always opens the Note). The first item is the signed-in student's own real avatar with a small
 * plain "+" at its bottom-right corner.
 */
@Composable
fun NotesRow(
    state: NotesUiState,
    onAddNote: () -> Unit,
    onOpenMyNote: () -> Unit,
    onOpenAvatar: (authorId: String) -> Unit,
    onOpenNote: (authorId: String) -> Unit,
    modifier: Modifier = Modifier,
    /** False until the Story viewer exists: then no ring is drawn and only people with a Note are listed. */
    showStories: Boolean = false,
    onOpenStories: (authorId: String) -> Unit = {},
    /** People known to be active right now from the chat list (a Story-only person has no Note to say so). */
    onlineIds: Set<String> = emptySet(),
) {
    val me = state.me ?: return
    val people = state.people(showStories)
    val slot = if (people.any { it.note != null } || state.myActive != null) BubbleSlot else 0.dp
    LazyRow(
        state = rememberLazyListState(),
        modifier = modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = Spacing.md, vertical = Spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.Bottom,
    ) {
        item(key = "me", contentType = "me") {
            YourNoteItem(
                me = me, note = state.myActive, slot = slot, hasStory = showStories && state.myStory != null,
                onAvatar = onAddNote, onNote = onOpenMyNote,
            )
        }
        items(people, key = { it.authorId }, contentType = { "person" }) { person ->
            PersonItem(
                person = person, slot = slot, online = person.online || person.authorId in onlineIds,
                // A teal (or muted) ring means a Story is waiting: the avatar opens it. Otherwise the avatar opens the Note.
                onAvatar = { if (person.story != null) onOpenStories(person.authorId) else onOpenAvatar(person.authorId) },
                onNote = { onOpenNote(person.authorId) },
            )
        }
    }
}

@Composable
private fun YourNoteItem(me: NotesMe, note: Note?, slot: Dp, hasStory: Boolean, onAvatar: () -> Unit, onNote: () -> Unit) {
    Column(Modifier.width(ItemWidth), horizontalAlignment = Alignment.CenterHorizontally) {
        BubbleArea(
            text = note?.text, slot = slot, onClick = onNote,
            description = note?.let { "Your note: ${it.text}. Opens your note." },
        )
        Box(
            Modifier
                .size(AvatarBox)
                .guideTarget("chats.myNote")
                .clickable(role = Role.Button, onClickLabel = if (note == null) "Add a note" else "Your note options", onClick = onAvatar)
                .semantics {
                    contentDescription = (if (note == null) "Add a note" else "Your profile photo. Edit or delete your note") +
                        if (hasStory) ". You have an active story" else ""
                },
        ) {
            // My own ring is always the teal "active" one: I do not have to watch my own Story to keep it lit.
            if (hasStory) StoryStatusRing(unviewed = true, modifier = Modifier.matchParentSize())
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
private fun PersonItem(person: RowPerson, slot: Dp, online: Boolean, onAvatar: () -> Unit, onNote: () -> Unit) {
    val note = person.note
    val active = if (online) ", active now" else ""
    val storyText = when {
        person.story == null -> ""
        person.hasUnviewedStory -> ". New story. Opens the story"
        else -> ". Story seen. Opens the story"
    }
    Column(Modifier.width(ItemWidth), horizontalAlignment = Alignment.CenterHorizontally) {
        BubbleArea(
            text = note?.text, slot = slot, onClick = onNote,
            description = note?.let { "${person.name}'s note: ${it.text}. Opens the note." },
        )
        // The dot sits on this outer box. Only the inner tap area is clipped to a circle, so the dot is never cut off.
        Box(Modifier.size(AvatarBox)) {
            Box(
                Modifier
                    .matchParentSize()
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClickLabel = if (person.story != null) "Open ${person.name}'s story" else "Open ${person.name}", onClick = onAvatar)
                    .semantics { contentDescription = "${person.name}$active$storyText" },
                contentAlignment = Alignment.Center,
            ) {
                // Ring = a Story. The teal dot = active now (only when the person shares it). Two different things.
                if (person.story != null) StoryStatusRing(unviewed = person.hasUnviewedStory, modifier = Modifier.matchParentSize())
                StudentAvatar(person.name, size = AvatarSize, accent = person.authorId.hashCode(), imagePath = person.avatarUrl)
            }
            if (online) ActiveDot(Modifier.align(Alignment.BottomEnd).padding(end = 2.dp, bottom = 2.dp))
        }
        ItemLabel(person.name.trim().substringBefore(' '), person.verified)
    }
}

@Composable
private fun BubbleArea(text: String?, slot: Dp, onClick: () -> Unit, description: String?) {
    // heightIn, not height: with a large font the bubble grows instead of being clipped.
    Box(Modifier.fillMaxWidth().heightIn(min = slot).padding(bottom = 2.dp), contentAlignment = Alignment.BottomCenter) {
        if (text != null && description != null) ThoughtBubble(text, description, onClick)
    }
}

/**
 * A "thought" bubble: a rounded cloud of text with two small circles trailing down toward the avatar. The whole bubble
 * is its own button, separate from the avatar below it.
 */
@Composable
private fun ThoughtBubble(text: String, description: String, onClick: () -> Unit) {
    val fill = MaterialTheme.colorScheme.surfaceContainerHigh
    val shape = RoundedCornerShape(16.dp)
    Column(Modifier.widthIn(max = ItemWidth + 6.dp), horizontalAlignment = Alignment.Start) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, lineHeight = 13.sp),
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .clip(shape)
                .background(fill)
                .clickable(role = Role.Button, onClickLabel = "View note", onClick = onClick)
                .semantics { contentDescription = description }
                .padding(horizontal = 8.dp, vertical = 5.dp),
        )
        // The thought trail: a bigger circle, then a smaller one, stepping toward the avatar.
        Column(Modifier.padding(start = 20.dp).clearAndSetSemantics { }) {
            Box(Modifier.padding(top = 1.dp).size(7.dp).background(fill, CircleShape))
            Box(Modifier.padding(start = 3.dp, top = 1.dp).size(4.dp).background(fill, CircleShape))
        }
    }
}

@Composable
private fun ItemLabel(text: String, verified: Boolean = false) {
    VerifiedName(
        text, verified,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        badgeSize = 12.dp,
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
        Column(Modifier.imePadding(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StudentAvatar(note.authorName, size = 40.dp, accent = note.authorId.hashCode(), imagePath = note.avatarUrl)
                Column(Modifier.padding(start = Spacing.md)) {
                    VerifiedName(note.authorName, note.authorVerified, style = MaterialTheme.typography.titleMedium)
                    if (note.authorHandle.isNotBlank()) Text("@${note.authorHandle}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    activeLabel(note.online, note.lastActiveMs, state.nowMs)?.let {
                        Text(it, style = MaterialTheme.typography.labelMedium, color = if (note.online) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            NoteBody(note, state.nowMs)
            // Reply: goes to the author as a chat message that quotes this Note.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                VibeTextField(
                    value = state.replyText,
                    onValueChange = vm::setReply,
                    placeholder = "Reply to ${note.authorName.trim().substringBefore(' ')}",
                    singleLine = false, minLines = 1, maxLines = 3,
                    isError = state.replyError != null,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = vm::sendReply, enabled = state.canSendReply) {
                    Icon(
                        VibeIcons.Send, contentDescription = "Send reply",
                        tint = if (state.canSendReply) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            state.replyError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            Text(
                "Your reply goes to ${note.authorName.trim().substringBefore(' ')} as a message.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
