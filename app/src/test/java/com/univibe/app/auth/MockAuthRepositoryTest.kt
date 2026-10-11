package com.univibe.app.auth

import com.univibe.app.data.auth.Clock
import com.univibe.app.data.auth.InMemorySecureStore
import com.univibe.app.data.auth.MockAuthRepository
import com.univibe.app.data.auth.SecureStore
import com.univibe.app.domain.auth.AuthError
import com.univibe.app.domain.auth.AuthResult
import com.univibe.app.domain.auth.AuthState
import com.univibe.app.domain.auth.OnboardingProfile
import com.univibe.app.domain.auth.SignUpRequest
import com.univibe.app.domain.auth.SignedOutReason
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeClock(var now: Long = 1_000_000L) : Clock { override fun nowMs() = now }

class MockAuthRepositoryTest {
    private val pw = "Campus2026!"
    private val email = "aisha@students.lakeside.example"

    private fun repo(store: SecureStore = InMemorySecureStore(), clock: Clock = FakeClock()) =
        MockAuthRepository(store, clock, latencyMs = 0)

    private fun request(email: String = this.email, password: String = pw, terms: Boolean = true) =
        SignUpRequest("Aisha Njoki", email, null, "lakeside", password, terms)

    private fun failure(r: AuthResult<*>): AuthError = (r as AuthResult.Failure).error

    private suspend fun MockAuthRepository.registerAndVerify(email: String = this@MockAuthRepositoryTest.email) {
        assertTrue(signUp(request(email)) is AuthResult.Success)
        assertTrue(verifyEmail(latestVerificationCode()!!) is AuthResult.Success)
    }

    private val profile = OnboardingProfile("Computer Science", listOf("Music"), "Hi", false)

    @Test fun signUpMovesToEmailVerification() = runTest {
        val r = repo()
        assertTrue(r.signUp(request()) is AuthResult.Success)
        assertEquals(AuthState.EmailVerificationRequired(email), r.session.value)
    }

    @Test fun signUpRejectsWeakPasswordAndMissingTerms() = runTest {
        val r = repo()
        assertTrue(failure(r.signUp(request(password = "weak"))) is AuthError.Validation)
        assertTrue(failure(r.signUp(request(terms = false))) is AuthError.Validation)
    }

    @Test fun duplicateSignUpIsGenericRejection() = runTest {
        val r = repo()
        r.signUp(request())
        assertEquals(AuthError.SignUpRejected, failure(r.signUp(request())))
    }

    @Test fun wrongCodeThenRightCodeLeadsToOnboarding() = runTest {
        val r = repo()
        r.signUp(request())
        assertEquals(AuthError.InvalidCode, failure(r.verifyEmail("000000".takeIf { it != r.latestVerificationCode() } ?: "111111")))
        assertTrue(r.verifyEmail(r.latestVerificationCode()!!) is AuthResult.Success)
        assertTrue(r.session.value is AuthState.OnboardingRequired)
    }

    @Test fun studentVerifiedOnlyForUniversityEmail() = runTest {
        val uni = repo(); uni.registerAndVerify()
        assertTrue((uni.session.value as AuthState.OnboardingRequired).user.studentVerified)
        val other = repo(); other.registerAndVerify("someone@gmail.com")
        val user = (other.session.value as AuthState.OnboardingRequired).user
        assertTrue(user.emailVerified)
        assertFalse(user.studentVerified)
    }

    @Test fun onboardingThenLogout() = runTest {
        val r = repo(); r.registerAndVerify()
        assertTrue(r.completeOnboarding(profile) is AuthResult.Success)
        assertTrue(r.session.value is AuthState.Authenticated)
        r.logOut()
        assertEquals(AuthState.SignedOut(), r.session.value)
    }

    @Test fun loginBeforeVerificationRequiresVerification() = runTest {
        val r = repo(); r.signUp(request())
        r.logOut()
        assertEquals(AuthError.EmailNotVerified, failure(r.logIn(email, pw, false)))
        assertEquals(AuthState.EmailVerificationRequired(email), r.session.value)
    }

