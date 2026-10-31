package com.univibe.app.features.chats.stories

import com.univibe.app.ui.components.VerifiedName
import com.univibe.app.ui.components.withSentenceCaps
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.media.MediaPlayer
import android.net.Uri
import android.widget.Toast
import android.widget.VideoView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import com.univibe.app.ui.components.BurstIcon
import com.univibe.app.ui.components.LikeColor
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.univibe.app.core.keyedViewModel
import com.univibe.app.data.model.StoryItem
import com.univibe.app.data.model.StoryKind
import com.univibe.app.data.net.timeAgo
import com.univibe.app.ui.components.StudentAvatar
import com.univibe.app.ui.components.VibeIcons
import kotlinx.coroutines.delay
import kotlin.math.abs

/** Brighter than the light-theme brand teal so it stays readable on the always-dark viewer. */
private val ViewerTeal = Color(0xFF2DD4BF)
private const val PHOTO_MS = 5_000L
private const val FALLBACK_VIDEO_MS = 15_000L
/** A view counts once the Story has been on screen this long. */
private const val VIEW_AFTER_MS = 1_000L

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Full-screen Story viewer. Tap right/left for next/previous, hold to pause, swipe sideways for the next/previous
 * person, swipe down (or the close button) to leave. Everything the server decides (audience, blocks, expiry) is
 * enforced there; this screen only shows what it was given and moves on when a person has nothing left to show.
 */
