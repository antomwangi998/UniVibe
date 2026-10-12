package com.univibe.app.auth

import com.univibe.app.domain.auth.AuthError
import com.univibe.app.domain.auth.AuthRepository
import com.univibe.app.domain.auth.AuthResult
import com.univibe.app.domain.auth.AuthState
import com.univibe.app.domain.auth.OnboardingProfile
import com.univibe.app.domain.auth.SignUpRequest
import com.univibe.app.domain.auth.University
import com.univibe.app.features.auth.LoginViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

internal class FakeAuthRepository : AuthRepository {
    override val session: StateFlow<AuthState> = MutableStateFlow(AuthState.SignedOut())
    var logInCalls = 0
    var logInResult: AuthResult<Unit> = AuthResult.Success(Unit)
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun logIn(email: String, password: String, rememberMe: Boolean): AuthResult<Unit> {
        logInCalls++
        gate?.await()
        return logInResult
    }
    override suspend fun universities() = emptyList<University>()
    override suspend fun restoreSession() = Unit
    override suspend fun signUp(request: SignUpRequest): AuthResult<Unit> = AuthResult.Success(Unit)
    override suspend fun verifyEmail(code: String): AuthResult<Unit> = AuthResult.Success(Unit)
    override suspend fun resendVerification(): AuthResult<Int> = AuthResult.Success(60)
    override suspend fun refreshVerificationStatus(): AuthResult<Boolean> = AuthResult.Success(false)
    override suspend fun requestPasswordReset(email: String): AuthResult<Unit> = AuthResult.Success(Unit)
    override suspend fun resetPassword(email: String, code: String, newPassword: String): AuthResult<Unit> = AuthResult.Success(Unit)
    override suspend fun completeOnboarding(profile: OnboardingProfile): AuthResult<Unit> = AuthResult.Success(Unit)
    override suspend fun changePassword(currentPassword: String, newPassword: String): AuthResult<Unit> = AuthResult.Success(Unit)
    override suspend fun deleteAccount(password: String): AuthResult<Unit> = AuthResult.Success(Unit)
    override suspend fun handleSessionExpired() = Unit
    override suspend fun logOut() = Unit
}

@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {
    @Before fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun invalidInputShowsFieldErrorsWithoutCallingRepository() {
        val repo = FakeAuthRepository()
        val vm = LoginViewModel(repo)
        vm.onEmail("not-an-email")
        vm.submit()
        assertNotNull(vm.state.value.emailError)
        assertNotNull(vm.state.value.passwordError)
        assertEquals(0, repo.logInCalls)
    }

    @Test fun networkFailureShowsMessageAndClearsPassword() {
        val repo = FakeAuthRepository().apply { logInResult = AuthResult.Failure(AuthError.Network) }
        val vm = LoginViewModel(repo)
        vm.onEmail("a@b.com"); vm.onPassword("secret123")
        vm.submit()
        val s = vm.state.value
        assertNotNull(s.error)
        assertEquals("", s.password)
        assertFalse(s.submitting)
    }

    @Test fun duplicateSubmissionsAreIgnoredWhileProcessing() {
        val repo = FakeAuthRepository().apply { gate = CompletableDeferred() }
        val vm = LoginViewModel(repo)
        vm.onEmail("a@b.com"); vm.onPassword("secret123")
        vm.submit(); vm.submit(); vm.submit()
        assertEquals(1, repo.logInCalls)
        assertEquals(true, vm.state.value.submitting)
        repo.gate!!.complete(Unit)
        assertFalse(vm.state.value.submitting)
        assertNull(vm.state.value.error)
    }
}
