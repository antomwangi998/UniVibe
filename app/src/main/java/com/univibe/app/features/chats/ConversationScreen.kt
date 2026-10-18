@file:OptIn(ExperimentalFoundationApi::class)

package com.univibe.app.features.chats

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.SentimentDissatisfied
import androidx.compose.material.icons.outlined.SentimentVerySatisfied
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.univibe.app.core.formatClock
import com.univibe.app.core.formatDayLabel
import com.univibe.app.core.isSameDay
import com.univibe.app.core.keyedViewModel
import com.univibe.app.data.model.Message
import com.univibe.app.data.model.MessageStatus
import com.univibe.app.data.model.Reaction
import com.univibe.app.ui.components.ErrorState
import com.univibe.app.ui.components.PhotoViewerDialog
import com.univibe.app.ui.components.VibeButton
import com.univibe.app.ui.components.PhotoImage
import com.univibe.app.ui.components.StudentAvatar
import com.univibe.app.ui.components.VibeBottomSheet
import com.univibe.app.ui.components.VibeIconButton
import com.univibe.app.ui.components.VibeTextField
import com.univibe.app.ui.components.VibeTopBar
import com.univibe.app.ui.components.rememberPhotoPicker
import com.univibe.app.ui.components.rememberReducedMotion
import com.univibe.app.ui.theme.Spacing
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private sealed interface ChatRow {
    val key: String
    data class Msg(val message: Message, val older: Message?, val newer: Message?) : ChatRow { override val key get() = message.id }
    data class Day(val label: String, val id: String) : ChatRow { override val key get() = "day-$id" }
}

/** Newest first, to match the reversed list. A day header goes after (above) the oldest message of each day. */
private fun buildRows(messages: List<Message>): List<ChatRow> {
    val newestFirst = messages.asReversed()
    return buildList {
        newestFirst.forEachIndexed { i, m ->
            val older = newestFirst.getOrNull(i + 1)
            add(ChatRow.Msg(m, older, newestFirst.getOrNull(i - 1)))
            if (older == null || !isSameDay(m.timeMillis, older.timeMillis)) add(ChatRow.Day(formatDayLabel(m.timeMillis), m.id))
        }
    }
}

