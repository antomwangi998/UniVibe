@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.univibe.app.features.stickers

import android.provider.Settings
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.univibe.app.data.model.StickerRef
import com.univibe.app.ui.components.rememberPhotoModel
import com.univibe.app.ui.theme.Spacing

/** False when the person has switched system animations off: stickers then show their first frame. */
@Composable
private fun rememberAnimationsOn(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f }.getOrDefault(true)
    }
}

/**
 * A sticker's picture. Lists and grids pass [still] = true: they show the small still thumbnail, so a screen of animated
 * stickers never decodes dozens of animations at once on a low-end phone. A chat bubble and the preview play the animation.
 */
@Composable
fun StickerImage(sticker: StickerRef, modifier: Modifier = Modifier, still: Boolean = false, dimmed: Boolean = false) {
    val animate = !still && sticker.animated && rememberAnimationsOn()
    val link = (if (animate) sticker.url else sticker.thumbUrl) ?: sticker.thumbUrl ?: sticker.url
    val description = sticker.label?.let { "Sticker: $it" } ?: "Sticker"
    if (link == null) {
        Box(modifier)
        return
    }
    AsyncImage(
        model = rememberPhotoModel(link),
        contentDescription = description,
        modifier = modifier.alpha(if (dimmed) 0.45f else 1f),
        contentScale = ContentScale.Fit,
    )
}

/**
 * A sticker message: the picture on its own, no bubble. Gives way to a quiet placeholder when the sticker was removed.
 * [meta] draws the time and tick under it (the chat screen owns that look).
 */
@Composable
fun StickerMessage(
    sticker: StickerRef,
    highlighted: Boolean,
    onLongPress: () -> Unit,
    onDoubleTap: () -> Unit,
    meta: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, horizontalAlignment = Alignment.End) {
        val press = Modifier.combinedClickable(onClick = {}, onLongClick = onLongPress, onDoubleClick = onDoubleTap)
        if (!sticker.available) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = press.semantics { contentDescription = "This sticker is no longer available" },
            ) {
                Text(
                    "Sticker unavailable", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
                )
            }
        } else {
            Box(
                Modifier.width(150.dp).aspectRatio(sticker.ratio.coerceIn(0.5f, 2f)).then(press)
                    .then(if (highlighted) Modifier.alpha(0.85f) else Modifier),
            ) { StickerImage(sticker, Modifier.fillMaxSize()) }
        }
        Box(Modifier.padding(horizontal = Spacing.xs, vertical = 2.dp)) { meta() }
    }
}
