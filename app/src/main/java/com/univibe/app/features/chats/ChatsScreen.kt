@file:OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)

package com.univibe.app.features.chats

import com.univibe.app.ui.components.VerifiedName
import com.univibe.app.ui.components.VibePullToRefresh
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.MarkChatRead
import androidx.compose.material.icons.outlined.MarkChatUnread
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.univibe.app.app.AppContainer
import com.univibe.app.data.model.Chat
import com.univibe.app.data.model.Delivery
import com.univibe.app.data.model.activeLabel
import com.univibe.app.data.model.LastKind
import com.univibe.app.data.model.StoryTrayEntry
import com.univibe.app.ui.components.ActiveDot
import com.univibe.app.ui.components.EmptyState
import com.univibe.app.ui.components.StoryStatusRing
import com.univibe.app.ui.components.ErrorState
import com.univibe.app.ui.components.InterestChip
import com.univibe.app.ui.components.LoadingSkeleton
import com.univibe.app.ui.components.StudentAvatar
import com.univibe.app.ui.components.VibeBottomSheet
import com.univibe.app.ui.components.VibeIconButton
import com.univibe.app.ui.components.VibeIcons
import com.univibe.app.ui.components.VibeTextField
import com.univibe.app.ui.components.VibeTopBar
import com.univibe.app.ui.components.rememberBottomClearance
import com.univibe.app.ui.theme.Spacing
import com.univibe.app.navigation.rememberTabListState
import com.univibe.app.features.chats.notes.NotesRow
import com.univibe.app.features.chats.notes.NotesSheets
import com.univibe.app.features.chats.notes.NotesViewModel

private val AvatarSize = 50.dp
/** Room around the avatar for the Story ring and the active dot. Every row has it, so avatars line up with or without a ring. */
private val AvatarBox = 58.dp

