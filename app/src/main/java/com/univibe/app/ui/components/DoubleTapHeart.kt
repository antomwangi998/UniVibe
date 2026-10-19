package com.univibe.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The big heart that pops over a post when it is double-tapped. Every change of [burst] plays it once (a new
 * double-tap restarts it). It never takes touches. With system animations off it only fades.
 * The animated values are read while drawing, so the post itself is not recomposed during the animation.
 */
@Composable
fun DoubleTapHeart(burst: Int, modifier: Modifier = Modifier) {
    val reduced = rememberReducedMotion()
    val scale = remember { Animatable(0f) }
    val heartAlpha = remember { Animatable(0f) }
    var showing by remember { mutableStateOf(false) }
    LaunchedEffect(burst) {
        if (burst == 0) return@LaunchedEffect
        showing = true
        scale.snapTo(if (reduced) 1f else 0.3f)
        heartAlpha.snapTo(1f)
        coroutineScope {
            if (!reduced) launch { scale.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = Spring.StiffnessMedium)) }
            delay(450)
            heartAlpha.animateTo(0f, tween(260))
        }
        showing = false
    }
    if (showing) {
        Icon(
            VibeIcons.HeartFilled,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = modifier
                .size(96.dp)
                .graphicsLayer { scaleX = scale.value; scaleY = scale.value; this.alpha = heartAlpha.value },
        )
    }
}
