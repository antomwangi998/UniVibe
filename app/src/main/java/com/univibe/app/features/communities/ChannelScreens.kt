package com.univibe.app.features.communities

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.univibe.app.core.keyedViewModel
import com.univibe.app.data.media.PhotoStore
import com.univibe.app.data.model.CHANNEL_REACTIONS
import com.univibe.app.data.model.Channel
import com.univibe.app.data.model.ChannelAccess
import com.univibe.app.data.model.ChannelMessage
import com.univibe.app.data.model.ChannelType
import com.univibe.app.data.model.Community
import com.univibe.app.data.repository.SearchFilters
import com.univibe.app.features.chats.SheetRow
import com.univibe.app.ui.components.ConfirmDialog
import com.univibe.app.ui.components.EmptyState
import com.univibe.app.ui.components.ErrorState
import com.univibe.app.ui.components.InterestChip
import com.univibe.app.ui.components.LoadingSkeleton
import com.univibe.app.ui.components.PhotoImage
import com.univibe.app.ui.components.StudentAvatar
import com.univibe.app.ui.components.VibeBottomSheet
import com.univibe.app.ui.components.VibeButton
import com.univibe.app.ui.components.VibeIconButton
import com.univibe.app.ui.components.VibeIcons
import com.univibe.app.ui.components.VibeTextField
import com.univibe.app.ui.components.VibeTopBar
import com.univibe.app.ui.theme.Spacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private fun ChannelType.icon(): ImageVector = when (this) {
    ChannelType.Text -> VibeIcons.Comment
    ChannelType.Announcement -> VibeIcons.Bell
    ChannelType.Qa -> VibeIcons.Help
    ChannelType.Resources -> VibeIcons.File
    ChannelType.Media -> VibeIcons.Photo
}

private val REPORT_REASONS = listOf("spam" to "Spam", "harassment" to "Harassment or bullying", "other" to "Something else")

/** "14:05" for today, "12 Mar" otherwise, in the phone's time zone. The server's timestamp is the truth; this only formats it. */
private fun timeLabel(iso: String): String = runCatching {
    val t = OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.systemDefault())
    if (t.toLocalDate() == LocalDate.now()) t.format(DateTimeFormatter.ofPattern("HH:mm")) else t.format(DateTimeFormatter.ofPattern("d MMM"))
}.getOrDefault("")

// ------------------------------------------------------------------ Discussions tab

/** The Discussions section of a community page: channels grouped by the community's own groups, with unread counts. */
@Composable
fun ChannelDirectoryContent(community: Community, onOpenChannel: (String) -> Unit) {
    val viewModel = keyedViewModel("channels-${community.id}") { ChannelListViewModel(community.id) }
    val s by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    var creating by rememberSaveable { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.load(silent = true) }
    LaunchedEffect(s.notice) { s.notice?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show(); viewModel.consumeNotice() } }
    val directory = s.directory

    when {
        !community.isMember || s.notAMember -> EmptyState(VibeIcons.Lock, "Members only", "Join this community to read and take part in its discussions.")
        s.loading && directory == null -> LoadingSkeleton(itemCount = 2)
        s.error != null && directory == null -> ErrorState(s.error.orEmpty(), onRetry = { viewModel.load() })
        directory == null || directory.channels.isEmpty() -> EmptyState(
            VibeIcons.Comment, "No channels yet",
            if (community.isStaff) "Create the first channel to start a conversation." else "Moderators haven't opened any channels yet.",
            actionLabel = if (community.isStaff) "New channel" else null, onAction = if (community.isStaff) ({ creating = true }) else null,
        )
        else -> Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            directory.sections.forEach { (group, channels) ->
                if (group != null) Text(group.name.uppercase(), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = Spacing.sm))
                channels.forEach { ChannelRow(it, onClick = { onOpenChannel(it.id) }) }
            }
            if (directory.canManage) VibeButton("New channel", { creating = true }, Modifier.fillMaxWidth().padding(top = Spacing.sm), leadingIcon = VibeIcons.Add)
        }
    }

    if (creating) NewChannelSheet(
        busy = s.creating, onDismiss = { creating = false },
        onCreate = { name, description, type -> viewModel.create(name, description, type) { creating = false; onOpenChannel(it) } },
    )
}

