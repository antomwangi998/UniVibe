package com.univibe.app.auth

import com.univibe.app.data.auth.Clock
import com.univibe.app.data.auth.InMemorySecureStore
import com.univibe.app.data.auth.RemoteAuthRepository
import com.univibe.app.data.auth.SecureStore
import com.univibe.app.data.net.HttpRequest
import com.univibe.app.data.net.HttpResponse
import com.univibe.app.data.net.HttpTransport
import com.univibe.app.domain.auth.AuthError
import com.univibe.app.domain.auth.AuthResult
import com.univibe.app.domain.auth.AuthState
import com.univibe.app.domain.auth.OnboardingProfile
import com.univibe.app.domain.auth.ProfileEdit
import com.univibe.app.domain.auth.SignUpRequest
import com.univibe.app.domain.auth.SignedOutReason
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

private const val BASE = "https://api.example.test"
private const val EMAIL = "411127-2026@student.cuk.ac.ke"
private const val REFRESH_KEY = "remote_refresh_token"
private const val USER_KEY = "remote_user"

private class TestClock : Clock {
    var now = 1_000_000L
    override fun nowMs() = now
}

private class FakeTransport(private val handler: (HttpRequest) -> HttpResponse) : HttpTransport {
    val requests = mutableListOf<HttpRequest>()
    override suspend fun execute(request: HttpRequest): HttpResponse {
        requests += request
        return handler(request)
    }
    fun route(request: HttpRequest) = "${request.method} ${request.url.removePrefix(BASE)}"
    fun count(route: String) = requests.count { route(it) == route }
}

private fun ok(body: String) = HttpResponse(200, body)
private fun errorResponse(status: Int, code: String, message: String = "msg", retryAfter: Int? = null): HttpResponse {
    val json = JSONObject().put("error", code).put("message", message)
    if (retryAfter != null) json.put("retryAfter", retryAfter)
    return HttpResponse(status, json.toString())
}

private fun userJson(verified: Boolean = true, onboarded: Boolean = false) = JSONObject()
    .put("id", "u1").put("fullName", "Test Student").put("email", EMAIL)
    .put("universityId", "cuk").put("universityName", "The Co-operative University of Kenya")
    .put("emailVerified", verified).put("studentVerified", true)
    .put("handle", "student1").put("yearOfStudy", "")
    .put("course", "").put("interests", org.json.JSONArray()).put("bio", "").put("hasPhoto", false)
    .put("onboardingComplete", onboarded).toString()

private fun sessionJson(access: String = "a1", refresh: String = "r1", onboarded: Boolean = false) =
    """{"accessToken":"$access","refreshToken":"$refresh","expiresIn":900,"user":${userJson(onboarded = onboarded)}}"""

private fun signUpRequest(email: String = EMAIL) = SignUpRequest(
    fullName = "Test Student", email = email, admissionNumber = "411127-2026",
    universityId = "cuk", password = "correct-horse-9", acceptedTerms = true,
)

class RemoteAuthRepositoryTest {
    private fun create(
        store: SecureStore = InMemorySecureStore(),
        clock: Clock = TestClock(),
        handler: (HttpRequest) -> HttpResponse,
    ): Triple<RemoteAuthRepository, FakeTransport, SecureStore> {
        val transport = FakeTransport(handler)
        return Triple(RemoteAuthRepository(BASE, transport, store, clock), transport, store)
    }

    private suspend fun RemoteAuthRepository.signedIn(onboarded: Boolean = true) =
        logIn(EMAIL, "correct-horse-9", rememberMe = true).also {
            assertTrue(it is AuthResult.Success)
            assertTrue(session.value.let { s -> if (onboarded) s is AuthState.Authenticated else s is AuthState.OnboardingRequired })
        }

    private fun failureOf(result: AuthResult<*>): AuthError = (result as AuthResult.Failure).error

    // ---- basics
    @Test fun refusesPlainHttp() {
        try {
            RemoteAuthRepository("http://insecure.example", FakeTransport { ok("{}") }, InMemorySecureStore())
            fail("expected a refusal")
        } catch (e: IllegalArgumentException) { /* expected */ }
    }

