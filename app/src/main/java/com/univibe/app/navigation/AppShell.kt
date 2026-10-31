package com.univibe.app.navigation

import androidx.compose.ui.unit.dp
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.AnimatedContentTransitionScope
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
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.univibe.app.app.AppContainer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
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
import com.univibe.app.features.communities.ChannelScreen
import com.univibe.app.features.communities.ManageChannelsScreen
import com.univibe.app.features.communities.CommunityDetailScreen
import com.univibe.app.features.communities.ThreadScreen
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
import com.univibe.app.features.settings.CampusIdentityScreen
import com.univibe.app.features.settings.CampusScreen
import com.univibe.app.features.settings.DefaultFeedScreen
import com.univibe.app.features.settings.LegalScreen
import com.univibe.app.features.settings.AccessibilityDisplayScreen
import com.univibe.app.features.settings.AccessibilityScreen
import com.univibe.app.features.settings.AccountScreen
import com.univibe.app.features.settings.AccountStatusScreen
import com.univibe.app.features.settings.AppearanceScreen
import com.univibe.app.features.settings.FontScreen
import com.univibe.app.features.settings.MotionScreen
import com.univibe.app.features.settings.TextSizeScreen
import com.univibe.app.features.settings.ThemeScreen
import com.univibe.app.features.settings.TouchAndHapticsScreen
import com.univibe.app.features.settings.LoginActivityScreen
import com.univibe.app.features.settings.DataExportScreen
import com.univibe.app.features.settings.DataStorageScreen
import com.univibe.app.features.settings.DeactivateAccountScreen
import com.univibe.app.features.settings.MessagingSettingsScreen
import com.univibe.app.features.settings.MutedConversationsScreen
import com.univibe.app.features.settings.PermissionsScreen
import com.univibe.app.features.settings.SecurityScreen
import com.univibe.app.features.settings.BlockedUsersScreen
import com.univibe.app.features.settings.ChangePasswordScreen
import com.univibe.app.features.settings.DeleteAccountScreen
import com.univibe.app.features.settings.FaqScreen
import com.univibe.app.features.settings.GuidelinesScreen
import com.univibe.app.features.settings.TutorialsScreen
import com.univibe.app.guide.GuideHost
import com.univibe.app.guide.GuideWelcome
import com.univibe.app.guide.NAVIGATION_TOUR_ID
import com.univibe.app.guide.TutorialRegistry
import com.univibe.app.guide.registerGuideTours
import com.univibe.app.guide.rememberGuideController
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.launch
import com.univibe.app.features.settings.HelpScreen
import com.univibe.app.features.settings.EventNoticesScreen
import com.univibe.app.features.settings.NotificationActivityScreen
import com.univibe.app.features.settings.NotificationsScreen
import com.univibe.app.features.settings.PhoneAlertsScreen
import com.univibe.app.features.settings.QuietHoursScreen
import com.univibe.app.features.settings.DiscoverProfileScreen
import com.univibe.app.features.settings.ActivityStatusScreen
import com.univibe.app.features.settings.MessagePolicyScreen
import com.univibe.app.features.settings.PrivacyMessagesScreen
import com.univibe.app.features.settings.PrivacyScreen
import com.univibe.app.features.settings.PrivacyVisibilityScreen
import com.univibe.app.features.settings.ReadReceiptsScreen
import com.univibe.app.features.settings.ReportProblemScreen
import com.univibe.app.features.settings.SafetyScreen
import com.univibe.app.features.settings.SettingsScreen
import com.univibe.app.features.settings.PrivacyPolicyScreen
import com.univibe.app.features.settings.TermsScreen
import com.univibe.app.ui.components.LocalChromeHidden
import com.univibe.app.ui.components.LocalFloatingBarVisible
import com.univibe.app.ui.components.VibeBottomBar
import com.univibe.app.ui.components.glassBackdropSource

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

/**
 * Which way a swipe moves through the tabs. With this true, swiping right goes to the next tab (Home -> Discover) and
 * swiping left goes back. Set it to false for the usual pager feel, where swiping left goes to the next tab.
 */
private const val SWIPE_RIGHT_GOES_FORWARD = true
private const val SWIPE_DISTANCE_DP = 96

/**
 * A horizontal swipe on a tab screen moves to the neighbouring tab. It only counts when nothing inside the screen used
 * the swipe (a chip row, a photo gallery, the swipe-to-connect deck and video seek bars all use their own swipes first),
 * and it does nothing on screens that are not tabs.
 */
