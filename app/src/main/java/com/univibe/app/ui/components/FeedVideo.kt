package com.univibe.app.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.ConnectivityManager
import android.net.Uri
import android.view.LayoutInflater
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.univibe.app.R
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * Decides which feed video plays. Every [PostVideo] reports how much of it is on screen; the one that is most visible
 * (and at least [START_AT] visible) plays, all others stay still. Only one video ever plays at a time, so the phone
 * decodes one stream, not one per post. Main thread only.
 */
object FeedAutoplay {
    private const val START_AT = 0.6f      // a video starts once this much of it is on screen
    private const val KEEP_UNTIL = 0.35f   // and keeps playing until it drops below this, so it does not flicker
    private const val SWITCH_MARGIN = 0.15f

    /** False when Data Saver is on: videos then play only when the person taps them. */
    var allowAutoStart = true

    private val fractions = HashMap<String, Float>()
    private val _active = MutableStateFlow<String?>(null)
    val active: StateFlow<String?> = _active

    /** Autoplay starts silent; tapping the speaker turns sound on for every video until you turn it off. */
    val muted = MutableStateFlow(true)

    /** Videos that failed to load, so we stop retrying them in a loop. Cleared when the person taps to retry. */
    val failed = MutableStateFlow<Set<String>>(emptySet())

    fun report(key: String, fraction: Float) {
        if (fraction < 0.02f) fractions.remove(key) else fractions[key] = fraction
        recompute()
    }

    fun forget(key: String) {
        fractions.remove(key)
        recompute()
    }

    /** The person tapped play on a video that is not yet the most visible one. */
    fun claim(key: String) { _active.value = key }

    private fun recompute() {
        val current = _active.value
        val currentFraction = current?.let { fractions[it] } ?: 0f
        val best = fractions.maxByOrNull { it.value }
        _active.value = when {
            current != null && currentFraction >= KEEP_UNTIL && (best == null || best.key == current || best.value < currentFraction + SWITCH_MARGIN) -> current
            best != null && allowAutoStart && best.value >= START_AT -> best.key
            else -> null
        }
    }
}

/** One ExoPlayer for the whole feed, with a disk cache for server videos. Created on first use. */
object FeedVideoPlayer {
    private const val CACHE_BYTES = 150L * 1024 * 1024

    private var player: ExoPlayer? = null
    private var cache: SimpleCache? = null
    var currentPath: String? = null
        private set

    fun get(context: Context): ExoPlayer = player ?: build(context.applicationContext).also { player = it }

    private fun build(app: Context): ExoPlayer {
        val loadControl = DefaultLoadControl.Builder()
            // Start after ~0.5s of video instead of the default 2.5s, but keep a few seconds ahead so playback does not stall.
            .setBufferDurationsMs(2_500, 15_000, 500, 1_500)
            .build()
        return ExoPlayer.Builder(app)
            .setLoadControl(loadControl)
            .setHandleAudioBecomingNoisy(true)
            .build()
            .apply {
                repeatMode = Player.REPEAT_MODE_ONE
                volume = 0f
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        currentPath?.let { bad -> FeedAutoplay.failed.value = FeedAutoplay.failed.value + bad }
                        currentPath = null
                    }
                })
            }
    }

    private fun remoteCache(app: Context): SimpleCache = cache ?: SimpleCache(
        File(app.cacheDir, "feed_video"),
        LeastRecentlyUsedCacheEvictor(CACHE_BYTES),
        StandaloneDatabaseProvider(app),
    ).also { cache = it }

    /** Loads [path] unless it is already the loaded video. Server videos go through the disk cache. */
    fun load(context: Context, path: String) {
        val p = get(context)
        if (currentPath == path && p.playbackState != Player.STATE_IDLE) return
        val app = context.applicationContext
        val remote = path.startsWith("http")
        val upstream = DefaultDataSource.Factory(app, DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true))
        val factory = if (remote) {
            CacheDataSource.Factory().setCache(remoteCache(app)).setUpstreamDataSourceFactory(upstream)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        } else upstream
        val uri = if (remote) Uri.parse(path) else Uri.fromFile(File(path))
        p.setMediaSource(ProgressiveMediaSource.Factory(factory).createMediaSource(MediaItem.fromUri(uri)))
        p.prepare()
        currentPath = path
    }

    fun setMuted(context: Context, muted: Boolean) {
        val p = get(context)
        p.volume = if (muted) 0f else 1f
        // Silent video must not take audio focus away from the person's music; sound video should.
        p.setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
            !muted,
        )
    }

    fun pause() { player?.pause() }
}

private class VideoFrame(val poster: ImageBitmap?, val ratio: Float?)

