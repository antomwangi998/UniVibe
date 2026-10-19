package com.univibe.app.auth

import com.univibe.app.data.auth.Clock
import com.univibe.app.data.auth.InMemorySecureStore
import com.univibe.app.data.auth.RemoteAuthRepository
import com.univibe.app.data.net.HttpRequest
import com.univibe.app.data.net.HttpResponse
import com.univibe.app.data.net.HttpTransport
import com.univibe.app.domain.auth.AuthError
import com.univibe.app.domain.auth.AuthResult
import com.univibe.app.domain.auth.AuthState
import com.univibe.app.domain.auth.GoogleRegistrationRequest
import com.univibe.app.domain.auth.SignedOutReason
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

private const val BASE = "https://api.example.test"
private const val EMAIL = "grace@gmail.example"
private const val REFRESH_KEY = "remote_refresh_token"

private class FixedClock : Clock { override fun nowMs() = 1_000_000L }

private class Recorder(private val handler: (HttpRequest) -> HttpResponse) : HttpTransport {
    val requests = mutableListOf<HttpRequest>()
    override suspend fun execute(request: HttpRequest): HttpResponse { requests += request; return handler(request) }
    fun route(r: HttpRequest) = "${r.method} ${r.url.removePrefix(BASE)}"
    fun routes() = requests.map(::route)
    fun bodyOf(route: String) = JSONObject(requests.last { route(it) == route }.body ?: "{}")
}

private fun ok(body: String) = HttpResponse(200, body)
private fun err(status: Int, code: String) = HttpResponse(status, JSONObject().put("error", code).put("message", "m").toString())

private fun userJson(onboarded: Boolean = true) = JSONObject()
    .put("id", "u1").put("fullName", "Grace Hopper").put("email", EMAIL)
    .put("universityId", "uni").put("universityName", "Uni").put("emailVerified", true).put("studentVerified", false)
    .put("handle", "grace").put("yearOfStudy", "").put("course", "").put("interests", org.json.JSONArray())
    .put("bio", "").put("hasPhoto", false).put("onboardingComplete", onboarded).toString()

private fun authenticated(onboarded: Boolean = true) =
    """{"status":"authenticated","accessToken":"a1","refreshToken":"r1","expiresIn":900,"user":${userJson(onboarded)}}"""
private const val REGISTRATION = """{"status":"registration_required","registrationToken":"reg-token","prefill":{"fullName":"Grace Hopper","email":"grace@gmail.example"}}"""
private const val LINK = """{"status":"link_required","linkToken":"link-token","email":"grace@gmail.example"}"""

class GoogleAuthRepositoryTest {
    private fun repo(enabled: Boolean = true, store: InMemorySecureStore = InMemorySecureStore(), handler: (HttpRequest) -> HttpResponse): Pair<RemoteAuthRepository, Recorder> {
        val transport = Recorder(handler)
        return RemoteAuthRepository(BASE, transport, store, FixedClock(), googleEnabled = enabled) to transport
    }
    private fun failure(r: AuthResult<*>) = (r as AuthResult.Failure).error
    private val registration = GoogleRegistrationRequest("Grace Hopper", "uni", null, acceptedTerms = true, termsVersion = "0.1")

    @Test fun existingAccountSignsInWithNormalSession() = runTest {
        val store = InMemorySecureStore()
        val (r, t) = repo(store = store) { ok(authenticated()) }
        assertTrue(r.signInWithGoogle("id-token", "nonce-1234567890abcd") is AuthResult.Success)
        assertTrue(r.session.value is AuthState.Authenticated)
        assertEquals("r1", store.get(REFRESH_KEY)) // the normal refresh token is kept, like an email/password login
        val sent = t.bodyOf("POST /auth/google")
        assertTrue(sent.keys().asSequence().all { it in setOf("idToken", "nonce", "deviceName") })
        assertFalse("the app never sends an email or name to be trusted", sent.has("email") || sent.has("fullName") || sent.has("userId"))
    }