@Composable
fun ChatsScreen(
    onOpenChat: (String) -> Unit = {},
    onNewGroup: () -> Unit = {},
    /** Opens the Story composer (camera button next to "New group"). */
    onAddStory: () -> Unit = {},
    /** Opens the Story viewer for a person. While this is null Stories are not shown at all (no rings, no Story-only people). */
    onOpenStory: ((authorId: String) -> Unit)? = null,
    /** Opens the screen where I review and manage my own active Stories. */
    onManageStories: () -> Unit = {},
    viewModel: ChatsViewModel = viewModel(),
    notesViewModel: NotesViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                NotesViewModel(
                    repository = AppContainer.noteRepository,
                    session = AppContainer.authRepository.session,
                    storyRepository = AppContainer.storyRepository,
                )
            }
        },
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val notes by notesViewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var myMenuOpen by remember { mutableStateOf(false) }
    // Who has an active Story (not me), and who is active right now. Both come from data the screen already loads.
    val storyByUser = remember(notes.stories, notes.me, onOpenStory) {
        if (onOpenStory == null) emptyMap() else notes.stories.filter { !it.isMine && it.authorId != notes.me?.id }.associateBy { it.authorId }
    }
    val onlineIds = remember(state.all) { state.all.filter { it.online && !it.isGroup && it.studentId != null }.mapNotNull { it.studentId }.toSet() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh(); notesViewModel.refresh() }
    LaunchedEffect(state.notice) { state.notice?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show(); viewModel.consumeNotice() } }
    LaunchedEffect(notes.notice) { notes.notice?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show(); notesViewModel.consumeNotice() } }

    Column(Modifier.fillMaxSize()) {
        VibeTopBar("Chats", actions = {
            VibeIconButton(VibeIcons.Camera, "Add to your story", onAddStory)
            VibeIconButton(VibeIcons.PersonAdd, "New group", onNewGroup)
        })
        VibeTextField(
            value = state.query,
            onValueChange = viewModel::setQuery,
            placeholder = "Search messages",
            leadingIcon = VibeIcons.Search,
            trailingIcon = {
                IconButton(onClick = viewModel::toggleFilters) {
                    Icon(
                        VibeIcons.Filter,
                        contentDescription = "Filter chats",
                        tint = if (state.filter != ChatFilter.All) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            modifier = Modifier.padding(horizontal = Spacing.md),
        )
        // Notes sit between the search field and the filters. The row is compact and never replaces the conversation list.
        NotesRow(
            state = notes,
            // With a Story running, my avatar offers a short choice; without one it goes straight to the Note, as before.
            onAddNote = { if (onOpenStory != null && notes.myStory != null) myMenuOpen = true else notesViewModel.onAddNoteClick() },
            onOpenMyNote = notesViewModel::onMyNoteClick,
            onOpenAvatar = notesViewModel::onAvatarClick,
            showStories = onOpenStory != null,
            onlineIds = onlineIds,
            onOpenStories = { id -> onOpenStory?.invoke(id) },
            onOpenNote = notesViewModel::onNoteClick,
        )
        if (myMenuOpen) AlertDialog(
            onDismissRequest = { myMenuOpen = false },
            title = { Text("Your story") },
            text = {
                androidx.compose.foundation.layout.Column {
                    TextButton(onClick = { myMenuOpen = false; notes.myStory?.let { onOpenStory?.invoke(it.authorId) } }) { Text("View your story") }
                    TextButton(onClick = { myMenuOpen = false; onManageStories() }) { Text("Manage your stories") }
                    TextButton(onClick = { myMenuOpen = false; notesViewModel.onAddNoteClick() }) { Text("Add or edit your note") }
                }
            },
            confirmButton = { TextButton(onClick = { myMenuOpen = false }) { Text("Cancel") } },
        )
        AnimatedVisibility(visible = state.showFilters) {
            Row(
                Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                ChatFilter.entries.forEach { f ->
                    InterestChip(f.label, selected = state.filter == f, onClick = { viewModel.setFilter(f) })
                }
            }
        }
        val nothingYet = state.query.isBlank() && state.filter == ChatFilter.All
        when {
            state.loading -> LoadingSkeleton(Modifier.padding(Spacing.md), itemCount = 2)
            state.error != null -> ErrorState(state.error!!, onRetry = viewModel::load)
            state.visible.isEmpty() -> EmptyState(
                icon = VibeIcons.Comment,
                title = when { state.filter == ChatFilter.Archived && state.query.isBlank() -> "Nothing archived"; nothingYet -> "No conversations yet"; else -> "No matches" },
                message = when {
                    state.filter == ChatFilter.Archived && state.query.isBlank() -> "Chats you archive show up here. Swipe a chat left to archive it."
                    nothingYet -> "Start a chat from a student's profile, or create a group."
                    else -> "Try a different name, word or filter."
                },
                actionLabel = if (nothingYet) "New group" else null,
                onAction = if (nothingYet) onNewGroup else null,
            )
            else -> {
                val pinned = state.pinned
                val others = state.others
                VibePullToRefresh(refreshing = state.refreshing, onRefresh = viewModel::pullRefresh) { LazyColumn(
                    state = rememberTabListState("chats"),
                    contentPadding = PaddingValues(start = Spacing.md, end = Spacing.md, top = Spacing.xs, bottom = rememberBottomClearance()),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (pinned.isNotEmpty()) {
                        item(key = "hdr-pinned", contentType = "header") { SectionLabel("Pinned") }
                        items(pinned, key = { it.id }, contentType = { "chat" }) { chat ->
                            ChatListItem(chat, state, viewModel, onOpenChat, storyByUser, onOpenStory)
                        }
                    }
                    if (others.isNotEmpty()) {
                        item(key = "hdr-all", contentType = "header") { SectionLabel("All chats") }
                        items(others, key = { it.id }, contentType = { "chat" }) { chat ->
                            ChatListItem(chat, state, viewModel, onOpenChat, storyByUser, onOpenStory)
                        }
                    }
                } }
            }
        }
    }

    NotesSheets(state = notes, vm = notesViewModel)

    state.menuChat?.let { chat ->
        ChatActionsSheet(
            chat = chat,
            onPin = { viewModel.togglePinned(chat.id) },
            onUnread = { viewModel.toggleUnread(chat.id) },
            onMute = { viewModel.toggleMute(chat.id) },
            onArchive = { viewModel.archive(chat.id) },
            archived = state.filter == ChatFilter.Archived,
            onUnarchive = { viewModel.unarchive(chat.id) },
            onDismiss = viewModel::closeMenu,
        )
    }
}

@Composable
private fun ChatListItem(
    chat: Chat, state: ChatsUiState, viewModel: ChatsViewModel, onOpenChat: (String) -> Unit,
    storyByUser: Map<String, StoryTrayEntry>, onOpenStory: ((String) -> Unit)?,
) {
    val typingIds = state.typing[chat.id].orEmpty()
    // Only a 1:1 chat has a person whose Story can ring the avatar.
    val story = if (chat.isGroup) null else chat.studentId?.let { storyByUser[it] }
    val onStory: () -> Unit = { story?.let { onOpenStory?.invoke(it.authorId) } }
    val selected = state.menuChatId == chat.id
    if (state.filter == ChatFilter.Archived) {
        // Archived chats open and offer "Unarchive" in the menu; no swipe gestures here.
        ChatRow(chat, typingIds, selected, story, { onOpenChat(chat.id) }, { viewModel.openMenu(chat.id) }, onStory)
    } else {
        SwipeableChatRow(
            chat = chat, typingIds = typingIds, selected = selected, story = story, onStory = onStory,
            onOpen = { onOpenChat(chat.id) },
            onMenu = { viewModel.openMenu(chat.id) },
            onMute = { viewModel.toggleMute(chat.id) },
            onArchive = { viewModel.archive(chat.id) },
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = Spacing.sm, bottom = Spacing.xs),
    )
}

/** Swipe right to mute/unmute (snaps back), swipe left to archive. Long-press opens the full menu. */
@Composable
private fun SwipeableChatRow(
    chat: Chat, typingIds: Set<String>, selected: Boolean, story: StoryTrayEntry?, onStory: () -> Unit,
    onOpen: () -> Unit, onMenu: () -> Unit, onMute: () -> Unit, onArchive: () -> Unit,
) {
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> { onMute(); false }
                SwipeToDismissBoxValue.EndToStart -> { onArchive(); true }
                SwipeToDismissBoxValue.Settled -> false
            }
        },
    )
    SwipeToDismissBox(
        state = state,
        backgroundContent = {
            val direction = state.dismissDirection
            val muteSide = direction == SwipeToDismissBoxValue.StartToEnd
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        if (muteSide) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.tertiaryContainer,
                        MaterialTheme.shapes.medium,
                    )
                    .padding(horizontal = Spacing.lg),
                contentAlignment = if (muteSide) Alignment.CenterStart else Alignment.CenterEnd,
            ) {
                Icon(
                    if (muteSide) VibeIcons.BellOff else VibeIcons.Archive,
                    contentDescription = if (muteSide) (if (chat.muted) "Unmute" else "Mute") else "Archive",
                )
            }
        },
    ) {
        ChatRow(chat, typingIds, selected, story, onOpen, onMenu, onStory)
    }
}

