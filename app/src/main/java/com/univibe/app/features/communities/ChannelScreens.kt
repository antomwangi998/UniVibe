package com.univibe.app.features.communities

import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.univibe.app.ui.components.VerifiedName
import com.univibe.app.core.keyedViewModel
import com.univibe.app.data.media.PhotoStore
import com.univibe.app.data.model.CHANNEL_REACTIONS
import com.univibe.app.data.model.Channel
import com.univibe.app.data.model.ChannelAccess
import com.univibe.app.data.model.ChannelMessage
import com.univibe.app.data.model.ChannelType
import com.univibe.app.data.model.ReplyPreview
import com.univibe.app.data.model.Community
import com.univibe.app.data.repository.SearchFilters
import com.univibe.app.features.chats.SheetRow
import com.univibe.app.ui.components.ConfirmDialog
import com.univibe.app.ui.components.EmptyState
import com.univibe.app.ui.components.ErrorState
import com.univibe.app.ui.components.InterestChip
import com.univibe.app.ui.components.LoadingSkeleton
import com.univibe.app.ui.components.PhotoImage
import com.univibe.app.ui.components.PostPoll
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

/** What "Copy text" puts on the clipboard: the title (if any) and the text. Null for a deleted message or one with only files. */
private fun ChannelMessage.copyableText(): String? =
    if (deleted) null else listOfNotNull(title, content).filter { it.isNotBlank() }.joinToString("\n").ifBlank { null }

private fun copyMessageText(clipboard: ClipboardManager, context: Context, text: String) {
    clipboard.setText(AnnotatedString(text))
    // From Android 13 the system shows its own "copied" confirmation; earlier versions show nothing.
    if (Build.VERSION.SDK_INT < 33) Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
}

/** "14:05" in the phone's time zone: the small timestamp beside a message that continues an earlier one. */
private fun clockLabel(iso: String): String = runCatching {
    OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))
}.getOrDefault("")

// ------------------------------------------------------------------ Discussions tab

/** The Discussions section of a community page: channels grouped by the community's own groups, with unread counts. */
@Composable
fun ChannelDirectoryContent(community: Community, onOpenChannel: (String) -> Unit, onManage: () -> Unit = {}) {
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
            if (directory.canManage) {
                VibeButton("New channel", { creating = true }, Modifier.fillMaxWidth().padding(top = Spacing.sm), leadingIcon = VibeIcons.Add)
                VibeButton("Manage channels", onManage, Modifier.fillMaxWidth(), style = com.univibe.app.ui.components.VibeButtonStyle.Secondary, leadingIcon = VibeIcons.Settings)
            }
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
    val clipboard = LocalClipboardManager.current
    var menuFor by remember { mutableStateOf<ChannelMessage?>(null) }
    var reportFor by remember { mutableStateOf<ChannelMessage?>(null) }
    var deleteFor by remember { mutableStateOf<ChannelMessage?>(null) }
    var settings by rememberSaveable { mutableStateOf(false) }
    var confirmArchive by rememberSaveable { mutableStateOf(false) }
    var pollSheet by rememberSaveable { mutableStateOf(false) }
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
                    // Grouping is computed once per list change. Question and announcement channels show cards, so they never group.
                    val continuing = remember(s.messages, channel.type) {
                        if (channel.type.threadedTimeline) emptySet() else channelContinuations(s.timeline)
                    }
                    var flashId by remember { mutableStateOf<String?>(null) }
                    LaunchedEffect(flashId) { if (flashId != null) { delay(1400); flashId = null } }
                    val jumpToOriginal: (String) -> Unit = { originalId ->
                        val at = items.indexOfFirst { it.id == originalId }
                        if (at < 0) Toast.makeText(context, "Scroll up to load the original message.", Toast.LENGTH_SHORT).show()
                        else scope.launch { listState.animateScrollToItem(at + s.pending.size); flashId = originalId }
                    }
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
                                onOpenStudent = onOpenStudent, onVote = { i -> viewModel.vote(m, i) },
                                highlighted = m.id == flashId, continuation = m.id in continuing, onJumpToReply = jumpToOriginal,
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
                    onPoll = if (channel.type == ChannelType.Text || channel.type == ChannelType.Announcement) ({ pollSheet = true }) else null,
                    placeholder = when (channel.type) { ChannelType.Qa -> "Add details (optional)"; ChannelType.Announcement -> "Write the announcement"; else -> "Message #${channel.name}" },
                )
            }
        }
    }

    if (pollSheet && channel != null) PollSheet(
        needsTitle = channel.type.needsTitle, onDismiss = { pollSheet = false },
        onCreate = { title, question, options, hours -> viewModel.sendPoll(title, question, options, hours); pollSheet = false },
    )
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
            onCopy = m.copyableText()?.let { text -> { copyMessageText(clipboard, context, text); menuFor = null } },
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
                pins.forEach { MessageRow(it, mine = it.author?.id == myId, type = channel?.type ?: ChannelType.Text, canReact = false, onReact = {}, onMore = { menuFor = it; viewModel.showPins(false) }, onThread = null, onOpenStudent = onOpenStudent, tapOpensMenu = true) }
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
                    MessageRow(m, mine = m.author?.id == myId, type = ChannelType.Text, canReact = false, onReact = {}, onMore = { onOpen(m) }, onThread = null, onOpenStudent = onOpenStudent, tapOpensMenu = true)
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
    val clipboard = LocalClipboardManager.current
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
                        MessageRow(m, mine = m.author?.id == myId, type = channel.type, canReact = channel.access.react, onReact = { viewModel.toggleReaction(m, it) }, onMore = { menuFor = m }, onThread = null, onOpenStudent = onOpenStudent, showReplyHeader = false)
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
            onCopy = m.copyableText()?.let { text -> { copyMessageText(clipboard, context, text); menuFor = null } },
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