@Composable
fun ConversationScreen(chatId: String, onBack: () -> Unit, onOpenGroupInfo: () -> Unit = {}, onOpenStudent: (String) -> Unit = {}) {
    val viewModel = keyedViewModel("chat-$chatId") { ConversationViewModel(chatId) }
    val s by viewModel.state.collectAsStateWithLifecycle()
    val pickPhoto = rememberPhotoPicker("chat") { viewModel.sendImage(it) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val composerFocus = remember { FocusRequester() }
    val chat = s.chat

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    LaunchedEffect(s.notice) { s.notice?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show(); viewModel.consumeNotice() } }
    LaunchedEffect(s.replyTo?.id) { if (s.replyTo != null) composerFocus.requestFocus() }
    // While searching, the current match stays highlighted; otherwise the flash fades after a moment.
    LaunchedEffect(s.highlightId, s.searching) { if (s.highlightId != null && !s.searching) { delay(1400); viewModel.highlight(null) } }
    val searchFocus = remember { FocusRequester() }
    LaunchedEffect(s.searching) { if (s.searching) searchFocus.requestFocus() }

    val rows = remember(s.messages) { buildRows(s.messages) }
    val showJump by remember { derivedStateOf { listState.firstVisibleItemIndex > 3 } }
    val typingOffset = if (s.typing) 1 else 0
    LaunchedEffect(s.jumpTo) {
        val id = s.jumpTo ?: return@LaunchedEffect
        val index = rows.indexOfFirst { it is ChatRow.Msg && it.message.id == id }
        if (index >= 0) listState.animateScrollToItem(index + typingOffset)
        viewModel.consumeJump()
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        if (s.searching) {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = Spacing.sm, vertical = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                    VibeIconButton(Icons.Outlined.Close, "Close search", viewModel::closeSearch)
                    VibeTextField(
                        value = s.query, onValueChange = viewModel::setQuery,
                        modifier = Modifier.weight(1f).padding(horizontal = Spacing.sm).focusRequester(searchFocus), placeholder = "Search in chat",
                    )
                    Text(
                        if (s.query.isBlank()) "" else if (s.matches.isEmpty()) "No results" else "${s.searchIndex + 1} of ${s.matches.size}",
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    IconButton(onClick = { viewModel.stepSearch(+1) }, enabled = s.matches.isNotEmpty()) { Icon(Icons.Outlined.KeyboardArrowUp, contentDescription = "Older match") }
                    IconButton(onClick = { viewModel.stepSearch(-1) }, enabled = s.matches.isNotEmpty()) { Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = "Newer match") }
                }
            }
        } else {
        val headerAction: (() -> Unit)? = when {
            chat?.isGroup == true -> onOpenGroupInfo
            chat?.studentId != null -> ({ onOpenStudent(chat.studentId!!) })
            else -> null
        }
        VibeTopBar(
            title = s.title, compactTitle = true,
            navigationIcon = { VibeIconButton(Icons.AutoMirrored.Outlined.ArrowBack, "Back", onBack) },
            actions = {
                VibeIconButton(Icons.Outlined.Search, "Search in chat", viewModel::openSearch)
                if (chat?.isGroup == true) VibeIconButton(Icons.Outlined.Info, "Group info", onOpenGroupInfo)
            },
            titleContent = if (chat == null) null else {
                {
                    Row(
                        Modifier.clickable(enabled = headerAction != null, role = Role.Button, onClickLabel = if (chat.isGroup) "Open group info" else "Open profile") { headerAction?.invoke() },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (chat.isGroup) GroupAvatar(s.group?.photoPath, 40.dp)
                        else StudentAvatar(chat.title, size = 40.dp, accent = chat.id.hashCode(), online = chat.online)
                        Column(Modifier.padding(start = Spacing.sm + 4.dp)) {
                            Text(s.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                when {
                                    s.typing -> "typing…"
                                    chat.isGroup -> s.group?.let { "${it.members.size} members" } ?: "Group chat"
                                    chat.online -> "Online"
                                    else -> "Offline"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = if (s.typing || chat.online) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            },
        )
        }
        s.pinned?.let { pinned ->
            if (!s.searching) PinnedBar(pinned, canUnpin = s.canPin, onJump = { viewModel.jumpTo(pinned.id) }, onUnpin = { viewModel.pin(null) })
        }

        when {
            s.loading -> Box(Modifier.weight(1f))
            s.notFound -> Box(Modifier.weight(1f)) { ErrorState("This conversation isn't available.", onBack, title = "Not found") }
            else -> Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    state = listState,
                    reverseLayout = true,
                    contentPadding = PaddingValues(horizontal = Spacing.md, vertical = Spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (s.typing) item(key = "typing") { TypingBubble() }
                    items(rows, key = { it.key }) { row ->
                        when (row) {
                            is ChatRow.Day -> DayHeader(row.label)
                            is ChatRow.Msg -> {
                                val m = row.message
                                MessageItem(
                                    m = m,
                                    isGroup = chat?.isGroup == true,
                                    showSender = chat?.isGroup == true && !m.isMine && row.older?.senderId != m.senderId,
                                    showAvatar = chat?.isGroup == true && !m.isMine && row.newer?.senderId != m.senderId,
                                    showReceipts = s.showReceipts,
                                    highlighted = s.highlightId == m.id,
                                    onLongPress = { viewModel.openMenu(m) },
                                    onDoubleTap = { viewModel.react(m, Reaction.Heart) },
                                    onReply = { viewModel.startReply(m) },
                                    onReaction = { viewModel.react(m, it) },
                                    onJump = viewModel::jumpTo,
                                    onOpenPhoto = { viewModel.openViewer(it) },
                                )
                            }
                        }
                    }
                    if (s.canLoadOlder) item(key = "older") {
                        androidx.compose.material3.TextButton(onClick = viewModel::loadOlder, modifier = Modifier.fillMaxWidth()) {
                            Text("Load earlier messages")
                        }
                    }
                    if (s.messages.isEmpty()) item(key = "empty") {
                        Text(
                            "Say hello to ${s.title}.", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(Spacing.xl),
                        )
                    }
                }
                // Plain `if`: AnimatedVisibility is ambiguous here (Box inside a Column), and the fade isn't needed.
                if (showJump) {
                    Surface(
                        modifier = Modifier.align(Alignment.BottomEnd).padding(Spacing.md),
                        shape = CircleShape, color = MaterialTheme.colorScheme.surface, shadowElevation = 4.dp,
                    ) {
                        IconButton(onClick = { scope.launch { listState.animateScrollToItem(0) } }) {
                            Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = "Scroll to latest message")
                        }
                    }
                }
            }
        }

        if (!s.notFound) {
            Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 6.dp) {
                Column(Modifier.navigationBarsPadding()) {
                    AnimatedVisibility(s.replyTo != null) {
                        s.replyTo?.let { ReplyBar(it, onCancel = viewModel::cancelReply) }
                    }
                    AnimatedVisibility(s.editing != null) {
                        s.editing?.let { EditBar(it, onCancel = viewModel::cancelEdit) }
                    }
                    if (s.canPost && s.mentionSuggestions.isNotEmpty()) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.sm)) {
                            s.mentionSuggestions.forEach { st ->
                                Row(
                                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClickLabel = "Mention ${st.name}") { viewModel.pickMention(st) }.padding(horizontal = Spacing.sm),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    StudentAvatar(st.name, size = 32.dp, accent = st.accent, imagePath = st.avatarUrl)
                                    Text(st.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = Spacing.sm + 4.dp))
                                    Text("  ${st.handle}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    if (!s.canPost) {
                        Text(
                            "Only admins can send messages in this group.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(Spacing.md),
                        )
                    } else {
                        Row(Modifier.fillMaxWidth().padding(Spacing.sm), verticalAlignment = Alignment.Bottom) {
                            IconButton(onClick = pickPhoto, modifier = Modifier.size(52.dp)) {
                                Icon(Icons.Outlined.AddPhotoAlternate, contentDescription = "Send a photo", tint = MaterialTheme.colorScheme.primary)
                            }
                            VibeTextField(
                                value = s.input, onValueChange = viewModel::onInput, modifier = Modifier.weight(1f).focusRequester(composerFocus),
                                placeholder = when { s.editing != null -> "Edit message"; s.replyTo != null -> "Write a reply"; else -> "Message" }, singleLine = false,
                                isError = s.input.length > MAX_MESSAGE,
                            )
                            Spacer(Modifier.width(Spacing.sm))
                            Surface(
                                shape = CircleShape,
                                color = if (s.canSend) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                                contentColor = if (s.canSend) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            ) {
                                IconButton(onClick = viewModel::send, enabled = s.canSend, modifier = Modifier.size(52.dp)) {
                                    Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = if (s.editing != null) "Save edit" else "Send message")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    s.menuMessage?.let { m ->
        val clipboard = LocalClipboardManager.current
        VibeBottomSheet(onDismiss = { viewModel.openMenu(null) }) {
            Row(Modifier.fillMaxWidth().padding(vertical = Spacing.sm), horizontalArrangement = Arrangement.SpaceEvenly) {
                Reaction.entries.forEach { r ->
                    val picked = m.myReaction == r
                    Surface(
                        shape = CircleShape,
                        color = if (picked) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {
                        IconButton(onClick = { viewModel.react(m, r) }, modifier = Modifier.size(52.dp)) {
                            Icon(r.icon(), contentDescription = if (picked) "Remove ${r.label()} reaction" else "React: ${r.label()}", tint = r.tint())
                        }
                    }
                }
            }
            SheetRow(Icons.AutoMirrored.Outlined.Reply, "Reply", false) { viewModel.startReply(m) }
            if (canEditMessage(m)) SheetRow(Icons.Outlined.Edit, "Edit", false) { viewModel.startEdit(m) }
            SheetRow(Icons.AutoMirrored.Outlined.Send, "Forward", false) { viewModel.openForward(m) }
            if (s.canPin) {
                if (s.pinnedId == m.id) SheetRow(Icons.Outlined.PushPin, "Unpin message", false) { viewModel.pin(null) }
                else SheetRow(Icons.Outlined.PushPin, "Pin message", false) { viewModel.pin(m) }
            }
            if (m.text.isNotBlank()) {
                SheetRow(Icons.Outlined.ContentCopy, "Copy text", false) { clipboard.setText(AnnotatedString(m.text)); viewModel.openMenu(null) }
            }
            if (m.isMine) SheetRow(Icons.Outlined.DeleteOutline, "Delete message", true, viewModel::deleteMessage)
        }
    }

    if (s.forwarding != null) ForwardSheet(s, viewModel::toggleForward, viewModel::confirmForward, viewModel::closeForward)
    s.viewerPath?.let { PhotoViewerDialog(it) { viewModel.openViewer(null) } }
}

@Composable
private fun ForwardSheet(s: ConversationUiState, onToggle: (String) -> Unit, onSend: () -> Unit, onDismiss: () -> Unit) {
    VibeBottomSheet(onDismiss = onDismiss) {
        Text("Forward to", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm))
        Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState())) {
            if (s.forwardTargets.isEmpty()) Text("No other chats yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(Spacing.md))
            s.forwardTargets.forEach { c ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable(role = Role.Checkbox) { onToggle(c.id) }.padding(horizontal = Spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (c.isGroup) GroupAvatar(null, 40.dp) else StudentAvatar(c.title, size = 40.dp, accent = c.id.hashCode())
                    Text(c.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f).padding(start = Spacing.md))
                    Checkbox(checked = c.id in s.forwardSelected, onCheckedChange = null)
                }
            }
        }
        VibeButton(
            if (s.forwardSelected.isEmpty()) "Select chats" else "Send to ${s.forwardSelected.size}", onSend,
            Modifier.fillMaxWidth().padding(Spacing.md), enabled = s.forwardSelected.isNotEmpty(), loading = s.forwardBusy,
        )
    }
}

@Composable
private fun PinnedBar(m: Message, canUnpin: Boolean, onJump: () -> Unit, onUnpin: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(color = colors.surfaceContainer) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClickLabel = "Jump to pinned message", onClick = onJump).padding(start = Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.PushPin, contentDescription = null, tint = colors.primary, modifier = Modifier.size(18.dp))
            Column(Modifier.weight(1f).padding(horizontal = Spacing.sm + 4.dp, vertical = 6.dp)) {
                Text("Pinned message", style = MaterialTheme.typography.labelMedium, color = colors.primary)
                Text(replyPreview(m), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (canUnpin) IconButton(onClick = onUnpin) { Icon(Icons.Outlined.Close, contentDescription = "Unpin message") }
        }
    }
}

@Composable
private fun EditBar(m: Message, onCancel: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(start = Spacing.md, top = Spacing.sm, end = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Edit, contentDescription = null, tint = colors.primary, modifier = Modifier.size(18.dp))
        Column(Modifier.weight(1f).padding(start = Spacing.sm + 4.dp)) {
            Text("Editing message", style = MaterialTheme.typography.labelLarge, color = colors.primary)
            Text(replyPreview(m), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onCancel) { Icon(Icons.Outlined.Close, contentDescription = "Cancel edit") }
    }
}

private val MentionRegex = Regex("@[A-Za-z0-9._]+")

@Composable
private fun messageText(text: String, mine: Boolean): AnnotatedString {
    val primary = MaterialTheme.colorScheme.primary
    return remember(text, mine, primary) {
        val style = if (mine) SpanStyle(fontWeight = FontWeight.Bold, textDecoration = TextDecoration.Underline) else SpanStyle(fontWeight = FontWeight.Bold, color = primary)
        buildAnnotatedString {
            var last = 0
            for (m in MentionRegex.findAll(text)) {
                append(text.substring(last, m.range.first))
                withStyle(style) { append(m.value) }
                last = m.range.last + 1
            }
            append(text.substring(last))
        }
    }
}

private fun Reaction.icon(): ImageVector = when (this) {
    Reaction.Heart -> Icons.Filled.Favorite
    Reaction.Like -> Icons.Outlined.ThumbUp
    Reaction.Laugh -> Icons.Outlined.SentimentVerySatisfied
    Reaction.Fire -> Icons.Outlined.LocalFireDepartment
    Reaction.Sad -> Icons.Outlined.SentimentDissatisfied
}

private fun Reaction.label(): String = when (this) {
    Reaction.Heart -> "heart"
    Reaction.Like -> "like"
    Reaction.Laugh -> "laugh"
    Reaction.Fire -> "fire"
    Reaction.Sad -> "sad"
}

@Composable
private fun Reaction.tint(): Color = if (this == Reaction.Heart) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary

@Composable
private fun DayHeader(label: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = Spacing.sm), contentAlignment = Alignment.Center) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
        }
    }
}