private fun Modifier.swipeBetweenTabs(currentRoute: String?, tabRoutes: List<String>, selectTab: (String) -> Unit): Modifier =
    pointerInput(currentRoute) {
        val index = tabRoutes.indexOf(currentRoute)
        if (index < 0) return@pointerInput
        val threshold = SWIPE_DISTANCE_DP.dp.toPx()
        var total = 0f
        detectHorizontalDragGestures(
            onDragStart = { total = 0f },
            onDragCancel = { total = 0f },
            onDragEnd = {
                if (kotlin.math.abs(total) >= threshold) {
                    val forward = (total > 0f) == SWIPE_RIGHT_GOES_FORWARD
                    tabRoutes.getOrNull(if (forward) index + 1 else index - 1)?.let(selectTab)
                }
                total = 0f
            },
            onHorizontalDrag = { _, dx -> total += dx },
        )
    }

private fun AnimatedContentTransitionScope<NavBackStackEntry>.tabSlideIn(from: String?, to: String?, tabs: List<String>): EnterTransition? {
    val a = tabs.indexOf(from)
    val b = tabs.indexOf(to)
    if (a < 0 || b < 0 || a == b) return null
    val side = if (b > a) AnimatedContentTransitionScope.SlideDirection.Left else AnimatedContentTransitionScope.SlideDirection.Right
    return slideIntoContainer(side, tween(260)) + fadeIn(tween(200))
}

private fun AnimatedContentTransitionScope<NavBackStackEntry>.tabSlideOut(from: String?, to: String?, tabs: List<String>): ExitTransition? {
    val a = tabs.indexOf(from)
    val b = tabs.indexOf(to)
    if (a < 0 || b < 0 || a == b) return null
    val side = if (b > a) AnimatedContentTransitionScope.SlideDirection.Left else AnimatedContentTransitionScope.SlideDirection.Right
    return slideOutOfContainer(side, tween(260)) + fadeOut(tween(160))
}

