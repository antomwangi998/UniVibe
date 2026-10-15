package com.univibe.app.domain.auth

import kotlinx.coroutines.flow.StateFlow

/**
 * Contract between the UI and any authentication backend. Every security rule
 * (rate limits, token expiry, university membership) must be enforced server-side;
 * the client only reflects the outcome.
 */
interface AuthRepository {
    val session: StateFlow<AuthState>

    suspend fun universities(): List<University>
    suspend fun restoreSession()
    suspend fun signUp(request: SignUpRequest): AuthResult<Unit>
    suspend fun logIn(email: String, password: String, rememberMe: Boolean): AuthResult<Unit>
    suspend fun verifyEmail(code: String): AuthResult<Unit>
    /** Returns the cooldown (seconds) before another resend is allowed. */
    suspend fun resendVerification(): AuthResult<Int>
    /** True once the server reports the email as verified. */
    suspend fun refreshVerificationStatus(): AuthResult<Boolean>
    /** Must succeed identically whether or not the email has an account. */
    suspend fun requestPasswordReset(email: String): AuthResult<Unit>
    suspend fun resetPassword(email: String, code: String, newPassword: String): AuthResult<Unit>
    suspend fun completeOnboarding(profile: OnboardingProfile): AuthResult<Unit>
    /** Saves edits to the signed-in student's profile on the server and refreshes the session user. */
    suspend fun updateProfile(edit: ProfileEdit): AuthResult<Unit> = AuthResult.Failure(AuthError.Unknown)
    suspend fun changePassword(currentPassword: String, newPassword: String): AuthResult<Unit>
    /** Permanently deletes the account after re-checking the password. */
    suspend fun deleteAccount(password: String): AuthResult<Unit>
    /** Called by the network layer when the server rejects the access/refresh token. */
    suspend fun handleSessionExpired()
    suspend fun logOut()
}

/** Implemented ONLY by the mock so developers can see codes that a real backend would email. */
interface DevAuthHints {
    fun latestVerificationCode(): String?
    fun latestResetCode(email: String): String?
}
