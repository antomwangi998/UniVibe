package com.univibe.app.data.repository

import com.univibe.app.data.model.Chat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch

/**
 * Total unread messages for the Chats tab badge.
 *
 * The server has no "total unread" endpoint; it reports an unread count per conversation in the chat list. This adds those
 * up with the same rule the Chats list uses for each row ([Chat.unread]: the real unread messages, or 1 for a chat the
 * student marked as unread by hand). Archived chats are not in the main list, so they are not counted.
 *
 * It rides on [ChatRepository.changes] (the existing live connection: new message, message changed, reconnect), so there is
 * no second socket and no polling. Screens that already hold a fresh chat list call [publish]; the shell calls [refresh] when
 * the app resumes or the student returns from a conversation.
 *
 * [total] is null until a count has been read (or when the list cannot be read): the badge shows nothing then, never a guess.
 */
class ChatUnreadTracker(
    private val repository: ChatRepository,
    scope: CoroutineScope,
) {
    private val _total = MutableStateFlow<Int?>(null)
    val total: StateFlow<Int?> = _total.asStateFlow()

    init {
        // A burst of events becomes one read.
        scope.launch { repository.changes().conflate().collect { delay(300); refresh() } }
    }

    /** Re-reads the chat list. On failure the last known count stays (it is the server's last word). */
    suspend fun refresh() {
        val chats = runCatching { repository.getChats() }.getOrNull() ?: return
        publish(chats)
    }

    /** For screens that just loaded the main (non-archived) chat list. */
    fun publish(chats: List<Chat>) {
        _total.value = totalUnread(chats)
    }

    /** Signed out: forget the count. */
    fun clear() {
        _total.value = null
    }

    companion object {
        fun totalUnread(chats: List<Chat>): Int = chats.sumOf { it.unread.coerceAtLeast(0) }
    }
}
