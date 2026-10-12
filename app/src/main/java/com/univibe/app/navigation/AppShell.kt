package com.univibe.app.navigation

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.core.util.Consumer
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navDeepLink
import com.univibe.app.data.prefs.ThemeMode
import com.univibe.app.features.chats.ChatsScreen
import com.univibe.app.features.discover.DiscoverScreen
import com.univibe.app.features.events.EventsScreen
import com.univibe.app.features.home.HomeScreen
import com.univibe.app.features.profile.ProfileScreen
import com.univibe.app.features.settings.AboutScreen
import com.univibe.app.features.settings.AccessibilityScreen
import com.univibe.app.features.settings.AccountScreen
import com.univibe.app.features.settings.BlockedUsersScreen
import com.univibe.app.features.settings.ChangePasswordScreen
import com.univibe.app.features.settings.DeleteAccountScreen
import com.univibe.app.features.settings.FaqScreen
import com.univibe.app.features.settings.GuidelinesScreen
import com.univibe.app.features.settings.HelpScreen
import com.univibe.app.features.settings.NotificationsScreen
import com.univibe.app.features.settings.PrivacyScreen
import com.univibe.app.features.settings.ReportProblemScreen
import com.univibe.app.features.settings.SafetyScreen
import com.univibe.app.features.settings.SettingsScreen
import com.univibe.app.ui.components.LocalFloatingBarVisible
import com.univibe.app.ui.components.VibeBottomBar

private const val DEEP_LINK_SCHEME = "univibe://"
private const val SCROLL_THRESHOLD_PX = 24f

/** Hides the bar while the user scrolls content up, shows it again as soon as they scroll back. */
private class BarScrollConnection(
    private val onHide: () -> Unit,
    private val onShow: () -> Unit,
) : NestedScrollConnection {
    private var accumulated = 0f
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        val dy = available.y
        if (dy == 0f) return Offset.Zero
        if ((dy > 0f) != (accumulated > 0f)) accumulated = 0f
        accumulated += dy
        if (accumulated < -SCROLL_THRESHOLD_PX) onHide() else if (accumulated > SCROLL_THRESHOLD_PX) onShow()
        return Offset.Zero
    }
}

