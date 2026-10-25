package com.univibe.app.push

/** The Android notification channels (Settings ▸ Apps ▸ UniVibe ▸ Notifications lets people tune each one). */
enum class PushChannel(val id: String) {
    DirectMessages("direct_messages"),
    GroupMessages("group_messages"),
    Social("social_activity"),
    Campus("campus_updates"),
    Security("security_alerts"),
}

/** What to show for one push. Built only from ids and generic labels the server sent; never from message text (previews are fetched separately). */
data class PushEvent(
    val channel: PushChannel,
    val title: String,
    val body: String,
    /** One notification per tag: a retry or a newer push for the same thing replaces it instead of stacking. */
    val tag: String,
    /** A `univibe://` link. The app's own screens re-check access, so a stale or forged id just shows "not found". */
    val deepLink: String,
    /** Set for message alerts: the conversation whose newest message can be previewed. */
    val conversationId: String? = null,
)

/**
 * Turns an FCM data payload into a [PushEvent]. Pure Kotlin so it can be unit tested.
 * The payload is untrusted input: ids are validated before they go into a link, and unknown kinds or types are ignored
 * (a newer server may send types this version does not know).
 */
object PushPayload {
    private val SAFE_ID = Regex("^[A-Za-z0-9-]{1,64}$")
    private const val SCHEME = "univibe://"
    private const val OPEN_TO_READ = "Open UniVibe to see it."

    private fun id(value: String?): String? = value?.takeIf { SAFE_ID.matches(it) }

    fun parse(data: Map<String, String>): PushEvent? = when (data["kind"]) {
        "message" -> message(data)
        "notification" -> notification(data)
        else -> null
    }

    private fun message(data: Map<String, String>): PushEvent? {
        val conversation = id(data["conversationId"]) ?: return null
        val group = data["group"] == "1"
        return PushEvent(
            channel = if (group) PushChannel.GroupMessages else PushChannel.DirectMessages,
            title = if (group) "New message in your group" else "New message",
            body = OPEN_TO_READ,
            tag = "c:$conversation",
            deepLink = "${SCHEME}chats/$conversation",
            conversationId = conversation,
        )
    }

    private fun notification(data: Map<String, String>): PushEvent? {
        val notificationId = id(data["notificationId"])
        val tag = "n:${notificationId ?: data["type"].orEmpty()}"
        val entity = id(data["entityId"])
        val post = id(data["postId"]) ?: entity
        val conversation = id(data["conversationId"]) ?: entity
        fun event(channel: PushChannel, title: String, body: String, link: String?) =
            PushEvent(channel, title, body, tag, link ?: "${SCHEME}notifications")
        return when (data["type"]) {
            "follow" -> event(PushChannel.Social, "New follower", "Someone started following you.", entity?.let { "${SCHEME}student/$it" })
            "like" -> event(PushChannel.Social, "New like", "Someone liked your post.", post?.let { "${SCHEME}post/$it/comments" })
            "comment" -> event(PushChannel.Social, "New comment", "Someone commented on your post.", post?.let { "${SCHEME}post/$it/comments" })
            "reply" -> event(PushChannel.Social, "New reply", "Someone replied to your comment.", post?.let { "${SCHEME}post/$it/comments" })
            "group_invite" -> event(PushChannel.GroupMessages, "Added to a group", "You were added to a group chat.", conversation?.let { "${SCHEME}chats/$it" })
            "event_update" -> event(PushChannel.Campus, "Event update", "An event you follow has news.", entity?.let { "${SCHEME}events/$it" })
            "verification" -> event(PushChannel.Security, "Account notice", "There is an update about your account.", null)
            else -> null
        }
    }
}
