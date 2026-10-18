package com.univibe.app.features.auth

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.univibe.app.ui.components.BannerKind
import com.univibe.app.ui.components.MessageBanner
import com.univibe.app.ui.components.UniVibeMark
import com.univibe.app.ui.components.UniVibeWordmark
import com.univibe.app.ui.components.VibeButton
import com.univibe.app.ui.components.VibeButtonStyle
import com.univibe.app.ui.components.rememberReducedMotion
import com.univibe.app.ui.theme.Spacing

@Composable
fun WelcomeScreen(onCreateAccount: () -> Unit, onLogIn: () -> Unit, notice: String?) {
    val reduced = rememberReducedMotion()
    var shown by remember { mutableStateOf(reduced) }
    LaunchedEffect(Unit) { shown = true }
    val glow = MaterialTheme.colorScheme.primary.copy(alpha = 0.20f)

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .drawBehind {
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(glow, Color.Transparent),
                        center = Offset(size.width / 2f, size.height * 0.18f),
                        radius = size.width * 0.9f,
                    ),
                )
            },
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg, vertical = Spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(72.dp))
            AnimatedVisibility(shown, enter = fadeIn(tween(500)) + scaleIn(spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessLow), initialScale = 0.7f)) {
                UniVibeMark(markSize = 104.dp)
            }
            Spacer(Modifier.height(Spacing.md))
            AnimatedVisibility(shown, enter = fadeIn(tween(500, delayMillis = 150)) + slideInVertically(tween(500, delayMillis = 150)) { it / 3 }) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    UniVibeWordmark(fontSize = 40)
                    Text(
                        "Connect. Share. Belong.",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = Spacing.xs),
                    )
                }
            }
            Spacer(Modifier.height(Spacing.lg))
            AnimatedVisibility(shown, enter = fadeIn(tween(500, delayMillis = 300))) {
                Text(
                    "UniVibe helps university students connect, share moments and take part in campus life: " +
                        "find people who share your interests, join communities and never miss an event.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.weight(1f))
            if (notice != null) {
                MessageBanner(notice, BannerKind.Info)
                Spacer(Modifier.height(Spacing.md))
            }
            AnimatedVisibility(shown, enter = fadeIn(tween(500, delayMillis = 450)) + slideInVertically(tween(500, delayMillis = 450)) { it / 2 }) {
                Column(Modifier.fillMaxWidth().padding(top = Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm + 4.dp)) {
                    VibeButton("Create account", onCreateAccount, Modifier.fillMaxWidth())
                    VibeButton("Log in", onLogIn, Modifier.fillMaxWidth(), style = VibeButtonStyle.Secondary)
                }
            }
        }
    }
}