@Composable
fun StoryViewerScreen(startAuthorId: String, onClose: () -> Unit) {
    val vm = keyedViewModel("story-viewer-$startAuthorId") { StoryViewerViewModel(startAuthorId) }
    val s by vm.state.collectAsStateWithLifecycle()
    val latest by rememberUpdatedState(s)
    val context = LocalContext.current
    val view = LocalView.current
    val focus = LocalFocusManager.current

    // The viewer is always dark, so the status bar icons must be light whatever the app theme is.
    DisposableEffect(Unit) {
        val window = context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val previous = controller?.isAppearanceLightStatusBars
        controller?.isAppearanceLightStatusBars = false
        onDispose { if (controller != null && previous != null) controller.isAppearanceLightStatusBars = previous }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.setBackgrounded(true) }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { vm.setBackgrounded(false) }
    LaunchedEffect(s.notice) { s.notice?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show(); vm.consumeNotice() } }
    LaunchedEffect(s.closed) { if (s.closed) onClose() }

    val item = s.item
    val author = s.author
    val isText = item?.kind == StoryKind.Text
    var ready by remember(item?.id, s.restartTick) { mutableStateOf(isText) }
    var buffering by remember(item?.id, s.restartTick) { mutableStateOf(false) }
    var videoMs by remember(item?.id) { mutableStateOf<Long?>(null) }

    val progress = remember(item?.id, s.restartTick) { Animatable(0f) }
    val total = if (item?.kind == StoryKind.Video) (videoMs ?: item.durationMs ?: FALLBACK_VIDEO_MS).coerceIn(1_000L, 60_000L) else PHOTO_MS
    val running = item != null && ready && !buffering && !s.paused && !s.mediaFailed

    LaunchedEffect(item?.id, s.restartTick, running, total) {
        if (running) {
            val remaining = ((1f - progress.value) * total).toLong().coerceAtLeast(1L)
            progress.animateTo(1f, tween(remaining.toInt(), easing = LinearEasing))
            vm.next()
        }
    }
    LaunchedEffect(item?.id, ready) {
        if (item != null && ready) { delay(VIEW_AFTER_MS); vm.markViewed(item.id) }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // Content
        if (item != null) {
            when (item.kind) {
                StoryKind.Photo -> if (item.mediaUrl != null) AsyncImage(
                    model = item.mediaUrl, contentDescription = item.caption ?: "Story photo from ${author?.authorName.orEmpty()}",
                    contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize(),
                    onSuccess = { ready = true }, onError = { vm.mediaFailed() },
                ) else LaunchedEffect(item.id) { vm.mediaFailed() }
                StoryKind.Video -> if (item.mediaUrl != null) androidx.compose.runtime.key(item.id, s.restartTick) {
                    StoryVideo(
                        url = item.mediaUrl, ready = ready, play = running,
                        onPrepared = { ms -> videoMs = ms; ready = true },
                        onBuffering = { buffering = it }, onFailed = vm::mediaFailed,
                    )
                } else LaunchedEffect(item.id) { vm.mediaFailed() }
                StoryKind.Text -> Box(
                    Modifier.fillMaxSize().background(backgroundBrush(item.background ?: "teal")).padding(horizontal = 32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        item.text.orEmpty(), color = Color.White, textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.headlineMedium,
                    )
                }
            }
        }

        // Loading, error and unavailable-media states
        if (s.loading || s.reelLoading) CircularProgressIndicator(color = Color.White, modifier = Modifier.align(Alignment.Center))
        if (s.error != null) Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(s.error.orEmpty(), color = Color.White, textAlign = TextAlign.Center)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = vm::load) { Text("Try again", color = ViewerTeal) }
                TextButton(onClick = vm::close) { Text("Close", color = Color.White) }
            }
        }
        if (s.mediaFailed) Text(
            "Couldn't load this story.\nTap to skip.", color = Color.White, textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.Center).padding(32.dp),
        )

        // Gesture layer: sits above the content, below the buttons.
        Box(
            Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = { vm.setHolding(true); try { tryAwaitRelease() } finally { vm.setHolding(false) } },
                        onTap = { offset ->
                            when {
                                latest.composing -> focus.clearFocus()
                                offset.x < size.width * 0.3f -> vm.previous()
                                else -> vm.next()
                            }
                        },
                    )
                }
                .pointerInput(Unit) {
                    var dx = 0f
                    var dy = 0f
                    detectDragGestures(
                        onDragStart = { dx = 0f; dy = 0f },
                        onDragEnd = {
                            when {
                                dy > 140.dp.toPx() && abs(dy) > abs(dx) -> vm.close()
                                abs(dx) > 96.dp.toPx() && abs(dx) > abs(dy) -> if (dx < 0) vm.nextAuthor() else vm.previousAuthor()
                            }
                        },
                    ) { change, amount -> change.consume(); dx += amount.x; dy += amount.y }
                }
                .semantics {
                    customActions = listOf(
                        CustomAccessibilityAction("Next story") { vm.next(); true },
                        CustomAccessibilityAction("Previous story") { vm.previous(); true },
                        CustomAccessibilityAction("Next person") { vm.nextAuthor(); true },
                    )
                },
        )

        // Top: progress segments, who it is, menu and close
        Column(
            Modifier.fillMaxWidth().align(Alignment.TopCenter)
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent)))
                .statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                s.items.indices.forEach { i ->
                    Segment(Modifier.weight(1f)) { when { i < s.itemIndex -> 1f; i == s.itemIndex -> progress.value; else -> 0f } }
                }
            }
            Spacer(Modifier.height(10.dp))
            if (author != null) Row(verticalAlignment = Alignment.CenterVertically) {
                StudentAvatar(name = author.authorName, size = 36.dp, imagePath = author.avatarUrl)
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    VerifiedName(
                        if (author.isMine) "Your story" else author.authorName, author.authorVerified && !author.isMine, color = Color.White,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    if (item != null) Text(
                        timeAgo(((System.currentTimeMillis() - item.createdAtMs) / 1000).coerceAtLeast(0)),
                        color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.labelSmall,
                    )
                }
                if (author.isMine && item != null) Box {
                    ViewerIconButton(VibeIcons.More, "More options") { vm.setMenu(true) }
                    DropdownMenu(expanded = s.menuOpen, onDismissRequest = { vm.setMenu(false) }) {
                        DropdownMenuItem(text = { Text("Delete story") }, onClick = vm::askDelete)
                    }
                }
                ViewerIconButton(VibeIcons.Close, "Close story", onClick = vm::close)
            } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                ViewerIconButton(VibeIcons.Close, "Close story", onClick = vm::close)
            }
        }

        // Bottom: caption and reply bar (never on my own Story)
        if (item != null) Column(
            Modifier.fillMaxWidth().align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.65f))))
                .navigationBarsPadding().imePadding().padding(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item.caption?.let {
                Text(it, color = Color.White, style = MaterialTheme.typography.bodyLarge, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 4.dp))
            }
            if (author?.isMine == false) ReplyBar(item, s.replyText, s.sending, vm)
        }
    }

    if (s.confirmDelete) AlertDialog(
        onDismissRequest = vm::cancelDelete,
        title = { Text("Delete this story?") },
        text = { Text("It disappears for everyone right away.") },
        confirmButton = { TextButton(onClick = vm::deleteCurrent) { Text("Delete") } },
        dismissButton = { TextButton(onClick = vm::cancelDelete) { Text("Cancel") } },
    )
}