    @Test fun invalidCredentialsAreIdenticalForUnknownAndWrongPassword() = runTest {
        val r = repo(); r.registerAndVerify(); r.logOut()
        val wrong = failure(r.logIn(email, "WrongPass1!", false))
        val unknown = failure(r.logIn("nobody@x.com", "WrongPass1!", false))
        assertEquals(AuthError.InvalidCredentials, wrong)
        assertEquals(wrong, unknown)
    }

    @Test fun loginIsRateLimitedAfterRepeatedFailures() = runTest {
        val r = repo(); r.registerAndVerify(); r.logOut()
        repeat(5) { r.logIn(email, "WrongPass1!", false) }
        assertTrue(failure(r.logIn(email, pw, false)) is AuthError.RateLimited)
    }

    @Test fun expiredCodeIsReported() = runTest {
        val clock = FakeClock(); val r = repo(clock = clock)
        r.signUp(request())
        val code = r.latestVerificationCode()!!
        clock.now += 11 * 60 * 1000
        assertEquals(AuthError.ExpiredCode, failure(r.verifyEmail(code)))
    }

    @Test fun resendHonoursCooldown() = runTest {
        val clock = FakeClock(); val r = repo(clock = clock)
        r.signUp(request())
        assertTrue(failure(r.resendVerification()) is AuthError.RateLimited)
        clock.now += 61_000
        assertTrue(r.resendVerification() is AuthResult.Success)
    }

    @Test fun passwordResetFlowAndSingleUseCode() = runTest {
        val r = repo(); r.registerAndVerify(); r.logOut()
        assertTrue(r.requestPasswordReset("ghost@x.com") is AuthResult.Success) // no enumeration
        assertTrue(r.requestPasswordReset(email) is AuthResult.Success)
        val code = r.latestResetCode(email)!!
        assertEquals(AuthError.InvalidCode, failure(r.resetPassword(email, if (code == "111111") "222222" else "111111", "NewPass2026!")))
        assertTrue(r.resetPassword(email, code, "NewPass2026!") is AuthResult.Success)
        assertEquals(AuthState.SignedOut(SignedOutReason.PasswordChanged), r.session.value)
        assertEquals(AuthError.InvalidCode, failure(r.resetPassword(email, code, "Another2026!"))) // single use
        assertEquals(AuthError.InvalidCredentials, failure(r.logIn(email, pw, false)))
        assertTrue(r.logIn(email, "NewPass2026!", false) is AuthResult.Success)
    }

    @Test fun rememberedSessionSurvivesRestartButOnlyIfRequested() = runTest {
        val store = InMemorySecureStore(); val clock = FakeClock()
        val first = repo(store, clock); first.registerAndVerify(); first.completeOnboarding(profile); first.logOut()
        first.logIn(email, pw, rememberMe = true)
        val restored = repo(store, clock); restored.restoreSession()
        assertTrue(restored.session.value is AuthState.Authenticated)

        first.logOut(); first.logIn(email, pw, rememberMe = false)
        val notRestored = repo(store, clock); notRestored.restoreSession()
        assertEquals(AuthState.SignedOut(), notRestored.session.value)
    }

    @Test fun expiredStoredSessionSignsOutWithReason() = runTest {
        val store = InMemorySecureStore(); val clock = FakeClock()
        val first = repo(store, clock); first.registerAndVerify(); first.completeOnboarding(profile); first.logOut()
        first.logIn(email, pw, rememberMe = true)
        clock.now += 8L * 24 * 60 * 60 * 1000
        val later = repo(store, clock); later.restoreSession()
        assertEquals(AuthState.SignedOut(SignedOutReason.SessionExpired), later.session.value)
    }

    @Test fun serverSideSessionExpiryReturnsToSignedOut() = runTest {
        val r = repo(); r.registerAndVerify(); r.completeOnboarding(profile)
        r.handleSessionExpired()
        assertEquals(AuthState.SignedOut(SignedOutReason.SessionExpired), r.session.value)
    }
}
