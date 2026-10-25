package com.univibe.app.auth

import com.univibe.app.domain.auth.AuthError
import com.univibe.app.domain.auth.AuthRepository
import com.univibe.app.domain.auth.AuthResult
import com.univibe.app.features.settings.DataExportViewModel
import com.univibe.app.features.settings.DeactivateAccountViewModel
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** An auth repository whose deactivate and export answers the test chooses. Everything else is the shared fake. */
private class LifecycleAuth(
    var deactivate: AuthResult<Unit> = AuthResult.Success(Unit),
    var export: AuthResult<String> = AuthResult.Success("{\"format\":1}"),
    private val base: FakeAuthRepository = FakeAuthRepository(),
) : AuthRepository by base {
    var deactivateCalls = 0
    var exportCalls = 0
    override suspend fun deactivateAccount(password: String): AuthResult<Unit> { deactivateCalls++; return deactivate }
    override suspend fun exportMyData(): AuthResult<String> { exportCalls++; return export }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AccountLifecycleViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    // ---- deactivate

    @Test fun deactivateNeedsAPasswordAndTheConfirmation() {
        val vm = DeactivateAccountViewModel(LifecycleAuth())
        assertFalse(vm.state.value.canSubmit)
        vm.onPassword("secret-pass-1")
        assertFalse(vm.state.value.canSubmit)
        vm.onUnderstood(true)
        assertTrue(vm.state.value.canSubmit)
    }

    @Test fun submittingWithoutConfirmationDoesNothing() {
        val auth = LifecycleAuth()
        val vm = DeactivateAccountViewModel(auth)
        vm.onPassword("secret-pass-1")
        vm.submit()
        assertEquals(0, auth.deactivateCalls)
    }

    @Test fun aWrongPasswordShowsAMessageAndClearsTheField() {
        val auth = LifecycleAuth(deactivate = AuthResult.Failure(AuthError.InvalidCredentials))
        val vm = DeactivateAccountViewModel(auth)
        vm.onPassword("nope-nope-1"); vm.onUnderstood(true)
        vm.submit()
        val s = vm.state.value
        assertEquals("That password isn't correct.", s.error)
        assertEquals("", s.password)
        assertFalse(s.submitting)
    }

    @Test fun aNetworkFailureIsReportedAsOne() {
        val vm = DeactivateAccountViewModel(LifecycleAuth(deactivate = AuthResult.Failure(AuthError.Network)))
        vm.onPassword("secret-pass-1"); vm.onUnderstood(true)
        vm.submit()
        assertTrue(vm.state.value.error!!.contains("offline"))
    }

    // ---- export

    @Test fun exportKeepsTheCopyInMemoryUntilItIsSaved() {
        val vm = DataExportViewModel(LifecycleAuth(), dispatcher)
        vm.request()
        assertTrue(vm.state.value.ready)
        val out = ByteArrayOutputStream()
        vm.save { out }
        assertEquals("{\"format\":1}", out.toString(Charsets.UTF_8.name()))
        val s = vm.state.value
        assertTrue(s.saved)
        assertFalse(s.ready)
        // The copy is dropped once saved: saving again writes nothing.
        val again = ByteArrayOutputStream()
        vm.save { again }
        assertEquals(0, again.size())
    }

    @Test fun aFailedSaveKeepsTheCopySoThePersonCanTryAgain() {
        val vm = DataExportViewModel(LifecycleAuth(), dispatcher)
        vm.request()
        vm.save { null }
        assertTrue(vm.state.value.ready)
        assertTrue(vm.state.value.error!!.contains("Couldn't save"))
        val out = ByteArrayOutputStream()
        vm.save { out }
        assertTrue(vm.state.value.saved)
        assertEquals("{\"format\":1}", out.toString(Charsets.UTF_8.name()))
    }

    @Test fun anIoErrorWhileWritingIsAFailedSaveNotACrash() {
        val vm = DataExportViewModel(LifecycleAuth(), dispatcher)
        vm.request()
        vm.save { object : OutputStream() { override fun write(b: Int) { throw IOException("disk full") } } }
        assertFalse(vm.state.value.saved)
        assertTrue(vm.state.value.ready)
    }

    @Test fun aServerFailureIsShownAndNothingIsReady() {
        val vm = DataExportViewModel(LifecycleAuth(export = AuthResult.Failure(AuthError.RateLimited(600))), dispatcher)
        vm.request()
        val s = vm.state.value
        assertFalse(s.ready)
        assertTrue(s.error!!.startsWith("Too many attempts"))
    }

    @Test fun savingBeforeRequestingDoesNothing() {
        val vm = DataExportViewModel(LifecycleAuth(), dispatcher)
        val out = ByteArrayOutputStream()
        vm.save { out }
        assertEquals(0, out.size())
        assertNull(vm.state.value.error)
    }
}
