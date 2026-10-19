package com.univibe.app.auth

import com.univibe.app.data.auth.GoogleCredentialResult
import com.univibe.app.domain.auth.AuthError
import com.univibe.app.domain.auth.AuthRepository
import com.univibe.app.domain.auth.AuthResult
import com.univibe.app.domain.auth.AuthState
import com.univibe.app.domain.auth.GooglePrefill
import com.univibe.app.domain.auth.GoogleRegistrationRequest
import com.univibe.app.domain.auth.OnboardingProfile
import com.univibe.app.domain.auth.SignUpRequest
import com.univibe.app.domain.auth.University
import com.univibe.app.features.auth.GoogleAuthViewModel
import com.univibe.app.features.auth.GoogleLinkViewModel
import com.univibe.app.features.auth.GoogleRegistrationViewModel
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class GoogleFakeRepository(initial: AuthState = AuthState.SignedOut()) : AuthRepository {
    override val session = MutableStateFlow(initial)
    override val googleSignInAvailable = true
    var nonceResult: AuthResult<String> = AuthResult.Success("nonce-1234567890abcd")
    var signInResult: AuthResult<Unit> = AuthResult.Success(Unit)
    var registerResult: AuthResult<Unit> = AuthResult.Success(Unit)
    var linkResult: AuthResult<Unit> = AuthResult.Success(Unit)
    var gate: CompletableDeferred<Unit>? = null
    var signInCalls = 0
    var registerCalls = 0
    var linkCalls = 0
    var cancelled = 0
    var lastToken: String? = null
    var lastNonce: String? = null

    override suspend fun googleNonce() = nonceResult
    override suspend fun signInWithGoogle(idToken: String, nonce: String): AuthResult<Unit> {
        signInCalls++; lastToken = idToken; lastNonce = nonce
        gate?.await()
        return signInResult
    }
    override suspend fun completeGoogleRegistration(request: GoogleRegistrationRequest): AuthResult<Unit> {
        registerCalls++
        gate?.await()
        return registerResult
    }
    override suspend fun linkGoogleAccount(password: String): AuthResult<Unit> { linkCalls++; return linkResult }
    override fun cancelGoogleFlow() { cancelled++ }

    override suspend fun universities() = listOf(University("uni", "Uni", listOf("uni.example")))
    override suspend fun restoreSession() = Unit
    override suspend fun signUp(request: SignUpRequest): AuthResult<Unit> = AuthResult.Success(Unit)
    override suspend fun logIn(email: String, password: String, rememberMe: Boolean): AuthResult<Unit> = AuthResult.Success(Unit)
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
class GoogleAuthViewModelTest {
    @Before fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun cancellingGoogleIsSilent() {
        val repo = GoogleFakeRepository()
        val vm = GoogleAuthViewModel(repo)
        vm.continueWithGoogle { GoogleCredentialResult.Cancelled }
        val s = vm.state.value
        assertFalse(s.busy); assertNull(s.error); assertNull(s.notice)
        assertEquals(0, repo.signInCalls)
    }

    @Test fun tokenGoesToTheServerWithTheSameNonce() {
        val repo = GoogleFakeRepository()
        val vm = GoogleAuthViewModel(repo)
        var nonceShownToGoogle: String? = null
        vm.continueWithGoogle { nonce -> nonceShownToGoogle = nonce; GoogleCredentialResult.Token("the-id-token") }
        assertEquals("nonce-1234567890abcd", nonceShownToGoogle)
        assertEquals("the-id-token", repo.lastToken)
        assertEquals(nonceShownToGoogle, repo.lastNonce)
        assertFalse(vm.state.value.busy)
        assertNull(vm.state.value.error)
    }

    @Test fun noGoogleAccountIsACalmHintNotAnError() {
        val vm = GoogleAuthViewModel(GoogleFakeRepository())
        vm.continueWithGoogle { GoogleCredentialResult.NoAccount }
        assertNull(vm.state.value.error)
        assertNotNull(vm.state.value.notice)
    }

    @Test fun serverRejectionShowsAGenericMessageWithoutTokenDetails() {
        val repo = GoogleFakeRepository().apply { signInResult = AuthResult.Failure(AuthError.InvalidGoogleToken) }
        val vm = GoogleAuthViewModel(repo)
        vm.continueWithGoogle { GoogleCredentialResult.Token("secret-token-value") }
        val message = vm.state.value.error
        assertNotNull(message)
        assertFalse(message!!.contains("secret-token-value"))
    }

    @Test fun offlineAndUnavailableAndFailedShowMessages() {
        val offline = GoogleFakeRepository().apply { nonceResult = AuthResult.Failure(AuthError.Network) }
        GoogleAuthViewModel(offline).also { it.continueWithGoogle { GoogleCredentialResult.Cancelled } }.let { assertNotNull(it.state.value.error) }
        listOf(GoogleCredentialResult.Unavailable, GoogleCredentialResult.Failed).forEach { result ->
            val vm = GoogleAuthViewModel(GoogleFakeRepository())
            vm.continueWithGoogle { result }
            assertNotNull(vm.state.value.error)
        }
    }

    @Test fun secondTapWhileBusyDoesNothing() {
        val repo = GoogleFakeRepository().apply { gate = CompletableDeferred() }
        val vm = GoogleAuthViewModel(repo)
        vm.continueWithGoogle { GoogleCredentialResult.Token("t") }
        assertTrue(vm.state.value.busy)
        vm.continueWithGoogle { GoogleCredentialResult.Token("t") }
        assertEquals(1, repo.signInCalls)
        repo.gate!!.complete(Unit)
        assertFalse(vm.state.value.busy)
    }

    // ---- registration step
    @Test fun registrationIsPrefilledFromVerifiedClaimsAndNeedsTheTerms() {
        val repo = GoogleFakeRepository(AuthState.GoogleRegistrationRequired(GooglePrefill("Grace Hopper", "grace@gmail.example")))
        val vm = GoogleRegistrationViewModel(repo)
        assertEquals("Grace Hopper", vm.state.value.fullName)
        assertEquals("grace@gmail.example", vm.state.value.email)
        vm.submit()
        assertTrue(vm.state.value.showErrors)
        assertEquals(0, repo.registerCalls)
        vm.onTerms(true)
        vm.submit()
        assertEquals(1, repo.registerCalls)
    }

    @Test fun registrationCannotBeSubmittedTwice() {
        val repo = GoogleFakeRepository(AuthState.GoogleRegistrationRequired(GooglePrefill("Grace Hopper", "g@x.co"))).apply { gate = CompletableDeferred() }
        val vm = GoogleRegistrationViewModel(repo)
        vm.onTerms(true)
        vm.submit(); vm.submit(); vm.submit()
        assertEquals(1, repo.registerCalls)
        repo.gate!!.complete(Unit)
        assertFalse(vm.state.value.submitting)
    }

    @Test fun leavingRegistrationCancelsTheFlow() {
        val repo = GoogleFakeRepository(AuthState.GoogleRegistrationRequired(GooglePrefill("A B", "a@b.co")))
        GoogleRegistrationViewModel(repo).cancel()
        assertEquals(1, repo.cancelled)
    }

    // ---- link step
    @Test fun linkNeedsAPasswordAndShowsAFriendlyWrongPasswordMessage() {
        val repo = GoogleFakeRepository(AuthState.GoogleLinkRequired("grace@gmail.example")).apply { linkResult = AuthResult.Failure(AuthError.InvalidCredentials) }
        val vm = GoogleLinkViewModel(repo)
        assertEquals("grace@gmail.example", vm.state.value.email)
        vm.submit()
        assertNotNull(vm.state.value.passwordError)
        assertEquals(0, repo.linkCalls)
        vm.onPassword("wrong")
        vm.submit()
        assertEquals(1, repo.linkCalls)
        assertEquals("That password isn't correct.", vm.state.value.error)
        assertEquals("", vm.state.value.password) // the typed password is not kept
    }
}
