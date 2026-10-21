package com.univibe.app.features.chats.stories

import android.media.MediaPlayer
import android.net.Uri
import android.widget.Toast
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.univibe.app.core.keyedViewModel
import com.univibe.app.data.repository.STORY_BACKGROUNDS
import com.univibe.app.data.repository.STORY_CAPTION_MAX
import com.univibe.app.data.repository.STORY_VIDEO_MAX_MS
import com.univibe.app.data.repository.STORY_TEXT_MAX
import com.univibe.app.ui.components.VibeButton
import com.univibe.app.ui.components.VibeButtonStyle
import com.univibe.app.ui.components.VibeIconButton
import com.univibe.app.ui.components.VibeIcons
import com.univibe.app.ui.components.VibeTextField
import com.univibe.app.ui.components.VibeTopBar
import com.univibe.app.ui.components.rememberCameraPicker
import com.univibe.app.ui.components.rememberPhotoPicker
import com.univibe.app.ui.components.rememberVideoCameraPicker
import com.univibe.app.ui.components.rememberVideoPicker
import com.univibe.app.ui.theme.Spacing
import java.io.File

internal fun backgroundBrush(name: String): Brush = when (name) {
    "indigo" -> Brush.verticalGradient(listOf(Color(0xFF4F46E5), Color(0xFF312E81)))
    "rose" -> Brush.verticalGradient(listOf(Color(0xFFF43F5E), Color(0xFF9F1239)))
    "amber" -> Brush.verticalGradient(listOf(Color(0xFFF59E0B), Color(0xFFB45309)))
    "slate" -> Brush.verticalGradient(listOf(Color(0xFF475569), Color(0xFF0F172A)))
    else -> Brush.verticalGradient(listOf(Color(0xFF14B8A6), Color(0xFF0F766E)))
}

