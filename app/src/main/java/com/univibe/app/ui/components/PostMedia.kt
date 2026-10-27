package com.univibe.app.ui.components

import android.util.LruCache
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.runtime.State

/** Corner radius shared by every piece of post media so photos, galleries and video match. */
val PostMediaShape = RoundedCornerShape(16.dp)

private const val DEFAULT_RATIO = 4f / 3f   // reserved until the real size is known
/** Tallest a feed photo may be (about 5:8); taller ones are trimmed on purpose. */
const val FEED_MIN_PHOTO_RATIO = 0.62f
/** Widest a feed photo may be. */
const val FEED_MAX_PHOTO_RATIO = 2.0f

/** True when the feed would cut part of a photo of this width / height (the cropper warns about it). */
fun isTrimmedInFeed(ratio: Float): Boolean = ratio < FEED_MIN_PHOTO_RATIO - 0.005f || ratio > FEED_MAX_PHOTO_RATIO + 0.005f

private const val MIN_RATIO = FEED_MIN_PHOTO_RATIO
private const val MAX_RATIO = FEED_MAX_PHOTO_RATIO

/** Tap opens the photo; with [onDoubleTap] a double tap does that instead (a single tap then waits a moment to be sure). */
@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.photoClick(onClick: () -> Unit, onDoubleTap: (() -> Unit)?): Modifier =
    if (onDoubleTap == null) clickable(role = Role.Button, onClickLabel = "View photo", onClick = onClick)
    else combinedClickable(role = Role.Button, onClickLabel = "View photo", onClick = onClick, onDoubleClick = onDoubleTap)

/** Remembers each photo's real width/height so scrolling back never re-measures it or makes the list jump. */
internal object PhotoAspects {
    private val cache = LruCache<String, Float>(400)
    private fun key(path: String) = path.substringBefore('?')
    fun get(path: String): Float? = cache.get(key(path))
    fun put(path: String, ratio: Float) { cache.put(key(path), ratio) }
}

/**
 * A single feed photo shown at its own proportions: landscape stays landscape, portrait stays portrait.
 * Never stretched. Extremely tall or wide photos are shown in a capped frame, cropped from the top, with a
 * "Full photo" tag so it is clear that tapping opens the whole image.
 */
@Composable
fun AdaptivePhoto(
    path: String,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** Width / height from the server, when it has it: the right space is reserved before the photo loads. */
    knownRatio: Float? = null,
    onDoubleTap: (() -> Unit)? = null,
) {
    var ratio by remember(path) { mutableStateOf(PhotoAspects.get(path) ?: knownRatio) }
    var failed by remember(path) { mutableStateOf(false) }
    val real = ratio
    val shown = (real ?: DEFAULT_RATIO).coerceIn(MIN_RATIO, MAX_RATIO)
    val capped = real != null && (real < MIN_RATIO || real > MAX_RATIO)
    val colors = MaterialTheme.colorScheme
    Box(
        modifier
            .fillMaxWidth()
            .animateContentSize(tween(180))
            .aspectRatio(shown)
            .clip(PostMediaShape)
            .background(colors.surfaceContainerHigh)
            .border(0.75.dp, colors.outlineVariant.copy(alpha = 0.6f), PostMediaShape)
            .photoClick(onClick, onDoubleTap),
    ) {
        AsyncImage(
            model = rememberPhotoModel(path),
            contentDescription = contentDescription,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            alignment = if (capped) Alignment.TopCenter else Alignment.Center,
            onState = { state ->
                when (state) {
                    is AsyncImagePainter.State.Success -> {
                        failed = false
                        val d = state.result.drawable
                        if (d.intrinsicWidth > 0 && d.intrinsicHeight > 0) {
                            val r = d.intrinsicWidth.toFloat() / d.intrinsicHeight
                            PhotoAspects.put(path, r)
                            ratio = r
                        }
                    }
                    is AsyncImagePainter.State.Error -> failed = true
                    else -> Unit
                }
            },
        )
        if (real == null || failed) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(VibeIcons.Photo, contentDescription = null, tint = colors.onSurfaceVariant.copy(alpha = 0.6f), modifier = Modifier.size(32.dp))
                if (failed) Text("Couldn't load photo", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            }
        }
        if (capped) MediaTag("Full photo", Modifier.align(Alignment.BottomStart).padding(10.dp))
    }
}