/** Autoplay is skipped when Android's Data Saver is on; the person can still tap play. */
private fun autoplayAllowed(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
    return cm.restrictBackgroundStatus != ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
}

/**
 * A feed video. It plays by itself, silently, while it is the most visible video on screen and stops when you scroll
 * past. Tap to pause or resume; tap the speaker to hear it. Videos from the server are streamed and cached.
 */
@Composable
fun PostVideo(path: String, authorName: String, modifier: Modifier = Modifier, knownRatio: Float? = null) {
    val context = LocalContext.current
    val key = remember(path) { "$path#${UUID.randomUUID()}" }
    val remote = path.startsWith("http")
    val windowHeight = LocalView.current.height.toFloat()

    val active by FeedAutoplay.active.collectAsState()
    val muted by FeedAutoplay.muted.collectAsState()
    val failedPaths by FeedAutoplay.failed.collectAsState()
    var manualPause by remember(path) { mutableStateOf(false) }
    remember { autoplayAllowed(context).also { FeedAutoplay.allowAutoStart = it } }

    val failed = path in failedPaths
    val isActive = active == key && !failed
    // Scrolling away clears a manual pause, so the video starts again next time it comes into view.
    LaunchedEffect(isActive) { if (!isActive) manualPause = false }

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
    val ratio = (frame?.ratio ?: knownRatio ?: (16f / 9f)).coerceIn(0.62f, 2f)

    // Report how much of this video is on screen (measured against the screen for very tall videos).
    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(ratio)
            .clip(PostMediaShape)
            .background(Color.Black)
            .onGloballyPositioned { c ->
                if (!c.isAttached) return@onGloballyPositioned
                val b = c.boundsInWindow()
                val h = b.height
                val visible = (minOf(b.bottom, windowHeight) - maxOf(b.top, 0f)).coerceAtLeast(0f)
                val denominator = minOf(h, windowHeight * 0.75f).coerceAtLeast(1f)
                FeedAutoplay.report(key, if (h <= 0f) 0f else (visible / denominator).coerceIn(0f, 1f))
            },
        contentAlignment = Alignment.Center,
    ) {
        DisposableEffect(key) { onDispose { FeedAutoplay.forget(key) } }

        if (isActive) {
            LaunchedEffect(path) { FeedVideoPlayer.load(context, path) }
            LaunchedEffect(muted) { FeedVideoPlayer.setMuted(context, muted) }
            LaunchedEffect(manualPause) { FeedVideoPlayer.get(context).playWhenReady = !manualPause }
            // Stop when this video stops being the active one, or the app goes to the background.
            DisposableEffect(path) { onDispose { if (FeedVideoPlayer.currentPath == path) FeedVideoPlayer.pause() } }
            val lifecycle = LocalLifecycleOwner.current.lifecycle
            DisposableEffect(lifecycle, path) {
                val observer = LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_STOP -> FeedVideoPlayer.pause()
                        Lifecycle.Event.ON_START -> if (!manualPause) FeedVideoPlayer.get(context).playWhenReady = true
                        else -> Unit
                    }
                }
                lifecycle.addObserver(observer)
                onDispose { lifecycle.removeObserver(observer) }
            }

            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx -> (LayoutInflater.from(ctx).inflate(R.layout.feed_player_view, null) as PlayerView) },
                update = { it.player = FeedVideoPlayer.get(context) },
                onRelease = { it.player = null },
            )
            Box(Modifier.fillMaxSize().clickable(role = Role.Button, onClickLabel = "Pause or play") { manualPause = !manualPause })
            if (manualPause) PlayBadge(authorName) { manualPause = false }
            SoundToggle(muted, Modifier.align(Alignment.BottomEnd).padding(10.dp)) { FeedAutoplay.muted.value = !muted }
        } else {
            poster?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            PlayBadge(authorName) {
                if (failed) FeedAutoplay.failed.value = FeedAutoplay.failed.value - path
                FeedAutoplay.claim(key)
            }
        }
    }
}

@Composable
private fun PlayBadge(authorName: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(role = Role.Button, onClickLabel = "Play video", onClick = onClick)
            .semantics { contentDescription = "Play video from $authorName" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(VibeIcons.Play, contentDescription = null, tint = Color.White, modifier = Modifier.size(32.dp))
    }
}

@Composable
private fun SoundToggle(muted: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(role = Role.Button, onClickLabel = if (muted) "Turn sound on" else "Turn sound off", onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (muted) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp,
            contentDescription = if (muted) "Sound off" else "Sound on",
            tint = Color.White,
            modifier = Modifier.size(20.dp),
        )
    }
}
