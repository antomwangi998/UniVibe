package com.univibe.app.features.auth

import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.univibe.app.ui.components.UniVibeMark
import com.univibe.app.ui.theme.BrandMint
import com.univibe.app.ui.theme.BrandTeal
import kotlinx.coroutines.delay

/** After this long without an answer the screen says so honestly (a free-tier server can take a while to wake up). */
private const val SLOW_AFTER_MS = 8_000L

private val EaseOutQuint: Easing = Easing { x -> 1f - Math.pow((1f - x).toDouble(), 5.0).toFloat() }

/**
 * Shown while the saved session is being checked ([com.univibe.app.domain.auth.AuthState.LoadingSession]).
 * It only exists while that state does: AuthGate removes it the moment the check finishes, which also stops
 * every infinite animation below. It never delays anything; it is purely a view of the real state.
 */
@Composable
fun SessionLoadingScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // "Remove animations" in system settings (animator scale 0) keeps the screen calm: no looping motion.
    val reduceMotion = remember {
        runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
            .getOrDefault(false)
    }

    var slow by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(SLOW_AFTER_MS)
        slow = true
    }

    // Entrance: the logo fades and settles from 0.85 to full size.
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val entrance by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(if (reduceMotion) 0 else 700, easing = EaseOutQuint),
        label = "entrance",
    )

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentAlignment = Alignment.Center,
    ) {
        // Fits small phones and landscape: never wider than 70% of the width or 45% of the height.
        val orbit = minOf(240.dp, maxWidth * 0.7f, maxHeight * 0.45f)
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Orbit(
                size = orbit,
                reduceMotion = reduceMotion,
                modifier = Modifier.alpha(entrance).scale(0.85f + 0.15f * entrance),
            )
            AnimatedContent(
                targetState = slow,
                transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(150)) },
                modifier = Modifier.padding(top = 20.dp).alpha(entrance),
                label = "sessionStatus",
            ) { isSlow ->
                Text(
                    text = if (isSlow) "Still connecting. The server can take a moment to wake up." else "Restoring your session...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp),
                )
            }
        }
    }
}

/** The logo with three thin teal arcs orbiting it, plus a faint glow. All motion is drawn, not laid out. */
@Composable
private fun Orbit(size: Dp, reduceMotion: Boolean, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "orbit")
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(3_600, easing = LinearEasing)),
        label = "spin",
    )
    val sweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(2_400, easing = LinearEasing)),
        label = "sweep",
    )
    // A gentle breathing pulse for the logo (about 4%), not a bounce.
    val pulse by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(tween(1_100, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse",
    )

    val track = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.06f)
    Column(modifier.semantics { contentDescription = "Restoring your session" }, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                // Reading the animated values here (not in composition) means only drawing re-runs each frame.
                val s = if (reduceMotion) 0f else spin
                val w = if (reduceMotion) 0f else sweep
                val r = this.size.minDimension / 2f
                val ellipse = Size(r * 1.9f, r * 1.05f)
                val topLeft = Offset(center.x - ellipse.width / 2f, center.y - ellipse.height / 2f)
                val stroke = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round)

                // Soft glow behind the logo: one radial gradient, no blur.
                drawCircle(
                    brush = Brush.radialGradient(listOf(BrandTeal.copy(alpha = 0.22f), Color.Transparent), center, r * 0.75f),
                    radius = r * 0.75f,
                    center = center,
                )

                val colors = listOf(BrandMint, BrandTeal, BrandMint.copy(alpha = 0.55f))
                for (i in 0 until 3) {
                    val tilt = i * 60f + s * (if (i % 2 == 0) 1f else -0.7f)
                    rotate(tilt, pivot = center) {
                        drawArc(track, 0f, 360f, false, topLeft, ellipse, style = Stroke(width = 1.dp.toPx()))
                        drawArc(colors[i], w * 1.3f + i * 120f, 150f, false, topLeft, ellipse, style = stroke)
                    }
                }
            }
            UniVibeMark(markSize = size * 0.34f, modifier = Modifier.scale(if (reduceMotion) 1f else pulse))
        }
    }
}