@Composable
private fun MediaTag(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = Color.White,
        modifier = modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/**
 * Photos of one post. One photo uses [AdaptivePhoto]; two to four use a tidy grid that fills the width
 * (cells are intentional crops, tapping any of them opens the full photos). More than four show "+N" on the last cell.
 */
@Composable
fun PostGallery(
    paths: List<String>,
    authorName: String,
    onOpen: (Int) -> Unit,
    modifier: Modifier = Modifier,
    ratios: List<Float?> = emptyList(),
    onDoubleTap: (() -> Unit)? = null,
) {
    if (paths.isEmpty()) return
    fun description(i: Int) = if (paths.size == 1) "Photo attached to $authorName's post" else "Photo ${i + 1} of ${paths.size} attached to $authorName's post"
    if (paths.size == 1) {
        AdaptivePhoto(paths[0], description(0), onClick = { onOpen(0) }, modifier = modifier, knownRatio = ratios.firstOrNull(), onDoubleTap = onDoubleTap)
        return
    }
    val shown = paths.take(4)
    val extra = paths.size - shown.size
    val gap = 2.dp
    val colors = MaterialTheme.colorScheme
    Row(
        modifier
            .fillMaxWidth()
            .aspectRatio(if (shown.size == 2) 1.5f else 4f / 3f)
            .clip(PostMediaShape)
            .border(0.75.dp, colors.outlineVariant.copy(alpha = 0.6f), PostMediaShape),
        horizontalArrangement = Arrangement.spacedBy(gap),
    ) {
        @Composable
        fun cell(i: Int, m: Modifier) = GalleryCell(shown[i], description(i), { onOpen(i) }, m, if (i == 3 && extra > 0) "+$extra" else null, onDoubleTap)
        when (shown.size) {
            2 -> { cell(0, Modifier.weight(1f).fillMaxHeight()); cell(1, Modifier.weight(1f).fillMaxHeight()) }
            3 -> {
                cell(0, Modifier.weight(1f).fillMaxHeight())
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(gap)) {
                    cell(1, Modifier.weight(1f).fillMaxWidth()); cell(2, Modifier.weight(1f).fillMaxWidth())
                }
            }
            else -> {
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(gap)) {
                    cell(0, Modifier.weight(1f).fillMaxWidth()); cell(1, Modifier.weight(1f).fillMaxWidth())
                }
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(gap)) {
                    cell(2, Modifier.weight(1f).fillMaxWidth()); cell(3, Modifier.weight(1f).fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun GalleryCell(path: String, description: String, onClick: () -> Unit, modifier: Modifier, overlay: String?, onDoubleTap: (() -> Unit)? = null) {
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh).photoClick(onClick, onDoubleTap)) {
        PhotoImage(path, contentDescription = description, modifier = Modifier.fillMaxSize())
        if (overlay != null) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
                Text(overlay, style = MaterialTheme.typography.headlineSmall, color = Color.White, textAlign = TextAlign.Center)
            }
        }
    }
}

/** Full-screen viewer for all photos of a post: swipe between them, pinch or double-tap to zoom. */
/** 0 -> 1 as a photo viewer opens: it fades in while the photo grows slightly into place. Instant with reduced motion. */
@Composable
internal fun rememberViewerOpen(): State<Float> {
    val reduced = rememberReducedMotion()
    val progress = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) { if (!reduced) progress.animateTo(1f, tween(240, easing = FastOutSlowInEasing)) }
    return progress.asState()
}

@Composable
fun PhotoGalleryViewerDialog(paths: List<String>, startIndex: Int, onDismiss: () -> Unit) {
    if (paths.isEmpty()) return
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val pager = rememberPagerState(initialPage = startIndex.coerceIn(0, paths.lastIndex)) { paths.size }
        var zoomed by remember { mutableStateOf(false) }
        val open = rememberViewerOpen()
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = open.value }.background(Color.Black)) {
            HorizontalPager(
                pager, Modifier.fillMaxSize().graphicsLayer { val sc = 0.9f + 0.1f * open.value; scaleX = sc; scaleY = sc },
                userScrollEnabled = !zoomed, key = { paths[it] },
            ) { page ->
                ZoomablePhoto(paths[page], active = pager.currentPage == page, onZoomed = { zoomed = it })
            }
            IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(8.dp)) {
                Icon(VibeIcons.Close, contentDescription = "Close photos", tint = Color.White)
            }
            if (paths.size > 1) {
                Text(
                    "${pager.currentPage + 1} / ${paths.size}",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White,
                    modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(16.dp)
                        .clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.5f)).padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun ZoomablePhoto(path: String, active: Boolean, onZoomed: (Boolean) -> Unit) {
    var scale by remember(path) { mutableStateOf(1f) }
    var offset by remember(path) { mutableStateOf(Offset.Zero) }
    var box by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(active) { if (!active) { scale = 1f; offset = Offset.Zero } }
    fun clamp(o: Offset, s: Float): Offset {
        val maxX = (box.width * (s - 1f) / 2f).coerceAtLeast(0f)
        val maxY = (box.height * (s - 1f) / 2f).coerceAtLeast(0f)
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }
    Box(
        Modifier.fillMaxSize().onSizeChanged { box = it }
            .pointerInput(path) {
                detectTapGestures(onDoubleTap = {
                    scale = if (scale > 1.05f) 1f else 2.5f
                    offset = clamp(offset, scale)
                    onZoomed(scale > 1.05f)
                })
            }
            .pointerInput(path) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 5f)
                    offset = if (scale <= 1.01f) Offset.Zero else clamp(offset + pan, scale)
                    onZoomed(scale > 1.05f)
                }
            },
    ) {
        PhotoImage(
            path, contentDescription = "Photo, full screen",
            modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y },
            contentScale = ContentScale.Fit,
        )
    }
}
