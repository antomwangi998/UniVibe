package com.univibe.app.data.repository

import com.univibe.app.data.model.AppNotification
import com.univibe.app.data.model.NotificationTarget
import com.univibe.app.data.model.NotificationType
import com.univibe.app.data.net.AuthedApi
import com.univibe.app.data.net.ChatEvent
import com.univibe.app.data.net.LiveConnection
import com.univibe.app.data.net.parseStudent
import com.univibe.app.data.net.send
import com.univibe.app.data.net.timeAgo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import org.json.JSONObject

/**
 * Notifications from the UniVibe server. The server creates them (from real likes, comments, follows, messages
 * and event updates) and decides who receives them; this app only shows them and marks them read.
 */
class RemoteNotificationRepository(
    private val api: AuthedApi,
    /** Live events, so new notifications show up without a refresh. Optional. */
    private val live: LiveConnection? = null,
) : NotificationRepository {
    override fun changes(): Flow<Unit> = live?.events
        ?.filter { it is ChatEvent.NotificationsChanged || it is ChatEvent.Connected }
        ?.map { }
        ?: emptyFlow()

    private val _unread = MutableStateFlow(0)
    override val unreadCount: StateFlow<Int> = _unread.asStateFlow()

    override suspend fun refreshUnread() {
        _unread.value = JSONObject(api.send("GET", "/api/v1/notifications/unread-count")).optInt("count", 0)
    }

    override suspend fun getNotifications(): List<AppNotification> {
        val items = JSONObject(api.send("GET", "/api/v1/notifications?limit=50")).getJSONArray("items")
        val list = (0 until items.length()).map { toNotification(items.getJSONObject(it)) }
        runCatching { refreshUnread() } // the badge always shows the server's own count
        return list
    }

    override suspend fun markRead(id: String) {
        api.send("POST", "/api/v1/notifications/$id/read", ok = setOf(204))
        runCatching { refreshUnread() }
    }

    override suspend fun markAllRead() {
        api.send("POST", "/api/v1/notifications/read-all", ok = setOf(204))
        _unread.value = 0
    }

    private class Shape(val type: NotificationType, val body: String, val target: NotificationTarget = NotificationTarget.None)

    private fun toNotification(o: JSONObject): AppNotification {
        val actor = o.optJSONObject("actor")?.let { parseStudent(it) }
        val meta = o.optJSONObject("metadata") ?: JSONObject()
        val entityId = if (o.isNull("entityId")) "" else o.getString("entityId")
        val postId = meta.optString("postId").ifBlank { entityId }
        val conversationId = meta.optString("conversationId").ifBlank { entityId }
        var title = actor?.name ?: "UniVibe"
        val shape = when (o.getString("type")) {
            "like" -> Shape(NotificationType.Like, "liked your post", NotificationTarget.Post(entityId))
            "comment" -> Shape(NotificationType.Comment, "commented on your post", NotificationTarget.Post(postId))
            "reply" -> Shape(NotificationType.Comment, "replied to your comment", NotificationTarget.Post(postId))
            "follow" -> Shape(
                NotificationType.Follow, "started following you",
                actor?.let { NotificationTarget.Student(it.id) } ?: NotificationTarget.None,
            )
            "message" -> Shape(NotificationType.Message, "sent you a message", NotificationTarget.Chat(conversationId))
            "group_invite" -> Shape(NotificationType.Community, "added you to a group chat", NotificationTarget.Chat(conversationId))
            "event_update" -> {
                title = meta.optString("title").ifBlank { "An event" }
                val what = when (meta.optString("kind")) {
                    "reminder" -> "starts soon"
                    "cancelled" -> "was cancelled"
                    else -> "was updated"
                }
                Shape(NotificationType.Event, what, NotificationTarget.Event(entityId))
            }
            "verification" -> {
                title = "Student verification"
                Shape(NotificationType.System, "Your verification status changed.")
            }
            else -> Shape(NotificationType.System, "")
        }
        return AppNotification(
            id = o.getString("id"), type = shape.type, title = title, body = shape.body,
            timeAgo = timeAgo(o.optLong("ageSeconds", 0L)), read = !o.isNull("readAt"),
            target = shape.target, actor = actor,
        )
    }
}
