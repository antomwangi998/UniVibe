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
    const val PERMISSIONS = "settings/permissions"
    const val MESSAGING = "settings/messages"
    const val MUTED_CHATS = "settings/messages/muted"
    const val DATA_STORAGE = "settings/data-storage"
    const val SECURITY = "settings/security"
    const val LOGIN_ACTIVITY = "settings/security/login-activity"
    const val APPEARANCE = "settings/appearance"
    const val APPEARANCE_THEME = "settings/appearance/theme"
    const val APPEARANCE_FONT = "settings/appearance/font"
    const val STATUS = "settings/status"
    const val DELETE = "settings/account/delete"
    const val DEACTIVATE = "settings/account/deactivate"
    const val DATA_EXPORT = "settings/account/export"
    const val PRIVACY = "settings/privacy"
    const val PRIVACY_VISIBILITY = "settings/privacy/visibility"
    const val PRIVACY_MESSAGES = "settings/privacy/messages"
    const val MESSAGE_POLICY = "settings/privacy/messages/who-can-message"
    const val READ_RECEIPTS = "settings/privacy/messages/read-receipts"
    const val ACTIVITY_STATUS = "settings/privacy/activity-status"
    const val BLOCKED = "settings/privacy/blocked"
    const val SAFETY = "settings/privacy/safety"
    const val DISCOVER_PROFILE = "settings/privacy/discover"
    const val CAMPUS = "settings/campus"
    const val CAMPUS_IDENTITY = "settings/campus/identity"
    const val DEFAULT_FEED = "settings/campus/default-feed"
    const val LEGAL = "settings/legal"
    const val NOTIFICATIONS = "settings/notifications"
    const val NOTIFICATIONS_PUSH = "settings/notifications/phone-alerts"
    const val NOTIFICATIONS_ACTIVITY = "settings/notifications/activity"
    const val NOTIFICATIONS_EVENTS = "settings/notifications/event-notices"
    const val NOTIFICATIONS_QUIET = "settings/notifications/quiet-hours"
    const val ACCESSIBILITY = "settings/accessibility"
    const val ACCESSIBILITY_DISPLAY = "settings/accessibility/display"
    const val ACCESSIBILITY_TEXT_SIZE = "settings/accessibility/display/text-size"
    const val ACCESSIBILITY_MOTION = "settings/accessibility/motion"
    const val ACCESSIBILITY_INTERACTION = "settings/accessibility/interaction"
    const val HELP = "settings/help"
    const val FAQ = "settings/help/faq"
    const val REPORT = "settings/help/report"
    const val GUIDELINES = "settings/help/guidelines"
    const val ABOUT = "settings/help/about"
    const val TERMS = "settings/help/terms"
    const val PRIVACY_POLICY = "settings/help/privacy-policy"
}

/** Routes with arguments. Each is also a deep link: `univibe://<route>`. */
object AppRoutes {
    const val CHAT = "chats/{chatId}"
    const val GROUP_INFO = "chats/{chatId}/info"
    const val GROUP_NEW = "group/new"
    const val STORY_NEW = "story/new"
    const val STORY_VIEW = "story/view/{authorId}"
    const val COMMENTS = "post/{postId}/comments"
    const val EVENT = "events/{eventId}"
    const val EVENT_NEW = "event/new"
    const val EVENT_EDIT = "event/{eventId}/edit"
    const val EVENT_ATTENDEES = "events/{eventId}/attendees"
    const val MODERATION_REPORTS = "moderation/event-reports"
    const val MODERATION_EVENT = "moderation/events/{eventId}"
    const val CREATE_POST = "post/new"
    const val NOTIFICATIONS = "notifications"
    const val SEARCH = "search"
    const val STUDENT = "student/{studentId}"
    const val COMMUNITIES = "communities"
    const val COMMUNITY = "communities/{communityId}"
    const val COMMUNITY_NEW = "community/new"
    const val COMMUNITY_EDIT = "community/{communityId}/edit"
    const val EDIT_PROFILE = "profile/edit"
    const val PHOTOS = "profile/photos"
    const val SAVED = "profile/saved"
    const val FOLLOWERS = "profile/followers"
    const val FOLLOWING = "profile/following"

    fun chat(id: String) = "chats/$id"
    fun storyView(authorId: String) = "story/view/$authorId"
    fun groupInfo(chatId: String) = "chats/$chatId/info"
    fun comments(postId: String) = "post/$postId/comments"
    fun event(id: String) = "events/$id"
    fun eventEdit(id: String) = "event/$id/edit"
    fun eventAttendees(id: String) = "events/$id/attendees"
    fun moderationEvent(id: String) = "moderation/events/$id"
    fun student(id: String) = "student/$id"
    fun community(id: String) = "communities/$id"
    fun communityEdit(id: String) = "community/$id/edit"
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
