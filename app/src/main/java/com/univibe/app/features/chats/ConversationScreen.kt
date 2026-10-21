@file:OptIn(ExperimentalFoundationApi::class)

package com.univibe.app.features.chats

import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import com.univibe.app.ui.components.rememberFilePicker
import com.univibe.app.ui.components.rememberCameraPicker
import com.univibe.app.data.model.FileAttachment
import com.univibe.app.data.media.PickedFile
import androidx.compose.runtime.saveable.Saver
import com.univibe.app.ui.components.PostMediaShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.ui.semantics.contentDescription
import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.TextButton
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.withLink
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalConfiguration
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
import com.univibe.app.data.model.activeLabel
import com.univibe.app.data.model.MessageStatus
import com.univibe.app.data.model.Reaction
import com.univibe.app.ui.components.ChatPhoto
import com.univibe.app.ui.components.ErrorState
import com.univibe.app.ui.components.PhotoGalleryViewerDialog
import com.univibe.app.ui.components.PhotoViewerDialog
import com.univibe.app.ui.components.VibeButton
import com.univibe.app.ui.components.PhotoImage
import com.univibe.app.ui.components.StudentAvatar
import com.univibe.app.ui.components.VibeBottomSheet
import com.univibe.app.ui.components.VibeIconButton
import com.univibe.app.ui.components.VibeIconSize
import com.univibe.app.ui.components.VibeIcons
import com.univibe.app.ui.components.VibeTextField
import com.univibe.app.ui.components.VibeTopBar
import com.univibe.app.ui.components.rememberMultiPhotoPicker
import com.univibe.app.data.repository.MAX_MESSAGE_PHOTOS
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import com.univibe.app.ui.components.rememberReducedMotion
import com.univibe.app.ui.theme.Spacing
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.univibe.app.ui.components.UndoBar

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
    // Photos are chosen first, then looked over (and captioned) in a sheet; nothing is sent until the person taps Send.
    var pendingPhotos by rememberSaveable(stateSaver = listSaver<List<String>, String>(save = { it }, restore = { it })) { mutableStateOf(emptyList<String>()) }
    val pickPhoto = rememberMultiPhotoPicker("chat", MAX_MESSAGE_PHOTOS) { picked -> pendingPhotos = (pendingPhotos + picked).distinct().take(MAX_MESSAGE_PHOTOS) }
    if (pendingPhotos.isNotEmpty()) {
        PhotoSendSheet(
            photos = pendingPhotos, canAddMore = pendingPhotos.size < MAX_MESSAGE_PHOTOS, onAdd = pickPhoto,
            onRemove = { p -> runCatching { java.io.File(p).delete() }; pendingPhotos = pendingPhotos - p },
            onCancel = { pendingPhotos.forEach { runCatching { java.io.File(it).delete() } }; pendingPhotos = emptyList() },
            onSend = { caption -> viewModel.sendPhotos(pendingPhotos, caption); pendingPhotos = emptyList() },
        )
    }
    // The camera adds its picture to the same review sheet; a chosen file gets its own (a file is sent on its own).
    val takePhoto = rememberCameraPicker("chat") { picked -> pendingPhotos = (pendingPhotos + picked).distinct().take(MAX_MESSAGE_PHOTOS) }
    var pendingFile by rememberSaveable(stateSaver = PickedFileSaver) { mutableStateOf<PickedFile?>(null) }
    val pickFile = rememberFilePicker { picked -> pendingFile?.let { runCatching { java.io.File(it.path).delete() } }; pendingFile = picked }
    pendingFile?.let { f ->
        FileSendSheet(
            file = f,
            onCancel = { runCatching { java.io.File(f.path).delete() }; pendingFile = null },
            onSend = { caption -> viewModel.sendFile(f, caption); pendingFile = null },
        )
    }
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
    // Upload progress for messages that are still sending (photos and voice messages), by their temporary id.
    val uploads by com.univibe.app.data.net.UploadTracker.uploads.collectAsStateWithLifecycle()

    // ---- Voice messages: record only after a tap and a granted permission; one recording or player at a time.
    val voiceContext = LocalContext.current
    val recorder = remember { VoiceRecorder(voiceContext.applicationContext) }
    var recordingStartedAt by remember { mutableStateOf<Long?>(null) }
    var recordedMs by remember { mutableLongStateOf(0L) }
    var recordLevel by remember { mutableStateOf(0f) }
    // Holding the mic records until you let go (send), slide left (cancel) or slide up (lock: the bar with Send and Delete).
    var holdMode by remember { mutableStateOf(false) }
    val showMic = s.input.isBlank() && s.editing == null
    DisposableEffect(Unit) { onDispose { recorder.cancel(); VoicePlayer.release() } } // leaving the chat discards an unsent recording
    LaunchedEffect(VoicePlayer.isPlaying) { while (VoicePlayer.isPlaying) { VoicePlayer.tick(); kotlinx.coroutines.delay(200) } }
    LaunchedEffect(VoicePlayer.failed) { if (VoicePlayer.failed) { Toast.makeText(voiceContext, "Couldn't play the voice message.", Toast.LENGTH_SHORT).show(); VoicePlayer.failed = false } }
    fun finishRecording(send: Boolean) {
        val rec = recorder.stop()
        recordingStartedAt = null
        holdMode = false
        when {
            rec == null -> if (send) Toast.makeText(voiceContext, "Recording too short.", Toast.LENGTH_SHORT).show()
            send -> viewModel.sendVoice(rec.path, rec.durationMs, rec.waveform)
            else -> java.io.File(rec.path).delete()
        }
    }
    fun beginRecording() {
        VoicePlayer.release()
        if (recorder.start()) { recordedMs = 0L; recordingStartedAt = SystemClock.elapsedRealtime() }
        else Toast.makeText(voiceContext, "Couldn't start recording.", Toast.LENGTH_SHORT).show()
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // The finger that asked is long gone, so the first recording after allowing the microphone is a locked one.
        holdMode = false
        if (granted) beginRecording() else Toast.makeText(voiceContext, "Allow the microphone to record voice messages.", Toast.LENGTH_LONG).show()
    }
    fun onMic() {
        holdMode = false // a tap always gives the locked recording bar
        if (ContextCompat.checkSelfPermission(voiceContext, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) beginRecording()
        else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }
    fun onMicHold() {
        if (ContextCompat.checkSelfPermission(voiceContext, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            holdMode = true
            beginRecording()
            if (recordingStartedAt == null) holdMode = false // it could not start
        }
        else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }
    fun onHoldEnd(cancel: Boolean) {
        if (recordingStartedAt == null) { holdMode = false; return }
        val tooShort = recordedMs < 600
        when {
            cancel -> finishRecording(false)
            tooShort -> { finishRecording(false); Toast.makeText(voiceContext, "Hold the microphone to record, let go to send.", Toast.LENGTH_SHORT).show() }
            else -> finishRecording(true)
        }
    }
    LaunchedEffect(recordingStartedAt) {
        val t = recordingStartedAt ?: return@LaunchedEffect
        while (true) {
            recordedMs = SystemClock.elapsedRealtime() - t
            recordLevel = recorder.sample()
            if (recordedMs >= VoiceRecorder.MAX_MS) { finishRecording(true); break } // five minutes is the limit
            kotlinx.coroutines.delay(100)
        }
    }

    // ---- Opening a shared file: download once if needed, PDFs in the app's own viewer, everything else in the app that handles it.
    var openingFileId by remember { mutableStateOf<String?>(null) }
    var pdf by remember { mutableStateOf<Pair<java.io.File, FileAttachment>?>(null) }
    fun openFile(m: Message) {
        val att = m.file ?: return
        if (openingFileId != null) return
        openingFileId = m.id
        scope.launch {
            val local = FileOpener.ensureLocal(voiceContext, att, m.id)
            openingFileId = null
            when {
                local == null -> Toast.makeText(voiceContext, "Couldn't get the file. Check your connection and try again.", Toast.LENGTH_LONG).show()
                att.contentType == "application/pdf" -> pdf = local to att
                !FileOpener.open(voiceContext, local, att.contentType) -> Toast.makeText(voiceContext, "No app on this phone can open this kind of file.", Toast.LENGTH_LONG).show()
            }
        }
    }
    pdf?.let { (local, att) ->
        PdfViewerDialog(
            file = local, title = att.name, onClose = { pdf = null },
            onOpenElsewhere = {
                pdf = null
                if (!FileOpener.open(voiceContext, local, att.contentType)) Toast.makeText(voiceContext, "No app on this phone can open this file.", Toast.LENGTH_LONG).show()
            },
        )
    }

    // "At the bottom" = the newest message (index 0 of the reversed list) is in view, give or take a little.
    val atBottomState = remember { derivedStateOf { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset < 160 } }
    // Read while composing, i.e. before the list measures any new row: this is where the reader was when a message arrived.
    val wasAtBottom = atBottomState.value
    val showJump = !atBottomState.value
    var pendingNew by remember { mutableIntStateOf(0) }
    val counted = remember { HashSet<String>() }
    var anchorId by remember { mutableStateOf<String?>(null) }
    val newestId = s.messages.lastOrNull()?.id
    LaunchedEffect(newestId, s.loading) {
        if (s.loading) return@LaunchedEffect
        val previous = anchorId
        anchorId = newestId
        if (previous == null || newestId == previous) return@LaunchedEffect // first load, or nothing new at the end
        val fresh = newIncomingAfter(s.messages, previous, counted) ?: return@LaunchedEffect
        when {
            // Own message, or the reader is already at the bottom: keep following the conversation.
            s.messages.lastOrNull()?.isMine == true || wasAtBottom -> listState.animateScrollToItem(0)
            // Reading older messages: never jump. Count each new message once; read receipts stay with the existing logic.
            fresh.isNotEmpty() -> { if (counted.size > 200) counted.clear(); counted.addAll(fresh); pendingNew += fresh.size }
        }
    }
    LaunchedEffect(atBottomState.value) { if (atBottomState.value) pendingNew = 0 }
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
                    VibeIconButton(VibeIcons.Close, "Close search", viewModel::closeSearch)
                    VibeTextField(
                        value = s.query, onValueChange = viewModel::setQuery,
                        modifier = Modifier.weight(1f).padding(horizontal = Spacing.sm).focusRequester(searchFocus), placeholder = "Search in chat",
                    )
                    Text(
                        if (s.query.isBlank()) "" else if (s.matches.isEmpty()) "No results" else "${s.searchIndex + 1} of ${s.matches.size}",
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    IconButton(onClick = { viewModel.stepSearch(+1) }, enabled = s.matches.isNotEmpty()) { Icon(VibeIcons.ChevronUp, contentDescription = "Older match") }
                    IconButton(onClick = { viewModel.stepSearch(-1) }, enabled = s.matches.isNotEmpty()) { Icon(VibeIcons.ChevronDown, contentDescription = "Newer match") }
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
            navigationIcon = { VibeIconButton(VibeIcons.Back, "Back", onBack) },
            actions = {
                VibeIconButton(VibeIcons.Search, "Search in chat", viewModel::openSearch)
                var menuOpen by remember { mutableStateOf(false) }
                Box {
                    VibeIconButton(VibeIcons.More, "More options", { menuOpen = true })
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        if (headerAction != null) {
                            DropdownMenuItem(
                                text = { Text(if (chat?.isGroup == true) "Group info" else "View profile") },
                                leadingIcon = { Icon(VibeIcons.Info, contentDescription = null) },
                                onClick = { menuOpen = false; headerAction.invoke() },
                            )
                        }
                        if (chat != null) {
                            DropdownMenuItem(
                                text = { Text(if (chat.muted) "Unmute notifications" else "Mute notifications") },
                                leadingIcon = { Icon(VibeIcons.BellOff, contentDescription = null) },
                                onClick = { menuOpen = false; viewModel.toggleMute() },
                            )
                        }
                    }
                }
            },
            titleContent = if (chat == null) null else {
                {
                    Row(
                        Modifier.clickable(enabled = headerAction != null, role = Role.Button, onClickLabel = if (chat.isGroup) "Open group info" else "Open profile") { headerAction?.invoke() },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (chat.isGroup) GroupAvatar(s.group?.photoPath ?: chat.groupPhotoUrl, 40.dp, name = s.title)
                        else StudentAvatar(chat.title, size = 40.dp, accent = chat.id.hashCode(), online = chat.online, imagePath = chat.avatarUrl)
                        Column(Modifier.padding(start = Spacing.sm + 4.dp)) {
                            Text(s.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            // "Online", or when they were last active. Nothing at all (never "Offline") when they keep it private.
                            val status = when {
                                s.typing -> if (chat.isGroup) "Someone is typing…" else "typing…"
                                chat.isGroup -> (s.group?.members?.size ?: chat.memberCount.takeIf { it > 0 })?.let { "$it members" } ?: "Group chat"
                                chat.online -> "Online"
                                else -> activeLabel(false, chat.lastActiveMs, System.currentTimeMillis())
                            }
                            if (status != null) Text(
                                status,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (s.typing || chat.online) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            },
        )
        }
        if (s.offline) {
            Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }) {
                Text(
                    "You're offline. Messages may not send until you reconnect.",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs),
                )
            }
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
                    // Spacing is per message (tight inside a group, wider between groups), so no uniform gap here.
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                ) {
                    if (s.typing) item(key = "typing") { TypingBubble() }
                    items(rows, key = { it.key }) { row ->
                        when (row) {
                            is ChatRow.Day -> DayHeader(row.label)
                            is ChatRow.Msg -> {
                                val m = row.message
                                val pos = groupPosition(m, row.older, row.newer)
                                MessageItem(
                                    m = m,
                                    pos = pos,
                                    uploadProgress = if (m.status == MessageStatus.Sending) uploads.firstOrNull { it.tag == m.id }?.fraction else null,
                                    isGroup = chat?.isGroup == true,
                                    showSender = chat?.isGroup == true && !m.isMine && pos.isFirst,
                                    showAvatar = chat?.isGroup == true && !m.isMine && pos.isLast,
                                    showReceipts = s.showReceipts,
                                    highlighted = s.highlightId == m.id,
                                    onLongPress = { viewModel.openMenu(m) },
                                    onDoubleTap = { viewModel.react(m, Reaction.Heart) },
                                    onReply = { viewModel.startReply(m) },
                                    onReaction = { viewModel.react(m, it) },
                                    onJump = viewModel::jumpTo,
                                    onOpenPhoto = { viewModel.openViewer(it) },
                                    onOpenFile = { openFile(m) },
                                    fileBusy = openingFileId == m.id,
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
                // Fades and scales in; not AnimatedVisibility, which is ambiguous here (Box inside a Column).
                val reduced = rememberReducedMotion()
                val jumpAlpha by animateFloatAsState(if (showJump) 1f else 0f, tween(if (reduced) 0 else 160), label = "jump")
                if (jumpAlpha > 0.01f) {
                    Box(
                        Modifier.align(Alignment.BottomEnd).padding(Spacing.md)
                            .graphicsLayer { alpha = jumpAlpha; scaleX = 0.85f + 0.15f * jumpAlpha; scaleY = 0.85f + 0.15f * jumpAlpha },
                    ) {
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface, shadowElevation = 4.dp) {
                            IconButton(onClick = { pendingNew = 0; scope.launch { listState.animateScrollToItem(0) } }, modifier = Modifier.size(48.dp)) {
                                Icon(
                                    VibeIcons.ChevronDown,
                                    contentDescription = if (pendingNew > 0) "Scroll to latest message, $pendingNew new" else "Scroll to latest message",
                                )
                            }
                        }
                        if (pendingNew > 0) {
                            Surface(
                                shape = CircleShape, color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-4).dp),
                            ) {
                                Text(
                                    if (pendingNew > 99) "99+" else pendingNew.toString(),
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        if (!s.notFound) {
            Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 6.dp) {
                Column(Modifier.navigationBarsPadding()) {
                    s.failedVoice?.let {
                        Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.md), verticalAlignment = Alignment.CenterVertically) {
                            Text("Couldn't send the voice message.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                            TextButton(onClick = viewModel::retryFailedVoice) { Text("Retry") }
                            TextButton(onClick = viewModel::dismissFailedVoice) { Text("Dismiss") }
                        }
                    }
                    s.failedFile?.let { failed ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.md), verticalAlignment = Alignment.CenterVertically) {
                            Text("Couldn't send ${failed.file.name}.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            TextButton(onClick = viewModel::retryFailedFile) { Text("Retry") }
                            TextButton(onClick = viewModel::dismissFailedFile) { Text("Dismiss") }
                        }
                    }
                    s.failedPhotos?.let { batch ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.md), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (batch.paths.size == 1) "Couldn't send the photo." else "Couldn't send the photos.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                            TextButton(onClick = viewModel::retryFailedPhotos) { Text("Retry") }
                            TextButton(onClick = viewModel::dismissFailedPhotos) { Text("Dismiss") }
                        }
                    }
                    s.failedPhoto?.let {
                        Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.md), verticalAlignment = Alignment.CenterVertically) {
                            Text("Couldn't send the photo.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                            TextButton(onClick = viewModel::retryFailedPhoto) { Text("Retry") }
                            TextButton(onClick = viewModel::dismissFailedPhoto) { Text("Dismiss") }
                        }
                    }
                    if (s.undoMessage != null) UndoBar("Message deleted", viewModel::undoDelete)
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
                    } else if (recordingStartedAt != null && !holdMode) {
                        RecordingBar(recordedMs, onCancel = { finishRecording(false) }, onSend = { finishRecording(true) }, level = recordLevel)
                    } else {
                        MessageComposerCard(
                            s = s, showMic = showMic, focus = composerFocus,
                            onInput = viewModel::onInput, onPhoto = pickPhoto, onCamera = takePhoto, onFile = pickFile,
                            onAction = { if (showMic) onMic() else viewModel.send() },
                            holding = recordingStartedAt != null && holdMode, holdMs = recordedMs, holdLevel = recordLevel,
                            onHoldStart = ::onMicHold, onHoldEnd = ::onHoldEnd, onHoldLock = { holdMode = false },
                            onImeSend = { if (s.canSend) viewModel.send() },
                        )
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
            SheetRow(VibeIcons.Reply, "Reply", false) { viewModel.startReply(m) }
            if (canEditMessage(m)) SheetRow(VibeIcons.Edit, "Edit", false) { viewModel.startEdit(m) }
            SheetRow(VibeIcons.Send, "Forward", false) { viewModel.openForward(m) }
            if (s.canPin) {
                if (s.pinnedId == m.id) SheetRow(VibeIcons.Pin, "Unpin message", false) { viewModel.pin(null) }
                else SheetRow(VibeIcons.Pin, "Pin message", false) { viewModel.pin(m) }
            }
            if (m.text.isNotBlank()) {
                SheetRow(VibeIcons.Copy, "Copy text", false) { clipboard.setText(AnnotatedString(m.text)); viewModel.openMenu(null) }
            }
            if (m.isMine) SheetRow(VibeIcons.Delete, "Delete message", true, viewModel::deleteMessage)
        }
    }

    if (s.forwarding != null) ForwardSheet(s, viewModel::toggleForward, viewModel::confirmForward, viewModel::closeForward)
    s.viewerPath?.let { open ->
        // Swipe through every photo in the conversation, oldest first, starting at the one that was tapped.
        val all = remember(s.messages) { s.messages.flatMap { it.photos } }
        val start = all.indexOf(open)
        if (start >= 0) PhotoGalleryViewerDialog(all, start) { viewModel.openViewer(null) }
        else PhotoViewerDialog(open) { viewModel.openViewer(null) }
    }
}

/** Keeps a chosen-but-unsent file across the app being recreated (rotation); null is simply not saved. */
private val PickedFileSaver = Saver<PickedFile?, List<String>>(
    save = { f -> f?.let { listOf(it.path, it.name, it.sizeBytes.toString(), it.contentType) } },
    restore = { l -> PickedFile(l[0], l[1], l[2].toLong(), l[3]) },
)

/**
 * The message box: one rounded row with the photo button, the text field and the send/mic button. It is as tall as one
 * line of text needs (never a fixed height, so it follows the person's font and text size) and grows to six lines.
 */
@Composable
private fun MessageComposerCard(
    s: ConversationUiState,
    showMic: Boolean,
    focus: FocusRequester,
    onInput: (String) -> Unit,
    onPhoto: () -> Unit,
    onCamera: () -> Unit,
    onFile: () -> Unit,
    onAction: () -> Unit,
    onImeSend: () -> Unit,
    holding: Boolean = false,
    holdMs: Long = 0L,
    holdLevel: Float = 0f,
    onHoldStart: () -> Unit = {},
    onHoldEnd: (cancel: Boolean) -> Unit = {},
    onHoldLock: () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    val tooLong = s.input.length > MAX_MESSAGE
    val active = s.canSend || showMic
    val hint = when { s.editing != null -> "Edit message"; s.replyTo != null -> "Write a reply"; else -> "Message" }
    val haptic = LocalHapticFeedback.current
    val tapAction by rememberUpdatedState(onAction)
    val holdStart by rememberUpdatedState(onHoldStart)
    val holdEnd by rememberUpdatedState(onHoldEnd)
    val holdLock by rememberUpdatedState(onHoldLock)
    val micScale by animateFloatAsState(if (holding) 1.3f else 1f, label = "micScale")
    val dotScale by animateFloatAsState(1f + holdLevel.coerceIn(0f, 1f) * 0.9f, label = "holdLevel")
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = colors.surfaceContainerHigh,
        border = BorderStroke(1.dp, if (tooLong) colors.error else colors.outlineVariant),
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.sm, vertical = Spacing.sm),
    ) {
        // Buttons stay at the bottom edge while the field grows upwards.
        Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.xs, vertical = 2.dp), verticalAlignment = Alignment.Bottom) {
            var attachMenu by remember { mutableStateOf(false) }
            if (holding) Spacer(Modifier.size(48.dp)) else Box {
                IconButton(onClick = { attachMenu = true }, modifier = Modifier.size(48.dp)) {
                    Icon(VibeIcons.Add, contentDescription = "Attach a photo, camera picture or file", tint = colors.primary, modifier = Modifier.size(VibeIconSize.Action))
                }
                DropdownMenu(expanded = attachMenu, onDismissRequest = { attachMenu = false }) {
                    DropdownMenuItem(text = { Text("Photos") }, leadingIcon = { Icon(VibeIcons.Photo, contentDescription = null) }, onClick = { attachMenu = false; onPhoto() })
                    DropdownMenuItem(text = { Text("Camera") }, leadingIcon = { Icon(VibeIcons.Camera, contentDescription = null) }, onClick = { attachMenu = false; onCamera() })
                    DropdownMenuItem(text = { Text("File") }, leadingIcon = { Icon(VibeIcons.File, contentDescription = null) }, onClick = { attachMenu = false; onFile() })
                }
            }
            Box(Modifier.weight(1f).heightIn(min = 48.dp).padding(horizontal = Spacing.xs, vertical = 10.dp), contentAlignment = Alignment.CenterStart) {
                if (holding) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Box(Modifier.size(10.dp).graphicsLayer { scaleX = dotScale; scaleY = dotScale }.background(colors.error, CircleShape))
                        Text(formatDuration(holdMs.toInt()), style = MaterialTheme.typography.bodyLarge)
                        Text("‹ Slide to cancel   ↑ Lock", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    }
                } else {
                if (s.input.isEmpty()) {
                    Text(hint, style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                BasicTextField(
                    value = s.input,
                    onValueChange = onInput,
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
                    cursorBrush = SolidColor(colors.primary),
                    maxLines = 6,
                    // Settings -> Messages -> "Enter key sends": the keyboard shows a Send key instead of a new-line key.
                    keyboardOptions = if (s.enterToSend) KeyboardOptions(imeAction = ImeAction.Send) else KeyboardOptions.Default,
                    keyboardActions = KeyboardActions(onSend = { onImeSend() }),
                )
                }
            }
            if (tooLong) {
                Box(Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
                    Text("${s.input.length}/$MAX_MESSAGE", style = MaterialTheme.typography.labelSmall, color = colors.error, modifier = Modifier.padding(horizontal = Spacing.xs))
                }
            }
            Box(Modifier.size(48.dp).graphicsLayer { scaleX = micScale; scaleY = micScale }, contentAlignment = Alignment.Center) {
                Surface(
                    shape = CircleShape,
                    color = if (active) colors.primary else colors.surfaceContainerHighest,
                    contentColor = if (active) colors.onPrimary else colors.onSurfaceVariant,
                ) {
                    if (showMic) {
                        // Tap: start a recording that stays open (Send / Delete bar). Hold: record until you let go. A plain
                        // Box rather than a button so the hold is not swallowed; TalkBack still gets a "Start recording" action.
                        Box(
                            Modifier.size(44.dp)
                                .pointerInput(Unit) {
                                    micGestures(
                                        onTap = { tapAction() },
                                        onHoldStart = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); holdStart() },
                                        onHoldEnd = { cancel -> if (cancel) haptic.performHapticFeedback(HapticFeedbackType.LongPress); holdEnd(cancel) },
                                        onLock = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); holdLock() },
                                    )
                                }
                                .semantics(mergeDescendants = true) {
                                    role = Role.Button
                                    contentDescription = "Record a voice message"
                                    onClick(label = "Start recording") { tapAction(); true }
                                },
                            contentAlignment = Alignment.Center,
                        ) { Icon(VibeIcons.Mic, contentDescription = null, modifier = Modifier.size(VibeIconSize.Action)) }
                    } else {
                        IconButton(onClick = onAction, enabled = active, modifier = Modifier.size(44.dp)) {
                            Icon(
                                if (s.canSend) VibeIcons.SendFilled else VibeIcons.Send,
                                contentDescription = if (s.editing != null) "Save edit" else "Send message",
                                modifier = Modifier.size(VibeIconSize.Action),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The microphone button's touch handling. A quick tap calls [onTap]. Holding for the long-press time starts push-to-talk
 * ([onHoldStart]); then letting go ends it ([onHoldEnd] with cancel = false), sliding left ends it as a cancel, and
 * sliding up locks the recording open ([onLock]). Moving away before the long-press time does nothing.
 */
private suspend fun PointerInputScope.micGestures(onTap: () -> Unit, onHoldStart: () -> Unit, onHoldEnd: (cancel: Boolean) -> Unit, onLock: () -> Unit) {
    val cancelDistance = 72.dp.toPx()
    val lockDistance = 72.dp.toPx()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val tapped = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            var released = false
            while (true) {
                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull false
                if (change.changedToUpIgnoreConsumed()) { released = true; break }
                if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop * 3) return@withTimeoutOrNull false
            }
            released
        }
        when (tapped) {
            true -> onTap()
            false -> Unit
            null -> {
                onHoldStart()
                while (true) {
                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                    if (change == null) { onHoldEnd(true); break }
                    if (change.changedToUpIgnoreConsumed()) { onHoldEnd(false); break }
                    change.consume()
                    val dx = change.position.x - down.position.x
                    val dy = change.position.y - down.position.y
                    if (dx < -cancelDistance) { onHoldEnd(true); break }
                    if (dy < -lockDistance) { onLock(); break }
                }
            }
        }
    }
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
                    if (c.isGroup) GroupAvatar(null, 40.dp, name = c.title) else StudentAvatar(c.title, size = 40.dp, accent = c.id.hashCode())
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
            Icon(VibeIcons.Pin, contentDescription = null, tint = colors.primary, modifier = Modifier.size(18.dp))
            Column(Modifier.weight(1f).padding(horizontal = Spacing.sm + 4.dp, vertical = 6.dp)) {
                Text("Pinned message", style = MaterialTheme.typography.labelMedium, color = colors.primary)
                Text(replyPreview(m), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (canUnpin) IconButton(onClick = onUnpin) { Icon(VibeIcons.Close, contentDescription = "Unpin message") }
        }
    }
}

@Composable
private fun EditBar(m: Message, onCancel: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(start = Spacing.md, top = Spacing.sm, end = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
        Icon(VibeIcons.Edit, contentDescription = null, tint = colors.primary, modifier = Modifier.size(18.dp))
        Column(Modifier.weight(1f).padding(start = Spacing.sm + 4.dp)) {
            Text("Editing message", style = MaterialTheme.typography.labelLarge, color = colors.primary)
            Text(replyPreview(m), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onCancel) { Icon(VibeIcons.Close, contentDescription = "Cancel edit") }
    }
}

private val MentionRegex = Regex("@[A-Za-z0-9._]+")

/** Message text with @mentions bold and web addresses tappable. Uses only theme colors, so it follows the person's theme, font and text size. */
@Composable
private fun messageText(text: String, mine: Boolean): AnnotatedString {
    val primary = MaterialTheme.colorScheme.primary
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    val uriHandler = LocalUriHandler.current
    return remember(text, mine, primary, onPrimary, uriHandler) {
        val mentionStyle = if (mine) SpanStyle(fontWeight = FontWeight.Bold, textDecoration = TextDecoration.Underline) else SpanStyle(fontWeight = FontWeight.Bold, color = primary)
        val linkStyle = TextLinkStyles(SpanStyle(color = if (mine) onPrimary else primary, textDecoration = TextDecoration.Underline))
        val links = findLinks(text)
        // A mention that sits inside a link (https://x.com/@jane) belongs to the link.
        val mentions = MentionRegex.findAll(text).filter { m -> links.none { it.range.first <= m.range.last && m.range.first <= it.range.last } }.toList()
        buildAnnotatedString {
            var last = 0
            val pieces: List<Triple<Int, Int, LinkSpan?>> = links.map { Triple(it.range.first, it.range.last + 1, it) } + mentions.map { Triple(it.range.first, it.range.last + 1, null) }
            for ((start, end, link) in pieces.sortedBy { it.first }) {
                append(text.substring(last, start))
                if (link != null) {
                    // A failed open (no browser installed) is ignored rather than crashing the chat.
                    val tag = LinkAnnotation.Clickable(link.url, linkStyle) { runCatching { uriHandler.openUri(link.url) } }
                    withLink(tag) { append(text.substring(start, end)) }
                } else withStyle(mentionStyle) { append(text.substring(start, end)) }
                last = end
            }
            append(text.substring(last))
        }
    }
}

private fun Reaction.icon(): ImageVector = when (this) {
    Reaction.Heart -> VibeIcons.HeartFilled
    Reaction.Like -> VibeIcons.ThumbUp
    Reaction.Laugh -> VibeIcons.Laugh
    Reaction.Fire -> VibeIcons.Fire
    Reaction.Sad -> VibeIcons.Sad
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
        IconButton(onClick = onCancel) { Icon(VibeIcons.Close, contentDescription = "Cancel reply") }
    }
}

/** Drag a message to the right to reply (the long-press menu and a TalkBack action do the same). */
@Composable
private fun SwipeToReply(onReply: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    val maxPx = with(LocalDensity.current) { 72.dp.toPx() }
    val trigger = maxPx * 0.75f
    val haptics = LocalHapticFeedback.current
    Box(
        modifier.fillMaxWidth().draggable(
            orientation = Orientation.Horizontal,
            state = rememberDraggableState { delta -> scope.launch { offset.snapTo((offset.value + delta).coerceIn(0f, maxPx)) } },
            onDragStopped = {
                if (offset.value >= trigger) { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onReply() }
                offset.animateTo(0f, spring())
            },
        ),
    ) {
        Icon(
            VibeIcons.Reply, contentDescription = null, tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.align(Alignment.CenterStart).padding(start = Spacing.sm).alpha((offset.value / trigger).coerceIn(0f, 1f)),
        )
        Box(Modifier.offset { IntOffset(offset.value.roundToInt(), 0) }) { content() }
    }
}

@Composable
private fun MessageItem(
    m: Message, pos: GroupPosition, uploadProgress: Float?, isGroup: Boolean, showSender: Boolean, showAvatar: Boolean, showReceipts: Boolean, highlighted: Boolean,
    onLongPress: () -> Unit, onDoubleTap: () -> Unit, onReply: () -> Unit, onReaction: (Reaction) -> Unit, onJump: (String) -> Unit,
    onOpenPhoto: (String) -> Unit,
    onOpenFile: () -> Unit = {}, fileBusy: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val mine = m.isMine
    // About four fifths of the screen, so bubbles use the width of big phones and tablets without crowding small ones.
    val maxBubble = (LocalConfiguration.current.screenWidthDp * 0.8f).coerceIn(280f, 440f).dp
    // Tight inside a group, wider between groups.
    SwipeToReply(onReply, Modifier.padding(top = if (pos.joinsOlder) 2.dp else 8.dp)) {
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
                // The corners on the sender's side flatten where the bubble joins its neighbours; the last one keeps the "tail".
                val big = 20.dp; val small = 6.dp
                val shape = if (mine) RoundedCornerShape(topStart = big, topEnd = if (pos.joinsOlder) small else big, bottomEnd = small, bottomStart = big)
                else RoundedCornerShape(topStart = if (pos.joinsOlder) small else big, topEnd = big, bottomEnd = big, bottomStart = small)
                // A message that starts with photos lets them fill the bubble edge to edge. With no caption the time and ticks
                // sit on the photo itself (like a photo gallery), otherwise they stay under the text.
                val photosList = m.photos
                val mediaFirst = photosList.isNotEmpty() && m.voice == null && m.replyTo == null && !m.forwarded
                val mediaOnly = mediaFirst && m.text.isBlank() && m.linkPreview == null
                val showTick = mine && (pos.isLast || m.status == MessageStatus.Sending)
                Surface(
                    shape = shape,
                    color = if (mine) colors.primary else colors.surfaceContainerHigh,
                    contentColor = if (mine) colors.onPrimary else colors.onSurface,
                    modifier = Modifier.widthIn(max = maxBubble)
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
                    Column(
                        // Edge-to-edge: the bubble is as wide as its photos, and a caption or time below lines up with that width.
                        Modifier.then(if (mediaFirst) Modifier.width(IntrinsicSize.Max) else Modifier)
                            .padding(if (mediaFirst) PaddingValues(3.dp) else PaddingValues(horizontal = 14.dp, vertical = 8.dp)),
                    ) {
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
                        val photos = photosList
                        val uploading = m.status == MessageStatus.Sending
                        if (photos.isNotEmpty()) {
                            Box {
                                if (photos.size == 1) {
                                    ChatPhoto(
                                        photos[0], onOpen = { onOpenPhoto(photos[0]) }, knownRatio = m.imageRatio, sending = uploading, progress = uploadProgress,
                                        shape = if (mediaFirst) RoundedCornerShape(17.dp) else PostMediaShape, bordered = !mediaFirst,
                                    )
                                } else {
                                    // Several photos (at most four): a tidy grid of equal tiles, two to a row.
                                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                        photos.chunked(2).forEach { pair ->
                                            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                                pair.forEach { p -> ChatPhoto(p, onOpen = { onOpenPhoto(p) }, square = true, sending = uploading, shape = if (mediaFirst) RoundedCornerShape(12.dp) else PostMediaShape, bordered = !mediaFirst) }
                                            }
                                        }
                                    }
                                }
                                if (mediaOnly) {
                                    MessageMeta(
                                        m, showTick, showReceipts, tint = Color.White, strength = 1f,
                                        modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp)
                                            .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(10.dp)).padding(horizontal = 7.dp, vertical = 3.dp),
                                    )
                                }
                            }
                            if (!mediaFirst) Spacer(Modifier.height(4.dp))
                        }
                        m.file?.let { f ->
                            FileCard(f, sending = m.status == MessageStatus.Sending, progress = uploadProgress, busy = fileBusy, onOpen = onOpenFile, onLongPress = onLongPress, modifier = Modifier.padding(bottom = 6.dp))
                        }
                        m.voice?.let { v -> VoiceBubble(v, sending = m.status == MessageStatus.Sending, progress = uploadProgress) }
                        m.eventId?.let { id -> com.univibe.app.features.events.SharedEventCard(id) }
                        val tail: @Composable ColumnScope.() -> Unit = {
                            if (m.text.isNotBlank()) Text(messageText(m.text, mine), style = MaterialTheme.typography.bodyLarge)
                            m.linkPreview?.let { LinkPreviewCard(it, onLongPress = onLongPress, modifier = Modifier.padding(top = 6.dp)) }
                            MessageMeta(m, showTick, showReceipts, Modifier.align(Alignment.End))
                        }
                        if (mediaOnly) Unit
                        else if (mediaFirst) Column(Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 4.dp)) { tail() }
                        else tail()
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

