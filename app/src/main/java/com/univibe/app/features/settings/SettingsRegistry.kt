package com.univibe.app.features.settings

import com.univibe.app.navigation.AppRoutes
import com.univibe.app.navigation.SettingsRoutes

/**
 * Every settings destination the app really has, described once. The hub's categories and search both read this list,
 * so a screen that is added here is findable, and a screen that is not built is simply not listed.
 *
 * [path] is the breadcrumb shown in search results ("Privacy & safety › Read receipts"). A control that lives
 * on a screen (a switch, say) points at that screen's [route]; there is no per-control deep link.
 */
data class SettingsEntry(
    val id: String,
    val title: String,
    val description: String,
    val route: String,
    val path: List<String>,
    val keywords: List<String> = emptyList(),
)

object SettingsCategories {
    const val ACCOUNT = "Account"
    const val SECURITY = "Security"
    const val PRIVACY = "Privacy & safety"
    const val NOTIFICATIONS = "Notifications"
    const val MESSAGES = "Messages"
    const val APPEARANCE = "Appearance"
    const val ACCESSIBILITY = "Accessibility"
    const val PERMISSIONS = "Permissions"
    const val DATA = "Data & storage"
    const val STATUS = "Account status"
    const val HELP = "Help & support"
}

object SettingsRegistry {
    private fun e(id: String, title: String, description: String, route: String, vararg path: String, keywords: List<String> = emptyList()) =
        SettingsEntry(id, title, description, route, path.toList(), keywords)

