package com.univibe.app.navigation

import androidx.compose.ui.graphics.vector.ImageVector
import com.univibe.app.ui.components.VibeBarItem
import com.univibe.app.ui.components.VibeIcons

object Routes {
    const val SETTINGS = SettingsRoutes.ROOT
}

/** Every route is also a deep link: `univibe://<route>` (see docs/DEEP_LINKS.md). */
object SettingsRoutes {
    const val ROOT = "settings"
    const val ACCOUNT = "settings/account"
    const val PASSWORD = "settings/account/password"
    const val DELETE = "settings/account/delete"
    const val PRIVACY = "settings/privacy"
    const val BLOCKED = "settings/privacy/blocked"
    const val SAFETY = "settings/privacy/safety"
    const val NOTIFICATIONS = "settings/notifications"
    const val ACCESSIBILITY = "settings/accessibility"
    const val HELP = "settings/help"
    const val FAQ = "settings/help/faq"
    const val REPORT = "settings/help/report"
    const val GUIDELINES = "settings/help/guidelines"
    const val ABOUT = "settings/help/about"
}

enum class TopLevelDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
) {
    Home("home", "Home", VibeIcons.Home, VibeIcons.HomeSelected),
    Discover("discover", "Discover", VibeIcons.Discover, VibeIcons.DiscoverSelected),
    Chats("chats", "Chats", VibeIcons.Chats, VibeIcons.ChatsSelected),
    Events("events", "Events", VibeIcons.Events, VibeIcons.EventsSelected),
    Profile("profile", "Profile", VibeIcons.Profile, VibeIcons.ProfileSelected);

    fun toBarItem() = VibeBarItem(route, label, icon, selectedIcon)
}