/** Add to your story: shoot or choose a photo (with a caption), or write a text Story. Choose who can see it, then share. */
@Composable
fun CreateStoryScreen(onBack: () -> Unit, onPublished: () -> Unit) {
    val vm = keyedViewModel("create-story") { CreateStoryViewModel() }
    val s by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme

    LaunchedEffect(s.notice) { s.notice?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show(); vm.consumeNotice() } }
    LaunchedEffect(s.published) { if (s.published) onPublished() }

    val takePhoto = rememberCameraPicker("story") { vm.setPhoto(it) }
    val choosePhoto = rememberPhotoPicker("story") { vm.setPhoto(it) }
    // The camera app is told to stop a little before the server's limit, so a recording is never refused for being too long.
    val recordVideo = rememberVideoCameraPicker("story", STORY_VIDEO_MAX_MS / 1000 - 1) { vm.setVideo(it) }
    val chooseVideo = rememberVideoPicker("story") { vm.setVideo(it) }

    Column(Modifier.fillMaxSize().imePadding().navigationBarsPadding()) {
        VibeTopBar(
            "Add to story", compactTitle = true,
            navigationIcon = { VibeIconButton(VibeIcons.Back, "Back", onBack) },
            actions = { VibeButton(if (s.failure != null) "Retry" else "Share", vm::publish, enabled = s.canPublish, loading = s.publishing, compact = true) },
        )
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            // Preview: 9:16 card, the same shape viewers will see.
            Box(
                Modifier.fillMaxWidth().heightIn(min = 320.dp, max = 460.dp).clip(RoundedCornerShape(20.dp))
                    .background(if (s.hasMedia) SolidColor(Color.Black) else backgroundBrush(s.background), RoundedCornerShape(20.dp)),
                contentAlignment = Alignment.Center,
            ) {
                val photo = s.photoPath
                val video = s.videoPath
                if (photo != null) {
                    AsyncImage(
                        model = File(photo), contentDescription = "Your story photo",
                        contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize(),
                    )
                } else if (video != null) {
                    var muted by remember(video) { mutableStateOf(true) }
                    VideoPreview(video, muted, Modifier.fillMaxSize())
                    // Autoplays silently and loops; tap to hear it.
                    Box(
                        Modifier.fillMaxSize().clickable(role = Role.Switch, onClickLabel = if (muted) "Turn sound on" else "Turn sound off") { muted = !muted },
                    )
                    Text(
                        (s.videoDurationMs ?: 0).let { "%d:%02d".format(it / 60_000, it / 1000 % 60) } + if (muted) " · sound off" else " · sound on",
                        color = Color.White, style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.align(Alignment.TopStart).padding(12.dp)
                            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                } else {
                    BasicTextField(
                        value = s.text, onValueChange = vm::setText,
                        textStyle = TextStyle(color = Color.White, textAlign = TextAlign.Center, fontSize = MaterialTheme.typography.headlineSmall.fontSize, fontWeight = MaterialTheme.typography.headlineSmall.fontWeight),
                        cursorBrush = SolidColor(Color.White),
                        modifier = Modifier.fillMaxWidth().padding(Spacing.lg),
                        decorationBox = { inner ->
                            Box(contentAlignment = Alignment.Center) {
                                if (s.text.isEmpty()) Text("Type something…", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                                inner()
                            }
                        },
                    )
                }
            }

            if (s.hasMedia) {
                VibeTextField(
                    s.caption, vm::setCaption, placeholder = "Add a caption", singleLine = false, maxLines = 3,
                    supportingText = "${s.caption.length}/$STORY_CAPTION_MAX",
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                    STORY_BACKGROUNDS.forEach { name ->
                        Box(
                            Modifier.size(32.dp).clip(CircleShape).background(backgroundBrush(name), CircleShape)
                                .border(if (name == s.background) 3.dp else 0.dp, colors.onSurface, CircleShape)
                                .clickable(role = Role.RadioButton, onClickLabel = "Use $name background") { vm.setBackground(name) },
                        )
                    }
                    Text("${s.text.length}/$STORY_TEXT_MAX", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, modifier = Modifier.padding(start = Spacing.sm))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                VibeButton("Take photo", takePhoto, style = VibeButtonStyle.Secondary, compact = true, enabled = !s.publishing, leadingIcon = VibeIcons.Camera)
                VibeButton("Photo gallery", choosePhoto, style = VibeButtonStyle.Secondary, compact = true, enabled = !s.publishing)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                VibeButton("Record video", recordVideo, style = VibeButtonStyle.Secondary, compact = true, enabled = !s.publishing, leadingIcon = VibeIcons.Camera)
                VibeButton("Video gallery", chooseVideo, style = VibeButtonStyle.Secondary, compact = true, enabled = !s.publishing)
            }
            if (s.hasMedia) VibeButton("Text story instead", vm::clearMedia, style = VibeButtonStyle.Secondary, compact = true, enabled = !s.publishing)

            // Progress while sending, with a way out; then a plain message and a retry if it did not work.
            if (s.publishing) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                val progress = s.uploadProgress
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (progress != null) "Uploading ${(progress * 100).toInt()}%" else "Posting your story…",
                        style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f),
                    )
                    if (progress != null) TextButton(onClick = vm::cancelUpload) { Text("Cancel") }
                }
                if (progress != null) Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(colors.surfaceVariant)) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).background(colors.primary))
                } else LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            s.failure?.let { message ->
                Surface(shape = RoundedCornerShape(12.dp), color = colors.errorContainer) {
                    Row(Modifier.fillMaxWidth().padding(start = Spacing.md, end = Spacing.xs, top = Spacing.xs, bottom = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                        Text(message, color = colors.onErrorContainer, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = vm::publish) { Text("Retry") }
                    }
                }
            }

            Text("Who can see it", style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = Modifier.padding(bottom = Spacing.md)) {
                FilterChip(selected = s.audience == "campus", onClick = { vm.setAudience("campus") }, label = { Text("Campus") })
                FilterChip(selected = s.audience == "followers", onClick = { vm.setAudience("followers") }, label = { Text("Followers") })
            }
        }
    }
}

/** Silent, looping autoplay of the chosen video. Releases the player when it leaves the screen. */
@Composable
private fun VideoPreview(path: String, muted: Boolean, modifier: Modifier = Modifier) {
    var player by remember(path) { mutableStateOf<MediaPlayer?>(null) }
    AndroidView(
        modifier = modifier,
        factory = { context ->
            VideoView(context).apply {
                setVideoURI(Uri.fromFile(File(path)))
                setOnPreparedListener { mp ->
                    mp.isLooping = true
                    player = mp
                    mp.setVolume(if (muted) 0f else 1f, if (muted) 0f else 1f)
                    start()
                }
            }
        },
        update = { player?.let { mp -> mp.setVolume(if (muted) 0f else 1f, if (muted) 0f else 1f) } },
        onRelease = { player = null; it.stopPlayback() },
    )
}
