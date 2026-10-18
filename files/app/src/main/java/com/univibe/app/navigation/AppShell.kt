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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.univibe.app.app.AppContainer
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
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import com.univibe.app.features.chats.ConversationScreen
import com.univibe.app.features.chats.GroupInfoScreen
import com.univibe.app.features.chats.NewGroupScreen
import com.univibe.app.data.model.NotificationTarget
import com.univibe.app.features.notifications.NotificationCenterScreen
import com.univibe.app.features.post.CreatePostScreen
import com.univibe.app.features.comments.CommentsScreen
import com.univibe.app.features.communities.CommunitiesScreen
import com.univibe.app.features.communities.CommunityDetailScreen
import com.univibe.app.features.communities.CommunityFormScreen
import com.univibe.app.features.post.PostComposeHandoff
import com.univibe.app.features.profile.EditProfileScreen
import com.univibe.app.features.profile.FullProfileScreen
import com.univibe.app.features.profile.FollowKind
import com.univibe.app.features.profile.FollowListScreen
import com.univibe.app.features.profile.PhotosScreen
import com.univibe.app.features.profile.ProfileActions
import com.univibe.app.features.profile.SavedPostsScreen
import com.univibe.app.features.profile.StudentProfileScreen
import com.univibe.app.data.prefs.ThemeMode
import com.univibe.app.features.chats.ChatsScreen
import com.univibe.app.features.discover.DiscoverScreen
import com.univibe.app.features.events.EventDetailsScreen
import com.univibe.app.features.events.EventFormScreen
import com.univibe.app.features.events.EventsScreen
import com.univibe.app.features.home.HomeScreen
import com.univibe.app.features.profile.ProfileScreen
import com.univibe.app.features.settings.AboutScreen
import com.univibe.app.features.settings.AccessibilityScreen
import com.univibe.app.features.settings.AccountScreen
import com.univibe.app.features.settings.AccountStatusScreen
import com.univibe.app.features.settings.AppearanceScreen
import com.univibe.app.features.settings.LoginActivityScreen
import com.univibe.app.features.settings.SecurityScreen
import com.univibe.app.features.settings.BlockedUsersScreen
import com.univibe.app.features.settings.ChangePasswordScreen
import com.univibe.app.features.settings.DeleteAccountScreen
import com.univibe.app.features.settings.FaqScreen
import com.univibe.app.features.settings.GuidelinesScreen
import com.univibe.app.features.settings.HelpScreen
import com.univibe.app.features.settings.NotificationsScreen
import com.univibe.app.features.settings.DiscoverProfileScreen
import com.univibe.app.features.settings.PrivacyScreen
import com.univibe.app.features.settings.ReportProblemScreen
import com.univibe.app.features.settings.SafetyScreen
import com.univibe.app.features.settings.SettingsScreen
import com.univibe.app.features.settings.TermsScreen
import com.univibe.app.ui.components.LocalChromeHidden
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

    com.univibe.app.push.AskForNotificationPermissionOnce()

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
    val unreadNotifications by AppContainer.notificationRepository.unreadCount.collectAsStateWithLifecycle()
    // Keep the bell badge in step with the server (does nothing in the offline demo).
    LaunchedEffect(Unit) {
        while (true) {
            runCatching { AppContainer.notificationRepository.refreshUnread() }
            kotlinx.coroutines.delay(60_000)
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            // Content runs edge to edge; the bar hovers above it.
            CompositionLocalProvider(LocalFloatingBarVisible provides barVisible, LocalChromeHidden provides hiddenByScroll.value) {
                NavHost(
                    navController = navController,
                    startDestination = TopLevelDestination.Home.route,
                    modifier = Modifier.fillMaxSize().nestedScroll(scrollConnection),
                    enterTransition = { fadeIn(tween(220, delayMillis = 40)) + scaleIn(tween(220, delayMillis = 40), initialScale = 0.98f) },
                    exitTransition = { fadeOut(tween(90)) },
                    popEnterTransition = { fadeIn(tween(220, delayMillis = 40)) + scaleIn(tween(220, delayMillis = 40), initialScale = 0.98f) },
                    popExitTransition = { fadeOut(tween(90)) },
                ) {
                    tab(TopLevelDestination.Home.route) {
                        HomeScreen(
                            onOpenEvents = { selectTab(TopLevelDestination.Events.route) },
                            onOpenComments = { open(AppRoutes.comments(it)) },
                            onOpenStudent = { open(AppRoutes.student(it)) },
                            onCreatePost = { open(AppRoutes.CREATE_POST) },
                            unreadNotifications = unreadNotifications,
                            onOpenNotifications = { open(AppRoutes.NOTIFICATIONS) },
                        )
                    }
                    tab(TopLevelDestination.Discover.route) { DiscoverScreen(onOpenStudent = { open(AppRoutes.student(it)) }) }
                    tab(TopLevelDestination.Chats.route) { ChatsScreen(onOpenChat = { open(AppRoutes.chat(it)) }, onNewGroup = { open(AppRoutes.GROUP_NEW) }) }
                    tab(TopLevelDestination.Events.route) { EventsScreen(onOpenEvent = { open(AppRoutes.event(it)) }, onCreateEvent = { open(AppRoutes.EVENT_NEW) }) }
                    tab(TopLevelDestination.Profile.route) {
                        ProfileScreen(
                            ProfileActions(
                                onOpenSettings = { open(SettingsRoutes.ROOT) },
                                onEditProfile = { open(AppRoutes.EDIT_PROFILE) },
                                onOpenPhotos = { open(AppRoutes.PHOTOS) },
                                onOpenSaved = { open(AppRoutes.SAVED) },
                                onOpenFollowers = { open(AppRoutes.FOLLOWERS) },
                                onOpenFollowing = { open(AppRoutes.FOLLOWING) },
                                onOpenCommunities = { open(AppRoutes.COMMUNITIES) },
                                onOpenCommunity = { open(AppRoutes.community(it)) },
                                onOpenPrivacy = { open(SettingsRoutes.PRIVACY) },
                                unreadNotifications = unreadNotifications,
                                onOpenNotifications = { open(AppRoutes.NOTIFICATIONS) },
                            ),
                        )
                    }

                    subScreen(AppRoutes.CHAT, stringArgs("chatId")) { entry ->
                        ConversationScreen(
                            chatId = entry.arg("chatId"), onBack = back,
                            onOpenGroupInfo = { open(AppRoutes.groupInfo(entry.arg("chatId"))) },
                            onOpenStudent = { open(AppRoutes.student(it)) },
                        )
                    }
                    subScreen(AppRoutes.GROUP_NEW) {
                        NewGroupScreen(
                            onBack = back,
                            // Replace the creation screen with the new chat so Back returns to the inbox.
                            onCreated = { id ->
                                navController.navigate(AppRoutes.chat(id)) {
                                    popUpTo(AppRoutes.GROUP_NEW) { inclusive = true }
                                    launchSingleTop = true
                                }
                            },
                        )
                    }
                    subScreen(AppRoutes.GROUP_INFO, stringArgs("chatId")) { entry ->
                        GroupInfoScreen(
                            chatId = entry.arg("chatId"), onBack = back,
                            onOpenStudent = { open(AppRoutes.student(it)) },
                            onOpenChat = { open(AppRoutes.chat(it)) },
                            onLeft = { selectTab(TopLevelDestination.Chats.route) },
                        )
                    }
                    subScreen(AppRoutes.COMMENTS, stringArgs("postId")) { entry ->
                        CommentsScreen(postId = entry.arg("postId"), onBack = back, onOpenStudent = { open(AppRoutes.student(it)) })
                    }
                    subScreen(AppRoutes.NOTIFICATIONS) {
                        NotificationCenterScreen(
                            onBack = back,
                            onOpenSettings = { open(SettingsRoutes.NOTIFICATIONS) },
                            onOpenTarget = { target ->
                                when (target) {
                                    is NotificationTarget.Post -> open(AppRoutes.comments(target.id))
                                    is NotificationTarget.Student -> open(AppRoutes.student(target.id))
                                    is NotificationTarget.Event -> open(AppRoutes.event(target.id))
                                    is NotificationTarget.Community -> open(AppRoutes.community(target.id))
                                    is NotificationTarget.Chat -> open(AppRoutes.chat(target.id))
                                    NotificationTarget.None -> Unit
                                }
                            },
                        )
                    }
                    subScreen(AppRoutes.CREATE_POST) { CreatePostScreen(onBack = back) }
                    subScreen(AppRoutes.EVENT, stringArgs("eventId")) { entry ->
                        EventDetailsScreen(eventId = entry.arg("eventId"), onBack = back, onEdit = { open(AppRoutes.eventEdit(entry.arg("eventId"))) })
                    }
                    subScreen(AppRoutes.EVENT_NEW) {
                        EventFormScreen(
                            editId = null, onBack = back,
                            // Replace the form with the new event so Back returns to the list.
                            onDone = { id ->
                                navController.navigate(AppRoutes.event(id)) {
                                    popUpTo(AppRoutes.EVENT_NEW) { inclusive = true }
                                    launchSingleTop = true
                                }
                            },
                        )
                    }
                    subScreen(AppRoutes.EVENT_EDIT, stringArgs("eventId")) { entry ->
                        EventFormScreen(editId = entry.arg("eventId"), onBack = back, onDone = { back() })
                    }
                    subScreen(AppRoutes.STUDENT, stringArgs("studentId")) { entry ->
                        FullProfileScreen(
                            studentId = entry.arg("studentId"), onBack = back,
                            onOpenChat = { open(AppRoutes.chat(it)) }, onEditProfile = { open(AppRoutes.EDIT_PROFILE) },
                            onOpenCommunity = { open(AppRoutes.community(it)) }, onOpenComments = { open(AppRoutes.comments(it)) },
                        )
                    }
                    subScreen(AppRoutes.EDIT_PROFILE) { EditProfileScreen(onBack = back) }
                    subScreen(AppRoutes.PHOTOS) { PhotosScreen(onBack = back) }
                    subScreen(AppRoutes.SAVED) {
                        SavedPostsScreen(onBack = back, onOpenComments = { open(AppRoutes.comments(it)) }, onOpenStudent = { open(AppRoutes.student(it)) })
                    }
                    subScreen(AppRoutes.FOLLOWERS) { FollowListScreen(FollowKind.Followers, back) { open(AppRoutes.student(it)) } }
                    subScreen(AppRoutes.FOLLOWING) { FollowListScreen(FollowKind.Following, back) { open(AppRoutes.student(it)) } }
                    subScreen(AppRoutes.COMMUNITIES) { CommunitiesScreen(onBack = back, onOpenCommunity = { open(AppRoutes.community(it)) }, onCreate = { open(AppRoutes.COMMUNITY_NEW) }) }
                    subScreen(AppRoutes.COMMUNITY, stringArgs("communityId")) { entry ->
                        val communityId = entry.arg("communityId")
                        CommunityDetailScreen(
                            communityId = communityId, onBack = back,
                            onEdit = { open(AppRoutes.communityEdit(communityId)) },
                            onPost = { PostComposeHandoff.communityId = communityId; open(AppRoutes.CREATE_POST) },
                            onOpenComments = { open(AppRoutes.comments(it)) },
                            onOpenStudent = { open(AppRoutes.student(it)) },
                        )
                    }
                    subScreen(AppRoutes.COMMUNITY_NEW) {
                        CommunityFormScreen(
                            editId = null, onBack = back,
                            // Replace the form with the new community so Back returns to the list.
                            onDone = { id ->
                                navController.navigate(AppRoutes.community(id)) {
                                    popUpTo(AppRoutes.COMMUNITY_NEW) { inclusive = true }
                                    launchSingleTop = true
                                }
                            },
                        )
                    }
                    subScreen(AppRoutes.COMMUNITY_EDIT, stringArgs("communityId")) { entry ->
                        CommunityFormScreen(editId = entry.arg("communityId"), onBack = back, onDone = { back() })
                    }

                    subScreen(SettingsRoutes.ROOT) {
                        SettingsScreen(onBack = back, onNavigate = open, onLogOut = onLogOut)
                    }
                    subScreen(SettingsRoutes.ACCOUNT) {
                        AccountScreen(onBack = back, onChangePassword = { open(SettingsRoutes.PASSWORD) }, onDelete = { open(SettingsRoutes.DELETE) })
                    }
                    subScreen(SettingsRoutes.PASSWORD) { ChangePasswordScreen(onBack = back) }
                    subScreen(SettingsRoutes.SECURITY) {
                        SecurityScreen(onBack = back, onPassword = { open(SettingsRoutes.PASSWORD) }, onLoginActivity = { open(SettingsRoutes.LOGIN_ACTIVITY) })
                    }
                    subScreen(SettingsRoutes.LOGIN_ACTIVITY) { LoginActivityScreen(onBack = back) }
                    subScreen(SettingsRoutes.APPEARANCE) { AppearanceScreen(themeMode, onThemeModeChange, onBack = back) }
                    subScreen(SettingsRoutes.STATUS) { AccountStatusScreen(onBack = back, onGuidelines = { open(SettingsRoutes.GUIDELINES) }) }
                    subScreen(SettingsRoutes.DELETE) { DeleteAccountScreen(onBack = back) }
                    subScreen(SettingsRoutes.PRIVACY) {
                        PrivacyScreen(
                            onBack = back, onBlocked = { open(SettingsRoutes.BLOCKED) }, onSafety = { open(SettingsRoutes.SAFETY) },
                            onDiscoverProfile = { open(SettingsRoutes.DISCOVER_PROFILE) },
                        )
                    }
                    subScreen(SettingsRoutes.DISCOVER_PROFILE) { DiscoverProfileScreen(onBack = back) }
                    subScreen(SettingsRoutes.BLOCKED) { BlockedUsersScreen(onBack = back) }
                    subScreen(SettingsRoutes.SAFETY) { SafetyScreen(onBack = back) }
                    subScreen(SettingsRoutes.NOTIFICATIONS) { NotificationsScreen(onBack = back) }
                    subScreen(SettingsRoutes.ACCESSIBILITY) { AccessibilityScreen(onBack = back) }
                    subScreen(SettingsRoutes.HELP) {
                        HelpScreen(
                            onBack = back,
                            onFaq = { open(SettingsRoutes.FAQ) }, onReport = { open(SettingsRoutes.REPORT) },
                            onGuidelines = { open(SettingsRoutes.GUIDELINES) }, onAbout = { open(SettingsRoutes.ABOUT) },
                            onTerms = { open(SettingsRoutes.TERMS) },
                        )
                    }
                    subScreen(SettingsRoutes.FAQ) { FaqScreen(onBack = back) }
                    subScreen(SettingsRoutes.TERMS) { TermsScreen(onBack = back) }
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

private fun stringArgs(vararg names: String): List<NamedNavArgument> =
    names.map { navArgument(it) { type = NavType.StringType } }

/** Reads a string route argument; deep links are untrusted input, so it is length-limited. */
private fun NavBackStackEntry.arg(name: String): String = arguments?.getString(name).orEmpty().take(64)

private fun NavGraphBuilder.subScreen(
    route: String,
    arguments: List<NamedNavArgument> = emptyList(),
    content: @Composable (NavBackStackEntry) -> Unit,
) {
    composable(
        route = route,
        arguments = arguments,
        deepLinks = listOf(navDeepLink { uriPattern = "$DEEP_LINK_SCHEME$route" }),
        enterTransition = { slideInHorizontally(tween(280)) { it / 5 } + fadeIn(tween(240)) },
        popExitTransition = { slideOutHorizontally(tween(240)) { it / 5 } + fadeOut(tween(200)) },
    ) { entry -> content(entry) }
}
