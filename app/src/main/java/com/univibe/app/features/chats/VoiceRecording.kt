package com.univibe.app.features.chats

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.univibe.app.data.model.VoiceNote
import com.univibe.app.ui.components.VibeIcons
import com.univibe.app.ui.theme.Spacing
import java.io.File

/** A finished recording: a local .m4a file and how long it runs. */
class Recording(val path: String, val durationMs: Int)

/**
 * Records one voice message to a private cache file (AAC in an .m4a container, which the server accepts as audio/mp4).
 * Nothing is recorded until [start] is called, and [start] is only called after the person tapped the microphone and the
 * microphone permission was granted.
 */
class VoiceRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L

    val isRecording: Boolean get() = recorder != null

    fun start(): Boolean {
        cancel()
        val out = File(context.cacheDir, "voice_${System.currentTimeMillis()}.m4a")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        return try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(64_000)
            r.setAudioSamplingRate(44_100)
            r.setMaxDuration(MAX_MS)
            r.setOutputFile(out.absolutePath)
            r.prepare()
            r.start()
            recorder = r; file = out; startedAt = SystemClock.elapsedRealtime()
            true
        } catch (e: Exception) {
            runCatching { r.release() }; out.delete()
            false
        }
    }

    /** Stops and returns the recording, or null (and deletes the file) if it was too short to be a message. */
    fun stop(): Recording? {
        val r = recorder ?: return null
        val f = file
        val ms = (SystemClock.elapsedRealtime() - startedAt).toInt().coerceAtMost(MAX_MS)
        recorder = null; file = null
        val ok = runCatching { r.stop() }.isSuccess // throws when nothing was captured
        runCatching { r.release() }
        if (f == null) return null
        if (!ok || ms < MIN_MS || !f.isFile || f.length() == 0L) { f.delete(); return null }
        return Recording(f.absolutePath, ms)
    }

    /** Throws the recording away. */
    fun cancel() {
        val r = recorder ?: return
        recorder = null
        runCatching { r.stop() }; runCatching { r.release() }
        file?.delete(); file = null
    }

    companion object {
        const val MAX_MS = 5 * 60 * 1000
        const val MIN_MS = 1_000
    }
}

/** Plays one voice message at a time; starting another stops the first. State is read by the bubbles. */
object VoicePlayer {
    /** The source that is loaded (playing or paused), or null. */
    var source by mutableStateOf<String?>(null)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    var positionMs by mutableIntStateOf(0)
        private set
    var failed by mutableStateOf(false)

    private var player: MediaPlayer? = null

    fun toggle(src: String) {
        if (source == src) {
            player?.let { p -> if (p.isPlaying) { p.pause(); isPlaying = false } else { p.start(); isPlaying = true } }
            return
        }
        release()
        failed = false
        val p = MediaPlayer()
        try {
            p.setAudioAttributes(AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).setUsage(AudioAttributes.USAGE_MEDIA).build())
            p.setDataSource(src)
            p.setOnPreparedListener { it.start(); isPlaying = true }
            p.setOnCompletionListener { release() }
            p.setOnErrorListener { _, _, _ -> release(); failed = true; true }
            player = p; source = src; positionMs = 0
            p.prepareAsync()
        } catch (e: Exception) {
            runCatching { p.release() }; player = null; source = null; failed = true
        }
    }

    fun tick() { player?.let { if (isPlaying) positionMs = runCatching { it.currentPosition }.getOrDefault(positionMs) } }

    fun release() {
        player?.let { runCatching { it.release() } }
        player = null; source = null; isPlaying = false; positionMs = 0
    }
}

fun formatDuration(ms: Int): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}

/** The bubble content of a voice message: play or pause, how far along, and the length. */
@Composable
fun VoiceBubble(note: VoiceNote, sending: Boolean, progress: Float? = null) {
    val active = VoicePlayer.source == note.source
    val playing = active && VoicePlayer.isPlaying
    val progress = if (active && note.durationMs > 0) (VoicePlayer.positionMs.toFloat() / note.durationMs).coerceIn(0f, 1f) else 0f
    val tint = LocalContentColor.current
    Row(
        Modifier.width(210.dp).semantics {
            contentDescription = if (sending && progress != null) "Sending voice message, ${(progress * 100).toInt().coerceIn(0, 100)} percent" else "Voice message, ${formatDuration(note.durationMs)}"
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            if (sending && progress != null) CircularProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.size(26.dp), strokeWidth = 2.5.dp, color = tint, trackColor = tint.copy(alpha = 0.25f))
            else if (sending) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = tint)
            else IconButton(onClick = { VoicePlayer.toggle(note.source) }, modifier = Modifier.size(44.dp)) {
                Icon(if (playing) VibeIcons.Pause else VibeIcons.Play, contentDescription = if (playing) "Pause voice message" else "Play voice message", tint = tint)
            }
        }
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.weight(1f).padding(horizontal = Spacing.sm).clip(CircleShape),
            color = tint, trackColor = tint.copy(alpha = 0.25f),
        )
        Text(
            if (sending && progress != null) "${(progress * 100).toInt().coerceIn(0, 100)}%"
            else formatDuration(if (active && VoicePlayer.positionMs > 0) note.durationMs - VoicePlayer.positionMs else note.durationMs),
            style = MaterialTheme.typography.labelSmall, color = tint,
        )
    }
}

/** Shown instead of the message box while recording: cancel, a running timer, and send. */
@Composable
fun RecordingBar(elapsedMs: Long, onCancel: () -> Unit, onSend: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(Spacing.sm), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        IconButton(onClick = onCancel, modifier = Modifier.size(52.dp)) {
            Icon(VibeIcons.Delete, contentDescription = "Discard recording", tint = colors.error)
        }
        Box(Modifier.size(10.dp).background(colors.error, CircleShape))
        Text(
            "Recording  ${formatDuration(elapsedMs.toInt())}",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f).semantics { contentDescription = "Recording, ${formatDuration(elapsedMs.toInt())}" },
        )
        Surface(shape = CircleShape, color = colors.primary, contentColor = colors.onPrimary) {
            IconButton(onClick = onSend, modifier = Modifier.size(52.dp)) { Icon(VibeIcons.SendFilled, contentDescription = "Send voice message") }
        }
    }
}
