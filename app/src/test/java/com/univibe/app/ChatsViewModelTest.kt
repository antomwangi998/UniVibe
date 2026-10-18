package com.univibe.app

import com.univibe.app.data.model.Chat
import com.univibe.app.data.repository.ChatRepository
import com.univibe.app.features.chats.ChatFilter
import com.univibe.app.features.chats.ChatsViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class ChatsViewModelTest {
    private class FakeChats(var chats: List<Chat>) : ChatRepository {
        var failNext = false
        var gate: CompletableDeferred<Unit>? = null
        val calls = mutableListOf<String>()
        var archived: List<Chat> = emptyList()
        private suspend fun act(name: String) { calls += name; gate?.await(); if (failNext) throw IOException("nope") }
        override suspend fun getChats() = chats
        override suspend fun getChat(chatId: String) = chats.firstOrNull { it.id == chatId }
        override suspend fun setMuted(chatId: String, muted: Boolean) = act("mute")
        override suspend fun archive(chatId: String) = act("archive")
        override suspend fun setPinned(chatId: String, pinned: Boolean) = act("pin")
        override suspend fun markUnread(chatId: String) = act("unread")
        override suspend fun markRead(chatId: String) = act("read")
        override suspend fun getArchivedChats() = archived
        override suspend fun unarchive(chatId: String) = act("unarchive")
    }

    private fun chat(id: String, unread: Int = 0) = Chat(id = id, title = "Chat $id", lastMessage = "hi", timeLabel = "1h", unread = unread)

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun pinningKeepsTheChangeWhenTheServerAgrees() {
        val repo = FakeChats(listOf(chat("a"), chat("b")))
        val vm = ChatsViewModel(repo)
        vm.togglePinned("a")
        assertTrue(vm.state.value.all.first { it.id == "a" }.pinned)
        assertEquals(listOf("a"), vm.state.value.pinned.map { it.id })
        assertTrue(vm.state.value.busy.isEmpty())
    }

    @Test fun aFailedPinPutsOnlyThatChatBackAndTellsThePerson() {
        val repo = FakeChats(listOf(chat("a"), chat("b"))).also { it.failNext = true }
        val vm = ChatsViewModel(repo)
        vm.togglePinned("a")
        assertFalse(vm.state.value.all.first { it.id == "a" }.pinned)
        assertNotNull(vm.state.value.notice)
        assertTrue(vm.state.value.busy.isEmpty())
    }

    @Test fun aSecondTapWhileTheFirstIsRunningIsIgnored() {
        val repo = FakeChats(listOf(chat("a"))).also { it.gate = CompletableDeferred() }
        val vm = ChatsViewModel(repo)
        vm.togglePinned("a")
        vm.togglePinned("a")
        assertEquals(1, repo.calls.count { it == "pin" })
        assertTrue(vm.state.value.all.single().pinned) // shown at once, before the server answered
        repo.gate!!.complete(Unit)
        assertTrue(vm.state.value.busy.isEmpty())
    }

    @Test fun unreadAndReadUseTheServersOwnCalls() {
        val repo = FakeChats(listOf(chat("a"), chat("b", unread = 3)))
        val vm = ChatsViewModel(repo)
        vm.toggleUnread("a")
        val a = vm.state.value.all.first { it.id == "a" }
        assertTrue(a.hasUnread); assertTrue(a.markedOnly)
        vm.toggleUnread("b")
        assertFalse(vm.state.value.all.first { it.id == "b" }.hasUnread)
        assertEquals(listOf("unread", "read"), repo.calls)
    }

    @Test fun aFailedArchiveBringsTheRowBackWhereItWas() {
        val repo = FakeChats(listOf(chat("a"), chat("b"), chat("c"))).also { it.failNext = true }
        val vm = ChatsViewModel(repo)
        vm.archive("b")
        assertEquals(listOf("a", "b", "c"), vm.state.value.all.map { it.id })
    }

    @Test fun unarchiveMovesTheChatBackAndReloads() {
        val repo = FakeChats(emptyList()).also { it.archived = listOf(chat("z")) }
        val vm = ChatsViewModel(repo)
        vm.setFilter(ChatFilter.Archived)
        assertEquals(listOf("z"), vm.state.value.visible.map { it.id })
        repo.chats = listOf(chat("z")); repo.archived = emptyList()
        vm.unarchive("z")
        assertTrue(vm.state.value.archived.isEmpty())
        vm.setFilter(ChatFilter.All)
        assertEquals(listOf("z"), vm.state.value.visible.map { it.id })
    }

    @Test fun aFailedUnarchiveKeepsTheChatInTheArchive() {
        val repo = FakeChats(emptyList()).also { it.archived = listOf(chat("z")); it.failNext = true }
        val vm = ChatsViewModel(repo)
        vm.setFilter(ChatFilter.Archived)
        vm.unarchive("z")
        assertEquals(listOf("z"), vm.state.value.archived.map { it.id })
        assertNotNull(vm.state.value.notice)
    }
}
