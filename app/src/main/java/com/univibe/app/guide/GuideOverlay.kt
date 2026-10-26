package com.univibe.app.guide

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateRectAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.univibe.app.ui.components.LocalReduceMotion
import com.univibe.app.ui.components.VibeButton
import com.univibe.app.ui.components.VibeButtonStyle
import com.univibe.app.ui.components.VibeIcons
import com.univibe.app.ui.components.VibeIconButton

/**
 * The tour layer. Put it last in the screen's root box. It draws nothing when no tour is running, so it cannot leave the
 * interface dimmed, and Back always closes it.
 */
@Composable
fun GuideHost(controller: GuideController, modifier: Modifier = Modifier) {
    val step = controller.step ?: return
    BackHandler { controller.exit() }

    var origin by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    val dir = LocalLayoutDirection.current
    val insets = WindowInsets.safeDrawing
    val safe = Rect(
        left = insets.getLeft(density, dir).toFloat(),
        top = insets.getTop(density).toFloat(),
        right = (size.width - insets.getRight(density, dir)).toFloat(),
        bottom = (size.height - insets.getBottom(density)).toFloat(),
    )

    // A target that is not on screen (scrolled away, hidden bar, feature missing) shows the card in the middle instead of failing.
    val windowSafe = safe.translate(origin)
    val raw = GuideTargets.boundsOf(step.targetId)?.takeIf { it.overlaps(windowSafe) }
    val padPx = with(density) { 8.dp.toPx() }
    val spot: Rect? = raw?.translate(-origin)?.let { r ->
        if (step.shape == GuideShape.Circle) {
            val radius = maxOf(r.width, r.height) / 2f + padPx
            Rect(r.center.x - radius, r.center.y - radius, r.center.x + radius, r.center.y + radius)
        } else r.inflate(padPx)
    }

    val reduced = LocalReduceMotion.current
    var lastSpot by remember { mutableStateOf(Rect.Zero) }
    if (spot != null) lastSpot = spot
    val animatedSpot by animateRectAsState(
        targetValue = spot ?: lastSpot,
        animationSpec = if (reduced) snap() else spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow),
        label = "guideSpot",
    )
    val scrim by animateColorAsState(Color.Black.copy(alpha = 0.72f), tween(if (reduced) 0 else 200), label = "guideScrim")
    val ring = MaterialTheme.colorScheme.primary

    Box(
        modifier
            .fillMaxSize()
            .onGloballyPositioned { origin = it.positionInWindow(); size = it.size }
            .pointerInput(step.id, spot) {
                detectTapGestures { tap -> if (step.kind == StepKind.TapTarget && spot?.contains(tap) == true) controller.next() }
            },
    ) {
        Canvas(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
            drawRect(scrim)
            if (spot != null) {
                val r = animatedSpot
                if (step.shape == GuideShape.Circle) {
                    drawCircle(Color.Black, radius = r.width / 2f, center = r.center, blendMode = BlendMode.Clear)
                } else {
                    val corner = CornerRadius(20.dp.toPx())
                    drawRoundRect(Color.Black, r.topLeft, Size(r.width, r.height), corner, blendMode = BlendMode.Clear)
                }
            }
        }
        if (spot != null) {
            Canvas(Modifier.fillMaxSize()) {
                val r = animatedSpot
                val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx())
                if (step.shape == GuideShape.Circle) drawCircle(ring, radius = r.width / 2f, center = r.center, style = stroke)
                else drawRoundRect(ring, r.topLeft, Size(r.width, r.height), CornerRadius(20.dp.toPx()), style = stroke)
            }
        }
        PlacedTooltip(target = spot, safe = safe) {
            AnimatedContent(
                targetState = step,
                transitionSpec = { fadeIn(tween(if (reduced) 0 else 180)) togetherWith fadeOut(tween(if (reduced) 0 else 120)) },
                label = "guideStep",
            ) { s -> TooltipCard(controller, s) }
        }
    }
}

@Composable
private fun PlacedTooltip(target: Rect?, safe: Rect, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val gap = with(density) { 14.dp.roundToPx() }
    val margin = with(density) { 16.dp.roundToPx() }
    Layout(content = content, modifier = Modifier.fillMaxSize()) { measurables, constraints ->
        val maxWidth = minOf(with(density) { 380.dp.roundToPx() }, (safe.width - 2 * margin).toInt()).coerceAtLeast(0)
        val placeable = measurables.first().measure(Constraints(maxWidth = maxWidth))
        val at = placeTooltip(safe, target, IntSize(placeable.width, placeable.height), gap, margin)
        layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(at) }
    }
}

@Composable
private fun TooltipCard(controller: GuideController, step: GuideStep) {
    val tutorial = controller.tutorial ?: return
    val total = tutorial.steps.size
    val index = tutorial.steps.indexOfFirst { it.id == step.id }.coerceAtLeast(0)
    val last = index == total - 1
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp,
        shadowElevation = 12.dp,
        modifier = Modifier.semantics { paneTitle = step.title; liveRegion = LiveRegionMode.Polite },
    ) {
        Column(Modifier.padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (step.icon != null) {
                    Box(
                        Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center,
                    ) { Icon(step.icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp)) }
                    Box(Modifier.width(10.dp))
                }
                Text(step.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                VibeIconButton(VibeIcons.Close, "Close tour", controller::exit)
            }
            Text(
                step.body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, end = 12.dp),
            )
            Row(Modifier.fillMaxWidth().padding(top = 14.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                TourProgressIndicator(index, total, Modifier.weight(1f))
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp, end = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!last) VibeButton("Skip", controller::skip, style = VibeButtonStyle.Text, compact = true)
                if (index > 0 && step.kind != StepKind.Completion) VibeButton("Back", controller::back, style = VibeButtonStyle.Secondary, compact = true)
                when (step.kind) {
                    StepKind.Action -> Text("Do it to continue", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    StepKind.TapTarget -> Text("Tap it to continue", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> VibeButton(step.ctaLabel ?: if (last) "Done" else "Next", controller::next, compact = true)
                }
            }
        }
    }
}

/** "Step 2 of 6", drawn as dots; the current one is longer. */
@Composable
fun TourProgressIndicator(index: Int, total: Int, modifier: Modifier = Modifier) {
    val reduced = LocalReduceMotion.current
    Row(
        modifier.semantics { contentDescription = "Step ${index + 1} of $total" },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(total) { i ->
            val w by animateDpAsState(if (i == index) 20.dp else 6.dp, if (reduced) snap() else tween(200), label = "dot")
            val c = if (i <= index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
            Box(Modifier.height(6.dp).width(w).clip(CircleShape).background(c))
        }
    }
}
