package com.univibe.app.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.univibe.app.ui.theme.Spacing

/** Placeholder block that gently pulses (static when system animations are off). */
@Composable
fun SkeletonBox(modifier: Modifier = Modifier, shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(12.dp)) {
    val reduced = rememberReducedMotion()
    val alpha = if (reduced) 0.6f else {
        val t = rememberInfiniteTransition(label = "skeleton")
        val a by t.animateFloat(
            initialValue = 0.4f, targetValue = 0.9f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "skeletonAlpha",
        )
        a
    }
    Box(modifier.clip(shape).alpha(alpha).background(MaterialTheme.colorScheme.surfaceContainerHighest))
}

@Composable
fun LoadingSkeleton(modifier: Modifier = Modifier, itemCount: Int = 3) {
    Column(
        modifier = modifier.fillMaxWidth().semantics { contentDescription = "Loading" },
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        repeat(itemCount) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    SkeletonBox(Modifier.size(44.dp), CircleShape)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        SkeletonBox(Modifier.height(14.dp).fillMaxWidth(0.4f))
                        SkeletonBox(Modifier.height(12.dp).fillMaxWidth(0.25f))
                    }
                }
                Spacer(Modifier.height(2.dp))
                SkeletonBox(Modifier.height(14.dp).fillMaxWidth())
                SkeletonBox(Modifier.height(140.dp).fillMaxWidth(), RoundedCornerShape(16.dp))
            }
        }
    }
}
