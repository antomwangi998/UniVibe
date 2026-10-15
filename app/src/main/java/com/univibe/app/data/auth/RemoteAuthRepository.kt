package com.univibe.app.data.auth

import com.univibe.app.data.net.HttpRequest
import com.univibe.app.data.net.HttpResponse
import com.univibe.app.data.net.AuthedApi
import com.univibe.app.data.net.HttpTransport
import com.univibe.app.domain.auth.AuthError
import com.univibe.app.domain.auth.AuthRepository
import com.univibe.app.domain.auth.AuthResult
import com.univibe.app.domain.auth.AuthState
import com.univibe.app.domain.auth.AuthUser
import com.univibe.app.domain.auth.AuthValidators
import com.univibe.app.domain.auth.OnboardingProfile
import com.univibe.app.domain.auth.ProfileEdit
import com.univibe.app.domain.auth.SignUpRequest
import com.univibe.app.domain.auth.SignedOutReason
import com.univibe.app.domain.auth.University
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * Talks to the UniVibe API (see backend/README.md). Every rule is enforced by the server; this class only
 * carries requests and turns answers into [AuthState]. Only the refresh token (and a copy of the profile for
 * offline start-up) is kept, in [SecureStore]. Passwords are never stored.
 */
class RemoteAuthRepository(
    baseUrl: String,
    private val transport: HttpTransport,
    private val store: SecureStore,
    private val clock: Clock = SystemClock,
) : AuthRepository, AuthedApi {

    private val baseUrl = baseUrl.trimEnd('/')

    init {
        require(this.baseUrl.startsWith("https://")) { "The API must be reached over HTTPS." }
    }

    private class Tokens(val access: String, val refresh: String, val accessExpiresAtMs: Long)
    private class ServerUser(val user: AuthUser, val onboardingComplete: Boolean, val raw: String)

    private val _session = MutableStateFlow<AuthState>(AuthState.LoadingSession)
    override val session: StateFlow<AuthState> = _session.asStateFlow()

    private val refreshLock = Mutex()
    private var tokens: Tokens? = null
    private var user: ServerUser? = null
    private var pendingEmail: String? = null
    /** False when the student left "remember me" off: tokens then live in memory only. */
    private var persistSession = true
    private var universityCache: List<University> = emptyList()

    // ---------------------------------------------------------------- public data
    override suspend fun universities(): List<University> {
        val response = call("GET", "/universities")
        if (response.status != 200) throw IOException("Could not load universities (${response.status})")
        val array = JSONArray(response.body)
        universityCache = (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            val domains = o.getJSONArray("emailDomains")
            University(o.getString("id"), o.getString("name"), (0 until domains.length()).map { domains.getString(it) })
        }
        return universityCache
    }

    // ---------------------------------------------------------------- session
    override suspend fun restoreSession() {
        if (store.get(REFRESH_KEY) == null) {
            _session.value = AuthState.SignedOut()
            return
        }
        persistSession = true
        val cached = store.get(USER_KEY)?.let { parseUser(it) }
        when (val me = authedCall("GET", "/me")) {
            is AuthResult.Success -> {
                val fresh = if (me.value.status == 200) parseUser(me.value.body) else null
                val chosen = fresh ?: cached
                if (fresh != null) setUser(fresh)
                _session.value = chosen?.let { stateFor(it) } ?: AuthState.SignedOut()
            }
            is AuthResult.Failure -> {
                // SessionExpired has already signed out. Anything else (offline, server asleep) falls back to the saved profile.
                if (me.error != AuthError.SessionExpired) {
                    user = cached
                    _session.value = cached?.let { stateFor(it) } ?: AuthState.SignedOut()
                }
            }
        }
    }

    override suspend fun handleSessionExpired() {
        clearSession()
        _session.value = AuthState.SignedOut(SignedOutReason.SessionExpired)
    }

    override suspend fun logOut() {
        val refresh = tokens?.refresh ?: store.get(REFRESH_KEY)
        if (refresh != null) {
            publicCall("POST", "/auth/logout", JSONObject().put("refreshToken", refresh)) // best effort
        }
        clearSession()
        _session.value = AuthState.SignedOut()
    }

    // ---------------------------------------------------------------- sign up / log in / verify
    override suspend fun signUp(request: SignUpRequest): AuthResult<Unit> {
        val email = normalize(request.email)
        val invalid = AuthValidators.fullName(request.fullName)
            ?: AuthValidators.email(email)
            ?: AuthValidators.admissionNumber(request.admissionNumber.orEmpty())
            ?: AuthValidators.password(request.password)
        if (invalid != null) return fail(AuthError.Validation(invalid))
        if (!request.acceptedTerms) return fail(AuthError.Validation("Accept the Terms and Privacy Policy to continue."))

        val body = JSONObject()
            .put("fullName", request.fullName.trim())
            .put("email", email)
            .put("universityId", request.universityId)
            .put("password", request.password)
            .put("acceptedTerms", true)
        request.admissionNumber?.trim()?.takeIf { it.isNotEmpty() }?.let { body.put("admissionNumber", it) }

        val response = when (val r = publicCall("POST", "/auth/register", body)) {
            is AuthResult.Failure -> return r
            is AuthResult.Success -> r.value
        }
        if (response.status != 202 && response.status != 200) return fail(mapError(response))
        pendingEmail = email
        _session.value = AuthState.EmailVerificationRequired(email)
        return AuthResult.Success(Unit)
    }

    override suspend fun logIn(email: String, password: String, rememberMe: Boolean): AuthResult<Unit> {
        val key = normalize(email)
        val response = when (val r = publicCall("POST", "/auth/login", JSONObject().put("email", key).put("password", password))) {
            is AuthResult.Failure -> return r
            is AuthResult.Success -> r.value
        }
        if (response.status == 200) {
            val parsed = parseSession(response.body) ?: return fail(AuthError.Unknown)
            persistSession = rememberMe
            adopt(parsed.first)
            setUser(parsed.second)
            _session.value = stateFor(parsed.second)
            return AuthResult.Success(Unit)
        }
        val error = mapError(response)
        if (error == AuthError.EmailNotVerified) {
            pendingEmail = key
            publicCall("POST", "/auth/resend-verification", JSONObject().put("email", key)) // best effort: make sure a code exists
            _session.value = AuthState.EmailVerificationRequired(key)
        }
        return fail(error)
    }

    override suspend fun verifyEmail(code: String): AuthResult<Unit> {
        val email = pendingEmail ?: return fail(AuthError.SessionExpired)
        val response = when (val r = publicCall("POST", "/auth/verify-email", JSONObject().put("email", email).put("code", code.trim()))) {
            is AuthResult.Failure -> return r
            is AuthResult.Success -> r.value
        }
        if (response.status != 200) return fail(mapError(response))
        val parsed = parseSession(response.body) ?: return fail(AuthError.Unknown)
        persistSession = true // they just proved they own this email on their own device
        adopt(parsed.first)
        setUser(parsed.second)
        pendingEmail = null
        _session.value = stateFor(parsed.second)
        return AuthResult.Success(Unit)
    }

    override suspend fun resendVerification(): AuthResult<Int> {
        val email = pendingEmail ?: return fail(AuthError.SessionExpired)
        val response = when (val r = publicCall("POST", "/auth/resend-verification", JSONObject().put("email", email))) {
            is AuthResult.Failure -> return r
            is AuthResult.Success -> r.value
        }
        if (response.status != 202 && response.status != 200) return fail(mapError(response))
        val cooldown = runCatching { JSONObject(response.body).getInt("resendCooldownSeconds") }.getOrDefault(60)
        return AuthResult.Success(cooldown)
    }

    /** Codes are the only way to verify, so there is nothing to poll: the answer is "not yet" until verifyEmail succeeds. */
    override suspend fun refreshVerificationStatus(): AuthResult<Boolean> {
        if (tokens == null) return AuthResult.Success(false)
        val response = when (val r = authedCall("GET", "/auth/status")) {
            is AuthResult.Failure -> return r
            is AuthResult.Success -> r.value
        }
        if (response.status != 200) return fail(mapError(response))
        return AuthResult.Success(runCatching { JSONObject(response.body).getBoolean("emailVerified") }.getOrDefault(false))
    }

    // ---------------------------------------------------------------- password reset
    override suspend fun requestPasswordReset(email: String): AuthResult<Unit> {
        val key = normalize(email)
        AuthValidators.email(key)?.let { return fail(AuthError.Validation(it)) }
        val response = when (val r = publicCall("POST", "/auth/password-reset/request", JSONObject().put("email", key))) {
            is AuthResult.Failure -> return r
            is AuthResult.Success -> r.value
        }
        return if (response.status == 202 || response.status == 200) AuthResult.Success(Unit) else fail(mapError(response))
    }

    override suspend fun resetPassword(email: String, code: String, newPassword: String): AuthResult<Unit> {
        val key = normalize(email)
        AuthValidators.password(newPassword)?.let { return fail(AuthError.Validation(it)) }
        val body = JSONObject().put("email", key).put("code", code.trim()).put("newPassword", newPassword)
        val response = when (val r = publicCall("POST", "/auth/password-reset/confirm", body)) {
            is AuthResult.Failure -> return r
            is AuthResult.Success -> r.value
        }
        if (response.status != 204 && response.status != 200) return fail(mapError(response))
        clearSession() // the server ended every session
        _session.value = AuthState.SignedOut(SignedOutReason.PasswordChanged)
        return AuthResult.Success(Unit)
    }

    // ---------------------------------------------------------------- account
    override suspend fun completeOnboarding(profile: OnboardingProfile): AuthResult<Unit> {
        if (profile.bio.length > 160) return fail(AuthError.Validation("Keep your bio under 160 characters."))
        val body = JSONObject()
            .put("course", profile.course.trim())
            .put("interests", JSONArray(profile.interests.map { it.trim().take(30) }.filter { it.isNotEmpty() }.distinct().take(10)))
            .put("bio", profile.bio.trim())
        val response = when (val r = authedCall("PUT", "/me/profile", body)) {
            is AuthResult.Failure -> return r
            is AuthResult.Success -> r.value
        }
        if (response.status != 200) return fail(mapError(response))
        val updated = parseUser(response.body) ?: return fail(AuthError.Unknown)
        setUser(updated)
        _session.value = stateFor(updated)
        return AuthResult.Success(Unit)
    }

    override suspend fun updateProfile(edit: ProfileEdit): AuthResult<Unit> {
        AuthValidators.fullName(edit.fullName)?.let { return fail(AuthError.Validation(it)) }
        if (edit.bio.length > 300) return fail(AuthError.Validation("Keep your bio under 300 characters."))
        val body = JSONObject()
            .put("fullName", edit.fullName.trim())
            .put("yearOfStudy", edit.yearOfStudy.trim())
            .put("course", edit.course.trim())
            .put("interests", JSONArray(edit.interests.map { it.trim().take(30) }.filter { it.isNotEmpty() }.distinct().take(10)))
            .put("bio", edit.bio.trim())
        val response = when (val r = authedCall("PUT", "/me/profile", body)) {
            is AuthResult.Failure -> return r
            is AuthResult.Success -> r.value
        }
        if (response.status != 200) return fail(mapError(response))
        val updated = parseUser(response.body) ?: return fail(AuthError.Unknown)
        setUser(updated)
        _session.value = stateFor(updated)
        return AuthResult.Success(Unit)
    }

    override suspend fun changePassword(currentPassword: String, newPassword: String): AuthResult<Unit> {
        AuthValidators.password(newPassword)?.let { return fail(AuthError.Validation(it)) }
        if (currentPassword == newPassword) return fail(AuthError.Validation("Choose a password you haven't used here."))
        val email = user?.user?.email ?: return fail(AuthError.SessionExpired)
        val body = JSONObject().put("currentPassword", currentPassword).put("newPassword", newPassword)
        val response = when (val r = authedCall("POST", "/auth/change-password", body)) {
            is AuthResult.Failure -> return r
            is AuthResult.Success -> r.value
        }
        if (response.status != 204 && response.status != 200) return fail(mapError(response))

        // The server ended all sessions, this device's included. Sign back in quietly with the new password.
        val relogin = publicCall("POST", "/auth/login", JSONObject().put("email", email).put("password", newPassword))
        val parsed = (relogin as? AuthResult.Success)?.value?.takeIf { it.status == 200 }?.let { parseSession(it.body) }
        if (parsed != null) {
            adopt(parsed.first)
            setUser(parsed.second)
        } else {
            clearSession()
            _session.value = AuthState.SignedOut(SignedOutReason.PasswordChanged)
        }
        return AuthResult.Success(Unit)
    }

    override suspend fun deleteAccount(password: String): AuthResult<Unit> {
        val response = when (val r = authedCall("POST", "/me/delete-account", JSONObject().put("password", password))) {
            is AuthResult.Failure -> return r
            is AuthResult.Success -> r.value
        }
        if (response.status != 204 && response.status != 200) return fail(mapError(response))
        clearSession()
        _session.value = AuthState.SignedOut()
        return AuthResult.Success(Unit)
    }

    // ---------------------------------------------------------------- tokens
    private fun adopt(newTokens: Tokens) {
        tokens = newTokens
        if (persistSession) store.put(REFRESH_KEY, newTokens.refresh) else store.remove(REFRESH_KEY)
    }

    private fun setUser(newUser: ServerUser) {
        user = newUser
        if (persistSession) store.put(USER_KEY, newUser.raw) else store.remove(USER_KEY)
    }

    private fun clearSession() {
        tokens = null
        user = null
        store.remove(REFRESH_KEY)
        store.remove(USER_KEY)
    }

    /**
     * Returns a usable access token, refreshing when it is missing, near expiry, or was just rejected ([stale]).
     * One lock for everyone: refresh tokens rotate, so two parallel refreshes would look like theft and end the login.
     */
    private suspend fun accessToken(stale: String? = null): AuthResult<String> = refreshLock.withLock {
        val current = tokens
        if (current != null && current.access != stale && clock.nowMs() < current.accessExpiresAtMs - EXPIRY_MARGIN_MS) {
            return@withLock AuthResult.Success(current.access)
        }
        val refreshToken = current?.refresh ?: store.get(REFRESH_KEY) ?: return@withLock AuthResult.Failure(AuthError.SessionExpired)
        val response = try {
            call("POST", "/auth/refresh", JSONObject().put("refreshToken", refreshToken))
        } catch (e: IOException) {
            return@withLock AuthResult.Failure(AuthError.Network)
        }
        when {
            response.status == 200 -> {
                val parsed = runCatching { parseTokens(JSONObject(response.body)) }.getOrNull()
                    ?: return@withLock AuthResult.Failure(AuthError.Unknown)
                adopt(parsed)
                AuthResult.Success(parsed.access)
            }
            response.status == 401 -> AuthResult.Failure(AuthError.SessionExpired)
            else -> AuthResult.Failure(mapError(response))
        }
    }

    /** A request that needs a signed-in student. Refreshes once if the server rejects the access token. */
    override suspend fun authedCall(method: String, path: String, body: JSONObject?): AuthResult<HttpResponse> {
        var token = when (val t = accessToken()) {
            is AuthResult.Success -> t.value
            is AuthResult.Failure -> return sessionFailure(t.error)
        }
        repeat(2) { attempt ->
            val response = try {
                call(method, path, body, token)
            } catch (e: IOException) {
                return fail(AuthError.Network)
            }
            val rejected = response.status == 401 && errorCode(response) == "unauthorized"
            if (!rejected) return AuthResult.Success(response)
            if (attempt == 1) return sessionFailure(AuthError.SessionExpired)
            token = when (val t = accessToken(stale = token)) {
                is AuthResult.Success -> t.value
                is AuthResult.Failure -> return sessionFailure(t.error)
            }
        }
        return fail(AuthError.Unknown)
    }

    private suspend fun sessionFailure(error: AuthError): AuthResult.Failure {
        if (error == AuthError.SessionExpired) handleSessionExpired()
        return AuthResult.Failure(error)
    }

    // ---------------------------------------------------------------- http and parsing
    private suspend fun call(method: String, path: String, body: JSONObject? = null, token: String? = null): HttpResponse {
        val headers = buildMap {
            put("Accept", "application/json")
            if (body != null) put("Content-Type", "application/json")
            if (token != null) put("Authorization", "Bearer $token")
        }
        return transport.execute(HttpRequest(method, baseUrl + path, headers, body?.toString()))
    }

    private suspend fun publicCall(method: String, path: String, body: JSONObject? = null): AuthResult<HttpResponse> =
        try {
            AuthResult.Success(call(method, path, body))
        } catch (e: IOException) {
            fail(AuthError.Network)
        }

    private fun errorCode(response: HttpResponse): String? =
        runCatching { JSONObject(response.body).optString("error").ifEmpty { null } }.getOrNull()

    private fun mapError(response: HttpResponse): AuthError {
        val json = runCatching { JSONObject(response.body) }.getOrNull()
        val code = json?.optString("error")?.ifEmpty { null }
        val message = json?.optString("message")?.ifEmpty { null }
        val retry = json?.optInt("retryAfter", 0)?.takeIf { it > 0 } ?: response.retryAfterSeconds ?: 60
        return when {
            response.status == 429 || code == "rate_limited" -> AuthError.RateLimited(retry)
            code == "invalid_credentials" -> AuthError.InvalidCredentials
            code == "email_not_verified" -> AuthError.EmailNotVerified
            code == "invalid_code" -> AuthError.InvalidCode
            code == "expired_code" -> AuthError.ExpiredCode
            code == "validation" -> AuthError.Validation(message ?: "Check your details and try again.")
            code == "unauthorized" || response.status == 401 -> AuthError.SessionExpired
            response.status >= 500 -> AuthError.ServiceUnavailable
            else -> AuthError.Unknown
        }
    }

    private fun parseTokens(o: JSONObject) = Tokens(
        access = o.getString("accessToken"),
        refresh = o.getString("refreshToken"),
        accessExpiresAtMs = clock.nowMs() + o.optLong("expiresIn", 900L) * 1000L,
    )

    private fun parseSession(body: String): Pair<Tokens, ServerUser>? = runCatching {
        val o = JSONObject(body)
        val parsedUser = parseUser(o.getJSONObject("user").toString()) ?: return null
        parseTokens(o) to parsedUser
    }.getOrNull()

    private fun parseUser(json: String): ServerUser? = runCatching {
        val o = JSONObject(json)
        val interests = o.optJSONArray("interests")
        ServerUser(
            user = AuthUser(
                id = o.getString("id"),
                fullName = o.getString("fullName"),
                email = o.getString("email"),
                universityId = o.getString("universityId"),
                universityName = o.getString("universityName"),
                emailVerified = o.getBoolean("emailVerified"),
                studentVerified = o.getBoolean("studentVerified"),
                course = o.optString("course"),
                interests = if (interests == null) emptyList() else (0 until interests.length()).map { interests.getString(it) },
                bio = o.optString("bio"),
                hasPhoto = o.optBoolean("hasPhoto", false),
                handle = o.optString("handle"),
                yearOfStudy = o.optString("yearOfStudy"),
            ),
            onboardingComplete = o.optBoolean("onboardingComplete", false),
            raw = json,
        )
    }.getOrNull()

    private fun stateFor(u: ServerUser): AuthState = when {
        !u.user.emailVerified -> AuthState.EmailVerificationRequired(u.user.email)
        !u.onboardingComplete -> AuthState.OnboardingRequired(u.user)
        else -> AuthState.Authenticated(u.user)
    }

    private fun normalize(email: String) = email.trim().lowercase()
    private fun fail(error: AuthError) = AuthResult.Failure(error)

    private companion object {
        const val REFRESH_KEY = "remote_refresh_token"
        const val USER_KEY = "remote_user"
        const val EXPIRY_MARGIN_MS = 30_000L
    }
}
