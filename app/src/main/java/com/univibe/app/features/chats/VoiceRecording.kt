package com.univibe.app.features.chats

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.PlaybackParams
import android.os.Build
import android.os.SystemClock
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import com.univibe.app.ui.components.rememberReducedMotion
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
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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

/** A finished recording: a local .m4a file, how long it runs, and its waveform (0..100 bar heights). */
class Recording(val path: String, val durationMs: Int, val waveform: List<Int> = emptyList())

/**
 * Records one voice message to a private cache file (AAC in an .m4a container, which the server accepts as audio/mp4).
 * Nothing is recorded until [start] is called, and [start] is only called after the person tapped the microphone and the
 * microphone permission was granted.
 */
class VoiceRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L
    private var pausedAt: Long? = null
    private var pausedTotal = 0L
    private val peaks = ArrayList<Int>()

    val isRecording: Boolean get() = recorder != null
    val isPaused: Boolean get() = pausedAt != null

    /** How long has been recorded, not counting time spent paused. */
    fun elapsedMs(): Long {
        if (recorder == null) return 0L
        val end = pausedAt ?: SystemClock.elapsedRealtime()
        return (end - startedAt - pausedTotal).coerceAtLeast(0L)
    }

    /** Pauses while the app is in the background (Android gives background apps silence from the microphone). */
    fun pause() {
        val r = recorder ?: return
        if (pausedAt != null) return
        if (runCatching { r.pause() }.isSuccess) pausedAt = SystemClock.elapsedRealtime()
    }

    fun resume() {
        val r = recorder ?: return
        val since = pausedAt ?: return
        if (runCatching { r.resume() }.isSuccess) { pausedTotal += SystemClock.elapsedRealtime() - since; pausedAt = null }
    }

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
            // A few seconds above the app's own limit: the screen stops at MAX_MS and hands over a finished file. If the
            // recorder hit its limit first, stop() could throw and the whole recording would be thrown away.
            r.setMaxDuration(MAX_MS + 3_000)
            r.setOutputFile(out.absolutePath)
            r.prepare()
            r.start()
            recorder = r; file = out; startedAt = SystemClock.elapsedRealtime(); pausedAt = null; pausedTotal = 0L; peaks.clear()
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
        val ms = elapsedMs().toInt().coerceAtMost(MAX_MS)
        recorder = null; file = null; pausedAt = null
        val ok = runCatching { r.stop() }.isSuccess // throws when nothing was captured
        runCatching { r.release() }
        if (f == null) return null
        if (!ok || ms < MIN_MS || !f.isFile || f.length() == 0L) { f.delete(); return null }
        return Recording(f.absolutePath, ms, waveformFromAmplitudes(peaks))
    }

    /** Throws the recording away. */
    fun cancel() {
        val r = recorder ?: return
        recorder = null; pausedAt = null
        runCatching { r.stop() }; runCatching { r.release() }
        file?.delete(); file = null; peaks.clear()
    }

    /** Call about ten times a second while recording: remembers the current peak for the waveform and returns the level, 0..1. */
    fun sample(): Float {
        val r = recorder ?: return 0f
        if (pausedAt != null) return 0f
        val amp = runCatching { r.maxAmplitude }.getOrDefault(0).coerceIn(0, 32_767)
        peaks.add(amp)
        return kotlin.math.sqrt(amp / 32_767f)
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

    /** Called with the source of a message that has just played to its end, so the chat can start the next one. */
    var onFinished: ((String) -> Unit)? = null

    /** 1x, 1.5x or 2x; kept from one message to the next. */
    var speed by mutableFloatStateOf(1f)
        private set

    private var player: MediaPlayer? = null
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null

    /** Gives the player what it needs to share the phone's audio politely. Safe to call more than once. */
    fun init(context: Context) {
        if (audioManager == null) audioManager = context.applicationContext.getSystemService(AudioManager::class.java)
    }

    // A call, an alarm or another app's audio takes over: pause rather than talk over it.
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) pause()
    }

    /** True when playback may start. Without an audio manager (not set up yet) nothing is blocked. */
    private fun requestFocus(): Boolean {
        val am = audioManager ?: return true
        val request = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).setUsage(AudioAttributes.USAGE_MEDIA).build())
            .setOnAudioFocusChangeListener(focusListener)
            .build().also { focusRequest = it }
        return am.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        val request = focusRequest ?: return
        runCatching { audioManager?.abandonAudioFocusRequest(request) }
    }

    /** Pauses whatever is playing (the app went to the background, or something else took the audio). */
    fun pause() {
        player?.let { p -> if (isPlaying) { runCatching { p.pause() }; isPlaying = false } }
        abandonFocus()
    }

    fun toggle(src: String) {
        if (source == src) {
            player?.let { p -> if (p.isPlaying) pause() else startAtSpeed(p) }
            return
        }
        release()
        failed = false
        val p = MediaPlayer()
        try {
            p.setAudioAttributes(AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).setUsage(AudioAttributes.USAGE_MEDIA).build())
            p.setDataSource(src)
            p.setOnPreparedListener { startAtSpeed(it) }
            p.setOnCompletionListener { val done = source; release(); if (done != null) onFinished?.invoke(done) }
            p.setOnErrorListener { _, _, _ -> release(); failed = true; true }
            player = p; source = src; positionMs = 0
            p.prepareAsync()
        } catch (e: Exception) {
            runCatching { p.release() }; player = null; source = null; failed = true
        }
    }

    /** On Android 8+ giving a prepared player a non-zero speed also starts it, so this is how playback starts or resumes. */
    private fun startAtSpeed(p: MediaPlayer) {
        if (!requestFocus()) return // a call or another app has the audio right now
        runCatching { p.playbackParams = PlaybackParams().setSpeed(speed) }.onFailure { runCatching { p.start() } }
        isPlaying = true
    }

    fun cycleSpeed() {
        speed = when (speed) { 1f -> 1.5f; 1.5f -> 2f; else -> 1f }
        // While paused the new speed is applied on resume; applying it now would restart playback.
        player?.let { p -> if (isPlaying) runCatching { p.playbackParams = PlaybackParams().setSpeed(speed) } }
    }

    /** Jumps to [fraction] (0..1) of the message that is loaded. */
    fun seekTo(fraction: Float, durationMs: Int) {
        val p = player ?: return
        val total = runCatching { p.duration }.getOrDefault(0).takeIf { it > 0 } ?: durationMs
        val ms = (fraction.coerceIn(0f, 1f) * total).toInt()
        runCatching { p.seekTo(ms) }
        positionMs = ms
    }

    fun tick() { player?.let { if (isPlaying) positionMs = runCatching { it.currentPosition }.getOrDefault(positionMs) } }

    fun release() {
        player?.let { runCatching { it.release() } }
        player = null; source = null; isPlaying = false; positionMs = 0
        abandonFocus()
    }
}

