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
    /** True when this build can offer Google sign-in (a server client id and a real backend are configured). */
    val googleSignInAvailable: Boolean get() = false
    /** A fresh single-use nonce to put into the Google request, so a copied ID token cannot be replayed. */
    suspend fun googleNonce(): AuthResult<String> = AuthResult.Failure(AuthError.GoogleUnavailable)
    /**
     * Sends the Google ID token to the server, which verifies it. Success means the session state moved on:
     * signed in, [AuthState.GoogleRegistrationRequired] (new person) or [AuthState.GoogleLinkRequired] (existing email).
     */
    suspend fun signInWithGoogle(idToken: String, nonce: String): AuthResult<Unit> = AuthResult.Failure(AuthError.GoogleUnavailable)
    /** Creates the account for a new Google person. Only valid in [AuthState.GoogleRegistrationRequired]. */
    suspend fun completeGoogleRegistration(request: GoogleRegistrationRequest): AuthResult<Unit> = AuthResult.Failure(AuthError.GoogleUnavailable)
    /** Links Google to the existing account after the account's password confirms it. Only valid in [AuthState.GoogleLinkRequired]. */
    suspend fun linkGoogleAccount(password: String): AuthResult<Unit> = AuthResult.Failure(AuthError.GoogleUnavailable)
    /** Abandons registration or linking and returns to the signed-out screens. */
    fun cancelGoogleFlow() {}
    suspend fun changePassword(currentPassword: String, newPassword: String): AuthResult<Unit>
    /** Permanently deletes the account after re-checking the password. */
    suspend fun deleteAccount(password: String): AuthResult<Unit>
    /**
     * Hides the account from other students without deleting anything, after re-checking the password. The server ends
     * every login, this one included, so success leaves the person signed out. Logging in again restores the account.
     */
    suspend fun deactivateAccount(password: String): AuthResult<Unit> = AuthResult.Failure(AuthError.ServiceUnavailable)
    /** The server's JSON copy of the person's own data. Only a real backend has one to give. */
    suspend fun exportMyData(): AuthResult<String> = AuthResult.Failure(AuthError.ServiceUnavailable)
    /** Called by the network layer when the server rejects the access/refresh token. */
    suspend fun handleSessionExpired()
    suspend fun logOut()
}

/** Implemented ONLY by the mock so developers can see codes that a real backend would email. */
interface DevAuthHints {
    fun latestVerificationCode(): String?
    fun latestResetCode(email: String): String?
}