@Composable
private fun ReplyBar(item: StoryItem, text: String, sending: Boolean, vm: StoryViewerViewModel) {
    val liked = item.myReaction == "heart"
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        BasicTextField(
            value = text, onValueChange = vm::setReply, enabled = !sending,
            textStyle = TextStyle(color = Color.White, fontSize = 16.sp), cursorBrush = SolidColor(Color.White), maxLines = 3,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send).withSentenceCaps(),
            keyboardActions = KeyboardActions(onSend = { vm.sendReply() }),
            modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                .border(1.dp, Color.White.copy(alpha = 0.7f), RoundedCornerShape(24.dp))
                .onFocusChanged { vm.setComposing(it.isFocused) }
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .semantics { contentDescription = "Reply to this story" },
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (text.isEmpty()) Text("Send message", color = Color.White.copy(alpha = 0.7f), fontSize = 16.sp)
                    inner()
                }
            },
        )
        if (text.isNotBlank()) ViewerIconButton(VibeIcons.SendFilled, "Send reply", ViewerTeal) { vm.sendReply() }
        // Same heart and burst as the feed. No clip here, so the burst can spread past the 48dp button.
        val heartTint = if (liked) LikeColor else Color.White
        val heartLabel = if (liked) "Remove your reaction" else "React with a heart"
        Box(
            Modifier.size(48.dp)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClickLabel = heartLabel, onClick = vm::toggleHeart)
                .semantics { contentDescription = heartLabel },
            contentAlignment = Alignment.Center,
        ) { BurstIcon(if (liked) VibeIcons.HeartFilled else VibeIcons.Heart, liked, heartTint, iconSize = 24.dp) }
    }
}

@Composable
private fun Segment(modifier: Modifier, fill: () -> Float) {
    Box(
        modifier.height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.3f))
            .drawBehind { drawRect(Color.White, size = Size(size.width * fill().coerceIn(0f, 1f), size.height)) },
    )
}

@Composable
private fun ViewerIconButton(icon: ImageVector, description: String, tint: Color = Color.White, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(CircleShape)
            .clickable(role = Role.Button, onClickLabel = description, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp)) }
}

/** One video Story: streams the short-lived link, plays only while [play] is true, and releases the player when it leaves the screen. */
@Composable
private fun StoryVideo(
    url: String, ready: Boolean, play: Boolean,
    onPrepared: (durationMs: Long) -> Unit, onBuffering: (Boolean) -> Unit, onFailed: () -> Unit,
) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            VideoView(context).apply {
                setVideoURI(Uri.parse(url))
                setOnPreparedListener { mp: MediaPlayer ->
                    mp.isLooping = false
                    mp.setOnInfoListener { _, what, _ ->
                        when (what) {
                            MediaPlayer.MEDIA_INFO_BUFFERING_START -> onBuffering(true)
                            MediaPlayer.MEDIA_INFO_BUFFERING_END -> onBuffering(false)
                        }
                        false
                    }
                    onPrepared(mp.duration.toLong())
                }
                setOnErrorListener { _, _, _ -> onFailed(); true }
            }
        },
        update = { v ->
            if (ready && play && !v.isPlaying) v.start()
            else if (!play && v.isPlaying) v.pause()
        },
        onRelease = { it.stopPlayback() },
    )
}
