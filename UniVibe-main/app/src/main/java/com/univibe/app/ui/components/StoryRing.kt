package com.univibe.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.univibe.app.ui.theme.BrandMint
import com.univibe.app.ui.theme.BrandTeal

/** Gradient ring that slowly rotates (static when system animations are off). */
@Composable
fun StoryRing(
    ringSize: Dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val reduced = rememberReducedMotion()
    val rotation = if (reduced) 0f else {
        val t = rememberInfiniteTransition(label = "storyRing")
        val r by t.animateFloat(
            initialValue = 0f, targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(7000, easing = LinearEasing), RepeatMode.Restart),
            label = "storyRingRotation",
        )
        r
    }
    Box(
        modifier
            .size(ringSize)
            .drawBehind {
                val stroke = 3.dp.toPx()
                rotate(rotation) {
                    drawCircle(
                        brush = Brush.sweepGradient(listOf(BrandTeal, BrandMint, BrandTeal)),
                        radius = this.size.minDimension / 2f - stroke / 2f,
                        style = Stroke(width = stroke),
                    )
                }
            }
            .padding(6.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}
