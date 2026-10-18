package com.univibe.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter

private const val MIN_RATIO = 0.6f   // very tall photos are cropped in the bubble; the full-screen viewer shows all of it
private const val MAX_RATIO = 1.8f
private const val DEFAULT_RATIO = 1f // only until the real size is known (the server usually sends it, so this is rare)

/**
 * A photo inside a chat bubble. Same frame, corner radius and aspect-ratio cache as feed photos ([PhotoAspects],
 * [PostMediaShape]), sized for a bubble: portrait stays portrait, landscape stays landscape, within sensible limits.
 * [knownRatio] (from the server) reserves the right shape before loading. [square] makes a fixed tile for multi-photo
 * messages. [sending] dims the photo with a spinner while it uploads. A failed load shows a tap-to-retry state.
 */
@Composable
fun ChatPhoto(
    path: String,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    knownRatio: Float? = null,
    square: Boolean = false,
    tileWidth: Dp = 118.dp,
    sending: Boolean = false,
    /** 0..1 while the photo uploads; null when unknown (a plain spinner is shown). */
    progress: Float? = null,
) {
    val colors = MaterialTheme.colorScheme
    var ratio by remember(path) { mutableStateOf(PhotoAspects.get(path) ?: knownRatio) }
    var failed by remember(path) { mutableStateOf(false) }
    var loaded by remember(path) { mutableStateOf(false) }
    var attempt by remember(path) { mutableIntStateOf(0) }
    val model = rememberPhotoModel(path)
    val shown = if (square) 1f else (ratio ?: DEFAULT_RATIO).coerceIn(MIN_RATIO, MAX_RATIO)
    val width = if (square) tileWidth else if (shown < 0.9f) 200.dp else 240.dp
    Box(
        modifier
            .width(width)
            .aspectRatio(shown)
            .clip(PostMediaShape)
            .background(colors.surfaceContainerHighest)
            .border(0.75.dp, colors.outlineVariant.copy(alpha = 0.6f), PostMediaShape)
            .clickable(role = Role.Button, onClickLabel = if (failed) "Retry loading photo" else "View photo") {
                if (failed) { failed = false; attempt++ } else onOpen()
            },
        contentAlignment = Alignment.Center,
    ) {
        key(attempt) {
            AsyncImage(
                model = model,
                contentDescription = "Photo in chat",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                alignment = if (!square && ratio?.let { it < MIN_RATIO } == true) Alignment.TopCenter else Alignment.Center,
                onState = { state ->
                    when (state) {
                        is AsyncImagePainter.State.Success -> {
                            failed = false; loaded = true
                            val d = state.result.drawable
                            if (d.intrinsicWidth > 0 && d.intrinsicHeight > 0) {
                                val r = d.intrinsicWidth.toFloat() / d.intrinsicHeight
                                PhotoAspects.put(path, r); ratio = r
                            }
                        }
                        is AsyncImagePainter.State.Error -> failed = true
                        else -> Unit
                    }
                },
            )
        }
        if (failed) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(12.dp)) {
                Icon(VibeIcons.Photo, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(28.dp))
                Text("Couldn't load photo. Tap to retry.", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
            }
        } else if (!loaded) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = colors.onSurfaceVariant)
        }
        if (sending) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
                if (progress != null) {
                    val percent = (progress * 100).toInt().coerceIn(0, 100)
                    Box(Modifier.semantics { contentDescription = "Uploading photo, $percent percent" }, contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.size(52.dp), strokeWidth = 3.dp, color = Color.White, trackColor = Color.White.copy(alpha = 0.3f))
                        Text("$percent%", style = MaterialTheme.typography.labelMedium, color = Color.White)
                    }
                } else CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp, color = Color.White)
            }
        }
    }
}