@Composable
private fun ChannelRow(c: Channel, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(colors.surfaceVariant.copy(alpha = 0.4f)).clickable(onClick = onClick).padding(Spacing.md),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Icon(c.type.icon(), contentDescription = c.type.label, tint = colors.primary)
        Column(Modifier.weight(1f)) {
            Text("# ${c.name}", style = MaterialTheme.typography.titleSmall, fontWeight = if (c.unread > 0) FontWeight.Bold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (c.description.isNotBlank()) Text(c.description, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (c.archived) Text("Archived", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
        else if (c.locked || (!c.access.canWrite && c.access.readOnlyReason == "staff_only")) Icon(VibeIcons.Lock, contentDescription = "Read only", modifier = Modifier.size(16.dp), tint = colors.onSurfaceVariant)
        if (c.unread > 0) Surface(shape = CircleShape, color = colors.primary) {
            Text(if (c.unread > 99) "99+" else c.unread.toString(), Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = colors.onPrimary)
        }
    }
}

@Composable
private fun NewChannelSheet(busy: Boolean, onDismiss: () -> Unit, onCreate: (String, String, ChannelType) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var type by rememberSaveable { mutableStateOf(ChannelType.Text.key) }
    VibeBottomSheet(onDismiss = onDismiss) {
        Text("New channel", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = Spacing.sm))
        VibeTextField(name, { name = it.take(40) }, label = "Name", placeholder = "programming-help")
        Spacer(Modifier.height(Spacing.sm))
        VibeTextField(description, { description = it.take(200) }, label = "Description (optional)", singleLine = false, maxLines = 3)
        Spacer(Modifier.height(Spacing.sm))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            items(ChannelType.entries.toList()) { t -> InterestChip(t.label, selected = type == t.key, onClick = { type = t.key }) }
        }
        Text(
            when (ChannelType.parse(type)) {
                ChannelType.Text -> "Everyone can write. Good for general conversation."
                ChannelType.Announcement -> "Only moderators post. Members read and react."
                ChannelType.Qa -> "Members ask questions; the asker or a moderator marks the accepted answer."
                ChannelType.Resources -> "Members share links and files."
                ChannelType.Media -> "Every post needs a photo."
            },
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = Spacing.sm),
        )
        VibeButton("Create channel", { onCreate(name, description, ChannelType.parse(type)) }, Modifier.fillMaxWidth(), enabled = name.isNotBlank(), loading = busy)
    }
}

// ------------------------------------------------------------------ conversation

