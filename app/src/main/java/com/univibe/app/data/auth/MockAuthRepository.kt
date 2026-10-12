package com.univibe.app.data.auth

import com.univibe.app.domain.auth.AuthError
import com.univibe.app.domain.auth.AuthRepository
import com.univibe.app.domain.auth.AuthResult
import com.univibe.app.domain.auth.AuthState
import com.univibe.app.domain.auth.AuthUser
import com.univibe.app.domain.auth.AuthValidators
import com.univibe.app.domain.auth.DevAuthHints
import com.univibe.app.domain.auth.OnboardingProfile
import com.univibe.app.domain.auth.SignUpRequest
import com.univibe.app.domain.auth.SignedOutReason
import com.univibe.app.domain.auth.University
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

fun interface Clock { fun nowMs(): Long }
object SystemClock : Clock { override fun nowMs() = System.currentTimeMillis() }

/*
 * =====================  MOCK AUTHENTICATION — DEVELOPMENT ONLY  =====================
 * There is NO server. Accounts live on this device only (password hashes in encrypted
 * storage), "emails" are never sent, and codes are shown in debug builds via DevAuthHints.
 * It exists so every auth screen and state transition can be built and tested. It does
 * NOT prove a person owns an email or belongs to a university. Replace with a
 * RemoteAuthRepository before any real launch (see docs/AUTH_BACKEND.md).
 * ====================================================================================
 */
