package com.univibe.app

import com.univibe.app.data.net.ApiException
import com.univibe.app.data.settings.AccountSecurityRepository
import com.univibe.app.data.settings.AccountStatus
import com.univibe.app.data.settings.LoginSession
import com.univibe.app.data.settings.SessionList
import com.univibe.app.data.settings.UnavailableAccountSecurityRepository
import com.univibe.app.features.settings.AccountStatusState
import com.univibe.app.features.settings.AccountStatusViewModel
import com.univibe.app.features.settings.LoginActivityState
import com.univibe.app.features.settings.LoginActivityViewModel
import com.univibe.app.features.settings.campusVerificationLabel
import com.univibe.app.features.settings.formatServerTime
import com.univibe.app.features.settings.noticeActionLabel
import java.io.IOException
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class FakeSecurity(
    var sessions: MutableList<LoginSession> = mutableListOf(
        LoginSession("a", "Pixel 8", "2026-10-18T08:00:00.000000Z", "2026-10-18T09:00:00.000000Z", current = true),
        LoginSession("b", "Galaxy Tab", "2026-10-17T08:00:00.000000Z", "2026-10-17T09:00:00.000000Z", current = false),
    ),
    var currentKnown: Boolean = true,
    var failWith: Exception? = null,
    var status: AccountStatus = AccountStatus(false, null, 0, 0, "verified", emptyList(), false),
) : AccountSecurityRepository {
    override val available = true
    val ended = mutableListOf<String>()
    private fun check() { failWith?.let { throw it } }
    override suspend fun sessions(): SessionList { check(); return SessionList(if (currentKnown) "a" else null, sessions.toList()) }
    override suspend fun endSession(id: String) { check(); ended += id; sessions.removeAll { it.id == id } }
    override suspend fun endOtherSessions(): Int { check(); val n = sessions.count { !it.current }; sessions.removeAll { !it.current }; return n }
    override suspend fun accountStatus(): AccountStatus { check(); return status }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SessionsAndStatusTest {
    @Before fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun loadsTheServersSessions() {
        val s = LoginActivityViewModel(FakeSecurity()).state.value as LoginActivityState.Ready
        assertEquals(2, s.sessions.size)
        assertEquals(1, s.others.size)
        assertTrue(s.currentKnown)
    }

    @Test fun offlinePreviewBuildSaysUnavailableInsteadOfInventingDevices() {
        assertEquals(LoginActivityState.Unavailable, LoginActivityViewModel(UnavailableAccountSecurityRepository).state.value)
        assertEquals(AccountStatusState.Unavailable, AccountStatusViewModel(UnavailableAccountSecurityRepository).state.value)
    }

    @Test fun networkFailureOnLoadShowsAnErrorThatCanBeRetried() {
        val repo = FakeSecurity(failWith = IOException())
        val vm = LoginActivityViewModel(repo)
        assertTrue(vm.state.value is LoginActivityState.Failed)
        repo.failWith = null
        vm.load()
        assertTrue(vm.state.value is LoginActivityState.Ready)
    }

    @Test fun endingOtherDevicesRefreshesFromTheServer() {
        val repo = FakeSecurity()
        val vm = LoginActivityViewModel(repo)
        vm.endOthers()
        val s = vm.state.value as LoginActivityState.Ready
        assertEquals(listOf("a"), s.sessions.map { it.id })
        assertEquals("Logged out of 1 other device.", s.notice)
        assertFalse(s.working)
    }

    @Test fun aFailedRevokeKeepsTheListAndSaysWhy() {
        val repo = FakeSecurity()
        val vm = LoginActivityViewModel(repo)
        repo.failWith = IOException()
        vm.endSession("b")
        val s = vm.state.value as LoginActivityState.Ready
        assertEquals(2, s.sessions.size)
        assertNotNull(s.error)
        assertNull(s.notice)
        assertFalse(s.working)
    }

    @Test fun endingOneDeviceRemovesOnlyThatDevice() {
        val repo = FakeSecurity()
        val vm = LoginActivityViewModel(repo)
        vm.endSession("b")
        assertEquals(listOf("b"), repo.ended)
        assertEquals(listOf("a"), (vm.state.value as LoginActivityState.Ready).sessions.map { it.id })
    }

    @Test fun reloginRequiredIsExplained() {
        val repo = FakeSecurity()
        val vm = LoginActivityViewModel(repo)
        repo.failWith = ApiException(409, "relogin_required", "x")
        vm.endOthers()
        assertTrue((vm.state.value as LoginActivityState.Ready).error!!.contains("log in again", ignoreCase = true))
    }

    @Test fun legacyLoginIsToldToLogInAgain() {
        val s = LoginActivityViewModel(FakeSecurity(currentKnown = false)).state.value as LoginActivityState.Ready
        assertFalse(s.currentKnown)
    }

    @Test fun accountStatusIsShownExactlyAsReported() {
        val st = AccountStatus(true, "2026-10-21T08:00:00.000Z", 2, 1, "pending", emptyList(), false)
        val s = AccountStatusViewModel(FakeSecurity(status = st)).state.value as AccountStatusState.Ready
        assertEquals(st, s.status)
    }

    @Test fun statusFailureCanBeRetried() {
        val repo = FakeSecurity(failWith = IOException())
        val vm = AccountStatusViewModel(repo)
        assertTrue(vm.state.value is AccountStatusState.Failed)
        repo.failWith = null
        vm.load()
        assertTrue(vm.state.value is AccountStatusState.Ready)
    }

    @Test fun serverTimesAreShownInTheDeviceZoneWithoutChangingTheValue() {
        val iso = "2026-10-18T22:30:00.123456Z"
        // Newer JDKs put a narrow no-break space before AM/PM; compare with a normal space.
        assertEquals("19 Oct 2026, 1:30 AM", formatServerTime(iso, ZoneId.of("Africa/Nairobi"), Locale.US).replace('\u202f', ' '))
        assertEquals("", formatServerTime("not a date"))
    }

    @Test fun labelsNeverClaimMoreThanTheServerSaid() {
        assertEquals("Not verified", campusVerificationLabel("unverified"))
        assertEquals("Not verified", campusVerificationLabel("something-new"))
        assertEquals("Moderation update", noticeActionLabel("something-new"))
        assertEquals("Account suspended", noticeActionLabel("suspend_user"))
    }
}
