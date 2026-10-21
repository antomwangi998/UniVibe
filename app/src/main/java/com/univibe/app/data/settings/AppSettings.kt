package com.univibe.app.data.settings

import com.univibe.app.data.model.FeedFilter

enum class MessagePolicy(val label: String, val description: String) {
    Everyone("Everyone", "Any student can message you"),
    Following("People I follow", "Only students you follow can message you"),
    Nobody("Nobody", "Turn off new messages"),
}

enum class TextScale(val label: String, val factor: Float) {
    Small("Small", 0.9f), Default("Default", 1f), Large("Large", 1.15f), Larger("Larger", 1.3f), Largest("Largest", 1.45f)
}

/** The typeface used for all text. The first is UniVibe's own; the others come with the phone, so they cost no download. */
enum class AppFont(val label: String, val description: String) {
    UniVibe("UniVibe", "Poppins, the app's own look"),
    System("Phone default", "The font your phone uses everywhere"),
    Serif("Serif", "Classic, book-like letters"),
    Mono("Monospace", "Even-width, typewriter style"),
}

data class BlockedUser(val id: String, val name: String)

/**
 * Everything the user can configure. Stored on the device for now.
 * Privacy values must ALSO be enforced by the backend once it exists.
 */
data class AppSettings(
    // Privacy & safety
    val discoverable: Boolean = true,
    val showOnline: Boolean = true,
    val showReadReceipts: Boolean = true,
    /** Cached copy of the server's typing-indicator setting (see PrivacyRepository). */
    val showTyping: Boolean = true,
    // Messages (this device only)
    /** The keyboard's Enter key sends the message instead of starting a new line. */
    val enterToSend: Boolean = false,
    /** Whether message alerts show the message text. Off shows "New message" instead. */
    val showMessagePreview: Boolean = true,
    val messagePolicy: MessagePolicy = MessagePolicy.Everyone,
    val blocked: List<BlockedUser> = emptyList(),
    // Notifications
    val notificationsEnabled: Boolean = true,
    val notifyLikes: Boolean = true,
    val notifyComments: Boolean = true,
    val notifyMessages: Boolean = true,
    val notifyEvents: Boolean = true,
    val notifyCommunities: Boolean = true,
    val quietHours: Boolean = false,
    val emailDigest: Boolean = false,
    // Accessibility
    val reduceMotion: Boolean = false,
    val textScale: TextScale = TextScale.Default,
    val font: AppFont = AppFont.UniVibe,
    /** Heavier text everywhere (one weight step up). */
    val boldText: Boolean = false,
    /** Stronger secondary text and borders. */
    val highContrast: Boolean = false,
    /** Short vibrations on long-press and swipe-to-reply. Off silences every UniVibe haptic. */
    val hapticFeedback: Boolean = true,
    // Home feed
    val feedFilter: FeedFilter = FeedFilter.Campus,
)