@Composable
private fun ChatRow(
    chat: Chat, typingIds: Set<String>, selected: Boolean, story: StoryTrayEntry?,
    onOpen: () -> Unit, onMenu: () -> Unit, onStory: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val unread = chat.hasUnread
    val typingText = typingLabel(chat, typingIds)
    val previewText = remember(chat) { previewDescription(chat) }
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) colors.surfaceContainerHigh else colors.background, MaterialTheme.shapes.medium)
            .combinedClickable(
                role = Role.Button,
                onClickLabel = "Open conversation",
                onClick = onOpen,
                onLongClickLabel = "More options",
                onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onMenu() },
            )
            .heightIn(min = 68.dp)
            .padding(vertical = Spacing.sm, horizontal = 2.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = buildString {
                    append(chat.title); append(". ")
                    append(typingText ?: previewText)
                    if (story != null) append(if (story.hasUnviewed) ". New story" else ". Story")
                    if (chat.online && !chat.isGroup) append(". Active now")
                    if (unread) append(if (chat.markedOnly) ". Marked as unread" else ". ${chat.unread} unread")
                    if (chat.pinned) append(". Pinned")
                    if (chat.muted) append(". Muted")
                }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(AvatarBox), contentAlignment = Alignment.Center) {
            if (chat.isGroup) GroupAvatar(chat.groupPhotoUrl, AvatarSize, name = chat.title, memberNames = chat.members.map { it.name })
            else {
                // Teal ring = a Story to watch (muted once all seen). Tapping the avatar opens it; the rest of the row opens the chat.
                Box(
                    Modifier.size(AvatarBox).then(
                        if (story != null) Modifier.clip(CircleShape).clickable(role = Role.Button, onClickLabel = "Open ${chat.title}'s story", onClick = onStory) else Modifier,
                    ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (story != null) StoryStatusRing(unviewed = story.hasUnviewed, modifier = Modifier.matchParentSize())
                    StudentAvatar(chat.title, size = AvatarSize - if (story != null) 6.dp else 0.dp, accent = chat.id.hashCode(), imagePath = chat.avatarUrl)
                }
                // Teal dot = active now (only when they share it). Drawn outside the clipped box so it is never cut off.
                if (chat.online) ActiveDot(Modifier.align(Alignment.BottomEnd).padding(end = 3.dp, bottom = 3.dp), borderColor = colors.background)
            }
        }

        Column(Modifier.weight(1f).padding(start = Spacing.md)) {
            // Name and time share a row; the name takes what is left and truncates, so it can never run into the time.
            Row(verticalAlignment = Alignment.CenterVertically) {
                VerifiedName(
                    chat.title, chat.verified,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (unread) FontWeight.Bold else FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    chat.timeLabel,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (unread && !chat.muted) colors.primary else colors.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.padding(start = Spacing.sm),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                if (typingText != null) {
                    Text(typingText, style = MaterialTheme.typography.bodyMedium, color = colors.primary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                } else {
                    PreviewLine(chat, unread, Modifier.weight(1f))
                }
                if (chat.muted) Icon(VibeIcons.BellOff, contentDescription = null, modifier = Modifier.padding(start = 6.dp).size(14.dp), tint = colors.onSurfaceVariant)
                if (chat.pinned) Icon(VibeIcons.Pin, contentDescription = null, modifier = Modifier.padding(start = 6.dp).size(14.dp), tint = colors.onSurfaceVariant)
                if (unread) UnreadBadge(chat)
            }
        }
    }
}

@Composable
private fun UnreadBadge(chat: Chat) {
    val colors = MaterialTheme.colorScheme
    val tint = if (chat.muted) colors.outline else colors.primary
    if (chat.markedOnly) {
        // Marked unread by hand: a plain dot, since there is no count to show.
        Surface(shape = CircleShape, color = tint, modifier = Modifier.padding(start = 8.dp).size(10.dp)) {}
    } else {
        Surface(shape = CircleShape, color = tint, modifier = Modifier.padding(start = 8.dp)) {
            Text(
                if (chat.unread > 99) "99+" else chat.unread.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = colors.onPrimary,
                maxLines = 1,
                modifier = Modifier.widthIn(min = 18.dp).padding(horizontal = 6.dp, vertical = 1.dp),
            )
        }
    }
}

/** "You: Photo", "Jane: See you tomorrow", or just the text in a 1:1 chat from the other person. */
@Composable
private fun PreviewLine(chat: Chat, unread: Boolean, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val tint = if (unread) colors.onSurface else colors.onSurfaceVariant
    val prefix = previewPrefix(chat)
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (prefix != null) {
            Text(prefix, style = MaterialTheme.typography.bodyMedium, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 110.dp))
        }
        if (chat.lastFromMe && chat.lastDelivery != Delivery.None && chat.lastKind != LastKind.Deleted) {
            // Our own last message: one tick sent, two delivered, two in the accent colour read (the icon differs, not just the colour).
            Icon(
                if (chat.lastDelivery == Delivery.Sent) VibeIcons.Check else VibeIcons.DoubleCheck,
                contentDescription = when (chat.lastDelivery) { Delivery.Read -> "Read"; Delivery.Delivered -> "Delivered"; else -> "Sent" },
                tint = if (chat.lastDelivery == Delivery.Read) colors.primary else tint,
                modifier = Modifier.padding(end = 4.dp).size(14.dp),
            )
        }
        if (chat.lastKind == LastKind.Voice) {
            Icon(VibeIcons.Mic, contentDescription = null, tint = tint, modifier = Modifier.padding(end = 4.dp).size(16.dp))
        }
        if (chat.lastKind == LastKind.Location) {
            Icon(VibeIcons.Place, contentDescription = null, tint = tint, modifier = Modifier.padding(end = 4.dp).size(16.dp))
        }
        if (chat.lastKind == LastKind.Photo) {
            Icon(VibeIcons.Photo, contentDescription = null, tint = tint, modifier = Modifier.padding(end = 4.dp).size(16.dp))
        }
        Text(
            chat.lastMessage.ifBlank { if (chat.isGroup) "Say hello" else activeLabel(chat.online, chat.lastActiveMs, System.currentTimeMillis()) ?: "Say hello" },
            style = MaterialTheme.typography.bodyMedium,
            fontStyle = if (chat.lastKind == LastKind.Deleted) FontStyle.Italic else FontStyle.Normal,
            fontWeight = if (unread) FontWeight.Medium else FontWeight.Normal,
            color = tint,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

private fun previewPrefix(chat: Chat): String? = when {
    chat.lastKind == LastKind.None || chat.lastKind == LastKind.Deleted -> null
    chat.lastFromMe -> "You: "
    chat.isGroup -> chat.lastSenderName?.trim()?.substringBefore(' ')?.takeIf { it.isNotBlank() }?.let { "$it: " }
    else -> null
}

private fun previewDescription(chat: Chat): String = buildString {
    previewPrefix(chat)?.let { append(it) }
    if (chat.lastKind == LastKind.Photo) append("Photo. ")
    append(chat.lastMessage.ifBlank { "No messages yet" })
}

/** Direct chat: "typing…". Group: names come from the chat list's member preview; anyone not in it is "Someone". */
private fun typingLabel(chat: Chat, ids: Set<String>): String? {
    if (ids.isEmpty()) return null
    if (!chat.isGroup) return "typing…"
    val names = ids.mapNotNull { id -> chat.members.firstOrNull { it.id == id }?.name?.substringBefore(' ')?.takeIf { it.isNotBlank() } }
    return when {
        ids.size == 1 -> (names.firstOrNull() ?: "Someone") + " is typing…"
        ids.size == 2 && names.size == 2 -> "${names[0]} and ${names[1]} are typing…"
        else -> "Several people are typing…"
    }
}

@Composable
private fun ChatActionsSheet(
    chat: Chat, onPin: () -> Unit, onUnread: () -> Unit, onMute: () -> Unit, onArchive: () -> Unit,
    archived: Boolean, onUnarchive: () -> Unit, onDismiss: () -> Unit,
) {
    VibeBottomSheet(onDismiss = onDismiss) {
        Row(Modifier.fillMaxWidth().padding(bottom = Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
            if (chat.isGroup) GroupAvatar(chat.groupPhotoUrl, 40.dp, name = chat.title, memberNames = chat.members.map { it.name }) else StudentAvatar(chat.title, size = 40.dp, accent = chat.id.hashCode(), imagePath = chat.avatarUrl)
            VerifiedName(chat.title, chat.verified, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(start = Spacing.md))
        }
        if (archived) {
            // Pin, mute and read state belong to the main list; from the archive the one action is to bring the chat back.
            SheetAction(VibeIcons.Archive, "Unarchive conversation", onUnarchive)
            return@VibeBottomSheet
        }
        SheetAction(VibeIcons.Pin, if (chat.pinned) "Unpin conversation" else "Pin conversation", onPin)
        SheetAction(
            if (chat.hasUnread) VibeIcons.DoubleCheck else VibeIcons.ChatUnread,
            if (chat.hasUnread) "Mark as read" else "Mark as unread", onUnread,
        )
        SheetAction(VibeIcons.BellOff, if (chat.muted) "Unmute notifications" else "Mute notifications", onMute)
        SheetAction(VibeIcons.Archive, "Archive conversation", onArchive)
    }
}

@Composable
private fun SheetAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = Spacing.md))
    }
}