@Composable
private fun ReplyBar(m: Message, onCancel: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(start = Spacing.md, top = Spacing.sm, end = Spacing.xs).height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(colors.primary, CircleShape))
        Column(Modifier.weight(1f).padding(start = Spacing.sm + 4.dp)) {
            Text("Replying to ${if (m.isMine) "yourself" else m.senderName}", style = MaterialTheme.typography.labelLarge, color = colors.primary)
            Text(replyPreview(m), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onCancel) { Icon(Icons.Outlined.Close, contentDescription = "Cancel reply") }
    }
}

/** Drag a message to the right to reply (the long-press menu and a TalkBack action do the same). */
@Composable
private fun SwipeToReply(onReply: () -> Unit, content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    val maxPx = with(LocalDensity.current) { 72.dp.toPx() }
    val trigger = maxPx * 0.75f
    val haptics = LocalHapticFeedback.current
    Box(
        Modifier.fillMaxWidth().draggable(
            orientation = Orientation.Horizontal,
            state = rememberDraggableState { delta -> scope.launch { offset.snapTo((offset.value + delta).coerceIn(0f, maxPx)) } },
            onDragStopped = {
                if (offset.value >= trigger) { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onReply() }
                offset.animateTo(0f, spring())
            },
        ),
    ) {
        Icon(
            Icons.AutoMirrored.Outlined.Reply, contentDescription = null, tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.align(Alignment.CenterStart).padding(start = Spacing.sm).alpha((offset.value / trigger).coerceIn(0f, 1f)),
        )
        Box(Modifier.offset { IntOffset(offset.value.roundToInt(), 0) }) { content() }
    }
}

