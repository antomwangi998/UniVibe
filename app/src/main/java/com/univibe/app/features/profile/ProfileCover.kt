package com.univibe.app.features.profile

import com.univibe.app.ui.components.VibeIcons
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.univibe.app.ui.components.PhotoImage
import com.univibe.app.ui.theme.Spacing

private val CoverBodyHeight = 150.dp
private val CoverCurveLift = 22.dp
private val DefaultAvatarRing = 116.dp

/** Appends the cover's curved lower edge (right to left): the sides hang low and the middle lifts to cradle the avatar. */
private fun Path.bottomCurve(w: Float, h: Float, lift: Float) {
    cubicTo(w * 0.74f, h, w * 0.66f, h - lift, w * 0.5f, h - lift)
    cubicTo(w * 0.34f, h - lift, w * 0.26f, h, 0f, h)
}

/** One real Shape for the cover (no stacked boxes): flat top, smooth curved bottom. */
private class CurvedBottomShape(private val lift: Dp) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val l = with(density) { lift.toPx() }
        return Outline.Generic(Path().apply {
            moveTo(0f, 0f); lineTo(size.width, 0f); lineTo(size.width, size.height)
            bottomCurve(size.width, size.height, l)
            close()
        })
    }
}

/**
 * Full-bleed profile cover: runs under the status bar, curved lower edge with a thin turquoise rim, a deep-teal gradient with a
 * faint campus skyline (or the student's own cover photo under a teal overlay), icon buttons on top and the avatar
 * overlapping the bottom edge.
 *
 * The avatar hangs [avatarSize]/2 below the cover without taking layout space, so put a Spacer of about that height under this
 * composable. [leading] sits top-left, [actions] top-right. Use [CoverIconButton] inside them.
 */
@Composable
fun ProfileCover(
    accent: Int,
    coverPath: String?,
    modifier: Modifier = Modifier,
    leading: @Composable RowScope.() -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    avatarSize: Dp = DefaultAvatarRing,
    avatar: @Composable () -> Unit,
) {
    val tokens = rememberProfileTokens()
    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val base = tokens.primary
    val gradient = remember(tokens) {
        if (tokens.dark) Brush.linearGradient(listOf(Color(0xFF02181B), Color(0xFF053C40), Color(0xFF0A7F7C)))
        else Brush.linearGradient(listOf(lerp(base, Color.Black, 0.22f), base, lerp(base, Color.White, 0.30f)))
    }
    val shape = remember { CurvedBottomShape(CoverCurveLift) }

    Box(modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(CoverBodyHeight + statusBar)
                .clip(shape)
                .background(gradient)
                .drawBehind {
                    // Soft light from the top-right.
                    drawRect(
                        Brush.radialGradient(
                            listOf(Color.White.copy(alpha = if (tokens.dark) 0.16f else 0.30f), Color.Transparent),
                            center = Offset(size.width * 0.85f, size.height * 0.05f),
                            radius = size.width * 0.75f,
                        ),
                    )
                    if (coverPath == null) drawCampusSkyline(tokens.dark)
                }
                .drawWithContent {
                    drawContent()
                    // Thin turquoise rim along the curved edge (drawn 4dp wide; the clip leaves the inner 2dp).
                    val rim = Path().apply { moveTo(size.width, size.height); bottomCurve(size.width, size.height, CoverCurveLift.toPx()) }
                    drawPath(rim, tokens.primary.copy(alpha = 0.85f), style = Stroke(width = 4.dp.toPx()))
                },
        ) {
            if (coverPath != null) {
                PhotoImage(coverPath, contentDescription = "Cover photo", modifier = Modifier.fillMaxSize())
                // Dark teal overlay: the photo sits in the interface, and the white icons stay legible.
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(listOf(Color(0xFF021E20).copy(alpha = 0.55f), Color(0xFF053C40).copy(alpha = 0.35f), Color(0xFF021E20).copy(alpha = 0.60f))),
                    ),
                )
            }
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = ProfileHorizontalPadding, vertical = Spacing.sm),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, content = leading)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm), content = actions)
            }
        }
        Box(Modifier.align(Alignment.BottomCenter).offset(y = avatarSize / 2)) { avatar() }
    }
}

/** A faint skyline of campus blocks with lit windows, drawn procedurally (no image asset, no allocation per frame beyond the draw). */
private fun DrawScope.drawCampusSkyline(dark: Boolean) {
    val ink = Color.White.copy(alpha = if (dark) 0.05f else 0.10f)
    val window = Color.White.copy(alpha = if (dark) 0.10f else 0.18f)
    val w = size.width
    val ground = size.height - 26.dp.toPx()
    // (left, width, height) as fractions of the cover's width and body height.
    val blocks = listOf(Triple(0.04f, 0.14f, 0.34f), Triple(0.20f, 0.10f, 0.22f), Triple(0.62f, 0.16f, 0.40f), Triple(0.80f, 0.12f, 0.28f))
    blocks.forEach { (l, bw, bh) ->
        val left = w * l; val width = w * bw; val height = size.height * bh
        drawRect(ink, Offset(left, ground - height), Size(width, height))
        val step = 9.dp.toPx()
        var y = ground - height + step
        while (y < ground - step) {
            var x = left + step
            while (x < left + width - step) { drawRect(window, Offset(x, y), Size(3.dp.toPx(), 3.dp.toPx())); x += step }
            y += step
        }
    }
}

/** Translucent dark circle with a thin light border and a white icon: readable on teal and on photos, 48dp touch target. */
@Composable
fun CoverIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = CircleShape, color = Color(0xFF031417).copy(alpha = 0.45f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)), modifier = modifier,
    ) {
        IconButton(onClick = onClick) {
            Icon(icon, contentDescription = contentDescription, tint = Color.White, modifier = Modifier.size(22.dp))
        }
    }
}

/** Bell for the cover's leading slot, with a turquoise dot when something is unread (count comes from the app's notification state). */
@Composable
fun CoverBellButton(unread: Int, onClick: () -> Unit) {
    Box {
        CoverIconButton(
            VibeIcons.Bell,
            if (unread > 0) "Notifications, $unread unread" else "Notifications",
            onClick,
        )
        if (unread > 0) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(top = 7.dp, end = 7.dp).size(11.dp)
                    .border(1.5.dp, Color(0xFF031417), CircleShape)
                    .background(MaterialTheme.colorScheme.primary, CircleShape)
                    .clearAndSetSemantics { },
            )
        }
    }
}

/** Lets a child ignore the parent's horizontal content padding (used to run the cover edge to edge). */
fun Modifier.fullBleed(horizontal: Dp): Modifier = layout { measurable, constraints ->
    val pad = horizontal.roundToPx()
    val width = constraints.maxWidth + pad * 2
    val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-pad, 0) }
}