@Composable
fun AppShell(themeMode: ThemeMode, onThemeModeChange: (ThemeMode) -> Unit, onLogOut: () -> Unit) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val barItems = remember { TopLevelDestination.entries.map { it.toBarItem() } }
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val isTopLevel = TopLevelDestination.entries.any { it.route == currentRoute }

    val hiddenByScroll = remember { mutableStateOf(false) }
    LaunchedEffect(currentRoute) { hiddenByScroll.value = false }
    val barVisible = isTopLevel && !imeVisible && !hiddenByScroll.value
    val scrollConnection = remember {
        BarScrollConnection(onHide = { hiddenByScroll.value = true }, onShow = { hiddenByScroll.value = false })
    }

    // Deep links that arrive while the app is already running.
    val activity = LocalContext.current as ComponentActivity
    DisposableEffect(navController) {
        val listener = Consumer<Intent> { navController.handleDeepLink(it) }
        activity.addOnNewIntentListener(listener)
        onDispose { activity.removeOnNewIntentListener(listener) }
    }

    val selectTab: (String) -> Unit = { route ->
        if (route != currentRoute) {
            navController.navigate(route) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }
    val open: (String) -> Unit = { route -> navController.navigate(route) { launchSingleTop = true } }
    val back: () -> Unit = { navController.popBackStack() }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            // Content runs edge to edge; the bar hovers above it.
            CompositionLocalProvider(LocalFloatingBarVisible provides barVisible) {
                NavHost(
                    navController = navController,
                    startDestination = TopLevelDestination.Home.route,
                    modifier = Modifier.fillMaxSize().nestedScroll(scrollConnection),
                    enterTransition = { fadeIn(tween(220, delayMillis = 40)) + scaleIn(tween(220, delayMillis = 40), initialScale = 0.98f) },
                    exitTransition = { fadeOut(tween(90)) },
                    popEnterTransition = { fadeIn(tween(220, delayMillis = 40)) + scaleIn(tween(220, delayMillis = 40), initialScale = 0.98f) },
                    popExitTransition = { fadeOut(tween(90)) },
                ) {
                    tab(TopLevelDestination.Home.route) { HomeScreen(onOpenEvents = { selectTab(TopLevelDestination.Events.route) }) }
                    tab(TopLevelDestination.Discover.route) { DiscoverScreen() }
                    tab(TopLevelDestination.Chats.route) { ChatsScreen() }
                    tab(TopLevelDestination.Events.route) { EventsScreen() }
                    tab(TopLevelDestination.Profile.route) { ProfileScreen(onOpenSettings = { open(SettingsRoutes.ROOT) }) }

                    subScreen(SettingsRoutes.ROOT) {
                        SettingsScreen(themeMode, onThemeModeChange, onBack = back, onNavigate = open, onLogOut = onLogOut)
                    }
                    subScreen(SettingsRoutes.ACCOUNT) {
                        AccountScreen(onBack = back, onChangePassword = { open(SettingsRoutes.PASSWORD) }, onDelete = { open(SettingsRoutes.DELETE) })
                    }
                    subScreen(SettingsRoutes.PASSWORD) { ChangePasswordScreen(onBack = back) }
                    subScreen(SettingsRoutes.DELETE) { DeleteAccountScreen(onBack = back) }
                    subScreen(SettingsRoutes.PRIVACY) {
                        PrivacyScreen(onBack = back, onBlocked = { open(SettingsRoutes.BLOCKED) }, onSafety = { open(SettingsRoutes.SAFETY) })
                    }
                    subScreen(SettingsRoutes.BLOCKED) { BlockedUsersScreen(onBack = back) }
                    subScreen(SettingsRoutes.SAFETY) { SafetyScreen(onBack = back) }
                    subScreen(SettingsRoutes.NOTIFICATIONS) { NotificationsScreen(onBack = back) }
                    subScreen(SettingsRoutes.ACCESSIBILITY) { AccessibilityScreen(onBack = back) }
                    subScreen(SettingsRoutes.HELP) {
                        HelpScreen(
                            onBack = back,
                            onFaq = { open(SettingsRoutes.FAQ) }, onReport = { open(SettingsRoutes.REPORT) },
                            onGuidelines = { open(SettingsRoutes.GUIDELINES) }, onAbout = { open(SettingsRoutes.ABOUT) },
                        )
                    }
                    subScreen(SettingsRoutes.FAQ) { FaqScreen(onBack = back) }
                    subScreen(SettingsRoutes.REPORT) { ReportProblemScreen(onBack = back) }
                    subScreen(SettingsRoutes.GUIDELINES) { GuidelinesScreen(onBack = back) }
                    subScreen(SettingsRoutes.ABOUT) { AboutScreen(onBack = back) }
                }
            }

            AnimatedVisibility(
                visible = barVisible,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = slideInVertically(spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)) { it } + fadeIn(tween(160)),
                exit = slideOutVertically(tween(180)) { it } + fadeOut(tween(140)),
            ) {
                VibeBottomBar(items = barItems, selectedRoute = currentRoute, onSelect = selectTab)
            }
        }
    }
}

private fun NavGraphBuilder.tab(route: String, content: @Composable () -> Unit) {
    composable(route, deepLinks = listOf(navDeepLink { uriPattern = "$DEEP_LINK_SCHEME$route" })) { content() }
}

private fun NavGraphBuilder.subScreen(route: String, content: @Composable () -> Unit) {
    composable(
        route = route,
        deepLinks = listOf(navDeepLink { uriPattern = "$DEEP_LINK_SCHEME$route" }),
        enterTransition = { slideInHorizontally(tween(280)) { it / 5 } + fadeIn(tween(240)) },
        popExitTransition = { slideOutHorizontally(tween(240)) { it / 5 } + fadeOut(tween(200)) },
    ) { content() }
}