/** The time (and "edited"), plus the delivery tick on your own last message. [tint] and [strength] let it sit on a photo in white. */
@Composable
private fun MessageMeta(m: Message, showTick: Boolean, showReceipts: Boolean, modifier: Modifier = Modifier, tint: Color = LocalContentColor.current, strength: Float = 0.8f) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(if (m.edited) "edited · ${formatClock(m.timeMillis)}" else formatClock(m.timeMillis), style = MaterialTheme.typography.labelSmall, color = tint, modifier = Modifier.alpha(strength))
        // One status tick per run of messages (on the last), unless a message is still sending.
        if (showTick) {
            Spacer(Modifier.width(4.dp))
            val seen = showReceipts && m.status == MessageStatus.Seen
            // Sent: one tick. Delivered or seen: two. Seen is also full strength, and TalkBack says which.
            val delivered = m.status == MessageStatus.Delivered || m.status == MessageStatus.Seen
            Icon(
                imageVector = when { m.status == MessageStatus.Sending -> VibeIcons.Clock; delivered -> VibeIcons.DoubleCheck; else -> VibeIcons.Check },
                contentDescription = when { m.status == MessageStatus.Sending -> "Sending"; seen -> "Seen"; delivered -> "Delivered"; else -> "Sent" },
                tint = tint,
                modifier = Modifier.size(14.dp).alpha(if (seen) 1f else strength),
            )
        }
    }
}

@Composable
private fun TypingBubble() {
    val reduced = rememberReducedMotion()
    val transition = rememberInfiniteTransition(label = "typing")
    Surface(
        shape = RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.semantics { contentDescription = "Typing"; liveRegion = LiveRegionMode.Polite },
    ) {
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
