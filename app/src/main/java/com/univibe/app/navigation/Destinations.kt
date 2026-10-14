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
    const val DISCOVER_PROFILE = "settings/privacy/discover"
    const val NOTIFICATIONS = "settings/notifications"
    const val ACCESSIBILITY = "settings/accessibility"
    const val HELP = "settings/help"
    const val FAQ = "settings/help/faq"
    const val REPORT = "settings/help/report"
    const val GUIDELINES = "settings/help/guidelines"
    const val ABOUT = "settings/help/about"
}

/** Routes with arguments. Each is also a deep link: `univibe://<route>`. */
object AppRoutes {
    const val CHAT = "chats/{chatId}"
    const val GROUP_INFO = "chats/{chatId}/info"
    const val GROUP_NEW = "group/new"
    const val COMMENTS = "post/{postId}/comments"
    const val EVENT = "events/{eventId}"
    const val CREATE_POST = "post/new"
    const val NOTIFICATIONS = "notifications"
    const val STUDENT = "student/{studentId}"
    const val COMMUNITIES = "communities"
    const val COMMUNITY = "communities/{communityId}"
    const val EDIT_PROFILE = "profile/edit"
    const val PHOTOS = "profile/photos"
    const val SAVED = "profile/saved"
    const val FOLLOWERS = "profile/followers"
    const val FOLLOWING = "profile/following"

    fun chat(id: String) = "chats/$id"
    fun groupInfo(chatId: String) = "chats/$chatId/info"
    fun comments(postId: String) = "post/$postId/comments"
    fun event(id: String) = "events/$id"
    fun student(id: String) = "student/$id"
    fun community(id: String) = "communities/$id"
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
