package com.univibe.app.app

import android.graphics.Color as AndroidColor
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Density
import com.univibe.app.ui.components.LocalCommunityMembership
import com.univibe.app.ui.components.LocalGlassEnabled
import com.univibe.app.ui.components.LocalReduceMotion
import com.univibe.app.ui.components.systemAnimationsOff
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.univibe.app.data.prefs.ThemeMode
import com.univibe.app.features.auth.AuthGate
import com.univibe.app.ui.theme.UniVibeTheme

@Composable
fun UniVibeApp(appViewModel: AppViewModel = viewModel()) {
    val themeMode by appViewModel.themeMode.collectAsStateWithLifecycle()
    val settings by appViewModel.settings.collectAsStateWithLifecycle()
    val systemDark = isSystemInDarkTheme()
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    // Keep status/navigation bar icon colours in sync with the in-app theme.
    val activity = LocalContext.current as ComponentActivity
    DisposableEffect(darkTheme) {
        activity.enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT) { darkTheme },
            navigationBarStyle = SystemBarStyle.auto(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT) { darkTheme },
        )
        onDispose { }
    }

    val context = LocalContext.current
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val reduceMotion = remember(settings.reduceMotion) { settings.reduceMotion || systemAnimationsOff(context) }

    // Glass is skipped on low-RAM phones and in High contrast; those get flat surfaces with the same layout.
    val glassEnabled = remember(settings.highContrast) {
        !settings.highContrast && !(context.getSystemService(android.app.ActivityManager::class.java)?.isLowRamDevice ?: false)
    }

    CompositionLocalProvider(
        LocalGlassEnabled provides glassEnabled,
        LocalDensity provides Density(density.density, (density.fontScale * settings.textScale.factor).coerceAtMost(2f)),
        LocalReduceMotion provides reduceMotion,
        LocalHapticFeedback provides if (settings.hapticFeedback) haptics else NoHaptics,
        LocalCommunityMembership provides AppContainer.communityMembership,
    ) {
        UniVibeTheme(darkTheme = darkTheme, appFont = settings.font, boldText = settings.boldText, highContrast = settings.highContrast) {
            AuthGate(themeMode = themeMode, onThemeModeChange = appViewModel::setThemeMode)
        }
    }
}

/** Provided in place of the system haptics when the person turns haptic feedback off, so every call site goes quiet at once. */
private object NoHaptics : HapticFeedback {
    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) = Unit
}
