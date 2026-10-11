package com.univibe.app.features.discover

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.univibe.app.data.model.Student
import com.univibe.app.ui.components.EmptyState
import com.univibe.app.ui.components.ErrorState
import com.univibe.app.ui.components.ProfileDiscoveryCard
import com.univibe.app.ui.components.VibeTopBar
import com.univibe.app.ui.theme.Spacing
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun DiscoverScreen(viewModel: DiscoverViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var request by remember { mutableStateOf<Boolean?>(null) }

    Column(Modifier.fillMaxSize()) {
        VibeTopBar("Discover")
        when {
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.error != null -> ErrorState(state.error!!, onRetry = viewModel::load)
            state.deck.isEmpty() -> EmptyState(
                icon = Icons.Outlined.Groups,
                title = "You're all caught up",
                message = "Check back soon for more students who share your interests.",
                actionLabel = "Refresh",
                onAction = viewModel::load,
            )
            else -> {
                val top = state.deck.first()
                Column(
                    Modifier.fillMaxSize().padding(horizontal = Spacing.md),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        key(top.id) {
                            SwipeableCard(
                                student = top,
                                request = request,
                                onDecision = { liked ->
                                    request = null
                                    viewModel.decide(liked)
                                },
                            )
                        }
                    }
                    Row(
                        Modifier.padding(vertical = Spacing.md),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.xl),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RoundAction(Icons.Outlined.Close, "Pass on ${top.name}", filled = false, enabled = request == null) { request = false }
                        RoundAction(Icons.Filled.Favorite, "Connect with ${top.name}", filled = true, enabled = request == null) { request = true }
                    }
                    Text(
                        "Only students who opted in to discovery appear here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(bottom = Spacing.sm),
                    )
                }
            }
        }
    }
}

@Composable
private fun SwipeableCard(student: Student, request: Boolean?, onDecision: (Boolean) -> Unit) {
    val scope = rememberCoroutineScope()
    val offsetX = remember { Animatable(0f) }
    val enter = remember { Animatable(0.94f) }
    val widthPx = with(LocalDensity.current) { LocalConfiguration.current.screenWidthDp.dp.toPx() }

    LaunchedEffect(Unit) { enter.animateTo(1f, spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow)) }

    suspend fun flyOut(liked: Boolean) {
        offsetX.animateTo(if (liked) widthPx * 1.4f else -widthPx * 1.4f, tween(220))
        onDecision(liked)
    }

    LaunchedEffect(request) { request?.let { flyOut(it) } }

    ProfileDiscoveryCard(
        student = student,
        modifier = Modifier
            .fillMaxSize()
            .offset { IntOffset(offsetX.value.roundToInt(), 0) }
            .graphicsLayer {
                rotationZ = offsetX.value / widthPx * 12f
                scaleX = enter.value
                scaleY = enter.value
            }
            .pointerInput(student.id) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        scope.launch {
                            if (abs(offsetX.value) > widthPx * 0.3f) {
                                flyOut(offsetX.value > 0)
                            } else {
                                offsetX.animateTo(0f, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMedium))
                            }
                        }
                    },
                    onDragCancel = { scope.launch { offsetX.animateTo(0f, spring()) } },
                ) { change, dragAmount ->
                    change.consume()
                    scope.launch { offsetX.snapTo(offsetX.value + dragAmount) }
                }
            },
    )
}

@Composable
private fun RoundAction(icon: ImageVector, description: String, filled: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = CircleShape,
        color = if (filled) colors.primary else colors.surface,
        contentColor = if (filled) colors.onPrimary else colors.onSurface,
        border = if (filled) null else BorderStroke(1.5.dp, colors.outline),
        shadowElevation = if (filled) 6.dp else 0.dp,
    ) {
        IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(64.dp)) {
            Icon(icon, contentDescription = description, modifier = Modifier.size(28.dp))
        }
    }
}
