package com.univibe.app.push

import com.univibe.app.data.net.AuthedApi
import com.univibe.app.data.net.send
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.URLEncoder

/** What a message alert shows when previews are on. */
data class MessagePreview(val title: String, val body: String)

/**
 * Builds the alert text for a new message. Pure Kotlin so it can be unit tested.
 * Direct chat: the sender's name as the title and the text as the body.
 * Group: the group's name as the title and "Sender: text" as the body.
 */
object MessagePreviewFormat {
    const val MAX_BODY = 140

    fun compose(isGroup: Boolean, groupTitle: String?, senderName: String, content: String, mediaKinds: List<String>): MessagePreview {
        val text = content.trim().replace(Regex("\\s+"), " ")
        val shown = when {
            text.isNotEmpty() -> text
            "audio" in mediaKinds -> "Voice message"
            "document" in mediaKinds -> "Sent a file"
            "image" in mediaKinds -> if (mediaKinds.count { it == "image" } > 1) "Sent photos" else "Sent a photo"
            else -> "New message"
        }
        val clipped = if (shown.length > MAX_BODY) shown.take(MAX_BODY - 1).trimEnd() + "\u2026" else shown
        return if (isGroup) MessagePreview(groupTitle?.takeIf { it.isNotBlank() } ?: "Group", "$senderName: $clipped")
        else MessagePreview(senderName, clipped)
    }
}

/**
 * Loads the newest message of a conversation from the API, so the text never travels through FCM (the push itself
 * still carries only ids). Returns null on any failure, signed out, or timeout; the caller then shows the generic alert.
 */
object MessagePreviewLoader {
    private const val TIMEOUT_MS = 8_000L

    suspend fun load(api: AuthedApi, conversationId: String, myId: String?): MessagePreview? = withTimeoutOrNull(TIMEOUT_MS) {
        runCatching {
            val id = URLEncoder.encode(conversationId, "UTF-8")
            val conversation = JSONObject(api.send("GET", "/api/v1/conversations/$id"))
            val items = JSONObject(api.send("GET", "/api/v1/conversations/$id/messages?limit=1")).getJSONArray("items")
            var latest: JSONObject? = null
            for (i in 0 until items.length()) {
                val o = items.getJSONObject(i)
                if (latest == null || o.getLong("seq") > latest.getLong("seq")) latest = o
            }
            val message = latest ?: return@runCatching null
            if (message.optBoolean("deleted", false)) return@runCatching null
            val senderId = message.optString("senderId")
            if (senderId.isEmpty() || senderId == myId) return@runCatching null
            val members = conversation.getJSONArray("members")
            var sender = "Student"
            for (i in 0 until members.length()) {
                val m = members.getJSONObject(i)
                if (m.getString("id") == senderId) sender = m.getString("fullName")
            }
            val media = message.optJSONArray("media")
            val kinds = buildList { if (media != null) for (i in 0 until media.length()) add(media.getJSONObject(i).optString("kind", "image")) }
            MessagePreviewFormat.compose(
                isGroup = conversation.getString("kind") == "group",
                groupTitle = conversation.optString("title").ifEmpty { null },
                senderName = sender,
                content = if (message.isNull("content")) "" else message.optString("content"),
                mediaKinds = kinds,
            )
        }.getOrNull()
    }
}
