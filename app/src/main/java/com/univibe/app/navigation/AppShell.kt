package com.univibe.app.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.univibe.app.data.prefs.ThemeMode
import com.univibe.app.features.chats.ChatsScreen
import com.univibe.app.features.discover.DiscoverScreen
import com.univibe.app.features.events.EventsScreen
import com.univibe.app.features.home.HomeScreen
import com.univibe.app.features.profile.ProfileScreen
import com.univibe.app.features.settings.SettingsScreen
import com.univibe.app.ui.components.VibeBottomBar

@Composable
fun AppShell(themeMode: ThemeMode, onThemeModeChange: (ThemeMode) -> Unit) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val barItems = remember { TopLevelDestination.entries.map { it.toBarItem() } }
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val showBar = TopLevelDestination.entries.any { it.route == currentRoute } && !imeVisible
    val selectTab: (String) -> Unit = { route ->
        if (route != currentRoute) {
            navController.navigate(route) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp),
        bottomBar = {
            AnimatedVisibility(
                visible = showBar,
                enter = slideInVertically(tween(260)) { it } + fadeIn(tween(200)),
                exit = slideOutVertically(tween(200)) { it } + fadeOut(tween(150)),
            ) {
                VibeBottomBar(
                    items = barItems,
                    selectedRoute = currentRoute,
                    onSelect = selectTab,
                )
            }
        },
    ) { inner ->
        NavHost(
            navController = navController,
            startDestination = TopLevelDestination.Home.route,
            modifier = Modifier.padding(bottom = inner.calculateBottomPadding()),
            enterTransition = { fadeIn(tween(220, delayMillis = 40)) + scaleIn(tween(220, delayMillis = 40), initialScale = 0.98f) },
            exitTransition = { fadeOut(tween(90)) },
            popEnterTransition = { fadeIn(tween(220, delayMillis = 40)) + scaleIn(tween(220, delayMillis = 40), initialScale = 0.98f) },
            popExitTransition = { fadeOut(tween(90)) },
        ) {
            composable(TopLevelDestination.Home.route) {
                HomeScreen(onOpenEvents = { selectTab(TopLevelDestination.Events.route) })
            }
            composable(TopLevelDestination.Discover.route) { DiscoverScreen() }
            composable(TopLevelDestination.Chats.route) { ChatsScreen() }
            composable(TopLevelDestination.Events.route) { EventsScreen() }
            composable(TopLevelDestination.Profile.route) {
                ProfileScreen(onOpenSettings = { navController.navigate(Routes.SETTINGS) { launchSingleTop = true } })
            }
            composable(
                route = Routes.SETTINGS,
                enterTransition = { slideInHorizontally(tween(280)) { it / 5 } + fadeIn(tween(240)) },
                popExitTransition = { slideOutHorizontally(tween(240)) { it / 5 } + fadeOut(tween(200)) },
            ) {
                SettingsScreen(
                    themeMode = themeMode,
                    onThemeModeChange = onThemeModeChange,
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}