fun formatDuration(ms: Int): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}

/**
 * The bubble content of a voice message: play or pause, a waveform that fills in as it plays (tap or drag it to jump
 * while this message is the one loaded), the time left, and the speed. Older messages without a waveform show even bars.
 */
@Composable
fun VoiceBubble(note: VoiceNote, sending: Boolean, progress: Float? = null) {
    val active = VoicePlayer.source == note.source
    val playing = active && VoicePlayer.isPlaying
    val played = if (active && note.durationMs > 0) (VoicePlayer.positionMs.toFloat() / note.durationMs).coerceIn(0f, 1f) else 0f
    val tint = LocalContentColor.current
    val bars = note.waveform.ifEmpty { List(WAVE_BARS) { 35 } }
    Row(
        Modifier.width(220.dp).semantics {
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
        WaveformBars(
            bars = bars, played = played, color = tint, seekable = active && !sending,
            onSeek = { VoicePlayer.seekTo(it, note.durationMs) },
            modifier = Modifier.weight(1f).height(32.dp).padding(horizontal = Spacing.sm),
        )
        Column(horizontalAlignment = Alignment.End) {
            Text(
                if (sending && progress != null) "${(progress * 100).toInt().coerceIn(0, 100)}%"
                else formatDuration(if (active && VoicePlayer.positionMs > 0) note.durationMs - VoicePlayer.positionMs else note.durationMs),
                style = MaterialTheme.typography.labelSmall, color = tint,
            )
            if (active && !sending) {
                val label = if (VoicePlayer.speed == 1f) "1x" else "${VoicePlayer.speed}x"
                Text(
                    label, style = MaterialTheme.typography.labelSmall, color = tint,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(tint.copy(alpha = 0.15f))
                        .clickable(role = Role.Button, onClickLabel = "Change playback speed") { VoicePlayer.cycleSpeed() }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}

/** The bars of a waveform; the part already played is full strength, the rest faded. Tap or drag to seek when [seekable]. */
@Composable
private fun WaveformBars(bars: List<Int>, played: Float, color: Color, seekable: Boolean, onSeek: (Float) -> Unit, modifier: Modifier = Modifier) {
    val seek by rememberUpdatedState(onSeek)
    val gestures = if (seekable) {
        Modifier
            .pointerInput(Unit) { detectTapGestures { o -> seek((o.x / size.width).coerceIn(0f, 1f)) } }
            .pointerInput(Unit) { detectHorizontalDragGestures { change, _ -> seek((change.position.x / size.width).coerceIn(0f, 1f)) } }
    } else Modifier
    Canvas(modifier.then(gestures)) {
        val n = bars.size
        if (n == 0) return@Canvas
        val slot = size.width / n
        val barWidth = slot * 0.6f
        val radius = CornerRadius(barWidth / 2)
        bars.forEachIndexed { i, v ->
            val h = (size.height * v / 100f).coerceAtLeast(barWidth)
            drawRoundRect(
                color = color.copy(alpha = if ((i + 0.5f) / n <= played) 1f else 0.35f),
                topLeft = Offset(i * slot + (slot - barWidth) / 2, (size.height - h) / 2),
                size = Size(barWidth, h),
                cornerRadius = radius,
            )
        }
    }
}

/** Shown instead of the message box while recording: cancel, a running timer (the dot pulses with your voice), and send. */
@Composable
fun RecordingBar(elapsedMs: Long, onCancel: () -> Unit, onSend: () -> Unit, onReview: () -> Unit, level: Float = 0f) {
    val colors = MaterialTheme.colorScheme
    val reduced = rememberReducedMotion()
    val pulse by animateFloatAsState(if (reduced) 1f else 1f + level.coerceIn(0f, 1f) * 0.9f, label = "recordingLevel")
    Row(Modifier.fillMaxWidth().padding(Spacing.sm), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        IconButton(onClick = onCancel, modifier = Modifier.size(52.dp)) {
            Icon(VibeIcons.Delete, contentDescription = "Discard recording", tint = colors.error)
        }
        Box(Modifier.size(10.dp).graphicsLayer { scaleX = pulse; scaleY = pulse }.background(colors.error, CircleShape))
        Text(
            "Recording  ${formatDuration(elapsedMs.toInt())}",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f).semantics { contentDescription = "Recording, ${formatDuration(elapsedMs.toInt())}" },
        )
        // Stops recording and shows the message to listen to before sending.
        IconButton(onClick = onReview, modifier = Modifier.size(52.dp)) {
            Icon(VibeIcons.Stop, contentDescription = "Stop recording and listen before sending", tint = colors.onSurfaceVariant)
        }
        Surface(shape = CircleShape, color = colors.primary, contentColor = colors.onPrimary) {
            IconButton(onClick = onSend, modifier = Modifier.size(52.dp)) { Icon(VibeIcons.SendFilled, contentDescription = "Send voice message") }
        }
    }
}

/**
 * Shown after you stop a recording: play it (with the waveform, seeking and speed of a normal voice message), then
 * delete it or send it.
 */
@Composable
fun VoiceDraftBar(draft: Recording, onDelete: () -> Unit, onSend: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(Spacing.sm), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        IconButton(onClick = onDelete, modifier = Modifier.size(52.dp)) {
            Icon(VibeIcons.Delete, contentDescription = "Discard recording", tint = colors.error)
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            CompositionLocalProvider(LocalContentColor provides colors.primary) {
                VoiceBubble(VoiceNote(draft.path, draft.durationMs, draft.waveform), sending = false)
            }
        }
        Surface(shape = CircleShape, color = colors.primary, contentColor = colors.onPrimary) {
            IconButton(onClick = onSend, modifier = Modifier.size(52.dp)) { Icon(VibeIcons.SendFilled, contentDescription = "Send voice message") }
        }
    }
}
