package com.univibe.app.ui.components

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.univibe.app.data.media.CropGeometry
import com.univibe.app.data.media.CropHandle
import com.univibe.app.data.media.CropRect
import com.univibe.app.data.media.PhotoStore
import com.univibe.app.ui.theme.Spacing
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.roundToInt

/** A shape the crop window can be locked to. [aspect] is width / height; null means free, [original] keeps the photo's own shape. */
private class CropPreset(val label: String, val aspect: Float?, val original: Boolean = false)

private val CropPresets = listOf(
    CropPreset("Free", null),
    CropPreset("Original", null, original = true),
    CropPreset("1:1", 1f),
    CropPreset("4:5", 4f / 5f),
    CropPreset("5:8", 5f / 8f),
    CropPreset("16:9", 16f / 9f),
)

/**
 * Full-screen photo editor for a post: pick a shape (or crop freely), drag the corners and the window, rotate.
 * [onSaved] gets the path of the edited photo (the same path when nothing was changed); deleting the old file is the caller's job.
 * Text uses the theme's type styles only, so the user's font and font-size settings apply.
 */
@Composable
fun PhotoEditDialog(path: String, onDismiss: () -> Unit, onSaved: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    var preview by remember(path) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(path) { mutableStateOf(false) }
    var turns by remember(path) { mutableStateOf(0) }
    var saving by remember(path) { mutableStateOf(false) }

    LaunchedEffect(path) {
        val loaded = PhotoStore.preview(path)
        if (loaded == null) failed = true else preview = loaded
    }
    val shown = remember(preview, turns) { preview?.let { PhotoStore.rotated(it, turns) } }
    val image = remember(shown) { shown?.asImageBitmap() }
    var rect by remember(shown) { mutableStateOf(CropRect.Full) }
    var presetIndex by remember(shown) { mutableStateOf(0) }

    val imgW = shown?.width ?: 1
    val imgH = shown?.height ?: 1
    val preset = CropPresets[presetIndex]
    val aspect: Float? = if (preset.original) imgW.toFloat() / imgH else preset.aspect

    Dialog(
        onDismissRequest = { if (!saving) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(color = Color.Black, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss, enabled = !saving) {
                        Icon(VibeIcons.Close, contentDescription = "Cancel editing", tint = Color.White)
                    }
                    Text(
                        "Edit photo", style = MaterialTheme.typography.titleMedium, color = Color.White,
                        modifier = Modifier.weight(1f).padding(start = Spacing.sm),
                    )
                    VibeButton(
                        "Done",
                        onClick = {
                            saving = true
                            val chosen = rect
                            val turned = turns
                            scope.launch {
                                val result = PhotoStore.edit(context, path, chosen, turned)
                                saving = false
                                if (result == null) {
                                    Toast.makeText(context, "Couldn't save the edit. Your photo is unchanged.", Toast.LENGTH_SHORT).show()
                                } else onSaved(result)
                            }
                        },
                        enabled = image != null && !saving,
                        loading = saving,
                        compact = true,
                    )
                }

                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    when {
                        image != null -> CropCanvas(
                            image = image, rect = rect, aspect = aspect,
                            onRect = { rect = it },
                            modifier = Modifier.fillMaxSize(),
                        )
                        failed -> Text("This photo couldn't be opened.", style = MaterialTheme.typography.bodyMedium, color = Color.White)
                        else -> CircularProgressIndicator(color = colors.primary)
                    }
                }

                val ratio = CropGeometry.ratioOf(rect, imgW, imgH)
                Text(
                    when {
                        image == null -> ""
                        ratio < FEED_MIN_PHOTO_RATIO - 0.005f -> "Too tall for the feed, so the feed would trim the bottom. Try 5:8 to show it all."
                        ratio > FEED_MAX_PHOTO_RATIO + 0.005f -> "Too wide for the feed, so the feed would trim the sides. Try 16:9 to show it all."
                        else -> "This shape shows in full in the feed."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.75f),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.xs),
                )

                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Spacing.md, vertical = Spacing.xs),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CropPresets.forEachIndexed { index, p ->
                        PresetChip(p.label, selected = index == presetIndex, enabled = image != null && !saving) {
                            presetIndex = index
                            when {
                                p.original -> rect = CropRect.Full
                                p.aspect != null -> rect = CropGeometry.fit(p.aspect, imgW, imgH)
                                else -> Unit // Free keeps the window as it is
                            }
                        }
                    }
                }

                Row(
                    Modifier.fillMaxWidth().padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { turns = (turns + 1) % 4 }, enabled = image != null && !saving) {
                        Icon(VibeIcons.Rotate, contentDescription = "Rotate photo", tint = Color.White)
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        onClick = { turns = 0; rect = CropRect.Full; presetIndex = 0 },
                        enabled = image != null && !saving,
                    ) { Text("Reset", style = MaterialTheme.typography.labelLarge, color = Color.White) }
                }
            }
        }
    }
}