    @Test fun loadsUniversities() = runTest {
        val (repo) = create { ok("""[{"id":"cuk","name":"The Co-operative University of Kenya","emailDomains":["student.cuk.ac.ke"]}]""") }
        val list = repo.universities()
        assertEquals(1, list.size)
        assertEquals("cuk", list[0].id)
        assertTrue(list[0].matchesEmail(EMAIL))
    }

    // ---- sign-up
    @Test fun signUpSendsCleanedDetailsAndMovesToVerification() = runTest {
        val (repo, transport) = create { HttpResponse(202, """{"status":"verification_sent","resendCooldownSeconds":60}""") }
        val result = repo.signUp(signUpRequest("  411127-2026@STUDENT.cuk.ac.ke "))
        assertTrue(result is AuthResult.Success)
        assertEquals(AuthState.EmailVerificationRequired(EMAIL), repo.session.value)
        val sent = transport.requests.single()
        assertEquals("POST $BASE/auth/register", "${sent.method} ${sent.url}")
        val body = JSONObject(sent.body!!)
        assertEquals(EMAIL, body.getString("email"))
        assertEquals("cuk", body.getString("universityId"))
        assertTrue(body.getBoolean("acceptedTerms"))
        assertFalse(sent.headers.containsKey("Authorization"))
    }

    @Test fun signUpIsCheckedLocallyBeforeAnyRequest() = runTest {
        val (repo, transport) = create { ok("{}") }
        assertTrue(failureOf(repo.signUp(signUpRequest("not-an-email"))) is AuthError.Validation)
        assertTrue(failureOf(repo.signUp(signUpRequest().copy(acceptedTerms = false))) is AuthError.Validation)
        assertTrue(transport.requests.isEmpty())
    }

    @Test fun serverValidationMessageIsShown() = runTest {
        val (repo) = create { errorResponse(400, "validation", "Choose your university.") }
        assertEquals(AuthError.Validation("Choose your university."), failureOf(repo.signUp(signUpRequest())))
    }

    // ---- verification
    @Test fun verifyingSignsInAndRemembersTheSession() = runTest {
        val (repo, transport, store) = create { request ->
            if (request.url.endsWith("/auth/register")) HttpResponse(202, "{}") else ok(sessionJson())
        }
        repo.signUp(signUpRequest())
        val result = repo.verifyEmail(" 123456 ")
        assertTrue(result is AuthResult.Success)
        assertTrue(repo.session.value is AuthState.OnboardingRequired)
        assertEquals("student1", (repo.session.value as AuthState.OnboardingRequired).user.handle)
        assertEquals("r1", store.get(REFRESH_KEY))
        assertEquals("123456", JSONObject(transport.requests.last().body!!).getString("code"))
    }

    @Test fun codeErrorsAreMapped() = runTest {
        var next = errorResponse(400, "invalid_code")
        val (repo) = create { request -> if (request.url.endsWith("/auth/register")) HttpResponse(202, "{}") else next }
        repo.signUp(signUpRequest())
        assertEquals(AuthError.InvalidCode, failureOf(repo.verifyEmail("111111")))
        next = errorResponse(400, "expired_code")
        assertEquals(AuthError.ExpiredCode, failureOf(repo.verifyEmail("111111")))
        next = errorResponse(429, "rate_limited", retryAfter = 600)
        assertEquals(AuthError.RateLimited(600), failureOf(repo.verifyEmail("111111")))
    }

    @Test fun verifyWithoutSignUpAsksToStartAgain() = runTest {
        val (repo) = create { ok("{}") }
        assertEquals(AuthError.SessionExpired, failureOf(repo.verifyEmail("123456")))
    }

    @Test fun resendReturnsTheServersCooldown() = runTest {
        val (repo) = create { HttpResponse(202, """{"status":"verification_sent","resendCooldownSeconds":45}""") }
        repo.signUp(signUpRequest())
        assertEquals(45, (repo.resendVerification() as AuthResult.Success).value)
    }

    // ---- log in
    @Test fun wrongPasswordIsGeneric() = runTest {
        val (repo) = create { errorResponse(401, "invalid_credentials") }
        assertEquals(AuthError.InvalidCredentials, failureOf(repo.logIn(EMAIL, "nope", true)))
        assertTrue(repo.session.value is com.univibe.app.domain.auth.AuthState.LoadingSession)
    }