    @Test fun newPersonMovesToRegistrationWithoutAnySession() = runTest {
        val store = InMemorySecureStore()
        val (r, _) = repo(store = store) { ok(REGISTRATION) }
        r.signInWithGoogle("id-token", "nonce-1234567890abcd")
        val s = r.session.value as AuthState.GoogleRegistrationRequired
        assertEquals("Grace Hopper", s.prefill.fullName)
        assertEquals(EMAIL, s.prefill.email)
        assertNull(store.get(REFRESH_KEY))
    }

    @Test fun registrationSendsOnlyTheSignedTokenAndChoices() = runTest {
        val (r, t) = repo { req -> if (req.url.endsWith("/auth/google")) ok(REGISTRATION) else ok(authenticated(onboarded = false)) }
        r.signInWithGoogle("id-token", "nonce-1234567890abcd")
        assertTrue(r.completeGoogleRegistration(registration) is AuthResult.Success)
        val body = t.bodyOf("POST /auth/google/register")
        assertEquals("reg-token", body.getString("registrationToken"))
        assertEquals("uni", body.getString("universityId"))
        assertTrue(body.getBoolean("acceptedTerms"))
        assertFalse(body.has("email"))
        assertTrue(r.session.value is AuthState.OnboardingRequired) // new people continue into the normal onboarding
    }

    @Test fun registrationWithoutTermsIsRefusedLocally() = runTest {
        val (r, t) = repo { ok(REGISTRATION) }
        r.signInWithGoogle("id-token", "nonce-1234567890abcd")
        val result = r.completeGoogleRegistration(registration.copy(acceptedTerms = false))
        assertTrue(failure(result) is AuthError.Validation)
        assertFalse(t.routes().contains("POST /auth/google/register"))
    }

    @Test fun registrationStepOutsideTheFlowIsRejected() = runTest {
        val (r, t) = repo { ok(authenticated()) }
        assertEquals(AuthError.SessionExpired, failure(r.completeGoogleRegistration(registration)))
        assertEquals(AuthError.SessionExpired, failure(r.linkGoogleAccount("pw")))
        assertTrue(t.requests.isEmpty())
    }

    @Test fun existingEmailPasswordAccountMustProveOwnershipToLink() = runTest {
        var linkAttempts = 0
        val (r, t) = repo { req ->
            when {
                req.url.endsWith("/auth/google") -> ok(LINK)
                else -> { linkAttempts++; if (linkAttempts == 1) err(401, "invalid_credentials") else ok(authenticated()) }
            }
        }
        r.signInWithGoogle("id-token", "nonce-1234567890abcd")
        assertEquals(AuthState.GoogleLinkRequired(EMAIL), r.session.value)
        assertEquals(AuthError.InvalidCredentials, failure(r.linkGoogleAccount("wrong")))
        assertEquals(AuthState.GoogleLinkRequired(EMAIL), r.session.value) // still waiting, not signed in
        assertTrue(r.linkGoogleAccount("right-password-1") is AuthResult.Success)
        assertTrue(r.session.value is AuthState.Authenticated)
        assertEquals("link-token", t.bodyOf("POST /auth/google/link").getString("linkToken"))
    }

    @Test fun invalidOrExpiredGoogleTokenIsAGenericFailureAndNeverSignsIn() = runTest {
        val (r, _) = repo { err(401, "invalid_google_token") }
        assertEquals(AuthError.InvalidGoogleToken, failure(r.signInWithGoogle("bad", "nonce-1234567890abcd")))
        assertFalse(r.session.value is AuthState.Authenticated)
    }

    @Test fun suspendedAccountAndUnavailableServerHaveClearErrors() = runTest {
        assertEquals(AuthError.AccountSuspended, failure(repo { err(403, "account_suspended") }.first.signInWithGoogle("t", "nonce-1234567890abcd")))
        assertEquals(AuthError.GoogleUnavailable, failure(repo { err(503, "google_unavailable") }.first.signInWithGoogle("t", "nonce-1234567890abcd")))
    }