    val entries: List<SettingsEntry> = listOf(
        // Account
        e("account", "Account", "Your name, email and account controls", SettingsRoutes.ACCOUNT, SettingsCategories.ACCOUNT, keywords = listOf("email", "profile", "name")),
        e("edit-profile", "Edit profile", "Photo, bio, course and interests", AppRoutes.EDIT_PROFILE, SettingsCategories.ACCOUNT, "Edit profile", keywords = listOf("bio", "photo", "avatar", "interests", "course")),
        e("password", "Change password", "Choose a new password; other devices are logged out", SettingsRoutes.PASSWORD, SettingsCategories.ACCOUNT, "Change password", keywords = listOf("passcode", "credentials")),
        e("delete", "Delete account", "Permanently delete your account and content", SettingsRoutes.DELETE, SettingsCategories.ACCOUNT, "Delete account", keywords = listOf("remove account", "close account", "erase")),
        // Security
        e("security", "Security", "Password and where you are logged in", SettingsRoutes.SECURITY, SettingsCategories.SECURITY, keywords = listOf("protect")),
        e("login-activity", "Login activity", "See your logged-in devices and log out the others", SettingsRoutes.LOGIN_ACTIVITY, SettingsCategories.SECURITY, "Login activity",
            keywords = listOf("sessions", "devices", "log out other devices", "sign out everywhere", "logged in")),
        e("security-password", "Password", "Change your password", SettingsRoutes.PASSWORD, SettingsCategories.SECURITY, "Password", keywords = listOf("change password")),
        // Privacy
        e("privacy", "Privacy & safety", "Who can find and message you, and who you've blocked", SettingsRoutes.PRIVACY, SettingsCategories.PRIVACY, keywords = listOf("private")),
        e("privacy-visibility", "Visibility", "Whether other students can find your profile", SettingsRoutes.PRIVACY_VISIBILITY, SettingsCategories.PRIVACY, "Visibility", keywords = listOf("who can see me", "profile visibility")),
        e("discoverable", "Appear in Discover", "Let other students find you in Discover", SettingsRoutes.PRIVACY_VISIBILITY, SettingsCategories.PRIVACY, "Visibility", "Appear in Discover", keywords = listOf("discoverable", "visibility", "find me", "hide profile")),
        e("privacy-messages", "Messages", "Who can message you, read receipts and typing", SettingsRoutes.PRIVACY_MESSAGES, SettingsCategories.PRIVACY, "Messages", keywords = listOf("dm", "direct messages", "message privacy")),
        e("who-can-message", "Who can message you", "Everyone, people you follow, or nobody", SettingsRoutes.MESSAGE_POLICY, SettingsCategories.PRIVACY, "Messages", "Who can message you", keywords = listOf("message requests", "dm", "direct messages")),
        e("online-status", "Online status", "Show when you're online", SettingsRoutes.ACTIVITY_STATUS, SettingsCategories.PRIVACY, "Activity status", "Online status", keywords = listOf("active", "last seen", "activity status")),
        e("read-receipts", "Read receipts", "Show when you've read a message", SettingsRoutes.READ_RECEIPTS, SettingsCategories.PRIVACY, "Messages", "Read receipts", keywords = listOf("seen", "read status")),
        e("blocked", "Blocked students", "Students you've blocked", SettingsRoutes.BLOCKED, SettingsCategories.PRIVACY, "Blocked students", keywords = listOf("block", "unblock", "blocked users", "blocked accounts")),
        e("discover-profile", "Discover profile", "Your card photo, preview and who sees it", SettingsRoutes.DISCOVER_PROFILE, SettingsCategories.PRIVACY, "Discover profile", keywords = listOf("card", "photo", "preview")),
        e("safety", "Safety tips", "Staying safe on campus and online", SettingsRoutes.SAFETY, SettingsCategories.PRIVACY, "Safety tips"),
        // Notifications
        e("notifications", "Notifications", "Choose which alerts you get", SettingsRoutes.NOTIFICATIONS, SettingsCategories.NOTIFICATIONS, keywords = listOf("alerts", "push", "mute notifications")),
        e("push-alerts", "Phone alerts", "Which alerts also pop up on this phone", SettingsRoutes.NOTIFICATIONS_PUSH, SettingsCategories.NOTIFICATIONS, "Phone alerts", keywords = listOf("push", "push notifications", "mute notifications", "turn off notifications", "message alerts")),
        e("notify-types", "Activity in your list", "Choose which activity creates a notification", SettingsRoutes.NOTIFICATIONS_ACTIVITY, SettingsCategories.NOTIFICATIONS, "Activity in your list", keywords = listOf("likes", "comments", "replies", "followers", "messages", "events", "communities", "groups", "story reactions", "alert types")),
        e("event-notices", "Event notices", "Confirmations, reminders, changes and cancellations", SettingsRoutes.NOTIFICATIONS_EVENTS, SettingsCategories.NOTIFICATIONS, "Event notices", keywords = listOf("event reminders", "reminders", "cancellations", "registrations")),
        e("quiet-hours", "Quiet hours", "No alerts on this phone from 10 PM to 7 AM", SettingsRoutes.NOTIFICATIONS_QUIET, SettingsCategories.NOTIFICATIONS, "Quiet hours", keywords = listOf("do not disturb", "silence", "night")),
        // Messages
        e("messages", "Messages", "Chat privacy, alerts and writing", SettingsRoutes.MESSAGING, SettingsCategories.MESSAGES, keywords = listOf("chat", "chats", "dm", "direct messages", "conversations")),
        e("enter-to-send", "Enter key sends", "Send with the keyboard's Enter key", SettingsRoutes.MESSAGING, SettingsCategories.MESSAGES, "Enter key sends", keywords = listOf("enter key", "send key", "new line", "keyboard")),
        e("message-preview", "Message previews", "Show or hide message text in alerts", SettingsRoutes.MESSAGING, SettingsCategories.MESSAGES, "Message previews", keywords = listOf("preview", "alert text", "hide message", "lock screen")),
        e("typing-indicators", "Typing indicators", "Show when you're typing", SettingsRoutes.MESSAGING, SettingsCategories.MESSAGES, "Typing indicators", keywords = listOf("typing", "is typing")),
        e("muted-chats", "Muted conversations", "Chats whose alerts you turned off", SettingsRoutes.MUTED_CHATS, SettingsCategories.MESSAGES, "Muted conversations", keywords = listOf("mute", "unmute", "muted chats", "silence chat")),
        // Appearance
        e("appearance", "Appearance", "Theme, font and text size", SettingsRoutes.APPEARANCE, SettingsCategories.APPEARANCE, keywords = listOf("look", "display", "customize")),
        e("theme", "Theme", "System, Light or Dark", SettingsRoutes.APPEARANCE_THEME, SettingsCategories.APPEARANCE, "Theme", keywords = listOf("dark mode", "light mode", "night mode", "system default")),
        e("font", "Font", "Choose the typeface UniVibe uses", SettingsRoutes.APPEARANCE_FONT, SettingsCategories.APPEARANCE, "Font", keywords = listOf("typeface", "serif", "monospace", "poppins")),
        // Accessibility
        e("accessibility", "Accessibility", "Text, contrast, motion and haptics", SettingsRoutes.ACCESSIBILITY, SettingsCategories.ACCESSIBILITY),
        e("text-and-display", "Text and display", "Text size, bold text and high contrast", SettingsRoutes.ACCESSIBILITY_DISPLAY, SettingsCategories.ACCESSIBILITY, "Text and display"),
        e("text-size", "Text size", "Make text larger", SettingsRoutes.ACCESSIBILITY_TEXT_SIZE, SettingsCategories.ACCESSIBILITY, "Text and display", "Text size", keywords = listOf("font", "bigger text", "font scaling", "large")),
        e("bold-text", "Bold text", "Heavier letters throughout UniVibe", SettingsRoutes.ACCESSIBILITY_DISPLAY, SettingsCategories.ACCESSIBILITY, "Text and display", "Bold text", keywords = listOf("heavier", "thicker text")),
        e("high-contrast", "High contrast", "Stronger secondary text and borders", SettingsRoutes.ACCESSIBILITY_DISPLAY, SettingsCategories.ACCESSIBILITY, "Text and display", "High contrast", keywords = listOf("contrast", "low vision", "readability")),
        e("reduce-motion", "Reduce motion", "Fewer animations", SettingsRoutes.ACCESSIBILITY_MOTION, SettingsCategories.ACCESSIBILITY, "Motion", "Reduce motion", keywords = listOf("animations", "transitions")),
        e("haptics", "Haptic feedback", "Vibration on long-press and swipe to reply", SettingsRoutes.ACCESSIBILITY_INTERACTION, SettingsCategories.ACCESSIBILITY, "Touch and haptics", "Haptic feedback", keywords = listOf("vibration", "vibrate", "touch feedback")),
        // Permissions
        e("permissions", "Permissions", "What UniVibe is allowed to do on your phone", SettingsRoutes.PERMISSIONS, SettingsCategories.PERMISSIONS, keywords = listOf("allow", "access", "android settings")),
        e("perm-notifications", "Notification permission", "Allow or block alerts from UniVibe", SettingsRoutes.PERMISSIONS, SettingsCategories.PERMISSIONS, "Notifications", keywords = listOf("notifications blocked", "turn on notifications")),
        e("perm-photos", "Photos and videos access", "How UniVibe gets the photos you choose", SettingsRoutes.PERMISSIONS, SettingsCategories.PERMISSIONS, "Photos and videos", keywords = listOf("camera", "gallery", "contacts", "location")),
        e("perm-microphone", "Microphone permission", "Used only to record voice messages", SettingsRoutes.PERMISSIONS, SettingsCategories.PERMISSIONS, "Microphone", keywords = listOf("mic", "voice message", "record audio", "voice notes")),
        // Data & storage
        e("data-storage", "Data & storage", "See and clear what UniVibe keeps on this phone", SettingsRoutes.DATA_STORAGE, SettingsCategories.DATA, keywords = listOf("storage", "space", "memory", "downloads")),
        e("clear-cache", "Clear cache", "Remove saved pictures and maps; your account is not affected", SettingsRoutes.DATA_STORAGE, SettingsCategories.DATA, "Clear cache", keywords = listOf("free up space", "temporary files", "delete cache", "cache size")),
        // Status
        e("status", "Account status", "Your standing, removed content and notices from moderators", SettingsRoutes.STATUS, SettingsCategories.STATUS, keywords = listOf("suspended", "suspension", "restriction", "moderation", "warning", "banned")),
        // Help
        e("help", "Help & support", "Answers, reporting and guidelines", SettingsRoutes.HELP, SettingsCategories.HELP, keywords = listOf("support", "contact")),
        e("faq", "Frequently asked questions", "Quick answers", SettingsRoutes.FAQ, SettingsCategories.HELP, "FAQ", keywords = listOf("questions", "how do i")),
        e("report", "Report a problem", "Tell us about a bug or a problem", SettingsRoutes.REPORT, SettingsCategories.HELP, "Report a problem", keywords = listOf("bug", "feedback", "issue")),
        e("guidelines", "Community guidelines", "What's okay on UniVibe", SettingsRoutes.GUIDELINES, SettingsCategories.HELP, "Community guidelines", keywords = listOf("rules")),
        e("terms", "Terms of Service", "Read the rules for using UniVibe", SettingsRoutes.TERMS, SettingsCategories.HELP, "Terms of Service", keywords = listOf("terms", "terms and conditions", "legal", "agreement", "tos", "policy")),
        e("privacy-policy", "Privacy Policy", "How UniVibe uses and protects your data", SettingsRoutes.PRIVACY_POLICY, SettingsCategories.HELP, "Privacy Policy", keywords = listOf("privacy policy", "data protection", "personal data", "legal", "my data")),
        e("about", "About UniVibe", "Version and information", SettingsRoutes.ABOUT, SettingsCategories.HELP, "About UniVibe", keywords = listOf("version", "build", "legal")),
    )