@Composable
fun AppShell(themeMode: ThemeMode, onThemeModeChange: (ThemeMode) -> Unit, onLogOut: () -> Unit, userId: String) {
    val navController = rememberNavController()
    remember { registerGuideTours() }
    val guide = rememberGuideController(userId)
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val barItems = remember { TopLevelDestination.entries.map { it.toBarItem() } }
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val isTopLevel = TopLevelDestination.entries.any { it.route == currentRoute }
    val tabRoutes = remember { TopLevelDestination.entries.map { it.route } }

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
        if (route == currentRoute) {
            // Tapping the current tab again: back to the top, with the bars showing.
            hiddenByScroll.value = false
            TabReselect.fire(route)
        } else {
            navController.navigate(route) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }
    val open: (String) -> Unit = { route -> navController.navigate(route) { launchSingleTop = true } }
    // The guided tour opens tabs the same way the bottom bar does, so Back and saved tab state behave normally.
    val goToTab: (String) -> Unit = { route ->
        if (TopLevelDestination.entries.any { it.route == route }) {
            navController.navigate(route) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        } else open(route)
    }
    SideEffect { guide.navigate = goToTab }
    val welcomeSeen by remember(userId) { AppContainer.guideStore.welcomeSeen(userId) }.collectAsStateWithLifecycle(initialValue = null)
    var welcomeDismissed by rememberSaveable { mutableStateOf(false) }
    val guideScope = rememberCoroutineScope()
    val back: () -> Unit = { navController.popBackStack() }
    val unreadNotifications by AppContainer.notificationRepository.unreadCount.collectAsStateWithLifecycle()
    // Chats badge: the real unread total (null until it has been read; the badge stays hidden then).
    val unreadMessages by AppContainer.chatUnread.total.collectAsStateWithLifecycle()
    // Re-read when the app resumes and when the student comes back from a conversation (opening one clears it on the server).
    var resumeTick by remember { mutableStateOf(0) }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { resumeTick++ }
    LaunchedEffect(currentRoute, resumeTick) {
        if (isTopLevel) runCatching { AppContainer.chatUnread.refresh() }
    }
    // Keep the bell badge in step with the server (does nothing in the offline demo).
    LaunchedEffect(Unit) {
        while (true) {
            runCatching { AppContainer.notificationRepository.refreshUnread() }
            kotlinx.coroutines.delay(60_000)
        }
    }

    // Null (nothing happens) below Android 12, on low-RAM phones and in High contrast.
    val backdrop = com.univibe.app.ui.components.rememberGlassBackdropState()
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            // Content runs edge to edge; the bar hovers above it.
            // Percentage for any media upload in progress; drawn above the screens.
            com.univibe.app.ui.components.UploadBanner(Modifier.align(androidx.compose.ui.Alignment.TopCenter).zIndex(1f))
            com.univibe.app.ui.components.OfflineBanner(Modifier.align(androidx.compose.ui.Alignment.TopCenter).zIndex(1f))
            CompositionLocalProvider(
                LocalFloatingBarVisible provides barVisible, LocalChromeHidden provides hiddenByScroll.value,
                // Any post card can open its community without every screen threading a callback through.
                com.univibe.app.ui.components.LocalOpenCommunity provides { id: String -> open(AppRoutes.community(id)) },
                com.univibe.app.ui.components.LocalOpenCommunityList provides { open(AppRoutes.COMMUNITIES) },
                // A tapped sponsored card opens what it advertises, from any list that shows one.
                com.univibe.app.ui.components.LocalSponsoredTargets provides remember {
                    com.univibe.app.ui.components.SponsoredTargets(
                        onEvent = { id -> open(AppRoutes.event(id)) }, onPost = { id -> open(AppRoutes.comments(id)) }, onBusiness = { id -> open(AppRoutes.business(id)) },
                    )
                },
            ) {
                NavHost(
                    navController = navController,
                    startDestination = TopLevelDestination.Home.route,
                    modifier = Modifier.fillMaxSize().glassBackdropSource(backdrop).nestedScroll(scrollConnection).swipeBetweenTabs(currentRoute, tabRoutes, selectTab),
                    // Moving between two tabs slides toward the tab's side; every other change keeps the soft fade.
                    enterTransition = { tabSlideIn(initialState.destination.route, targetState.destination.route, tabRoutes) ?: (fadeIn(tween(220, delayMillis = 40)) + scaleIn(tween(220, delayMillis = 40), initialScale = 0.98f)) },
                    exitTransition = { tabSlideOut(initialState.destination.route, targetState.destination.route, tabRoutes) ?: fadeOut(tween(90)) },
                    popEnterTransition = { tabSlideIn(initialState.destination.route, targetState.destination.route, tabRoutes) ?: (fadeIn(tween(220, delayMillis = 40)) + scaleIn(tween(220, delayMillis = 40), initialScale = 0.98f)) },
                    popExitTransition = { tabSlideOut(initialState.destination.route, targetState.destination.route, tabRoutes) ?: fadeOut(tween(90)) },
                ) {
                    tab(TopLevelDestination.Home.route) {
                        HomeScreen(
                            onOpenEvents = { selectTab(TopLevelDestination.Events.route) },
                            onOpenEvent = { open(AppRoutes.event(it)) },
                            onOpenComments = { open(AppRoutes.comments(it)) },
                            onOpenStudent = { open(AppRoutes.student(it)) },
                            onCreatePost = { open(AppRoutes.CREATE_POST) },
                            unreadNotifications = unreadNotifications,
                            onOpenNotifications = { open(AppRoutes.NOTIFICATIONS) },
                            onOpenDiscover = { selectTab(TopLevelDestination.Discover.route) },
                        )
                    }
                    tab(TopLevelDestination.Discover.route) {
                        DiscoverScreen(
                            onOpenStudent = { open(AppRoutes.student(it)) },
                            onOpenComments = { open(AppRoutes.comments(it)) },
                            onOpenEvent = { open(AppRoutes.event(it)) },
                            onOpenSearch = { open(AppRoutes.SEARCH) },
                        )
                    }
                    tab(TopLevelDestination.Chats.route) { ChatsScreen(onOpenChat = { open(AppRoutes.chat(it)) }, onNewGroup = { open(AppRoutes.GROUP_NEW) }, onAddStory = { open(AppRoutes.STORY_NEW) }, onOpenStory = { open(AppRoutes.storyView(it)) }, onManageStories = { open(AppRoutes.STORY_MANAGE) }) }
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
                                onOpenPromotions = { open(AppRoutes.PROMOTIONS) },
                                onOpenBusinesses = { open(AppRoutes.BUSINESSES) },
                                unreadNotifications = unreadNotifications,
                                onOpenNotifications = { open(AppRoutes.NOTIFICATIONS) },
                                onOpenPost = { open(AppRoutes.comments(it)) },
                            ),
                        )
                    }

                    subScreen(AppRoutes.CHAT, stringArgs("chatId")) { entry ->
                        androidx.compose.runtime.CompositionLocalProvider(com.univibe.app.features.events.LocalOpenEvent provides { open(AppRoutes.event(it)) }) {
                            ConversationScreen(
                                chatId = entry.arg("chatId"), onBack = back,
                                onOpenGroupInfo = { open(AppRoutes.groupInfo(entry.arg("chatId"))) },
                                onOpenStudent = { open(AppRoutes.student(it)) },
                                onOpenPremium = { open(AppRoutes.PREMIUM) },
                            )
                        }
                    }
                    subScreen(AppRoutes.STORY_VIEW, stringArgs("authorId")) { entry ->
                        com.univibe.app.features.chats.stories.StoryViewerScreen(startAuthorId = entry.arg("authorId"), onClose = back)
                    }
                    subScreen(AppRoutes.STORY_MANAGE) {
                        com.univibe.app.features.chats.stories.StoryManagementScreen(
                            onBack = back,
                            onOpenMyStory = { open(AppRoutes.storyView("me")) },
                            onAddStory = { open(AppRoutes.STORY_NEW) },
                        )
                    }
                    subScreen(AppRoutes.STORY_NEW) {
                        com.univibe.app.features.chats.stories.CreateStoryScreen(onBack = back, onPublished = back)
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
                    subScreen(AppRoutes.SEARCH) {
                        com.univibe.app.features.search.SearchScreen(
                            onBack = back,
                            onOpenStudent = { open(AppRoutes.student(it)) },
                            onOpenPost = { open(AppRoutes.comments(it)) },
                            onOpenEvent = { open(AppRoutes.event(it)) },
                        )
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
                                    NotificationTarget.Promotions -> open(AppRoutes.PROMOTIONS)
                                    NotificationTarget.None -> Unit
                                }
                            },
                        )
                    }
                    subScreen(AppRoutes.CREATE_POST) { CreatePostScreen(onBack = back) }
                    subScreen(AppRoutes.PREMIUM) { com.univibe.app.features.premium.PremiumScreen(onBack = back, onCustomizeProfile = { open(AppRoutes.PROFILE_LOOK) }) }
                    subScreen(AppRoutes.THEMES_STUDIO) { com.univibe.app.features.themes.ThemesStudioScreen(onBack = back, onOpenPremium = { open(AppRoutes.PREMIUM) }) }
                    subScreen(AppRoutes.PROFILE_LOOK) { com.univibe.app.features.profile.ProfileLookScreen(onBack = back, onOpenPremium = { open(AppRoutes.PREMIUM) }) }
                    subScreen(AppRoutes.PROMOTIONS) {
                        com.univibe.app.features.promotions.PromotionsScreen(
                            onBack = back, onNewPromotion = { open(AppRoutes.PROMOTION_NEW) },
                            onOpenCampaign = { open(AppRoutes.promotion(it)) }, onMyBusinesses = { open(AppRoutes.MY_BUSINESSES) },
                        )
                    }
                    subScreen(AppRoutes.PROMOTION_NEW) {
                        com.univibe.app.features.promotions.NewPromotionScreen(
                            onBack = back, onAddBusiness = { open(AppRoutes.BUSINESS_NEW) },
                            // Replace the form with the new promotion so Back returns to the list.
                            onCreated = { id ->
                                navController.navigate(AppRoutes.promotion(id)) {
                                    popUpTo(AppRoutes.PROMOTION_NEW) { inclusive = true }
                                    launchSingleTop = true
                                }
                            },
                        )
                    }
                    subScreen(AppRoutes.PROMOTION, stringArgs("campaignId")) { entry ->
                        com.univibe.app.features.promotions.CampaignScreen(campaignId = entry.arg("campaignId"), onBack = back)
                    }
                    subScreen(AppRoutes.MY_BUSINESSES) {
                        com.univibe.app.features.promotions.MyBusinessesScreen(onBack = back, onAdd = { open(AppRoutes.BUSINESS_NEW) }, onEdit = { open(AppRoutes.businessEdit(it)) })
                    }
                    subScreen(AppRoutes.BUSINESS_NEW) { com.univibe.app.features.promotions.BusinessFormScreen(editId = null, onBack = back, onDone = { back() }) }
                    subScreen(AppRoutes.BUSINESS_EDIT, stringArgs("businessId")) { entry ->
                        com.univibe.app.features.promotions.BusinessFormScreen(editId = entry.arg("businessId"), onBack = back, onDone = { back() })
                    }
                    subScreen(AppRoutes.BUSINESSES) { com.univibe.app.features.promotions.BusinessDirectoryScreen(onBack = back, onOpen = { open(AppRoutes.business(it)) }) }
                    subScreen(AppRoutes.BUSINESS, stringArgs("businessId")) { entry ->
                        com.univibe.app.features.promotions.BusinessDetailScreen(businessId = entry.arg("businessId"), onBack = back)
                    }
                    subScreen(AppRoutes.EVENT, stringArgs("eventId")) { entry ->
                        EventDetailsScreen(
                            eventId = entry.arg("eventId"), onBack = back, onEdit = { open(AppRoutes.eventEdit(entry.arg("eventId"))) },
                            onOpenAttendees = { open(AppRoutes.eventAttendees(entry.arg("eventId"))) },
                            onModerate = { open(AppRoutes.moderationEvent(entry.arg("eventId"))) },
                        )
                    }
                    subScreen(AppRoutes.MODERATION_REPORTS) {
                        com.univibe.app.features.moderation.EventReportsScreen(onBack = back, onOpenEvent = { open(AppRoutes.moderationEvent(it)) })
                    }
                    subScreen(AppRoutes.MODERATION_EVENT, stringArgs("eventId")) { entry ->
                        com.univibe.app.features.moderation.ModeratorEventScreen(eventId = entry.arg("eventId"), onBack = back)
                    }
                    subScreen(AppRoutes.EVENT_ATTENDEES, stringArgs("eventId")) { entry ->
                        com.univibe.app.features.events.EventAttendeesScreen(eventId = entry.arg("eventId"), onBack = back, onOpenStudent = { open(AppRoutes.student(it)) })
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
                            onOpenFollowers = { open(AppRoutes.studentFollowers(it)) }, onOpenFollowing = { open(AppRoutes.studentFollowing(it)) },
                        )
                    }
                    subScreen(AppRoutes.STUDENT_FOLLOWERS, stringArgs("studentId")) { entry ->
                        FollowListScreen(FollowKind.Followers, back, entry.arg("studentId")) { open(AppRoutes.student(it)) }
                    }
                    subScreen(AppRoutes.STUDENT_FOLLOWING, stringArgs("studentId")) { entry ->
                        FollowListScreen(FollowKind.Following, back, entry.arg("studentId")) { open(AppRoutes.student(it)) }
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
                            onOpenEvent = { open(AppRoutes.event(it)) },
                            onOpenChannel = { open(AppRoutes.channel(communityId, it)) },
                            onManageChannels = { open(AppRoutes.manageChannels(communityId)) },
                        )
                    }
                    subScreen(AppRoutes.COMMUNITY_MANAGE_CHANNELS, stringArgs("communityId")) { entry ->
                        ManageChannelsScreen(communityId = entry.arg("communityId"), onBack = back)
                    }
                    subScreen(AppRoutes.COMMUNITY_CHANNEL, stringArgs("communityId", "channelId")) { entry ->
                        val communityId = entry.arg("communityId")
                        val channelId = entry.arg("channelId")
                        ChannelScreen(
                            communityId = communityId, channelId = channelId, onBack = back,
                            onOpenThread = { open(AppRoutes.thread(communityId, channelId, it)) },
                            onOpenStudent = { open(AppRoutes.student(it)) },
                        )
                    }
                    subScreen(AppRoutes.COMMUNITY_THREAD, stringArgs("communityId", "channelId", "messageId")) { entry ->
                        ThreadScreen(
                            communityId = entry.arg("communityId"), channelId = entry.arg("channelId"), messageId = entry.arg("messageId"),
                            onBack = back, onOpenStudent = { open(AppRoutes.student(it)) },
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
                        AccountScreen(
                            onBack = back, onChangePassword = { open(SettingsRoutes.PASSWORD) }, onDelete = { open(SettingsRoutes.DELETE) },
                            onDeactivate = { open(SettingsRoutes.DEACTIVATE) }, onExport = { open(SettingsRoutes.DATA_EXPORT) },
                        )
                    }
                    subScreen(SettingsRoutes.PASSWORD) { ChangePasswordScreen(onBack = back) }
                    subScreen(SettingsRoutes.SECURITY) {
                        SecurityScreen(onBack = back, onPassword = { open(SettingsRoutes.PASSWORD) }, onLoginActivity = { open(SettingsRoutes.LOGIN_ACTIVITY) })
                    }
                    subScreen(SettingsRoutes.LOGIN_ACTIVITY) { LoginActivityScreen(onBack = back) }
                    subScreen(SettingsRoutes.PERMISSIONS) { PermissionsScreen(onBack = back) }
                    subScreen(SettingsRoutes.DATA_STORAGE) { DataStorageScreen(onBack = back) }
                    subScreen(SettingsRoutes.MESSAGING) {
                        MessagingSettingsScreen(
                            onBack = back, onPrivacy = { open(SettingsRoutes.PRIVACY_MESSAGES) },
                            onMuted = { open(SettingsRoutes.MUTED_CHATS) }, onBlocked = { open(SettingsRoutes.BLOCKED) },
                        )
                    }
                    subScreen(SettingsRoutes.MUTED_CHATS) { MutedConversationsScreen(onBack = back) }
                    subScreen(SettingsRoutes.APPEARANCE) {
                        AppearanceScreen(
                            themeMode = themeMode, onBack = back,
                            onTheme = { open(SettingsRoutes.APPEARANCE_THEME) },
                            onFont = { open(SettingsRoutes.APPEARANCE_FONT) },
                            onTextSize = { open(SettingsRoutes.ACCESSIBILITY_TEXT_SIZE) },
                        )
                    }
                    subScreen(SettingsRoutes.APPEARANCE_THEME) { ThemeScreen(themeMode, onThemeModeChange, onBack = back) }
                    subScreen(SettingsRoutes.APPEARANCE_FONT) { FontScreen(onBack = back) }
                    subScreen(SettingsRoutes.STATUS) { AccountStatusScreen(onBack = back, onGuidelines = { open(SettingsRoutes.GUIDELINES) }) }
                    subScreen(SettingsRoutes.DELETE) { DeleteAccountScreen(onBack = back) }
                    subScreen(SettingsRoutes.DEACTIVATE) { DeactivateAccountScreen(onBack = back) }
                    subScreen(SettingsRoutes.DATA_EXPORT) { DataExportScreen(onBack = back) }
                    subScreen(SettingsRoutes.PRIVACY) {
                        PrivacyScreen(
                            onBack = back, onBlocked = { open(SettingsRoutes.BLOCKED) }, onSafety = { open(SettingsRoutes.SAFETY) },
                            onVisibility = { open(SettingsRoutes.PRIVACY_VISIBILITY) },
                            onMessages = { open(SettingsRoutes.PRIVACY_MESSAGES) },
                            onActivityStatus = { open(SettingsRoutes.ACTIVITY_STATUS) },
                        )
                    }
                    subScreen(SettingsRoutes.PRIVACY_VISIBILITY) {
                        PrivacyVisibilityScreen(onBack = back, onDiscoverProfile = { open(SettingsRoutes.DISCOVER_PROFILE) })
                    }
                    subScreen(SettingsRoutes.PRIVACY_MESSAGES) {
                        PrivacyMessagesScreen(
                            onBack = back, onWhoCanMessage = { open(SettingsRoutes.MESSAGE_POLICY) },
                            onReadReceipts = { open(SettingsRoutes.READ_RECEIPTS) }, onBlocked = { open(SettingsRoutes.BLOCKED) },
                        )
                    }
                    subScreen(SettingsRoutes.MESSAGE_POLICY) { MessagePolicyScreen(onBack = back) }
                    subScreen(SettingsRoutes.READ_RECEIPTS) { ReadReceiptsScreen(onBack = back) }
                    subScreen(SettingsRoutes.ACTIVITY_STATUS) { ActivityStatusScreen(onBack = back) }
                    subScreen(SettingsRoutes.DISCOVER_PROFILE) { DiscoverProfileScreen(onBack = back) }
                    subScreen(SettingsRoutes.BLOCKED) { BlockedUsersScreen(onBack = back) }
                    subScreen(SettingsRoutes.SAFETY) { SafetyScreen(onBack = back) }
                    subScreen(SettingsRoutes.NOTIFICATIONS) {
                        NotificationsScreen(
                            onBack = back,
                            onPhoneAlerts = { open(SettingsRoutes.NOTIFICATIONS_PUSH) },
                            onActivity = { open(SettingsRoutes.NOTIFICATIONS_ACTIVITY) },
                            onEventNotices = { open(SettingsRoutes.NOTIFICATIONS_EVENTS) },
                            onQuietHours = { open(SettingsRoutes.NOTIFICATIONS_QUIET) },
                        )
                    }
                    subScreen(SettingsRoutes.NOTIFICATIONS_PUSH) { PhoneAlertsScreen(onBack = back) }
                    subScreen(SettingsRoutes.NOTIFICATIONS_ACTIVITY) { NotificationActivityScreen(onBack = back) }
                    subScreen(SettingsRoutes.NOTIFICATIONS_EVENTS) { EventNoticesScreen(onBack = back) }
                    subScreen(SettingsRoutes.NOTIFICATIONS_QUIET) { QuietHoursScreen(onBack = back) }
                    subScreen(SettingsRoutes.ACCESSIBILITY) {
                        AccessibilityScreen(
                            onBack = back,
                            onDisplay = { open(SettingsRoutes.ACCESSIBILITY_DISPLAY) },
                            onMotion = { open(SettingsRoutes.ACCESSIBILITY_MOTION) },
                            onInteraction = { open(SettingsRoutes.ACCESSIBILITY_INTERACTION) },
                        )
                    }
                    subScreen(SettingsRoutes.ACCESSIBILITY_DISPLAY) {
                        AccessibilityDisplayScreen(onBack = back, onTextSize = { open(SettingsRoutes.ACCESSIBILITY_TEXT_SIZE) })
                    }
                    subScreen(SettingsRoutes.ACCESSIBILITY_TEXT_SIZE) { TextSizeScreen(onBack = back) }
                    subScreen(SettingsRoutes.ACCESSIBILITY_MOTION) { MotionScreen(onBack = back) }
                    subScreen(SettingsRoutes.ACCESSIBILITY_INTERACTION) { TouchAndHapticsScreen(onBack = back) }
                    subScreen(SettingsRoutes.HELP) {
                        HelpScreen(
                            onBack = back,
                            onFaq = { open(SettingsRoutes.FAQ) }, onReport = { open(SettingsRoutes.REPORT) },
                            onGuidelines = { open(SettingsRoutes.GUIDELINES) }, onLegal = { open(SettingsRoutes.LEGAL) },
                            onTutorials = { open(SettingsRoutes.TUTORIALS) },
                        )
                    }
                    subScreen(SettingsRoutes.TUTORIALS) {
                        TutorialsScreen(userId, onBack = back, onStart = { tutorial, from ->
                            goToTab(tutorial.entryRoute)
                            guide.start(tutorial, from)
                        })
                    }
                    subScreen(SettingsRoutes.LEGAL) { LegalScreen(onBack = back, onNavigate = open) }
                    subScreen(SettingsRoutes.CAMPUS) { CampusScreen(onBack = back, onNavigate = open) }
                    subScreen(SettingsRoutes.CAMPUS_IDENTITY) { CampusIdentityScreen(onBack = back) }
                    subScreen(SettingsRoutes.DEFAULT_FEED) { DefaultFeedScreen(onBack = back) }
                    subScreen(SettingsRoutes.FAQ) { FaqScreen(onBack = back) }
                    subScreen(SettingsRoutes.TERMS) { TermsScreen(onBack = back) }
                    subScreen(SettingsRoutes.PRIVACY_POLICY) { PrivacyPolicyScreen(onBack = back) }
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
                VibeBottomBar(items = barItems, selectedRoute = currentRoute, onSelect = selectTab, backdrop = backdrop, badges = mapOf(TopLevelDestination.Chats.route to unreadMessages))
            }

            // Guided tour and first-time welcome. Both draw nothing when idle, and Back closes either one.
            GuideHost(guide, Modifier.zIndex(10f))
            if (welcomeSeen == false && !welcomeDismissed) {
                val finishWelcome = {
                    welcomeDismissed = true
                    guideScope.launch { runCatching { AppContainer.guideStore.markWelcomeSeen(userId) } }
                }
                GuideWelcome(
                    onStartTour = { finishWelcome(); TutorialRegistry.get(NAVIGATION_TOUR_ID)?.let { guide.start(it) } },
                    onFinished = { finishWelcome() },
                    modifier = Modifier.zIndex(20f),
                )
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