    @Test fun unverifiedLoginGoesToVerificationAndAsksForACode() = runTest {
        val (repo, transport) = create { request ->
            if (request.url.endsWith("/auth/login")) errorResponse(403, "email_not_verified") else HttpResponse(202, "{}")
        }
        assertEquals(AuthError.EmailNotVerified, failureOf(repo.logIn(EMAIL, "correct-horse-9", true)))
        assertEquals(AuthState.EmailVerificationRequired(EMAIL), repo.session.value)
        assertEquals(1, transport.count("POST /auth/resend-verification"))
    }

    @Test fun rememberMeOffKeepsNothingOnDisk() = runTest {
        val (repo, _, store) = create { ok(sessionJson(onboarded = true)) }
        assertTrue(repo.logIn(EMAIL, "correct-horse-9", rememberMe = false) is AuthResult.Success)
        assertTrue(repo.session.value is AuthState.Authenticated)
        assertNull(store.get(REFRESH_KEY))
        assertNull(store.get(USER_KEY))
    }

    @Test fun rememberMeOnStoresOnlyTheRefreshTokenAndProfile() = runTest {
        val (repo, _, store) = create { ok(sessionJson(onboarded = true)) }
        repo.logIn(EMAIL, "correct-horse-9", rememberMe = true)
        assertEquals("r1", store.get(REFRESH_KEY))
        assertFalse(store.get(USER_KEY)!!.contains("correct-horse-9"))
    }

    @Test fun networkFailureIsReportedAsOffline() = runTest {
        val (repo) = create { throw IOException("no route") }
        assertEquals(AuthError.Network, failureOf(repo.logIn(EMAIL, "x", true)))
    }

    @Test fun serverErrorsMeanUnavailable() = runTest {
        val (repo) = create { HttpResponse(503, "<html>asleep</html>") }
        assertEquals(AuthError.ServiceUnavailable, failureOf(repo.logIn(EMAIL, "x", true)))
    }

    @Test fun retryAfterHeaderIsUsedWhenTheBodyHasNone() = runTest {
        val (repo) = create { HttpResponse(429, "", retryAfterSeconds = 90) }
        assertEquals(AuthError.RateLimited(90), failureOf(repo.logIn(EMAIL, "x", true)))
    }

    // ---- restoring a session
    @Test fun restoreRefreshesAndLoadsTheProfile() = runTest {
        val store = InMemorySecureStore().apply { put(REFRESH_KEY, "r0") }
        val (repo, transport) = create(store) { request ->
            when {
                request.url.endsWith("/auth/refresh") -> ok("""{"accessToken":"a2","refreshToken":"r2","expiresIn":900}""")
                request.url.endsWith("/me") -> ok(userJson(onboarded = true))
                else -> errorResponse(404, "not_found")
            }
        }
        repo.restoreSession()
        assertTrue(repo.session.value is AuthState.Authenticated)
        assertEquals("r2", store.get(REFRESH_KEY)) // the rotated token replaced the old one
        assertEquals("Bearer a2", transport.requests.last().headers["Authorization"])
    }

    @Test fun restoreWithNothingSavedIsSignedOut() = runTest {
        val (repo, transport) = create { ok("{}") }
        repo.restoreSession()
        assertEquals(AuthState.SignedOut(), repo.session.value)
        assertTrue(transport.requests.isEmpty())
    }

    @Test fun restoreWithADeadRefreshTokenSignsOutAndForgetsIt() = runTest {
        val store = InMemorySecureStore().apply { put(REFRESH_KEY, "dead"); put(USER_KEY, userJson()) }
        val (repo) = create(store) { errorResponse(401, "unauthorized") }
        repo.restoreSession()
        assertEquals(AuthState.SignedOut(SignedOutReason.SessionExpired), repo.session.value)
        assertNull(store.get(REFRESH_KEY))
        assertNull(store.get(USER_KEY))
    }

    @Test fun restoreOfflineFallsBackToTheSavedProfile() = runTest {
        val store = InMemorySecureStore().apply { put(REFRESH_KEY, "r0"); put(USER_KEY, userJson(onboarded = true)) }
        val (repo) = create(store) { throw IOException("offline") }
        repo.restoreSession()
        assertTrue(repo.session.value is AuthState.Authenticated)
        assertEquals("r0", store.get(REFRESH_KEY)) // kept for next time
    }