@Composable
fun ChannelScreen(
    communityId: String,
    channelId: String,
    onBack: () -> Unit,
    onOpenThread: (String) -> Unit,
    onOpenStudent: (String) -> Unit,
) {
    val viewModel = keyedViewModel("channel-$channelId") { ChannelViewModel(communityId, channelId) }
    val s by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var menuFor by remember { mutableStateOf<ChannelMessage?>(null) }
    var reportFor by remember { mutableStateOf<ChannelMessage?>(null) }
    var deleteFor by remember { mutableStateOf<ChannelMessage?>(null) }
    var settings by rememberSaveable { mutableStateOf(false) }
    var confirmArchive by rememberSaveable { mutableStateOf(false) }
    val channel = s.channel
    val myId = viewModel.myId

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.load() }
    LaunchedEffect(s.notice) { s.notice?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show(); viewModel.consumeNotice() } }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch { PhotoStore.import(context, uri, "channel")?.let(viewModel::addPhoto) }
    }
    val newestId = s.timeline.lastOrNull()?.id
    LaunchedEffect(newestId, s.pending.size) { if (listState.firstVisibleItemIndex <= 1) listState.animateScrollToItem(0) }
    val nearTop by remember { derivedStateOf { val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0; last >= listState.layoutInfo.totalItemsCount - 4 } }
    LaunchedEffect(nearTop, s.messages.size) { if (nearTop) viewModel.loadOlder() }

    Column(Modifier.fillMaxSize().imePadding()) {
        VibeTopBar(
            channel?.let { "# ${it.name}" } ?: "Channel", compactTitle = true,
            navigationIcon = { VibeIconButton(VibeIcons.Back, "Back", onBack) },
            actions = {
                if (channel != null && !s.accessLost) {
                    VibeIconButton(VibeIcons.Search, "Search this channel", { viewModel.setSearching(!s.searching) })
                    VibeIconButton(VibeIcons.Pin, "Pinned messages", { viewModel.showPins(true) })
                    if (channel.access.manage) VibeIconButton(VibeIcons.Settings, "Channel settings", { settings = true })
                }
            },
        )
        when {
            s.accessLost -> EmptyState(VibeIcons.Lock, "You can't open this channel", "You may have left the community, been restricted, or the channel may have changed.", actionLabel = "Back", onAction = onBack)
            s.loading && channel == null -> LoadingSkeleton(Modifier.padding(Spacing.md), itemCount = 3)
            channel == null -> ErrorState(s.error ?: "We couldn't load this channel.", onRetry = { viewModel.load() })
            else -> {
                if (channel.description.isNotBlank() && !s.searching) Text(channel.description, Modifier.padding(horizontal = Spacing.md), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (s.searching) SearchPanel(s.search, s.searchLoading, s.results, channel.access, myId, onChange = viewModel::search, onOpen = { menuFor = it }, onOpenStudent = onOpenStudent, modifier = Modifier.weight(1f))
                else Box(Modifier.weight(1f)) {
                    val items = s.timeline.asReversed()
                    if (items.isEmpty() && s.pending.isEmpty()) {
                        EmptyState(channel.type.icon(), "Nothing here yet", if (channel.access.send) "Be the first to post." else "Check back soon.")
                    } else LazyColumn(Modifier.fillMaxSize(), state = listState, reverseLayout = true, contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = Spacing.sm)) {
                        items(s.pending.asReversed(), key = { "pending-${it.draft.clientId}" }) { p ->
                            PendingRow(p, onRetry = { viewModel.retry(p.draft.clientId) }, onDiscard = { viewModel.discard(p.draft.clientId) })
                        }
                        items(items, key = { it.id }) { m ->
                            MessageRow(
                                m, mine = m.author?.id == myId, type = channel.type, canReact = channel.access.react,
                                onReact = { viewModel.toggleReaction(m, it) }, onMore = { menuFor = m },
                                onThread = if (channel.type.threadedTimeline || m.replyCount > 0) ({ onOpenThread(m.id) }) else null,
                                onOpenStudent = onOpenStudent,
                            )
                        }
                        if (s.loadingOlder) item { Text("Loading earlier messages…", Modifier.fillMaxWidth().padding(Spacing.md), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Composer(
                    access = channel.access, replying = s.replyTo, editing = s.editing != null, needsTitle = channel.type.needsTitle && s.replyTo == null && s.editing == null,
                    requiresPhoto = channel.type == ChannelType.Media && s.replyTo == null,
                    title = s.title, onTitle = viewModel::setTitle, text = s.text, onText = viewModel::setText,
                    photos = s.photos, onRemovePhoto = viewModel::removePhoto, onPickPhoto = { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    onCancelReply = viewModel::cancelReply, onCancelEdit = viewModel::cancelEdit, onSend = viewModel::send,
                    placeholder = when (channel.type) { ChannelType.Qa -> "Add details (optional)"; ChannelType.Announcement -> "Write the announcement"; else -> "Message #${channel.name}" },
                )
            }
        }
    }

    menuFor?.let { m ->
        val access = channel?.access ?: ChannelAccess()
        MessageSheet(
            m, access, mine = m.author?.id == myId, type = channel?.type ?: ChannelType.Text,
            onDismiss = { menuFor = null },
            onReact = { viewModel.toggleReaction(m, it); menuFor = null },
            onReply = { viewModel.startReply(m); menuFor = null },
            onThread = { onOpenThread(m.id); menuFor = null },
            onPin = { viewModel.togglePin(m); menuFor = null },
            onEdit = { viewModel.startEdit(m); menuFor = null },
            onDelete = { deleteFor = m; menuFor = null },
            onReport = { reportFor = m; menuFor = null },
            onAccept = null,
        )
    }
    reportFor?.let { m ->
        VibeBottomSheet(onDismiss = { reportFor = null }) {
            Text("Report this message", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = Spacing.sm))
            REPORT_REASONS.forEach { (key, label) -> SheetRow(VibeIcons.Report, label, false) { viewModel.report(m, key); reportFor = null } }
        }
    }
    deleteFor?.let { m ->
        ConfirmDialog(
            title = "Delete this message?", message = "Its text and files are removed for everyone. Replies to it stay.",
            confirmLabel = "Delete", destructive = true, onConfirm = { viewModel.delete(m); deleteFor = null }, onDismiss = { deleteFor = null },
        )
    }
    s.pins?.let { pins ->
        VibeBottomSheet(onDismiss = { viewModel.showPins(false) }) {
            Text("Pinned messages", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = Spacing.sm))
            if (pins.isEmpty()) Text("Nothing is pinned in this channel.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                pins.forEach { MessageRow(it, mine = it.author?.id == myId, type = channel?.type ?: ChannelType.Text, canReact = false, onReact = {}, onMore = { menuFor = it; viewModel.showPins(false) }, onThread = null, onOpenStudent = onOpenStudent) }
            }
        }
    }
    if (settings && channel != null) {
        VibeBottomSheet(onDismiss = { settings = false }) {
            Text("# ${channel.name}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = Spacing.sm))
            SheetRow(VibeIcons.Lock, if (channel.locked) "Unlock channel" else "Lock channel (read-only for members)", false) { viewModel.setLocked(!channel.locked); settings = false }
            SheetRow(VibeIcons.Archive, "Archive channel", true) { settings = false; confirmArchive = true }
        }
    }
    if (confirmArchive) ConfirmDialog(
        title = "Archive this channel?", message = "Members won't see it any more. Moderators can still read it and bring it back.",
        confirmLabel = "Archive", destructive = true, onConfirm = { viewModel.setArchived(true); confirmArchive = false }, onDismiss = { confirmArchive = false },
    )
}

@Composable
private fun SearchPanel(
    filters: SearchFilters, loading: Boolean, results: List<ChannelMessage>?, access: ChannelAccess, myId: String?,
    onChange: (SearchFilters) -> Unit, onOpen: (ChannelMessage) -> Unit, onOpenStudent: (String) -> Unit, modifier: Modifier = Modifier,
) {
    var text by rememberSaveable { mutableStateOf(filters.text) }
    LaunchedEffect(text) { delay(300); if (text != filters.text) onChange(filters.copy(text = text)) }
    Column(modifier) {
        Column(Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            VibeTextField(text, { text = it.take(80) }, placeholder = "Search this channel", leadingIcon = VibeIcons.Search)
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                InterestChip("Pinned", selected = filters.pinnedOnly, onClick = { onChange(filters.copy(pinnedOnly = !filters.pinnedOnly)) })
                InterestChip("With photos or files", selected = filters.withAttachments, onClick = { onChange(filters.copy(withAttachments = !filters.withAttachments)) })
            }
        }
        when {
            loading -> LoadingSkeleton(Modifier.padding(Spacing.md), itemCount = 2)
            results == null -> Text("Type at least two letters, or pick a filter.", Modifier.padding(Spacing.md), color = MaterialTheme.colorScheme.onSurfaceVariant)
            results.isEmpty() -> EmptyState(VibeIcons.Search, "No matches", "Try different words.")
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(results, key = { it.id }) { m ->
                    MessageRow(m, mine = m.author?.id == myId, type = ChannelType.Text, canReact = false, onReact = {}, onMore = { onOpen(m) }, onThread = null, onOpenStudent = onOpenStudent)
                }
            }
        }
    }
}

// ------------------------------------------------------------------ thread

@Composable
fun ThreadScreen(communityId: String, channelId: String, messageId: String, onBack: () -> Unit, onOpenStudent: (String) -> Unit) {
    val viewModel = keyedViewModel("thread-$messageId") { ThreadViewModel(communityId, channelId, messageId) }
    val s by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var menuFor by remember { mutableStateOf<ChannelMessage?>(null) }
    var reportFor by remember { mutableStateOf<ChannelMessage?>(null) }
    var deleteFor by remember { mutableStateOf<ChannelMessage?>(null) }
    val channel = s.channel
    val root = s.root
    val myId = viewModel.myId
    LaunchedEffect(s.notice) { s.notice?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show(); viewModel.consumeNotice() } }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch { PhotoStore.import(context, uri, "channel")?.let(viewModel::addPhoto) }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        VibeTopBar(
            if (channel?.type == ChannelType.Qa) "Question" else "Thread", compactTitle = true,
            navigationIcon = { VibeIconButton(VibeIcons.Back, "Back", onBack) },
        )
        when {
            s.accessLost -> EmptyState(VibeIcons.Lock, "You can't open this thread", "You may have left the community, or the channel changed.", actionLabel = "Back", onAction = onBack)
            s.loading && root == null -> LoadingSkeleton(Modifier.padding(Spacing.md), itemCount = 3)
            root == null || channel == null -> ErrorState(s.error ?: "We couldn't load this thread.", onRetry = { viewModel.load() })
            else -> {
                LazyColumn(Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = Spacing.sm)) {
                    item {
                        MessageRow(root, mine = root.author?.id == myId, type = channel.type, canReact = channel.access.react, onReact = { viewModel.toggleReaction(root, it) }, onMore = { menuFor = root }, onThread = null, onOpenStudent = onOpenStudent, highlighted = true)
                        Text(
                            if (root.replyCount == 1) "1 ${if (channel.type == ChannelType.Qa) "answer" else "reply"}" else "${root.replyCount} ${if (channel.type == ChannelType.Qa) "answers" else "replies"}",
                            Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    items(s.replies, key = { it.id }) { m ->
                        MessageRow(m, mine = m.author?.id == myId, type = channel.type, canReact = channel.access.react, onReact = { viewModel.toggleReaction(m, it) }, onMore = { menuFor = m }, onThread = null, onOpenStudent = onOpenStudent)
                    }
                    items(s.pending, key = { "pending-${it.draft.clientId}" }) { p -> PendingRow(p, onRetry = { viewModel.retry(p.draft.clientId) }, onDiscard = { viewModel.discard(p.draft.clientId) }) }
                    if (s.nextCursor != null) item { TextButton(onClick = viewModel::loadMore, Modifier.fillMaxWidth()) { Text("Load more") } }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Composer(
                    access = channel.access, replying = null, editing = false, needsTitle = false, requiresPhoto = false, title = "", onTitle = {},
                    text = s.text, onText = viewModel::setText, photos = s.photos, onRemovePhoto = viewModel::removePhoto,
                    onPickPhoto = { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    onCancelReply = {}, onCancelEdit = {}, onSend = viewModel::send, repliesOnly = true,
                    placeholder = if (channel.type == ChannelType.Qa) "Write an answer" else "Write a reply",
                )
            }
        }
    }

    menuFor?.let { m ->
        val access = channel?.access ?: ChannelAccess()
        val isRoot = m.id == root?.id
        // The asker or a moderator marks the helpful answer; the server checks who may.
        val canAccept = channel?.type == ChannelType.Qa && !isRoot && !m.deleted && (root?.author?.id == myId || access.manage)
        MessageSheet(
            m, access, mine = m.author?.id == myId, type = channel?.type ?: ChannelType.Text,
            onDismiss = { menuFor = null }, onReact = { viewModel.toggleReaction(m, it); menuFor = null },
            onReply = null, onThread = null, onPin = null, onEdit = null,
            onDelete = { deleteFor = m; menuFor = null }, onReport = { reportFor = m; menuFor = null },
            onAccept = if (canAccept) ({ viewModel.accept(if (root?.acceptedAnswerId == m.id) null else m.id); menuFor = null }) else null,
            acceptLabel = if (root?.acceptedAnswerId == m.id) "Remove accepted mark" else "Mark as accepted answer",
        )
    }
    reportFor?.let { m ->
        VibeBottomSheet(onDismiss = { reportFor = null }) {
            Text("Report this message", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = Spacing.sm))
            REPORT_REASONS.forEach { (key, label) -> SheetRow(VibeIcons.Report, label, false) { viewModel.report(m, key); reportFor = null } }
        }
    }
    deleteFor?.let { m ->
        ConfirmDialog(
            title = "Delete this message?", message = "Its text and files are removed for everyone.",
            confirmLabel = "Delete", destructive = true, onConfirm = { viewModel.delete(m); deleteFor = null }, onDismiss = { deleteFor = null },
        )
    }
}

// ------------------------------------------------------------------ shared pieces

@Composable
private fun MessageRow(
    m: ChannelMessage, mine: Boolean, type: ChannelType, canReact: Boolean,
    onReact: (String) -> Unit, onMore: () -> Unit, onThread: (() -> Unit)?, onOpenStudent: (String) -> Unit,
    highlighted: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().then(if (highlighted) Modifier.background(colors.primary.copy(alpha = 0.06f)) else Modifier)
            .clickable(onClick = onMore).padding(horizontal = Spacing.md, vertical = Spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        val a = m.author
        Box(Modifier.clickable(enabled = a != null) { a?.let { onOpenStudent(it.id) } }) { StudentAvatar(a?.name ?: "?", size = 36.dp, accent = a?.accent ?: 0, imagePath = a?.avatarUrl) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(a?.name ?: "Deleted account", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (m.authorRole == "owner" || m.authorRole == "moderator") Surface(shape = RoundedCornerShape(6.dp), color = colors.primary.copy(alpha = 0.15f)) {
                    Text(if (m.authorRole == "owner") "Owner" else "Mod", Modifier.padding(horizontal = 6.dp, vertical = 1.dp), style = MaterialTheme.typography.labelSmall, color = colors.primary)
                }
                Text(timeLabel(m.createdAt) + if (m.edited && !m.deleted) " · edited" else "", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                if (m.pinned) Icon(VibeIcons.Pin, contentDescription = "Pinned", modifier = Modifier.size(14.dp), tint = colors.onSurfaceVariant)
            }
            m.replyTo?.let { r ->
                Surface(shape = RoundedCornerShape(8.dp), color = colors.surfaceVariant.copy(alpha = 0.5f)) {
                    Text(
                        if (r.deleted) "Original message deleted" else r.preview?.let { "↪ $it" } ?: "↪ Original message", Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (m.deleted) {
                Text(if (m.removedByModerators) "Removed by moderators." else "This message was deleted.", style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic, color = colors.onSurfaceVariant)
            } else {
                m.title?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
                if (!m.content.isNullOrBlank()) Text(m.content, style = MaterialTheme.typography.bodyMedium)
                m.attachments.forEach { f ->
                    if (f.isImage && f.url != null) Box(Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(12.dp))) { PhotoImage(f.url, f.fileName ?: "Photo") }
                    else Row(Modifier.clip(RoundedCornerShape(10.dp)).background(colors.surfaceVariant.copy(alpha = 0.5f)).padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(VibeIcons.File, contentDescription = null, modifier = Modifier.size(18.dp)); Text(f.fileName ?: "File", style = MaterialTheme.typography.bodySmall)
                    }
                }
                val counts = CHANNEL_REACTIONS.filter { (m.reactions[it.first] ?: 0) > 0 }
                if (counts.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    counts.forEach { (key, emoji) ->
                        val on = key in m.myReactions
                        Surface(
                            shape = RoundedCornerShape(12.dp), color = if (on) colors.primary.copy(alpha = 0.18f) else colors.surfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.clickable(enabled = canReact) { onReact(key) },
                        ) { Text("$emoji ${m.reactions[key]}", Modifier.padding(horizontal = 8.dp, vertical = 3.dp), style = MaterialTheme.typography.labelMedium) }
                    }
                }
                if (type == ChannelType.Qa && m.replyTo == null) Text(
                    if (m.resolved) "Resolved" else "Unresolved", style = MaterialTheme.typography.labelMedium, color = if (m.resolved) colors.primary else colors.onSurfaceVariant,
                )
                if (m.isAcceptedAnswer) Text("Accepted answer", style = MaterialTheme.typography.labelMedium, color = colors.primary, fontWeight = FontWeight.SemiBold)
                if (onThread != null) Text(
                    when { m.replyCount == 0 -> if (type == ChannelType.Qa) "Answer" else "Reply"; type == ChannelType.Qa -> "${m.replyCount} ${if (m.replyCount == 1) "answer" else "answers"}"; else -> "${m.replyCount} ${if (m.replyCount == 1) "reply" else "replies"}" },
                    Modifier.clickable(onClick = onThread), style = MaterialTheme.typography.labelLarge, color = colors.primary,
                )
            }
        }
    }
}

@Composable
private fun PendingRow(p: PendingSend, onRetry: () -> Unit, onDiscard: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.sm), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(listOfNotNull(p.draft.title, p.draft.content.ifBlank { null }, if (p.draft.photoPaths.isNotEmpty()) "${p.draft.photoPaths.size} photo(s)" else null).joinToString("\n"), style = MaterialTheme.typography.bodyMedium, color = colors.onSurface.copy(alpha = 0.6f))
        if (!p.failed) Text("Sending…", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
        else Row(verticalAlignment = Alignment.CenterVertically) {
            Text(p.error ?: "Not sent", style = MaterialTheme.typography.labelMedium, color = colors.error, modifier = Modifier.weight(1f, fill = false))
            TextButton(onClick = onRetry) { Text("Retry") }
            TextButton(onClick = onDiscard) { Text("Discard") }
        }
    }
}

@Composable
private fun Composer(
    access: ChannelAccess, replying: ChannelMessage?, editing: Boolean, needsTitle: Boolean, requiresPhoto: Boolean,
    title: String, onTitle: (String) -> Unit, text: String, onText: (String) -> Unit,
    photos: List<String>, onRemovePhoto: (String) -> Unit, onPickPhoto: () -> Unit,
    onCancelReply: () -> Unit, onCancelEdit: () -> Unit, onSend: () -> Unit, placeholder: String,
    repliesOnly: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val canWrite = if (repliesOnly || replying != null) access.reply else access.send
    if (!canWrite && !editing) {
        // Read-only for this student: say why instead of showing a dead input.
        Text(
            if (repliesOnly && access.readOnlyReason == null) "Replies are turned off here." else access.readOnlyText(),
            Modifier.fillMaxWidth().navigationBarsPadding().padding(Spacing.md), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant,
        )
        return
    }
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = Spacing.md, vertical = Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        replying?.let { r ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Replying to ${r.author?.name ?: "a deleted account"}", style = MaterialTheme.typography.labelMedium, color = colors.primary, modifier = Modifier.weight(1f))
                VibeIconButton(VibeIcons.Close, "Cancel reply", onCancelReply)
            }
        }
        if (editing) Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Editing message", style = MaterialTheme.typography.labelMedium, color = colors.primary, modifier = Modifier.weight(1f))
            VibeIconButton(VibeIcons.Close, "Cancel edit", onCancelEdit)
        }
        if (needsTitle) VibeTextField(title, onTitle, placeholder = "Title")
        if (photos.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            items(photos) { path ->
                Box(Modifier.size(64.dp).clip(RoundedCornerShape(10.dp)).clickable { onRemovePhoto(path) }) { PhotoImage(path, "Attached photo (tap to remove)") }
            }
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            if (access.attach && !editing) VibeIconButton(VibeIcons.Photo, "Attach a photo", onPickPhoto)
            VibeTextField(text, onText, Modifier.weight(1f), placeholder = placeholder, singleLine = false, maxLines = 5)
            val ready = (text.isNotBlank() || photos.isNotEmpty() || (needsTitle && title.isNotBlank())) && (!requiresPhoto || photos.isNotEmpty() || editing)
            VibeIconButton(VibeIcons.Send, "Send", { if (ready) onSend() })
        }
    }
}

