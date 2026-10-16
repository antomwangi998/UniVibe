package com.univibe.app.domain.auth

data class University(val id: String, val name: String, val emailDomains: List<String>) {
    fun matchesEmail(email: String): Boolean {
        val domain = email.substringAfterLast('@', "").lowercase()
        return emailDomains.any { domain == it || domain.endsWith(".$it") }
    }
}

data class AuthUser(
    val id: String,
    val fullName: String,
    val email: String,
    val universityId: String,
    val universityName: String,
    /** True only after the emailed code was confirmed. */
    val emailVerified: Boolean,
    /** True only if the server matched the verified email to the university. Never assume it. */
    val studentVerified: Boolean,
    val course: String = "",
    val interests: List<String> = emptyList(),
    val bio: String = "",
    val hasPhoto: Boolean = false,
    /** Public handle without the "@". Chosen by the server at sign-up; empty for the offline mock. */
    val handle: String = "",
    /** For example "2nd Year". Empty until the student sets it. */
    val yearOfStudy: String = "",
    /** Short-lived link to the profile photo, or null. Refreshed every time the profile is loaded. */
    val avatarUrl: String? = null,
    val coverUrl: String? = null,
)

/** What to do with a profile or cover photo when saving a profile. */
sealed interface PhotoChange {
    data object Keep : PhotoChange
    data object Remove : PhotoChange
    /** A photo on this device that still has to be uploaded. */
    data class Replace(val localPath: String) : PhotoChange
}

/** A later edit of the signed-in student's own profile (as opposed to the first-time [OnboardingProfile]). */
data class ProfileEdit(
    val fullName: String,
    val course: String,
    val yearOfStudy: String,
    val interests: List<String>,
    val bio: String,
    val avatar: PhotoChange = PhotoChange.Keep,
    val cover: PhotoChange = PhotoChange.Keep,
)

data class SignUpRequest(
    val fullName: String,
    val email: String,
    val admissionNumber: String?,
    val universityId: String,
    val password: String,
    val acceptedTerms: Boolean,
)

data class OnboardingProfile(
    val course: String,
    val interests: List<String>,
    val bio: String,
    val hasPhoto: Boolean,
)

enum class SignedOutReason { SessionExpired, PasswordChanged }

/** Single source of truth for where the user is in the account lifecycle. */
sealed interface AuthState {
    data object LoadingSession : AuthState
    data class SignedOut(val reason: SignedOutReason? = null) : AuthState
    data class EmailVerificationRequired(val email: String) : AuthState
    data class OnboardingRequired(val user: AuthUser) : AuthState
    data class Authenticated(val user: AuthUser) : AuthState
}

sealed interface AuthError {
    data object InvalidCredentials : AuthError
    data object EmailNotVerified : AuthError
    data object Network : AuthError
    data object ServiceUnavailable : AuthError
    data object InvalidCode : AuthError
    data object ExpiredCode : AuthError
    data object SessionExpired : AuthError
    data object SignUpRejected : AuthError
    data class RateLimited(val retryAfterSeconds: Int) : AuthError
    data class Validation(val message: String) : AuthError
    data object Unknown : AuthError
}

sealed interface AuthResult<out T> {
    data class Success<T>(val value: T) : AuthResult<T>
    data class Failure(val error: AuthError) : AuthResult<Nothing>
}

/**
 * User-facing copy. Deliberately generic for credentials and sign-up so responses
 * never confirm whether a particular account exists.
 */
fun AuthError.userMessage(): String = when (this) {
    AuthError.InvalidCredentials -> "Email or password is incorrect."
    AuthError.EmailNotVerified -> "Please verify your email to continue."
    AuthError.Network -> "You appear to be offline. Check your connection and try again."
    AuthError.ServiceUnavailable -> "UniVibe is temporarily unavailable. Please try again in a few minutes."
    AuthError.InvalidCode -> "That code isn't right. Check it and try again."
    AuthError.ExpiredCode -> "That code has expired. Request a new one."
    AuthError.SessionExpired -> "Your session expired. Please log in again."
    AuthError.SignUpRejected ->
        "We couldn't create an account with those details. If you may already have one, try logging in or resetting your password."
    is AuthError.RateLimited -> "Too many attempts. Try again in ${formatWait(retryAfterSeconds)}."
    is AuthError.Validation -> message
    AuthError.Unknown -> "Something went wrong. Please try again."
}

private fun formatWait(seconds: Int): String = when {
    seconds < 60 -> "$seconds seconds"
    seconds < 120 -> "1 minute"
    else -> "${(seconds + 59) / 60} minutes"
}
