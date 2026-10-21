package com.univibe.app.data.settings

import com.univibe.app.data.net.AuthedApi
import com.univibe.app.data.net.send
import org.json.JSONObject

/**
 * Stored and enforced by the server (GET/PUT /me/notification-preferences). The first six decide which activity creates a
 * notification in your notification list at all; the push ones decide which of those also reach your phone as alerts.
 */
data class NotificationPrefs(
    val follows: Boolean = true,
    val likes: Boolean = true,
    val comments: Boolean = true,
    val messages: Boolean = true,
    val events: Boolean = true,
    val groups: Boolean = true,
    val pushEnabled: Boolean = true,
    val pushMessages: Boolean = true,
    val pushSocial: Boolean = true,
    val pushEvents: Boolean = true,
    /** Join requests, invitations and announcements from communities. Role changes and removals always come through. */
    val communities: Boolean = true,
    val storyReactions: Boolean = true,
)

/** Only the fields that are set are sent. */
data class NotificationPrefsChange(
    val follows: Boolean? = null, val likes: Boolean? = null, val comments: Boolean? = null,
    val messages: Boolean? = null, val events: Boolean? = null, val groups: Boolean? = null,
    val pushEnabled: Boolean? = null, val pushMessages: Boolean? = null, val pushSocial: Boolean? = null, val pushEvents: Boolean? = null,
    val communities: Boolean? = null, val storyReactions: Boolean? = null,
)

fun NotificationPrefs.updated(c: NotificationPrefsChange) = NotificationPrefs(
    c.follows ?: follows, c.likes ?: likes, c.comments ?: comments, c.messages ?: messages, c.events ?: events, c.groups ?: groups,
    c.pushEnabled ?: pushEnabled, c.pushMessages ?: pushMessages, c.pushSocial ?: pushSocial, c.pushEvents ?: pushEvents,
    c.communities ?: communities, c.storyReactions ?: storyReactions,
)

interface NotificationPrefsRepository {
    /** False in the offline preview build: choices are kept for this session only and nothing is sent anywhere. */
    val syncedWithServer: Boolean
    suspend fun load(): NotificationPrefs
    suspend fun save(change: NotificationPrefsChange): NotificationPrefs
}

class LocalNotificationPrefsRepository : NotificationPrefsRepository {
    override val syncedWithServer = false
    private var current = NotificationPrefs()
    override suspend fun load() = current
    override suspend fun save(change: NotificationPrefsChange): NotificationPrefs { current = current.updated(change); return current }
}

class RemoteNotificationPrefsRepository(private val api: AuthedApi) : NotificationPrefsRepository {
    override val syncedWithServer = true

    private fun parse(j: JSONObject) = NotificationPrefs(
        follows = j.optBoolean("follows", true), likes = j.optBoolean("likes", true), comments = j.optBoolean("comments", true),
        messages = j.optBoolean("messages", true), events = j.optBoolean("events", true), groups = j.optBoolean("groups", true),
        pushEnabled = j.optBoolean("pushEnabled", true), pushMessages = j.optBoolean("pushMessages", true),
        pushSocial = j.optBoolean("pushSocial", true), pushEvents = j.optBoolean("pushEvents", true),
        communities = j.optBoolean("communities", true), storyReactions = j.optBoolean("storyReactions", true),
    )

    override suspend fun load() = parse(JSONObject(api.send("GET", "/api/v1/me/notification-preferences")))

    override suspend fun save(change: NotificationPrefsChange): NotificationPrefs {
        val body = JSONObject()
        change.follows?.let { body.put("follows", it) }
        change.likes?.let { body.put("likes", it) }
        change.comments?.let { body.put("comments", it) }
        change.messages?.let { body.put("messages", it) }
        change.events?.let { body.put("events", it) }
        change.groups?.let { body.put("groups", it) }
        change.pushEnabled?.let { body.put("pushEnabled", it) }
        change.pushMessages?.let { body.put("pushMessages", it) }
        change.pushSocial?.let { body.put("pushSocial", it) }
        change.pushEvents?.let { body.put("pushEvents", it) }
        change.communities?.let { body.put("communities", it) }
        change.storyReactions?.let { body.put("storyReactions", it) }
        return parse(JSONObject(api.send("PUT", "/api/v1/me/notification-preferences", body)))
    }
}
