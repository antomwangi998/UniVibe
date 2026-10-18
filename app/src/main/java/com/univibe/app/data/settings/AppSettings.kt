package com.univibe.app.data.settings

import com.univibe.app.data.model.FeedFilter

enum class MessagePolicy(val label: String, val description: String) {
    Everyone("Everyone", "Any student can message you"),
    Following("People I follow", "Only students you follow can message you"),
    Nobody("Nobody", "Turn off new messages"),
}

enum class TextScale(val label: String, val factor: Float) {
    Default("Default", 1f), Large("Large", 1.15f), Larger("Larger", 1.3f)
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
    // Home feed
    val feedFilter: FeedFilter = FeedFilter.Campus,
)