    /** Entries whose [SettingsEntry.path] starts with [category], in registry order. */
    fun inCategory(category: String): List<SettingsEntry> = entries.filter { it.path.first() == category }
}

/**
 * Local, offline search over [entries]: case-insensitive, matches title, description, keywords and category, and ranks
 * by where the words matched. Every word of the query must match somewhere ("read rec" finds Read receipts).
 * Results are collapsed so two entries that open the same screen with the same title appear once.
 */
object SettingsSearch {
    fun search(query: String, entries: List<SettingsEntry> = SettingsRegistry.entries, limit: Int = 12): List<SettingsEntry> {
        val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        return entries
            .mapNotNull { entry -> score(entry, words)?.let { entry to it } }
            .sortedWith(compareByDescending<Pair<SettingsEntry, Int>> { it.second }.thenBy { it.first.title })
            .map { it.first }
            .distinctBy { it.route to it.title }
            .take(limit)
    }

    private fun score(entry: SettingsEntry, words: List<String>): Int? {
        val title = entry.title.lowercase()
        val keywords = entry.keywords.map { it.lowercase() }
        val description = entry.description.lowercase()
        val category = entry.path.joinToString(" ").lowercase()
        var total = 0
        for (w in words) {
            val s = when {
                title == w -> 100
                title.startsWith(w) -> 80
                title.split(' ').any { it.startsWith(w) } -> 70
                title.contains(w) -> 60
                keywords.any { it == w } -> 55
                keywords.any { k -> k.split(' ').any { it.startsWith(w) } || k.contains(w) } -> 45
                category.contains(w) -> 25
                description.contains(w) -> 20
                else -> return null
            }
            total += s
        }
        return total
    }
}
