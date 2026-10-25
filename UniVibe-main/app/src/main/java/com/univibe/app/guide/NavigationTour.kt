package com.univibe.app.guide

import com.univibe.app.navigation.TopLevelDestination
import com.univibe.app.ui.components.VibeIcons

const val NAVIGATION_TOUR_ID = "app_navigation"

/** Id of the control the bottom bar reports for a tab, e.g. "tab.home". */
fun tabTargetId(route: String) = "tab.$route"

/**
 * The first tour: the real bottom bar (Home, Discover, Chats, Events, Profile) plus the bell on Home. Notifications,
 * search and communities are not tabs in UniVibe, so the tour says where they actually are instead of pointing at a tab.
 */
fun registerNavigationTour() {
    val home = TopLevelDestination.Home.route
    TutorialRegistry.register(
        Tutorial(
            id = NAVIGATION_TOUR_ID,
            title = "Getting around UniVibe",
            description = "The five tabs and where to find notifications, search and settings.",
            entryRoute = home,
            steps = listOf(
                GuideStep("home", tabTargetId(home), "Home", "Posts, campus updates and events from people and communities you follow appear here. Pull down to refresh.", VibeIcons.HomeSelected),
                GuideStep("bell", "home.bell", "Notifications", "The bell shows new reactions, comments, followers, messages and event updates. A dot means something is waiting.", VibeIcons.BellFilled, kind = StepKind.Info, route = home),
                GuideStep("discover", tabTargetId(TopLevelDestination.Discover.route), "Discover", "Find people, communities and events on campus. The search icon at the top looks up students and posts.", VibeIcons.DiscoverSelected),
                GuideStep("chats", tabTargetId(TopLevelDestination.Chats.route), "Chats", "Your conversations and group chats live here. Start a new one from the top bar.", VibeIcons.ChatsSelected),
                GuideStep("events", tabTargetId(TopLevelDestination.Events.route), "Events", "See what is on, say you're going or interested, and host your own.", VibeIcons.EventsSelected),
                GuideStep("profile", tabTargetId(TopLevelDestination.Profile.route), "Profile", "Edit how others see you, find your posts, followers and saved items. Settings (privacy, notifications, appearance) is the gear at the top of this tab.", VibeIcons.ProfileSelected),
                GuideStep(
                    "done", null, "You're ready to explore UniVibe!",
                    "You can replay this or take other tours any time from Settings ▸ Help & support ▸ Help and tutorials.",
                    VibeIcons.Check, kind = StepKind.Completion, route = home, ctaLabel = "Take me to Home",
                ),
            ),
        ),
    )
}
