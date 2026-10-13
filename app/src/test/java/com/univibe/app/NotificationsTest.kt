package com.univibe.app

import com.univibe.app.data.model.AppNotification
import com.univibe.app.data.model.NotificationType
import com.univibe.app.data.repository.MockData
import com.univibe.app.data.repository.MockNotificationRepository
import com.univibe.app.features.notifications.NotificationsUiState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationsTest {
    @Test
    fun unreadCountMatchesItemsAndDropsWhenMarkedRead() = runTest {
        val repo = MockNotificationRepository()
        val unread = repo.getNotifications().count { !it.read }
        assertTrue(unread > 0)
        assertEquals(unread, repo.unreadCount.value)
        repo.markRead(repo.getNotifications().first { !it.read }.id)
        assertEquals(unread - 1, repo.unreadCount.value)
    }

    @Test
    fun markAllReadClearsBadge() = runTest {
        val repo = MockNotificationRepository()
        repo.markAllRead()
        assertEquals(0, repo.unreadCount.value)
        assertTrue(repo.getNotifications().all { it.read })
    }

    @Test
    fun unknownIdIsIgnored() = runTest {
        val repo = MockNotificationRepository()
        val before = repo.unreadCount.value
        repo.markRead("does-not-exist")
        assertEquals(before, repo.unreadCount.value)
    }

    @Test
    fun blockedActorsAreHiddenButSystemItemsStay() {
        val blocked = AppNotification("a", NotificationType.Like, "Brian", "liked your post", "1m", false, actor = MockData.brian)
        val system = AppNotification("b", NotificationType.System, "UniVibe", "Hello", "1d", true)
        val state = NotificationsUiState(loading = false, items = listOf(blocked, system), blocked = setOf(MockData.brian.id))
        assertEquals(listOf(system), state.visible)
        assertTrue(state.unread.isEmpty())
    }
}