    @Test fun offlineIsReportedAsNetwork() = runTest {
        val (r, _) = repo { throw IOException("no route") }
        assertEquals(AuthError.Network, failure(r.signInWithGoogle("t", "nonce-1234567890abcd")))
        assertEquals(AuthError.Network, failure(r.googleNonce()))
    }

    @Test fun expiredRegistrationStepReturnsToTheStart() = runTest {
        val (r, _) = repo { req -> if (req.url.endsWith("/auth/google")) ok(REGISTRATION) else err(401, "invalid_google_token") }
        r.signInWithGoogle("id-token", "nonce-1234567890abcd")
        assertEquals(AuthError.InvalidGoogleToken, failure(r.completeGoogleRegistration(registration)))
        assertEquals(AuthState.SignedOut(SignedOutReason.SessionExpired), r.session.value)
    }

    @Test fun duplicateAccountAnswerReturnsToSignedOut() = runTest {
        val (r, _) = repo { req -> if (req.url.endsWith("/auth/google")) ok(REGISTRATION) else err(409, "account_exists") }
        r.signInWithGoogle("id-token", "nonce-1234567890abcd")
        assertEquals(AuthError.AccountExists, failure(r.completeGoogleRegistration(registration)))
        assertEquals(AuthState.SignedOut(), r.session.value)
    }

    @Test fun cancellingTheFlowForgetsTheTokens() = runTest {
        val (r, t) = repo { ok(REGISTRATION) }
        r.signInWithGoogle("id-token", "nonce-1234567890abcd")
        r.cancelGoogleFlow()
        assertEquals(AuthState.SignedOut(), r.session.value)
        assertEquals(AuthError.SessionExpired, failure(r.completeGoogleRegistration(registration)))
        assertFalse(t.routes().contains("POST /auth/google/register"))
    }

    @Test fun nonceIsFetchedFromTheServer() = runTest {
        val (r, _) = repo { ok("""{"nonce":"abcdefghijklmnopqrstuvwx","expiresIn":300}""") }
        assertEquals("abcdefghijklmnopqrstuvwx", (r.googleNonce() as AuthResult.Success).value)
    }

    @Test fun googleIsOffWithoutAServerClientId() = runTest {
        val (r, t) = repo(enabled = false) { ok(authenticated()) }
        assertFalse(r.googleSignInAvailable)
        assertEquals(AuthError.GoogleUnavailable, failure(r.googleNonce()))
        assertEquals(AuthError.GoogleUnavailable, failure(r.signInWithGoogle("t", "nonce-1234567890abcd")))
        assertTrue(t.requests.isEmpty())
    }

    @Test fun sessionRestoresAfterRestartWithoutAnotherGoogleSignIn() = runTest {
        val store = InMemorySecureStore()
        repo(store = store) { ok(authenticated()) }.first.signInWithGoogle("id-token", "nonce-1234567890abcd")
        val (second, t) = repo(store = store) { req ->
            when {
                req.url.endsWith("/auth/refresh") -> ok("""{"accessToken":"a2","refreshToken":"r2","expiresIn":900}""")
                req.url.endsWith("/me") -> ok(userJson())
                else -> err(500, "unexpected")
            }
        }
        second.restoreSession()
        assertTrue(second.session.value is AuthState.Authenticated)
        assertFalse(t.routes().any { it.contains("/auth/google") })
    }

    @Test fun googleOnlyAccountIsToldToSetAPasswordBeforeChangingIt() = runTest {
        val (r, _) = repo { req -> if (req.url.endsWith("/auth/google")) ok(authenticated()) else err(400, "password_not_set") }
        r.signInWithGoogle("id-token", "nonce-1234567890abcd")
        assertEquals(AuthError.PasswordNotSet, failure(r.changePassword("old-password-1", "new-password-2")))
    }
}
