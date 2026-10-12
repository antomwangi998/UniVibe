package com.univibe.app.auth

import com.univibe.app.data.settings.BlockedUser
import com.univibe.app.data.settings.InMemorySettingsRepository
import com.univibe.app.data.settings.MessagePolicy
import com.univibe.app.data.settings.TextScale
import com.univibe.app.features.settings.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    @Before fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun vm() = SettingsViewModel(InMemorySettingsRepository(), FakeAuthRepository())

    @Test fun defaultsAreSensible() {
        val s = vm().settings.value
        assertTrue(s.notificationsEnabled)
        assertEquals(MessagePolicy.Everyone, s.messagePolicy)
        assertEquals(TextScale.Default, s.textScale)
        assertFalse(s.reduceMotion)
    }

    @Test fun updatesAreReflectedInState() {
        val vm = vm()
        vm.update { it.copy(discoverable = false, messagePolicy = MessagePolicy.Nobody, textScale = TextScale.Larger) }
        val s = vm.settings.value
        assertFalse(s.discoverable)
        assertEquals(MessagePolicy.Nobody, s.messagePolicy)
        assertEquals(TextScale.Larger, s.textScale)
    }

    @Test fun blockedStudentsCanBeUnblocked() {
        val vm = vm()
        vm.update { it.copy(blocked = listOf(BlockedUser("s3", "Grace"), BlockedUser("s4", "Kevin"))) }
        vm.unblock("s3")
        assertEquals(listOf(BlockedUser("s4", "Kevin")), vm.settings.value.blocked)
    }
}
