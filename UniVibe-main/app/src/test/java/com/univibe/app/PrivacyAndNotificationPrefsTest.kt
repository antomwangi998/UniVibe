package com.univibe.app

import com.univibe.app.data.net.ApiException
import com.univibe.app.data.settings.BlockedUser
import com.univibe.app.data.settings.InMemorySettingsRepository
import com.univibe.app.data.settings.LocalPrivacyRepository
import com.univibe.app.data.settings.MessagePolicy
import com.univibe.app.data.settings.NotificationPrefs
import com.univibe.app.data.settings.NotificationPrefsChange
import com.univibe.app.data.settings.NotificationPrefsRepository
import com.univibe.app.data.settings.PrivacyChange
import com.univibe.app.data.settings.PrivacyRepository
import com.univibe.app.data.settings.ServerPrivacy
import com.univibe.app.data.settings.updated
import com.univibe.app.features.settings.BlockedState
import com.univibe.app.features.settings.BlockedUsersViewModel
import com.univibe.app.features.settings.NotificationPrefsState
import com.univibe.app.features.settings.NotificationPrefsViewModel
import com.univibe.app.features.settings.PrivacyState
import com.univibe.app.features.settings.PrivacyViewModel
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

private class FakePrivacy(
    var server: ServerPrivacy = ServerPrivacy(true, true, true, MessagePolicy.Everyone),
    var blocked: MutableList<BlockedUser> = mutableListOf(BlockedUser("u1", "Amina"), BlockedUser("u2", "Brian")),
    var failWith: Exception? = null,
    override val syncedWithServer: Boolean = true,
) : PrivacyRepository {
    var saves = 0
    private fun check() { failWith?.let { throw it } }
    override suspend fun load(): ServerPrivacy { check(); return server }
    override suspend fun save(change: PrivacyChange): ServerPrivacy { check(); saves++; server = server.updated(change); return server }
    override suspend fun blockedUsers(): List<BlockedUser> { check(); return blocked.toList() }
    override suspend fun block(id: String, name: String) { check(); blocked += BlockedUser(id, name) }
    override suspend fun unblock(id: String) { check(); blocked.removeAll { it.id == id } }
}

private class FakeNotifPrefs(var failWith: Exception? = null) : NotificationPrefsRepository {
    override val syncedWithServer = true
    var server = NotificationPrefs()
    override suspend fun load(): NotificationPrefs { failWith?.let { throw it }; return server }
    override suspend fun save(change: NotificationPrefsChange): NotificationPrefs { failWith?.let { throw it }; server = server.updated(change); return server }
}

@OptIn(ExperimentalCoroutinesApi::class)
class PrivacyAndNotificationPrefsTest {
    @Before fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun privacyLoadsFromTheServer() {
        val vm = PrivacyViewModel(FakePrivacy(server = ServerPrivacy(false, true, false, MessagePolicy.Nobody)))
        val s = vm.state.value as PrivacyState.Ready
        assertFalse(s.privacy.discoverable)
        assertEquals(MessagePolicy.Nobody, s.privacy.messagePolicy)
        assertTrue(s.synced)
    }

    @Test fun privacyLoadFailureCanBeRetried() {
        val repo = FakePrivacy(failWith = IOException())
        val vm = PrivacyViewModel(repo)
        assertTrue(vm.state.value is PrivacyState.Failed)
        repo.failWith = null
        vm.load()
        assertTrue(vm.state.value is PrivacyState.Ready)
    }

    @Test fun aSuccessfulChangeIsSavedToTheServer() {
        val repo = FakePrivacy()
        val vm = PrivacyViewModel(repo)
        vm.change(PrivacyChange(readReceipts = false))
        assertFalse(repo.server.readReceipts)
        val s = vm.state.value as PrivacyState.Ready
        assertFalse(s.privacy.readReceipts)
        assertFalse(s.saving)
        assertEquals(null, s.error)
    }

    @Test fun aFailedChangeRestoresTheOldValueAndSaysSo() {
        val repo = FakePrivacy()
        val vm = PrivacyViewModel(repo)
        repo.failWith = IOException()
        vm.change(PrivacyChange(discoverable = false))
        val s = vm.state.value as PrivacyState.Ready
        assertTrue("old value must come back", s.privacy.discoverable)
        assertNotNull(s.error)
        assertFalse(s.saving)
        assertEquals(0, repo.saves)
    }

    @Test fun aServerRejectionIsShownNotHidden() {
        val repo = FakePrivacy()
        val vm = PrivacyViewModel(repo)
        repo.failWith = ApiException(400, "validation", "Not allowed.")
        vm.change(PrivacyChange(messagePolicy = MessagePolicy.Nobody))
        val s = vm.state.value as PrivacyState.Ready
        assertEquals(MessagePolicy.Everyone, s.privacy.messagePolicy)
        assertTrue(s.error!!.contains("Not allowed."))
    }

