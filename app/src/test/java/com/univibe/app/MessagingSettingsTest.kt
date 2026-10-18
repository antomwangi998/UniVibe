package com.univibe.app

import com.univibe.app.data.model.Chat
import com.univibe.app.data.net.ApiException
import com.univibe.app.data.repository.ChatRepository
import com.univibe.app.data.settings.AppSettings
import com.univibe.app.data.settings.InMemorySettingsRepository
import com.univibe.app.data.settings.LocalPrivacyRepository
import com.univibe.app.data.settings.MessagePolicy
import com.univibe.app.data.settings.PrivacyChange
import com.univibe.app.data.settings.PrivacyRepository
import com.univibe.app.data.settings.ServerPrivacy
import com.univibe.app.data.settings.updated
import com.univibe.app.data.settings.BlockedUser
import com.univibe.app.features.settings.MutedConversationsViewModel
import com.univibe.app.features.settings.MutedState
import com.univibe.app.features.settings.PrivacyState
import com.univibe.app.features.settings.PrivacyViewModel
import com.univibe.app.features.settings.SettingsSearch
import com.univibe.app.navigation.SettingsRoutes
import com.univibe.app.push.PushChannel
import com.univibe.app.push.PushEvent
import com.univibe.app.push.PushPolicy
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class FakeChats(var chats: List<Chat>, var failWith: Exception? = null) : ChatRepository {
    val unmuted = mutableListOf<String>()
    override suspend fun getChats(): List<Chat> { failWith?.let { throw it }; return chats }
    override suspend fun getChat(chatId: String) = chats.firstOrNull { it.id == chatId }
    override suspend fun setMuted(chatId: String, muted: Boolean) { failWith?.let { throw it }; if (!muted) unmuted += chatId }
    override suspend fun archive(chatId: String) {}
}

private class TypingPrivacy(var failWith: Exception? = null) : PrivacyRepository {
    override val syncedWithServer = true
    var server = ServerPrivacy(true, true, true, MessagePolicy.Everyone, typingIndicators = true)
    override suspend fun load() = server
    override suspend fun save(change: PrivacyChange): ServerPrivacy { failWith?.let { throw it }; server = server.updated(change); return server }
    override suspend fun blockedUsers(): List<BlockedUser> = emptyList()
    override suspend fun block(id: String, name: String) {}
    override suspend fun unblock(id: String) {}
}

@OptIn(ExperimentalCoroutinesApi::class)
class MessagingSettingsTest {
    @Before fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun chat(id: String, muted: Boolean) = Chat(id = id, title = "Chat $id", lastMessage = "hi", timeLabel = "now", muted = muted)
    private fun push(c: PushChannel) = PushEvent(c, "Amina", "See you at 5?", "tag", "univibe://chats/c1")

    @Test fun messagePreviewOffHidesOnlyMessageText() {
        assertEquals("New message", PushPolicy.forDisplay(push(PushChannel.DirectMessages), showMessagePreview = false).body)
        assertEquals("New message", PushPolicy.forDisplay(push(PushChannel.GroupMessages), showMessagePreview = false).body)
        assertEquals("See you at 5?", PushPolicy.forDisplay(push(PushChannel.DirectMessages), showMessagePreview = true).body)
        assertEquals("See you at 5?", PushPolicy.forDisplay(push(PushChannel.Social), showMessagePreview = false).body)
        assertEquals("Amina", PushPolicy.forDisplay(push(PushChannel.DirectMessages), showMessagePreview = false).title)
    }

    @Test fun deviceMessageSettingsStartAtSafeDefaults() = runTest {
        val s = AppSettings()
        assertFalse("Enter must not send by default", s.enterToSend)
        assertTrue(s.showMessagePreview)
        assertTrue(s.showTyping)
        val repo = InMemorySettingsRepository()
        repo.update { it.copy(enterToSend = true, showMessagePreview = false) }
        assertTrue(repo.settings.first().enterToSend)
        assertFalse(repo.settings.first().showMessagePreview)
    }

    @Test fun typingIndicatorsAreSavedToTheServerAndRolledBackOnFailure() {
        val repo = TypingPrivacy()
        val vm = PrivacyViewModel(repo)
        vm.change(PrivacyChange(typingIndicators = false))
        assertFalse(repo.server.typingIndicators)
        assertFalse((vm.state.value as PrivacyState.Ready).privacy.typingIndicators)
        repo.failWith = IOException()
        vm.change(PrivacyChange(typingIndicators = true))
        val s = vm.state.value as PrivacyState.Ready
        assertFalse("old value must come back", s.privacy.typingIndicators)
        assertNotNull(s.error)
    }

    @Test fun previewBuildKeepsTypingChoiceOnTheDeviceAndSaysSo() = runTest {
        val settings = InMemorySettingsRepository()
        val repo = LocalPrivacyRepository(settings)
        repo.save(PrivacyChange(typingIndicators = false))
        assertFalse(settings.settings.first().showTyping)
        assertFalse(repo.load().typingIndicators)
        assertFalse(repo.syncedWithServer)
    }

    @Test fun mutedListShowsOnlyMutedChatsAndUnmuteRemovesOne() {
        val repo = FakeChats(listOf(chat("a", true), chat("b", false), chat("c", true)))
        val vm = MutedConversationsViewModel(repo)
        assertEquals(listOf("a", "c"), (vm.state.value as MutedState.Ready).chats.map { it.id })
        vm.unmute("a")
        assertEquals(listOf("a"), repo.unmuted)
        assertEquals(listOf("c"), (vm.state.value as MutedState.Ready).chats.map { it.id })
    }

    @Test fun aFailedUnmuteKeepsTheChatListedAndSaysWhy() {
        val repo = FakeChats(listOf(chat("a", true)))
        val vm = MutedConversationsViewModel(repo)
        repo.failWith = ApiException(500, "server", "Server error.")
        vm.unmute("a")
        val s = vm.state.value as MutedState.Ready
        assertEquals(1, s.chats.size)
        assertNotNull(s.error)
        assertEquals(null, s.working)
    }

    @Test fun mutedListLoadFailureCanBeRetried() {
        val repo = FakeChats(listOf(chat("a", true)), failWith = IOException())
        val vm = MutedConversationsViewModel(repo)
        assertTrue(vm.state.value is MutedState.Failed)
        repo.failWith = null
        vm.load()
        assertTrue(vm.state.value is MutedState.Ready)
    }

    @Test fun messageSettingsAreSearchable() {
        assertEquals(SettingsRoutes.MESSAGING, SettingsSearch.search("typing").first().route)
        assertEquals(SettingsRoutes.MESSAGING, SettingsSearch.search("enter key").first().route)
        assertEquals(SettingsRoutes.MESSAGING, SettingsSearch.search("message previews").first().route)
        assertEquals(SettingsRoutes.MUTED_CHATS, SettingsSearch.search("unmute").first().route)
        assertEquals(SettingsRoutes.PRIVACY, SettingsSearch.search("read receipts").first().route)
    }
}
