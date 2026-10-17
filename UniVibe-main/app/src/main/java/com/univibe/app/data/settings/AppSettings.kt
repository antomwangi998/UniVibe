package com.univibe.app.data.settings

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
)
