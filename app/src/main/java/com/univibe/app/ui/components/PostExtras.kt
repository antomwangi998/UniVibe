package com.univibe.app.ui.components

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.univibe.app.data.model.Poll
import com.univibe.app.data.model.Post
import com.univibe.app.data.model.PostAudience
import com.univibe.app.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * A poll inside a post card. With [onVote] the options are tappable until the student has voted; without it
 * (read-only lists such as Saved) results are always shown.
 */
@Composable
fun PostPoll(poll: Poll, onVote: ((Int) -> Unit)?, modifier: Modifier = Modifier) {
    val showResults = poll.myVote != null || onVote == null
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        poll.options.forEachIndexed { index, option ->
            val chosen = poll.myVote == index
            val share = if (poll.totalVotes == 0) 0f else option.votes.toFloat() / poll.totalVotes
            val colors = MaterialTheme.colorScheme
            val shape = RoundedCornerShape(14.dp)
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clip(shape)
                    .border(1.dp, if (chosen) colors.primary else colors.outlineVariant, shape)
                    .then(
                        if (!showResults && onVote != null) Modifier.clickable(role = Role.Button) { onVote(index) } else Modifier,
                    )
                    .semantics {
                        contentDescription = if (showResults) {
                            "${option.text}, ${(share * 100).roundToInt()} percent${if (chosen) ", your vote" else ""}"
                        } else "Vote for ${option.text}"
                    },
                contentAlignment = Alignment.CenterStart,
            ) {
                if (showResults) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(share.coerceIn(0f, 1f)).background(colors.primaryContainer.copy(alpha = 0.6f)))
                }
                Row(Modifier.padding(horizontal = Spacing.md, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(option.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    if (showResults) {
                        if (chosen) {
                            Icon(VibeIcons.CheckCircle, contentDescription = null, tint = colors.primary, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                        }
                        Text("${(share * 100).roundToInt()}%", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
        val total = poll.totalVotes
        Text(
            if (total == 1) "1 vote" else "$total votes",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private class VideoFrame(val poster: ImageBitmap?, val ratio: Float?)

/** Poster frame with a play button; tapping plays the video in place. Videos are local files in this build. */
@Composable
fun PostVideo(path: String, authorName: String, modifier: Modifier = Modifier) {
    var playing by remember(path) { mutableStateOf(false) }
    val remote = path.startsWith("https://")
    val frame by produceState<VideoFrame?>(null, path) {
        // A server video is streamed, so no poster frame is fetched up front (it would download the file).
        value = if (remote) null else withContext(Dispatchers.IO) {
            runCatching {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(path)
                    val bitmap: Bitmap? = retriever.getFrameAtTime(0)
                    val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toFloatOrNull()
                    val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toFloatOrNull()
                    val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                    val ratio = if (w != null && h != null && w > 0f && h > 0f) (if (rotation == 90 || rotation == 270) h / w else w / h) else null
                    VideoFrame(bitmap?.asImageBitmap(), ratio)
                } finally {
                    retriever.release()
                }
            }.getOrNull()
        }
    }
    val poster = frame?.poster
    // Keep the video's own shape (portrait stays portrait), within sane limits, so nothing is stretched or cut off.
    val ratio = (frame?.ratio ?: (16f / 9f)).coerceIn(0.62f, 2f)
    Box(
        modifier.fillMaxWidth().animateContentSize().aspectRatio(ratio).clip(PostMediaShape).background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        if (playing) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    VideoView(context).apply {
                        setMediaController(MediaController(context).also { it.setAnchorView(this) })
                        if (remote) setVideoURI(Uri.parse(path)) else setVideoPath(path)
                        setOnPreparedListener { start() }
                        setOnErrorListener { _, _, _ -> playing = false; true }
                    }
                },
                onRelease = { it.stopPlayback() },
            )
        } else {
            poster?.let {
                Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            Box(
                Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f))
                    .clickable(role = Role.Button, onClickLabel = "Play video") { playing = true }
                    .semantics { contentDescription = "Play video from $authorName" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(32.dp))
            }
        }
    }
}

/**
 * Opens the system share sheet with text only (no media, no private data). Only campus-wide posts may leave the app;
 * returns false for followers-only and community posts so the caller can explain why.
 */
fun sharePost(context: Context, post: Post): Boolean {
    if (post.audience != PostAudience.Campus) return false
    val body = buildString {
        append("${post.author.name} (${post.author.handle}) on UniVibe")
        if (post.text.isNotBlank()) append(":\n\n${post.text.take(280)}")
    }
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, body)
    }
    context.startActivity(Intent.createChooser(send, "Share post").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    return true
}