@Composable
private fun MessageItem(
    m: Message, isGroup: Boolean, showSender: Boolean, showAvatar: Boolean, showReceipts: Boolean, highlighted: Boolean,
    onLongPress: () -> Unit, onDoubleTap: () -> Unit, onReply: () -> Unit, onReaction: (Reaction) -> Unit, onJump: (String) -> Unit,
    onOpenPhoto: (String) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val mine = m.isMine
    SwipeToReply(onReply) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
            verticalAlignment = Alignment.Bottom,
        ) {
            if (isGroup && !mine) {
                if (showAvatar) StudentAvatar(m.senderName, size = 28.dp, accent = m.senderId.hashCode())
                else Spacer(Modifier.size(28.dp))
                Spacer(Modifier.width(6.dp))
            }
            Column(horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
                if (showSender) Text(m.senderName, style = MaterialTheme.typography.labelMedium, color = colors.primary, modifier = Modifier.padding(start = 12.dp, bottom = 2.dp))
                val shape = if (mine) RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp) else RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp)
                Surface(
                    shape = shape,
                    color = if (mine) colors.primary else colors.surfaceContainerHigh,
                    contentColor = if (mine) colors.onPrimary else colors.onSurface,
                    modifier = Modifier.widthIn(max = 280.dp)
                        .then(if (highlighted) Modifier.border(2.dp, colors.tertiary, shape) else Modifier)
                        .clip(shape)
                        .combinedClickable(onClick = {}, onLongClick = onLongPress, onDoubleClick = onDoubleTap)
                        .semantics {
                            customActions = listOf(
                                CustomAccessibilityAction("Reply") { onReply(); true },
                                CustomAccessibilityAction("React with heart") { onDoubleTap(); true },
                                CustomAccessibilityAction("More options") { onLongPress(); true },
                            )
                        },
                ) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                        m.replyTo?.let { r ->
                            val barColor = if (mine) colors.onPrimary else colors.primary
                            Row(
                                Modifier.padding(bottom = 6.dp).clip(RoundedCornerShape(12.dp))
                                    .background((if (mine) colors.onPrimary else colors.onSurface).copy(alpha = if (mine) 0.18f else 0.08f))
                                    .clickable(role = Role.Button, onClickLabel = "Jump to original message") { onJump(r.messageId) }
                                    .height(IntrinsicSize.Min),
                            ) {
                                Box(Modifier.width(3.dp).fillMaxHeight().background(barColor))
                                Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                                    Text(r.senderName, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                    Text(r.preview, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.alpha(0.9f))
                                }
                            }
                        }
                        if (m.forwarded) Text("Forwarded", style = MaterialTheme.typography.labelSmall, modifier = Modifier.alpha(0.8f).padding(bottom = 2.dp))
                        m.imagePath?.let { path ->
                            Box(
                                Modifier.size(width = 220.dp, height = 160.dp).clip(RoundedCornerShape(12.dp))
                                    .clickable(role = Role.Button, onClickLabel = "View photo") { onOpenPhoto(path) },
                            ) { PhotoImage(path, "Photo in chat") }
                            Spacer(Modifier.height(4.dp))
                        }
                        if (m.text.isNotBlank()) Text(messageText(m.text, mine), style = MaterialTheme.typography.bodyLarge)
                        Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (m.edited) "edited · ${formatClock(m.timeMillis)}" else formatClock(m.timeMillis), style = MaterialTheme.typography.labelSmall, modifier = Modifier.alpha(0.8f))
                            if (mine) {
                                Spacer(Modifier.width(4.dp))
                                val seen = showReceipts && m.status == MessageStatus.Seen
                                Icon(
                                    imageVector = when { m.status == MessageStatus.Sending -> Icons.Outlined.Schedule; seen -> Icons.Outlined.DoneAll; else -> Icons.Outlined.Check },
                                    contentDescription = when { m.status == MessageStatus.Sending -> "Sending"; seen -> "Seen"; else -> "Sent" },
                                    modifier = Modifier.size(14.dp).alpha(if (seen) 1f else 0.8f),
                                )
                            }
                        }
                    }
                }
                if (m.reactions.isNotEmpty()) {
                    Row(Modifier.padding(top = 2.dp, start = 8.dp, end = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Reaction.entries.filter { (m.reactions[it] ?: 0) > 0 }.forEach { r ->
                            val count = m.reactions.getValue(r)
                            val mineR = m.myReaction == r
                            Surface(
                                shape = CircleShape,
                                color = if (mineR) colors.primaryContainer else colors.surfaceContainerHigh,
                                modifier = Modifier.clip(CircleShape)
                                    .clickable(role = Role.Button, onClickLabel = if (mineR) "Remove your reaction" else "React the same") { onReaction(r) }
                                    .semantics(mergeDescendants = true) {},
                            ) {
                                Row(Modifier.padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(r.icon(), contentDescription = "${r.label()} reaction", tint = r.tint(), modifier = Modifier.size(14.dp))
                                    if (count > 1) Text(" $count", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TypingBubble() {
    val reduced = rememberReducedMotion()
    val transition = rememberInfiniteTransition(label = "typing")
    Surface(shape = RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            repeat(3) { i ->
                val alpha = if (reduced) 0.6f else {
                    val a by transition.animateFloat(
                        initialValue = 0.25f, targetValue = 1f,
                        animationSpec = infiniteRepeatable(tween(600, delayMillis = i * 150), RepeatMode.Reverse), label = "dot$i",
                    )
                    a
                }
                Box(Modifier.size(8.dp).alpha(alpha).background(MaterialTheme.colorScheme.onSurfaceVariant, CircleShape))
            }
        }
    }
}