class MockAuthRepository(
    private val store: SecureStore,
    private val clock: Clock = SystemClock,
    private val latencyMs: Long = 600,
    private val random: SecureRandom = SecureRandom(),
) : AuthRepository, DevAuthHints {

    private data class Account(
        val email: String, val fullName: String, val universityId: String, val admission: String,
        val salt: String, val hash: String, val verified: Boolean, val onboarded: Boolean,
        val studentVerified: Boolean, val course: String, val interests: List<String>,
        val bio: String, val hasPhoto: Boolean,
    )

    private class PendingCode(val code: String, val expiresAtMs: Long)

    private val _session = MutableStateFlow<AuthState>(AuthState.LoadingSession)
    override val session: StateFlow<AuthState> = _session.asStateFlow()

    private val universities = listOf(University("lakeside", "Lakeside University", listOf("lakeside.example")))
    private val accounts = mutableMapOf<String, Account>()
    private var loaded = false
    private val codes = mutableMapOf<String, PendingCode>()
    private val codeAttempts = mutableMapOf<String, Int>()
    private val lastSent = mutableMapOf<String, Long>()
    private val failedLogins = mutableMapOf<String, MutableList<Long>>()
    private var activeEmail: String? = null
    private var activeRemember = false

    // ---------------------------------------------------------------- queries
    override suspend fun universities(): List<University> { simulateLatency(); return universities }

    override fun latestVerificationCode(): String? = activeEmail?.let { codes["verify:$it"]?.code }
    override fun latestResetCode(email: String): String? = codes["reset:${normalize(email)}"]?.code

    // ---------------------------------------------------------------- session
    override suspend fun restoreSession() {
        ensureLoaded()
        val raw = store.get(SESSION_KEY)
        if (raw == null) { _session.value = AuthState.SignedOut(); return }
        val parts = raw.split(";").map { dec(it) }
        val email = parts.getOrNull(0).orEmpty()
        val expiresAt = parts.getOrNull(1)?.toLongOrNull() ?: 0L
        val account = accounts[email]
        when {
            account == null -> { store.remove(SESSION_KEY); _session.value = AuthState.SignedOut() }
            clock.nowMs() >= expiresAt -> { store.remove(SESSION_KEY); _session.value = AuthState.SignedOut(SignedOutReason.SessionExpired) }
            else -> { activeEmail = email; activeRemember = true; _session.value = stateFor(account) }
        }
    }

    override suspend fun handleSessionExpired() {
        store.remove(SESSION_KEY)
        activeEmail = null
        _session.value = AuthState.SignedOut(SignedOutReason.SessionExpired)
    }

    override suspend fun logOut() {
        store.remove(SESSION_KEY)
        activeEmail = null
        _session.value = AuthState.SignedOut()
    }

    // ---------------------------------------------------------------- sign up / log in
    override suspend fun signUp(request: SignUpRequest): AuthResult<Unit> {
        ensureLoaded(); simulateLatency()
        val email = normalize(request.email)
        // Server-side rules are repeated here on purpose.
        val invalid = AuthValidators.fullName(request.fullName)
            ?: AuthValidators.email(email)
            ?: AuthValidators.admissionNumber(request.admissionNumber.orEmpty())
            ?: AuthValidators.password(request.password)
        if (invalid != null) return fail(AuthError.Validation(invalid))
        if (!request.acceptedTerms) return fail(AuthError.Validation("Accept the Terms and Privacy Policy to continue."))
        if (universities.none { it.id == request.universityId }) return fail(AuthError.Validation("Choose your university."))
        if (accounts.containsKey(email)) return fail(AuthError.SignUpRejected) // generic on purpose

        val salt = ByteArray(16).also { random.nextBytes(it) }
        val hash = hashPassword(request.password, salt)
        accounts[email] = Account(
            email = email, fullName = request.fullName.trim(), universityId = request.universityId,
            admission = request.admissionNumber.orEmpty().trim(), salt = enc64(salt), hash = enc64(hash),
            verified = false, onboarded = false, studentVerified = false,
            course = "", interests = emptyList(), bio = "", hasPhoto = false,
        )
        persistAccounts()
        activeEmail = email
        activeRemember = false
        issueCode("verify:$email")
        _session.value = AuthState.EmailVerificationRequired(email)
        return AuthResult.Success(Unit)
    }

    override suspend fun logIn(email: String, password: String, rememberMe: Boolean): AuthResult<Unit> {
        ensureLoaded(); simulateLatency()
        val key = normalize(email)
        retryAfter(failedLogins[key], LOGIN_WINDOW_MS, MAX_LOGIN_FAILURES)?.let { return fail(AuthError.RateLimited(it)) }

        val account = accounts[key]
        // Always hash, so unknown accounts cost the same time as wrong passwords.
        val saltBytes = account?.let { dec64(it.salt) } ?: ByteArray(16)
        val candidate = hashPassword(password, saltBytes)
        val ok = account != null && MessageDigest.isEqual(candidate, dec64(account.hash))
        if (!ok) {
            failedLogins.getOrPut(key) { mutableListOf() }.add(clock.nowMs())
            return fail(AuthError.InvalidCredentials)
        }
        failedLogins.remove(key)
        activeEmail = key
        activeRemember = rememberMe
        if (!account!!.verified) {
            issueCode("verify:$key")
            _session.value = AuthState.EmailVerificationRequired(key)
            return fail(AuthError.EmailNotVerified)
        }
        startSession(account)
        return AuthResult.Success(Unit)
    }

    // ---------------------------------------------------------------- email verification
    override suspend fun verifyEmail(code: String): AuthResult<Unit> {
        ensureLoaded(); simulateLatency()
        val email = activeEmail ?: return fail(AuthError.SessionExpired)
        val account = accounts[email] ?: return fail(AuthError.SessionExpired)
        val key = "verify:$email"
        val attempts = codeAttempts[key] ?: 0
        if (attempts >= MAX_CODE_ATTEMPTS) return fail(AuthError.RateLimited(60))
        val pending = codes[key] ?: return fail(AuthError.ExpiredCode)
        if (clock.nowMs() > pending.expiresAtMs) { codes.remove(key); return fail(AuthError.ExpiredCode) }
        if (!MessageDigest.isEqual(pending.code.toByteArray(), code.trim().toByteArray())) {
            codeAttempts[key] = attempts + 1
            return fail(AuthError.InvalidCode)
        }
        codes.remove(key); codeAttempts.remove(key)
        val uni = universities.first { it.id == account.universityId }
        val updated = account.copy(verified = true, studentVerified = uni.matchesEmail(account.email))
        accounts[email] = updated
        persistAccounts()
        startSession(updated)
        return AuthResult.Success(Unit)
    }

    override suspend fun resendVerification(): AuthResult<Int> {
        ensureLoaded(); simulateLatency()
        val email = activeEmail ?: return fail(AuthError.SessionExpired)
        val key = "verify:$email"
        val wait = cooldownLeft(key)
        if (wait > 0) return fail(AuthError.RateLimited(wait))
        issueCode(key)
        codeAttempts.remove(key)
        return AuthResult.Success(RESEND_COOLDOWN_S)
    }

    override suspend fun refreshVerificationStatus(): AuthResult<Boolean> {
        ensureLoaded(); simulateLatency()
        val email = activeEmail ?: return fail(AuthError.SessionExpired)
        val account = accounts[email] ?: return fail(AuthError.SessionExpired)
        if (account.verified) { startSession(account); return AuthResult.Success(true) }
        return AuthResult.Success(false)
    }

    // ---------------------------------------------------------------- password reset
    override suspend fun requestPasswordReset(email: String): AuthResult<Unit> {
        ensureLoaded(); simulateLatency()
        val key = normalize(email)
        AuthValidators.email(key)?.let { return fail(AuthError.Validation(it)) }
        // Identical outcome whether or not the account exists.
        if (accounts.containsKey(key) && cooldownLeft("reset:$key") == 0) issueCode("reset:$key")
        return AuthResult.Success(Unit)
    }

    override suspend fun resetPassword(email: String, code: String, newPassword: String): AuthResult<Unit> {
        ensureLoaded(); simulateLatency()
        val key = normalize(email)
        AuthValidators.password(newPassword)?.let { return fail(AuthError.Validation(it)) }
        val codeKey = "reset:$key"
        val attempts = codeAttempts[codeKey] ?: 0
        if (attempts >= MAX_CODE_ATTEMPTS) return fail(AuthError.RateLimited(60))
        val pending = codes[codeKey]
        val account = accounts[key]
        if (pending == null || account == null) { codeAttempts[codeKey] = attempts + 1; return fail(AuthError.InvalidCode) }
        if (clock.nowMs() > pending.expiresAtMs) { codes.remove(codeKey); return fail(AuthError.ExpiredCode) }
        if (!MessageDigest.isEqual(pending.code.toByteArray(), code.trim().toByteArray())) {
            codeAttempts[codeKey] = attempts + 1
            return fail(AuthError.InvalidCode)
        }
        codes.remove(codeKey); codeAttempts.remove(codeKey) // single use
        val salt = ByteArray(16).also { random.nextBytes(it) }
        accounts[key] = account.copy(salt = enc64(salt), hash = enc64(hashPassword(newPassword, salt)))
        persistAccounts()
        failedLogins.remove(key)
        if (activeEmail == key) { store.remove(SESSION_KEY); activeEmail = null }
        _session.value = AuthState.SignedOut(SignedOutReason.PasswordChanged)
        return AuthResult.Success(Unit)
    }

    // ---------------------------------------------------------------- account management
    override suspend fun changePassword(currentPassword: String, newPassword: String): AuthResult<Unit> {
        ensureLoaded(); simulateLatency()
        val email = activeEmail ?: return fail(AuthError.SessionExpired)
        val account = accounts[email] ?: return fail(AuthError.SessionExpired)
        AuthValidators.password(newPassword)?.let { return fail(AuthError.Validation(it)) }
        retryAfter(failedLogins[email], LOGIN_WINDOW_MS, MAX_LOGIN_FAILURES)?.let { return fail(AuthError.RateLimited(it)) }
        if (!passwordMatches(account, currentPassword)) {
            failedLogins.getOrPut(email) { mutableListOf() }.add(clock.nowMs())
            return fail(AuthError.InvalidCredentials)
        }
        if (currentPassword == newPassword) return fail(AuthError.Validation("Choose a password you haven't used here."))
        val salt = ByteArray(16).also { random.nextBytes(it) }
        accounts[email] = account.copy(salt = enc64(salt), hash = enc64(hashPassword(newPassword, salt)))
        persistAccounts()
        failedLogins.remove(email)
        return AuthResult.Success(Unit)
    }

    override suspend fun deleteAccount(password: String): AuthResult<Unit> {
        ensureLoaded(); simulateLatency()
        val email = activeEmail ?: return fail(AuthError.SessionExpired)
        val account = accounts[email] ?: return fail(AuthError.SessionExpired)
        retryAfter(failedLogins[email], LOGIN_WINDOW_MS, MAX_LOGIN_FAILURES)?.let { return fail(AuthError.RateLimited(it)) }
        if (!passwordMatches(account, password)) {
            failedLogins.getOrPut(email) { mutableListOf() }.add(clock.nowMs())
            return fail(AuthError.InvalidCredentials)
        }
        accounts.remove(email)
        persistAccounts()
        store.remove(SESSION_KEY)
        activeEmail = null
        _session.value = AuthState.SignedOut()
        return AuthResult.Success(Unit)
    }

    private suspend fun passwordMatches(account: Account, password: String): Boolean =
        MessageDigest.isEqual(hashPassword(password, dec64(account.salt)), dec64(account.hash))

    // ---------------------------------------------------------------- onboarding
    override suspend fun completeOnboarding(profile: OnboardingProfile): AuthResult<Unit> {
        ensureLoaded(); simulateLatency()
        val email = activeEmail ?: return fail(AuthError.SessionExpired)
        val account = accounts[email] ?: return fail(AuthError.SessionExpired)
        if (!account.verified) return fail(AuthError.EmailNotVerified)
        if (profile.bio.length > 160) return fail(AuthError.Validation("Keep your bio under 160 characters."))
        val updated = account.copy(
            onboarded = true, course = profile.course.trim(), interests = profile.interests.distinct().take(12),
            bio = profile.bio.trim(), hasPhoto = profile.hasPhoto,
        )
        accounts[email] = updated
        persistAccounts()
        _session.value = AuthState.Authenticated(toUser(updated))
        return AuthResult.Success(Unit)
    }

    // ---------------------------------------------------------------- helpers
    private fun startSession(account: Account) {
        activeEmail = account.email
        val ttl = if (activeRemember) REMEMBER_TTL_MS else SHORT_TTL_MS
        if (activeRemember) store.put(SESSION_KEY, listOf(account.email, (clock.nowMs() + ttl).toString()).joinToString(";") { enc(it) })
        else store.remove(SESSION_KEY)
        _session.value = stateFor(account)
    }

    private fun stateFor(account: Account): AuthState = when {
        !account.verified -> AuthState.EmailVerificationRequired(account.email)
        !account.onboarded -> AuthState.OnboardingRequired(toUser(account))
        else -> AuthState.Authenticated(toUser(account))
    }

    private fun toUser(a: Account) = AuthUser(
        id = a.email.hashCode().toString(), fullName = a.fullName, email = a.email, universityId = a.universityId,
        universityName = universities.first { it.id == a.universityId }.name, emailVerified = a.verified,
        studentVerified = a.studentVerified, course = a.course, interests = a.interests, bio = a.bio, hasPhoto = a.hasPhoto,
    )

    private fun issueCode(key: String) {
        val code = "%06d".format(random.nextInt(1_000_000))
        codes[key] = PendingCode(code, clock.nowMs() + CODE_TTL_MS)
        lastSent[key] = clock.nowMs()
    }

    private fun cooldownLeft(key: String): Int {
        val last = lastSent[key] ?: return 0
        val left = RESEND_COOLDOWN_S - ((clock.nowMs() - last) / 1000).toInt()
        return left.coerceAtLeast(0)
    }

    private fun retryAfter(times: MutableList<Long>?, windowMs: Long, max: Int): Int? {
        if (times == null) return null
        val now = clock.nowMs()
        times.removeAll { now - it > windowMs }
        if (times.size < max) return null
        return (((times.first() + windowMs) - now) / 1000).toInt().coerceAtLeast(1)
    }

    private suspend fun simulateLatency() { if (latencyMs > 0) delay(latencyMs) }
    private fun normalize(email: String) = email.trim().lowercase()
    private fun fail(error: AuthError) = AuthResult.Failure(error)

    private suspend fun hashPassword(password: String, salt: ByteArray): ByteArray = withContext(Dispatchers.Default) {
        val spec = PBEKeySpec(password.toCharArray(), salt, 20_000, 256)
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    // ---- persistence (Base64 fields, no JSON dependency)
    private fun enc(s: String) = Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8))
    private fun dec(s: String) = String(Base64.getDecoder().decode(s), Charsets.UTF_8)
    private fun enc64(b: ByteArray) = Base64.getEncoder().encodeToString(b)
    private fun dec64(s: String) = Base64.getDecoder().decode(s)

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        store.get(ACCOUNTS_KEY)?.lineSequence()?.filter { it.isNotBlank() }?.forEach { line ->
            runCatching {
                val f = line.split(";").map { dec(it) }
                accounts[f[0]] = Account(
                    f[0], f[1], f[2], f[3], f[4], f[5], f[6].toBoolean(), f[7].toBoolean(), f[8].toBoolean(),
                    f[9], f[10].split('\u001F').filter { it.isNotEmpty() }, f[11], f[12].toBoolean(),
                )
            }
        }
    }

    private fun persistAccounts() {
        val text = accounts.values.joinToString("\n") { a ->
            listOf(
                a.email, a.fullName, a.universityId, a.admission, a.salt, a.hash, a.verified.toString(),
                a.onboarded.toString(), a.studentVerified.toString(), a.course, a.interests.joinToString("\u001F"),
                a.bio, a.hasPhoto.toString(),
            ).joinToString(";") { enc(it) }
        }
        store.put(ACCOUNTS_KEY, text)
    }

    private companion object {
        const val ACCOUNTS_KEY = "mock_accounts"
        const val SESSION_KEY = "mock_session"
        const val CODE_TTL_MS = 10 * 60 * 1000L
        const val RESEND_COOLDOWN_S = 60
        const val MAX_CODE_ATTEMPTS = 5
        const val MAX_LOGIN_FAILURES = 5
        const val LOGIN_WINDOW_MS = 5 * 60 * 1000L
        const val REMEMBER_TTL_MS = 7 * 24 * 60 * 60 * 1000L
        const val SHORT_TTL_MS = 12 * 60 * 60 * 1000L
    }
}