/** Width of the avatar column. Continuation rows keep the same gutter so text stays aligned down the whole run. */
private val AvatarGutter: Dp = 36.dp

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageRow(
    m: ChannelMessage, mine: Boolean, type: ChannelType, canReact: Boolean,
    onReact: (String) -> Unit, onMore: () -> Unit, onThread: (() -> Unit)?, onOpenStudent: (String) -> Unit,
    highlighted: Boolean = false, onVote: ((Int) -> Unit)? = null,
    /** True when this message continues the one above it from the same author: no avatar or name, just a small time. */
    continuation: Boolean = false,
    /** Off in a thread, where every reply points at the root and a connector on each would only repeat it. */
    showReplyHeader: Boolean = true,
    onJumpToReply: ((String) -> Unit)? = null,
    /** Pins and search results have nothing else to do on a tap, so there a tap opens the menu too. */
    tapOpensMenu: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val reply = if (showReplyHeader) m.replyTo else null
    Column(
        Modifier.fillMaxWidth().then(if (highlighted) Modifier.background(colors.primary.copy(alpha = 0.10f)) else Modifier).combinedClickable(onClick = { if (tapOpensMenu) onMore() }, onLongClick = onMore, onLongClickLabel = "Message actions"),
    ) {
        if (reply != null) ReplyHeader(reply, onJump = onJumpToReply?.let { jump -> { jump(reply.id) } })
        Row(
            Modifier.fillMaxWidth().padding(
                start = Spacing.md, end = Spacing.md,
                top = if (reply != null || continuation) 2.dp else Spacing.sm, bottom = 4.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            val a = m.author
            if (continuation) {
                Text(
                    clockLabel(m.createdAt), Modifier.width(AvatarGutter).padding(top = 3.dp), style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant.copy(alpha = 0.8f), textAlign = TextAlign.Center, maxLines = 1, softWrap = false,
                )
            } else {
                Box(Modifier.width(AvatarGutter).clickable(enabled = a != null) { a?.let { onOpenStudent(it.id) } }) {
                    StudentAvatar(a?.name ?: "?", size = AvatarGutter, accent = a?.accent ?: 0, imagePath = a?.avatarUrl)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (!continuation) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    VerifiedName(a?.name ?: "Deleted account", a?.verified == true, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f, fill = false))
                    if (m.authorRole == "owner" || m.authorRole == "moderator") Surface(shape = RoundedCornerShape(6.dp), color = colors.primary.copy(alpha = 0.15f)) {
                        Text(if (m.authorRole == "owner") "Owner" else "Mod", Modifier.padding(horizontal = 6.dp, vertical = 1.dp), style = MaterialTheme.typography.labelSmall, color = colors.primary)
                    }
                    Text(timeLabel(m.createdAt), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                    if (m.pinned) Icon(VibeIcons.Pin, contentDescription = "Pinned", modifier = Modifier.size(14.dp), tint = colors.onSurfaceVariant)
                }
                if (m.deleted) {
                    Text(if (m.removedByModerators) "Removed by moderators." else "This message was deleted.", style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic, color = colors.onSurfaceVariant)
                } else {
                    m.title?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
                    if (!m.content.isNullOrBlank()) {
                        val editedStyle = MaterialTheme.typography.labelSmall.toSpanStyle().copy(color = colors.onSurfaceVariant)
                        Text(
                            if (m.edited) buildAnnotatedString { append(m.content); withStyle(editedStyle) { append(" (edited)") } } else AnnotatedString(m.content),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    // The same poll component the feed uses. Without voting rights (read-only member) it shows the results.
                    m.poll?.let { PostPoll(it, onVote = if (canReact) onVote else null) }
                    m.attachments.forEach { f ->
                        if (f.isImage && f.url != null) Box(Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(12.dp))) { PhotoImage(f.url, f.fileName ?: "Photo") }
                        else Row(Modifier.clip(RoundedCornerShape(10.dp)).background(colors.surfaceVariant.copy(alpha = 0.5f)).padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(VibeIcons.File, contentDescription = null, modifier = Modifier.size(18.dp)); Text(f.fileName ?: "File", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    val counts = CHANNEL_REACTIONS.filter { (m.reactions[it.first] ?: 0) > 0 }
                    if (counts.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        counts.forEach { (key, emoji) ->
                            ReactionChip(emoji, m.reactions[key] ?: 0, selected = key in m.myReactions, enabled = canReact, onClick = { onReact(key) })
                        }
                        // "Add reaction" opens the same menu, whose first row is the quick-reaction picker.
                        if (canReact) Surface(
                            shape = RoundedCornerShape(12.dp), color = colors.surfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.semantics { contentDescription = "Add reaction"; role = Role.Button }.clickable(onClick = onMore),
                        ) { Box(Modifier.heightIn(min = 32.dp).widthIn(min = 40.dp), contentAlignment = Alignment.Center) { Icon(VibeIcons.Add, contentDescription = null, modifier = Modifier.size(14.dp), tint = colors.onSurfaceVariant) } }
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
}

/** One reaction: emoji and count, tinted with a teal outline when it is mine. The tint eases in and out instead of jumping. */
@Composable
private fun ReactionChip(emoji: String, count: Int, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val background by animateColorAsState(if (selected) colors.primary.copy(alpha = 0.18f) else colors.surfaceVariant.copy(alpha = 0.6f), label = "reactionBackground")
    val spoken = "$emoji, $count ${if (count == 1) "reaction" else "reactions"}" + if (selected) ", including yours" else ""
    Surface(
        shape = RoundedCornerShape(12.dp), color = background,
        border = BorderStroke(1.dp, if (selected) colors.primary.copy(alpha = 0.6f) else Color.Transparent),
        modifier = Modifier.semantics { contentDescription = spoken; role = Role.Button }.clickable(enabled = enabled, onClick = onClick),
    ) {
        Box(Modifier.heightIn(min = 32.dp).padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
            Text("$emoji $count", style = MaterialTheme.typography.labelMedium)
        }
    }
}

/**
 * The line above a reply, Discord style: a curve rising from the replying author's avatar, then running right to
 * "@name  excerpt". Tapping it scrolls to the original when it is loaded. The excerpt and name come from the server,
 * which already withholds them when the original's author is blocked, so nothing here can reveal hidden content.
 */
@Composable
private fun ReplyHeader(r: ReplyPreview, onJump: (() -> Unit)?) {
    val colors = MaterialTheme.colorScheme
    val lineColor = colors.outline.copy(alpha = 0.7f)
    val label = when {
        r.deleted -> "Original message was deleted"
        r.preview.isNullOrBlank() -> "Original message"
        else -> r.preview
    }
    val spoken = "Reply to ${r.authorName ?: "a message"}: $label" + if (onJump != null) ". Double tap to jump to it." else ""
    val topPad = 6.dp
    val bottomPad = 4.dp
    val reach = 2.dp // how far the line runs past this row so it meets the top of the avatar below
    Row(
        Modifier.fillMaxWidth()
            .drawBehind {
                val rtl = layoutDirection == LayoutDirection.Rtl
                fun x(v: Float) = if (rtl) size.width - v else v
                val stroke = 2.dp.toPx()
                val radius = 8.dp.toPx()
                val cx = (Spacing.md + AvatarGutter / 2).toPx()
                val endX = (Spacing.md + AvatarGutter + Spacing.sm / 2).toPx()
                val midY = (topPad.toPx() + (size.height - bottomPad.toPx())) / 2f
                val path = Path().apply {
                    moveTo(x(cx), size.height + reach.toPx())
                    lineTo(x(cx), midY + radius)
                    quadraticBezierTo(x(cx), midY, x(cx + radius), midY)
                    lineTo(x(endX), midY)
                }
                drawPath(path, lineColor, style = Stroke(width = stroke, cap = StrokeCap.Round))
            }
            .then(if (onJump != null) Modifier.clickable(onClick = onJump) else Modifier)
            .semantics(mergeDescendants = true) { contentDescription = spoken; if (onJump != null) role = Role.Button }
            .padding(start = Spacing.md + AvatarGutter + Spacing.sm, end = Spacing.md, top = topPad, bottom = bottomPad),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        r.authorName?.let { Text("@$it", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = colors.primary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)) }
        Text(
            label, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
            fontStyle = if (r.deleted) FontStyle.Italic else FontStyle.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PendingRow(p: PendingSend, onRetry: () -> Unit, onDiscard: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.sm), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(listOfNotNull(p.draft.title, p.draft.content.ifBlank { null }, if (p.draft.photoPaths.isNotEmpty()) "${p.draft.photoPaths.size} photo(s)" else null, p.draft.poll?.let { "Poll: " + it.options.joinToString(" / ") }).joinToString("\n"), style = MaterialTheme.typography.bodyMedium, color = colors.onSurface.copy(alpha = 0.6f))
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
    repliesOnly: Boolean = false, onPoll: (() -> Unit)? = null,
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
            if (onPoll != null && access.send && !editing && replying == null) VibeIconButton(VibeIcons.Poll, "Add a poll", onPoll)
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
    /** Copies the message text on this phone. Null when there is no text to copy. */
    onCopy: (() -> Unit)? = null,
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
        if (onCopy != null) SheetRow(VibeIcons.Copy, "Copy text", false, onCopy)
        if (onThread != null && (m.replyCount > 0 || type.threadedTimeline)) SheetRow(VibeIcons.Comment, if (type == ChannelType.Qa) "View answers" else "View thread", false, onThread)
        if (onAccept != null) SheetRow(VibeIcons.Check, acceptLabel, false, onAccept)
        if (onPin != null && access.pin) SheetRow(VibeIcons.Pin, if (m.pinned) "Unpin" else "Pin", false, onPin)
        if (onEdit != null && mine && access.canWrite) SheetRow(VibeIcons.Edit, "Edit", false, onEdit)
        if (mine || access.manage) SheetRow(VibeIcons.Delete, "Delete", true, onDelete)
        if (!mine) SheetRow(VibeIcons.Report, "Report", true, onReport)
    }
}

/** A poll for the channel: a question, 2 to 4 different options, and how long voting stays open (the server enforces the same limits). */
@Composable
private fun PollSheet(needsTitle: Boolean, onDismiss: () -> Unit, onCreate: (String?, String, List<String>, Int) -> Unit) {
    var title by rememberSaveable { mutableStateOf("") }
    var question by rememberSaveable { mutableStateOf("") }
    val options = remember { mutableStateListOf("", "") }
    var hours by rememberSaveable { mutableStateOf(24) }
    val filled = options.map { it.trim() }.filter { it.isNotEmpty() }
    val distinct = filled.map { it.lowercase() }.toSet().size == filled.size
    val valid = question.isNotBlank() && filled.size >= 2 && distinct && (!needsTitle || title.isNotBlank())
    VibeBottomSheet(onDismiss = onDismiss) {
        Text("New poll", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = Spacing.sm))
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            if (needsTitle) VibeTextField(title, { title = it.take(100) }, label = "Title")
            VibeTextField(question, { question = it.take(280) }, label = "Question", singleLine = false, maxLines = 3)
            options.forEachIndexed { i, text ->
                VibeTextField(text, { options[i] = it.take(40) }, placeholder = "Option ${i + 1}")
            }
            if (options.size < 4) TextButton(onClick = { options.add("") }) { Text("Add an option") }
            if (filled.size >= 2 && !distinct) Text("Options must be different.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Text("Open for", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                listOf(1 to "1 hour", 6 to "6 hours", 24 to "1 day", 72 to "3 days").forEach { (h, label) -> InterestChip(label, selected = hours == h, onClick = { hours = h }) }
            }
            Text("One vote each, and votes can't be changed. Nobody sees who voted for what.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        VibeButton("Post poll", { onCreate(title.takeIf { needsTitle }, question, options.toList(), hours) }, Modifier.fillMaxWidth().padding(top = Spacing.sm), enabled = valid)
    }
}
