package com.univibe.app.app

import android.graphics.Color as AndroidColor
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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

    UniVibeTheme(darkTheme = darkTheme) {
        AuthGate(themeMode = themeMode, onThemeModeChange = appViewModel::setThemeMode)
    }
}
