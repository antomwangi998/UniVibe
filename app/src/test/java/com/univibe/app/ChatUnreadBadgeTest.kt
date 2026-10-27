package com.univibe.app

import com.univibe.app.data.model.Chat
import com.univibe.app.data.repository.ChatUnreadTracker
import com.univibe.app.ui.components.unreadBadgeText
import com.univibe.app.ui.components.unreadDescription
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatUnreadBadgeTest {
    private fun chat(id: String, unread: Int, markedOnly: Boolean = false) =
        Chat(id = id, title = id, lastMessage = "", timeLabel = "", unread = unread, markedOnly = markedOnly)

    @Test fun totalAddsEachChatsUnreadCount() {
        assertEquals(0, ChatUnreadTracker.totalUnread(emptyList()))
        assertEquals(5, ChatUnreadTracker.totalUnread(listOf(chat("a", 2), chat("b", 0), chat("c", 3))))
    }

    @Test fun aChatMarkedUnreadByHandCountsAsOne() {
        assertEquals(1, ChatUnreadTracker.totalUnread(listOf(chat("a", 1, markedOnly = true))))
    }

    @Test fun negativeCountsNeverReduceTheTotal() {
        assertEquals(2, ChatUnreadTracker.totalUnread(listOf(chat("a", -4), chat("b", 2))))
    }

    @Test fun badgeTextFollowsTheSpec() {
        assertNull(unreadBadgeText(0))
        assertNull(unreadBadgeText(-3))
        assertEquals("1", unreadBadgeText(1))
        assertEquals("9", unreadBadgeText(9))
        assertEquals("10", unreadBadgeText(10))
        assertEquals("99", unreadBadgeText(99))
        assertEquals("99+", unreadBadgeText(100))
        assertEquals("99+", unreadBadgeText(1234))
    }

    @Test fun spokenDescriptionIncludesTheCount() {
        assertEquals("Chats", unreadDescription("Chats", 0))
        assertEquals("Chats, 1 unread message", unreadDescription("Chats", 1))
        assertEquals("Chats, 3 unread messages", unreadDescription("Chats", 3))
        assertEquals("Chats, more than 99 unread messages", unreadDescription("Chats", 150))
    }
}