@Composable
private fun MessageSheet(
    m: ChannelMessage, access: ChannelAccess, mine: Boolean, type: ChannelType, onDismiss: () -> Unit,
    onReact: (String) -> Unit, onReply: (() -> Unit)?, onThread: (() -> Unit)?, onPin: (() -> Unit)?, onEdit: (() -> Unit)?,
    onDelete: () -> Unit, onReport: () -> Unit, onAccept: (() -> Unit)?, acceptLabel: String = "Mark as accepted answer",
) {
    VibeBottomSheet(onDismiss = onDismiss) {
        if (m.deleted) { Text("This message was deleted.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(Spacing.md)); return@VibeBottomSheet }
        if (access.react) Row(Modifier.fillMaxWidth().padding(vertical = Spacing.sm), horizontalArrangement = Arrangement.SpaceEvenly) {
            CHANNEL_REACTIONS.forEach { (key, emoji) ->
                Surface(shape = CircleShape, color = if (key in m.myReactions) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.clickable { onReact(key) }) {
                    Text(emoji, Modifier.padding(12.dp), style = MaterialTheme.typography.titleLarge)
                }
            }
        }
        if (onReply != null && access.reply) SheetRow(VibeIcons.Reply, "Reply", false, onReply)
        if (onThread != null && (m.replyCount > 0 || type.threadedTimeline)) SheetRow(VibeIcons.Comment, if (type == ChannelType.Qa) "View answers" else "View thread", false, onThread)
        if (onAccept != null) SheetRow(VibeIcons.Check, acceptLabel, false, onAccept)
        if (onPin != null && access.pin) SheetRow(VibeIcons.Pin, if (m.pinned) "Unpin" else "Pin", false, onPin)
        if (onEdit != null && mine && access.canWrite) SheetRow(VibeIcons.Edit, "Edit", false, onEdit)
        if (mine || access.manage) SheetRow(VibeIcons.Delete, "Delete", true, onDelete)
        if (!mine) SheetRow(VibeIcons.Report, "Report", true, onReport)
    }
}