    @Test fun offlinePreviewBuildSaysChoicesStayOnThisDevice() = runTest {
        val settings = InMemorySettingsRepository()
        val repo = LocalPrivacyRepository(settings)
        val vm = PrivacyViewModel(repo)
        assertFalse((vm.state.value as PrivacyState.Ready).synced)
        vm.change(PrivacyChange(showOnline = false, messagePolicy = MessagePolicy.Following))
        assertFalse(settings.settings.first().showOnline)
        assertEquals(MessagePolicy.Following, settings.settings.first().messagePolicy)
    }

    @Test fun localBlockingKeepsOneEntryPerPerson() = runTest {
        val settings = InMemorySettingsRepository()
        val repo = LocalPrivacyRepository(settings)
        repo.block("u1", "Amina"); repo.block("u1", "Amina")
        assertEquals(1, settings.settings.first().blocked.size)
        repo.unblock("u1")
        assertTrue(settings.settings.first().blocked.isEmpty())
    }

    @Test fun blockedListComesFromTheServerAndUnblockRemovesOnlyThatPerson() {
        val repo = FakePrivacy()
        val vm = BlockedUsersViewModel(repo)
        assertEquals(listOf("Amina", "Brian"), (vm.state.value as BlockedState.Ready).users.map { it.name })
        vm.unblock("u1")
        assertEquals(listOf("u2"), (vm.state.value as BlockedState.Ready).users.map { it.id })
        assertEquals(listOf("u2"), repo.blocked.map { it.id })
    }

    @Test fun aFailedUnblockKeepsThePersonBlocked() {
        val repo = FakePrivacy()
        val vm = BlockedUsersViewModel(repo)
        repo.failWith = IOException()
        vm.unblock("u1")
        val s = vm.state.value as BlockedState.Ready
        assertEquals(2, s.users.size)
        assertNotNull(s.error)
        assertEquals(null, s.working)
    }

    @Test fun notificationChoiceIsSavedAndRolledBackOnFailure() {
        val repo = FakeNotifPrefs()
        val vm = NotificationPrefsViewModel(repo)
        vm.change(NotificationPrefsChange(likes = false))
        assertFalse(repo.server.likes)
        assertFalse((vm.state.value as NotificationPrefsState.Ready).prefs.likes)
        repo.failWith = IOException()
        vm.change(NotificationPrefsChange(pushEnabled = false))
        val s = vm.state.value as NotificationPrefsState.Ready
        assertTrue(s.prefs.pushEnabled)
        assertNotNull(s.error)
    }

    @Test fun communityAndStoryReactionSwitchesAreSavedLikeTheOthers() {
        val repo = FakeNotifPrefs()
        val vm = NotificationPrefsViewModel(repo)
        vm.change(NotificationPrefsChange(communities = false, storyReactions = false))
        val p = (vm.state.value as NotificationPrefsState.Ready).prefs
        assertFalse(p.communities)
        assertFalse(p.storyReactions)
        assertTrue(p.likes)
        assertFalse(repo.server.communities)
    }

    @Test fun refreshPicksUpNewServerValuesWithoutALoadingState() {
        val repo = FakeNotifPrefs()
        val vm = NotificationPrefsViewModel(repo)
        repo.server = repo.server.copy(likes = false)
        vm.refresh()
        assertFalse((vm.state.value as NotificationPrefsState.Ready).prefs.likes)
    }

    @Test fun aFailedRefreshKeepsWhatIsShownAndSaysNothing() {
        val repo = FakeNotifPrefs()
        val vm = NotificationPrefsViewModel(repo)
        repo.failWith = IOException("offline")
        repo.server = repo.server.copy(likes = false)
        vm.refresh()
        val s = vm.state.value as NotificationPrefsState.Ready
        assertTrue(s.prefs.likes)
        assertEquals(null, s.error)
    }

    @Test fun quietHoursSilenceEverythingExceptSecurity() {
        fun event(c: PushChannel) = PushEvent(c, "t", "b", "tag", "univibe://notifications")
        assertFalse(PushPolicy.shouldShow(event(PushChannel.Social), quietHoursOn = true, hourOfDay = 23))
        assertFalse(PushPolicy.shouldShow(event(PushChannel.DirectMessages), quietHoursOn = true, hourOfDay = 3))
        assertTrue(PushPolicy.shouldShow(event(PushChannel.Social), quietHoursOn = true, hourOfDay = 7))
        assertTrue(PushPolicy.shouldShow(event(PushChannel.Social), quietHoursOn = true, hourOfDay = 21))
        assertTrue(PushPolicy.shouldShow(event(PushChannel.Social), quietHoursOn = false, hourOfDay = 23))
        assertTrue(PushPolicy.shouldShow(event(PushChannel.Security), quietHoursOn = true, hourOfDay = 2))
    }
}