    @Test fun restoreOfflineWithNoSavedProfileIsSignedOut() = runTest {
        val store = InMemorySecureStore().apply { put(REFRESH_KEY, "r0") }
        val (repo) = create(store) { throw IOException("offline") }
        repo.restoreSession()
        assertEquals(AuthState.SignedOut(), repo.session.value)
    }

    // ---- signed-in requests
    @Test fun aRejectedAccessTokenIsRefreshedOnceThenRetried() = runTest {
        var profileCalls = 0
        val (repo, transport) = create { request ->
            when {
                request.url.endsWith("/auth/login") -> ok(sessionJson(onboarded = false))
                request.url.endsWith("/auth/refresh") -> ok("""{"accessToken":"a2","refreshToken":"r2","expiresIn":900}""")
                request.url.endsWith("/me/profile") -> {
                    profileCalls++
                    if (request.headers["Authorization"] == "Bearer a1") errorResponse(401, "unauthorized") else ok(userJson(onboarded = true))
                }
                else -> errorResponse(404, "not_found")
            }
        }
        repo.signedIn(onboarded = false)
        val result = repo.completeOnboarding(OnboardingProfile("CS", listOf("Music"), "Hi", false))
        assertTrue(result is AuthResult.Success)
        assertTrue(repo.session.value is AuthState.Authenticated)
        assertEquals(2, profileCalls)
        assertEquals(1, transport.count("POST /auth/refresh"))
    }

    @Test fun aSecondRejectionSignsTheStudentOut() = runTest {
        val (repo, _, store) = create { request ->
            when {
                request.url.endsWith("/auth/login") -> ok(sessionJson())
                request.url.endsWith("/auth/refresh") -> ok("""{"accessToken":"a2","refreshToken":"r2","expiresIn":900}""")
                else -> errorResponse(401, "unauthorized")
            }
        }
        repo.signedIn(onboarded = false)
        assertEquals(AuthError.SessionExpired, failureOf(repo.completeOnboarding(OnboardingProfile("CS", emptyList(), "", false))))
        assertEquals(AuthState.SignedOut(SignedOutReason.SessionExpired), repo.session.value)
        assertNull(store.get(REFRESH_KEY))
    }

    @Test fun anExpiredAccessTokenIsRefreshedBeforeTheCall() = runTest {
        val clock = TestClock()
        val (repo, transport) = create(clock = clock) { request ->
            when {
                request.url.endsWith("/auth/login") -> ok(sessionJson(onboarded = true))
                request.url.endsWith("/auth/refresh") -> ok("""{"accessToken":"a2","refreshToken":"r2","expiresIn":900}""")
                request.url.endsWith("/auth/status") -> ok("""{"emailVerified":true,"studentVerified":true}""")
                else -> errorResponse(404, "not_found")
            }
        }
        repo.signedIn()
        clock.now += 16 * 60 * 1000L // past the 15-minute lifetime
        assertEquals(true, (repo.refreshVerificationStatus() as AuthResult.Success).value)
        assertEquals(1, transport.count("POST /auth/refresh"))
        assertEquals("Bearer a2", transport.requests.last().headers["Authorization"])
    }

    @Test fun profileAnswerWithBadFieldsShowsTheServersMessage() = runTest {
        val (repo) = create { request ->
            if (request.url.endsWith("/auth/login")) ok(sessionJson()) else errorResponse(400, "validation", "Too many interests.")
        }
        repo.signedIn(onboarded = false)
        assertEquals(AuthError.Validation("Too many interests."), failureOf(repo.completeOnboarding(OnboardingProfile("CS", listOf("a"), "", false))))
    }

    // ---- profile edits
    @Test fun profileEditsAreSentAndUpdateTheSession() = runTest {
        var sent: JSONObject? = null
        val (repo) = create { request ->
            when {
                request.url.endsWith("/auth/login") -> ok(sessionJson(onboarded = true))
                request.url.endsWith("/me/profile") -> {
                    sent = JSONObject(request.body!!)
                    ok(userJson(onboarded = true).replace("Test Student", "New Name"))
                }
                else -> errorResponse(404, "not_found")
            }
        }
        repo.signedIn()
        val result = repo.updateProfile(ProfileEdit("  New Name ", "Law", "2nd Year", listOf("Music", "Music", " "), "Hi"))
        assertTrue(result is AuthResult.Success)
        assertEquals("New Name", (repo.session.value as AuthState.Authenticated).user.fullName)
        assertEquals("New Name", sent!!.getString("fullName"))
        assertEquals("2nd Year", sent!!.getString("yearOfStudy"))
        assertEquals(1, sent!!.getJSONArray("interests").length())
    }