@Composable
private fun PresetChip(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = CircleShape,
        color = if (selected) colors.primary else Color.White.copy(alpha = 0.12f),
        contentColor = if (selected) colors.onPrimary else Color.White,
        modifier = Modifier.heightIn(min = 40.dp).clip(CircleShape)
            .clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = if (selected) "Crop shape $label, selected" else "Crop shape $label" },
    ) {
        Box(Modifier.padding(horizontal = Spacing.md).heightIn(min = 40.dp), contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** Where the photo sits inside the drawing area: as large as fits, centred, with [pad] of room around it for the handles. */
private class FitBox(val left: Float, val top: Float, val width: Float, val height: Float)

private fun fitBox(canvasW: Float, canvasH: Float, imgW: Int, imgH: Int, pad: Float): FitBox {
    val availW = (canvasW - 2 * pad).coerceAtLeast(1f)
    val availH = (canvasH - 2 * pad).coerceAtLeast(1f)
    val scale = min(availW / imgW, availH / imgH)
    val w = imgW * scale
    val h = imgH * scale
    return FitBox((canvasW - w) / 2f, (canvasH - h) / 2f, w, h)
}

@Composable
private fun CropCanvas(
    image: androidx.compose.ui.graphics.ImageBitmap,
    rect: CropRect,
    aspect: Float?,
    onRect: (CropRect) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val pad = with(density) { 20.dp.toPx() }
    val slop = with(density) { 40.dp.toPx() }
    val frameStroke = with(density) { 2.dp.toPx() }
    val gridStroke = with(density) { 1.dp.toPx() }
    val cornerStroke = with(density) { 4.dp.toPx() }
    val cornerLen = with(density) { 22.dp.toPx() }
    val currentRect by rememberUpdatedState(rect)
    val currentAspect by rememberUpdatedState(aspect)
    val currentOnRect by rememberUpdatedState(onRect)

    Canvas(
        modifier.pointerInput(image) {
            var active: CropHandle? = null
            detectDragGestures(
                onDragStart = { start ->
                    val f = fitBox(size.width.toFloat(), size.height.toFloat(), image.width, image.height, pad)
                    val r = currentRect
                    active = CropGeometry.pickHandle(
                        f.left + r.left * f.width, f.top + r.top * f.height,
                        f.left + r.right * f.width, f.top + r.bottom * f.height,
                        start.x, start.y, slop, currentAspect == null,
                    )
                },
                onDragEnd = { active = null },
                onDragCancel = { active = null },
                onDrag = { change, amount ->
                    val handle = active
                    if (handle != null) {
                        change.consume()
                        val f = fitBox(size.width.toFloat(), size.height.toFloat(), image.width, image.height, pad)
                        currentOnRect(
                            CropGeometry.drag(currentRect, handle, amount.x / f.width, amount.y / f.height, currentAspect, image.width, image.height),
                        )
                    }
                },
            )
        },
    ) {
        val f = fitBox(size.width, size.height, image.width, image.height, pad)
        drawImage(
            image,
            dstOffset = IntOffset(f.left.roundToInt(), f.top.roundToInt()),
            dstSize = IntSize(f.width.roundToInt(), f.height.roundToInt()),
        )
        val cl = f.left + rect.left * f.width
        val ct = f.top + rect.top * f.height
        val cr = f.left + rect.right * f.width
        val cb = f.top + rect.bottom * f.height
        val dim = Color.Black.copy(alpha = 0.6f)
        drawRect(dim, Offset(f.left, f.top), Size(f.width, ct - f.top))
        drawRect(dim, Offset(f.left, cb), Size(f.width, f.top + f.height - cb))
        drawRect(dim, Offset(f.left, ct), Size(cl - f.left, cb - ct))
        drawRect(dim, Offset(cr, ct), Size(f.left + f.width - cr, cb - ct))

        val grid = Color.White.copy(alpha = 0.35f)
        for (i in 1..2) {
            val x = cl + (cr - cl) * i / 3f
            val y = ct + (cb - ct) * i / 3f
            drawLine(grid, Offset(x, ct), Offset(x, cb), strokeWidth = gridStroke)
            drawLine(grid, Offset(cl, y), Offset(cr, y), strokeWidth = gridStroke)
        }
        drawRect(Color.White, Offset(cl, ct), Size(cr - cl, cb - ct), style = Stroke(width = frameStroke))

        val len = min(cornerLen, min(cr - cl, cb - ct) / 2f)
        fun corner(x: Float, y: Float, sx: Float, sy: Float) {
            drawLine(Color.White, Offset(x, y), Offset(x + sx * len, y), strokeWidth = cornerStroke)
            drawLine(Color.White, Offset(x, y), Offset(x, y + sy * len), strokeWidth = cornerStroke)
        }
        corner(cl, ct, 1f, 1f)
        corner(cr, ct, -1f, 1f)
        corner(cl, cb, 1f, -1f)
        corner(cr, cb, -1f, -1f)
        if (aspect == null) {
            // A free window can also be pulled from the middle of each edge.
            val mx = (cl + cr) / 2f
            val my = (ct + cb) / 2f
            drawLine(Color.White, Offset(mx - len / 2f, ct), Offset(mx + len / 2f, ct), strokeWidth = cornerStroke)
            drawLine(Color.White, Offset(mx - len / 2f, cb), Offset(mx + len / 2f, cb), strokeWidth = cornerStroke)
            drawLine(Color.White, Offset(cl, my - len / 2f), Offset(cl, my + len / 2f), strokeWidth = cornerStroke)
            drawLine(Color.White, Offset(cr, my - len / 2f), Offset(cr, my + len / 2f), strokeWidth = cornerStroke)
        }
    }
}
