package com.univibe.app.guide

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.univibe.app.ui.components.LocalReduceMotion
import com.univibe.app.ui.components.UniVibeLockup
import com.univibe.app.ui.components.VibeButton
import com.univibe.app.ui.components.VibeButtonStyle

/**
 * Shown once, right after the first successful sign-in on this phone. Short on purpose: page one welcomes, page two offers
 * the navigation tour. Nothing here is required; Skip goes straight to the app and the tour stays available in Help.
 */
@Composable
fun GuideWelcome(onStartTour: () -> Unit, onFinished: () -> Unit, modifier: Modifier = Modifier) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    val reduced = LocalReduceMotion.current
    BackHandler { onFinished() }
    val primary = MaterialTheme.colorScheme.primary
    Column(
        modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(primary.copy(alpha = 0.16f), MaterialTheme.colorScheme.background, MaterialTheme.colorScheme.background)))
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.0f))
            .statusBarsPadding().navigationBarsPadding()
            .padding(horizontal = 28.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AnimatedContent(
            targetState = page,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            transitionSpec = {
                (fadeIn(tween(if (reduced) 0 else 260)) + slideInVertically(tween(if (reduced) 0 else 260)) { it / 12 }) togetherWith fadeOut(tween(if (reduced) 0 else 140))
            },
            label = "welcomePage",
        ) { p ->
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                UniVibeLockup(markSize = 64.dp, fontSize = 34)
                Spacer(Modifier.height(28.dp))
                if (p == 0) {
                    Text("Welcome to UniVibe!", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(8.dp))
                    Text("Your campus, your people, your vibe.", style = MaterialTheme.typography.titleMedium, color = primary, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Discover campus communities, connect with students, share moments, explore events, and stay connected to everything happening around you.",
                        style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                    )
                } else {
                    Text("Want a quick look around?", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "A one-minute tour shows you where Home, Discover, Chats, Events and your Profile are. You can stop at any point, and find it again any time under Settings ▸ Help & support ▸ Help and tutorials.",
                        style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                    )
                }
            }
        }
        if (page == 0) {
            VibeButton("Let's explore", { page = 1 }, Modifier.fillMaxWidth())
            VibeButton("Skip introduction", onFinished, Modifier.fillMaxWidth(), style = VibeButtonStyle.Text)
        } else {
            VibeButton("Start the tour", onStartTour, Modifier.fillMaxWidth())
            VibeButton("Not now", onFinished, Modifier.fillMaxWidth(), style = VibeButtonStyle.Text)
        }
    }
}