    @Test fun badProfileEditsNeverReachTheServer() = runTest {
        val (repo, transport) = create { ok(sessionJson(onboarded = true)) }
        repo.signedIn()
        val before = transport.requests.size
        assertTrue(failureOf(repo.updateProfile(ProfileEdit("A", "Law", "", emptyList(), ""))) is AuthError.Validation)
        assertEquals(before, transport.requests.size)
    }

    // ---- account actions
    @Test fun changingThePasswordQuietlySignsBackIn() = runTest {
        var logins = 0
        val (repo, transport, store) = create { request ->
            when {
                request.url.endsWith("/auth/login") -> {
                    logins++
                    ok(sessionJson(access = "a$logins", refresh = "r$logins", onboarded = true))
                }
                request.url.endsWith("/auth/change-password") -> HttpResponse(204, "")
                else -> errorResponse(404, "not_found")
            }
        }
        repo.signedIn()
        assertTrue(repo.changePassword("correct-horse-9", "brand-new-pass-1") is AuthResult.Success)
        assertTrue(repo.session.value is AuthState.Authenticated)
        assertEquals("r2", store.get(REFRESH_KEY))
        assertEquals("brand-new-pass-1", JSONObject(transport.requests.last().body!!).getString("password"))
    }

    @Test fun wrongCurrentPasswordIsNotASessionProblem() = runTest {
        val (repo) = create { request ->
            if (request.url.endsWith("/auth/login")) ok(sessionJson(onboarded = true)) else errorResponse(401, "invalid_credentials")
        }
        repo.signedIn()
        assertEquals(AuthError.InvalidCredentials, failureOf(repo.changePassword("wrong-password", "brand-new-pass-1")))
        assertTrue(repo.session.value is AuthState.Authenticated)
    }

    @Test fun deletingTheAccountSignsOutAndForgets() = runTest {
        val (repo, _, store) = create { request ->
            if (request.url.endsWith("/auth/login")) ok(sessionJson(onboarded = true)) else HttpResponse(204, "")
        }
        repo.signedIn()
        assertTrue(repo.deleteAccount("correct-horse-9") is AuthResult.Success)
        assertEquals(AuthState.SignedOut(), repo.session.value)
        assertNull(store.get(REFRESH_KEY))
    }

    @Test fun logOutTellsTheServerAndClearsEverything() = runTest {
        val (repo, transport, store) = create { request ->
            if (request.url.endsWith("/auth/login")) ok(sessionJson(onboarded = true)) else HttpResponse(204, "")
        }
        repo.signedIn()
        repo.logOut()
        assertEquals(AuthState.SignedOut(), repo.session.value)
        assertNull(store.get(REFRESH_KEY))
        assertNull(store.get(USER_KEY))
        assertEquals("r1", JSONObject(transport.requests.last().body!!).getString("refreshToken"))
    }

    @Test fun logOutWorksEvenWhenOffline() = runTest {
        val store = InMemorySecureStore().apply { put(REFRESH_KEY, "r0") }
        val (repo) = create(store) { throw IOException("offline") }
        repo.logOut()
        assertEquals(AuthState.SignedOut(), repo.session.value)
        assertNull(store.get(REFRESH_KEY))
    }

    @Test fun resettingThePasswordSignsOutWithAMessage() = runTest {
        val (repo) = create { HttpResponse(204, "") }
        assertTrue(repo.resetPassword(EMAIL, "123456", "brand-new-pass-1") is AuthResult.Success)
        assertEquals(AuthState.SignedOut(SignedOutReason.PasswordChanged), repo.session.value)
    }

    @Test fun resetRequestsLookTheSameWhetherOrNotTheAccountExists() = runTest {
        val (repo) = create { HttpResponse(202, """{"status":"ok"}""") }
        assertTrue(repo.requestPasswordReset(EMAIL) is AuthResult.Success)
        assertTrue(failureOf(repo.requestPasswordReset("nope")) is AuthError.Validation)
    }
}
