package com.univibe.app.features.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.univibe.app.app.AppContainer
import com.univibe.app.data.auth.GoogleCredentialResult
import com.univibe.app.domain.auth.AuthError
import com.univibe.app.domain.auth.AuthRepository
import com.univibe.app.domain.auth.AuthResult
import com.univibe.app.domain.auth.AuthState
import com.univibe.app.domain.auth.AuthValidators
import com.univibe.app.domain.auth.GoogleRegistrationRequest
import com.univibe.app.domain.auth.University
import com.univibe.app.domain.auth.userMessage
import com.univibe.app.features.legal.TermsOfService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class GoogleButtonState(
    val busy: Boolean = false,
    val error: String? = null,
    /** A calm hint, not an error (for example "no Google account on this device"). */
    val notice: String? = null,
)

/** Drives the "Continue with Google" button on both the log-in and the sign-up screen. */
class GoogleAuthViewModel(
    private val repository: AuthRepository = AppContainer.authRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(GoogleButtonState())
    val state: StateFlow<GoogleButtonState> = _state.asStateFlow()

    /** The button is shown only when this build is set up for Google sign-in. */
    val available: Boolean get() = repository.googleSignInAvailable

    /**
     * [chooseAccount] shows Google's account chooser for the given nonce and is supplied by the screen (it needs the
     * Activity). Nothing is treated as signed in until the server has verified the token.
     */
    fun continueWithGoogle(chooseAccount: suspend (nonce: String) -> GoogleCredentialResult) {
        if (_state.value.busy) return // no duplicate sign-ins or duplicate account creation
        viewModelScope.launch {
            _state.value = GoogleButtonState(busy = true)
            val nonce = when (val r = repository.googleNonce()) {
                is AuthResult.Success -> r.value
                is AuthResult.Failure -> return@launch fail(r.error)
            }
            when (val picked = chooseAccount(nonce)) {
                is GoogleCredentialResult.Token -> when (val r = repository.signInWithGoogle(picked.idToken, nonce)) {
                    is AuthResult.Success -> _state.value = GoogleButtonState() // the session state moves the screen on
                    is AuthResult.Failure -> fail(r.error)
                }
                // Backing out of Google's chooser is a normal choice: no message, no dialog.
                GoogleCredentialResult.Cancelled -> _state.value = GoogleButtonState()
                GoogleCredentialResult.NoAccount ->
                    _state.value = GoogleButtonState(notice = "No Google account found on this device. Add one in your phone's settings, or use your email below.")
                GoogleCredentialResult.Unavailable -> fail(AuthError.GoogleUnavailable)
                GoogleCredentialResult.Failed ->
                    _state.value = GoogleButtonState(error = "We couldn't sign you in with Google. Check your connection and try again.")
            }
        }
    }

    private fun fail(error: AuthError) {
        _state.value = GoogleButtonState(error = error.userMessage())
    }
}

data class GoogleRegistrationUiState(
    val fullName: String = "",
    val email: String = "",
    val admission: String = "",
    val universities: List<University> = emptyList(),
    val universityId: String? = null,
    val campusId: String? = null,
    val acceptedTerms: Boolean = false,
    val showErrors: Boolean = false,
    val submitting: Boolean = false,
    val error: String? = null,
) {
    val nameError get() = if (showErrors) AuthValidators.fullName(fullName) else null
    val admissionError get() = if (showErrors) AuthValidators.admissionNumber(admission) else null
    val termsError get() = if (showErrors && !acceptedTerms) "Accept the Terms and Privacy Policy to continue." else null
    val university: University? get() = universities.firstOrNull { it.id == universityId }
    private val needsCampus: Boolean get() = university?.campuses?.isNotEmpty() == true
    val campusError get() = if (showErrors && needsCampus && campusId == null) "Choose your campus." else null
    val domainHint: String?
        get() = if (university?.matchesEmail(email) == false)
            "This isn't a ${university?.name} email, so you won't be verified as a student. You can still join." else null
    val isValid: Boolean
        get() = AuthValidators.fullName(fullName) == null && AuthValidators.admissionNumber(admission) == null &&
            acceptedTerms && universityId != null && (!needsCampus || university?.campuses?.any { it.id == campusId } == true)
}

/** The short step for a first-time Google person: name (prefilled), university and the Terms. The email is Google's and read-only. */
class GoogleRegistrationViewModel(
    private val repository: AuthRepository = AppContainer.authRepository,
) : ViewModel() {
    private val prefill = (repository.session.value as? AuthState.GoogleRegistrationRequired)?.prefill
    private val _state = MutableStateFlow(GoogleRegistrationUiState(fullName = prefill?.fullName.orEmpty(), email = prefill?.email.orEmpty()))
    val state: StateFlow<GoogleRegistrationUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val list = runCatching { repository.universities() }.getOrDefault(emptyList())
            _state.update { it.copy(universities = list, universityId = list.singleOrNull()?.id ?: it.universityId) }
        }
    }

    fun onName(v: String) = _state.update { it.copy(fullName = v, error = null) }
    fun onAdmission(v: String) = _state.update { it.copy(admission = v, error = null) }
    fun onUniversity(id: String) = _state.update { it.copy(universityId = id, campusId = null) }
    fun onCampus(id: String) = _state.update { it.copy(campusId = id) }
    fun onTerms(v: Boolean) = _state.update { it.copy(acceptedTerms = v) }
    fun cancel() = repository.cancelGoogleFlow()

    fun submit() {
        val s = _state.value
        if (s.submitting) return // one account-creation request at a time
        if (!s.isValid) { _state.update { it.copy(showErrors = true) }; return }
        viewModelScope.launch {
            _state.update { it.copy(submitting = true, error = null) }
            val request = GoogleRegistrationRequest(
                fullName = s.fullName, universityId = s.universityId!!, admissionNumber = s.admission.ifBlank { null },
                acceptedTerms = s.acceptedTerms, termsVersion = TermsOfService.VERSION, campusId = s.campusId,
            )
            when (val r = repository.completeGoogleRegistration(request)) {
                is AuthResult.Success -> _state.update { it.copy(submitting = false) }
                is AuthResult.Failure -> _state.update { it.copy(submitting = false, error = r.error.userMessage()) }
            }
        }
    }
}

data class GoogleLinkUiState(
    val email: String = "",
    val password: String = "",
    val submitting: Boolean = false,
    val passwordError: String? = null,
    val error: String? = null,
)

/** The email already has a UniVibe account: its password proves the person owns it before Google is linked. */
class GoogleLinkViewModel(
    private val repository: AuthRepository = AppContainer.authRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(GoogleLinkUiState(email = (repository.session.value as? AuthState.GoogleLinkRequired)?.email.orEmpty()))
    val state: StateFlow<GoogleLinkUiState> = _state.asStateFlow()

    fun onPassword(v: String) = _state.update { it.copy(password = v, passwordError = null, error = null) }
    fun cancel() = repository.cancelGoogleFlow()

    fun submit() {
        val s = _state.value
        if (s.submitting) return
        if (s.password.isEmpty()) { _state.update { it.copy(passwordError = "Enter your password.") }; return }
        viewModelScope.launch {
            _state.update { it.copy(submitting = true, error = null) }
            when (val r = repository.linkGoogleAccount(s.password)) {
                is AuthResult.Success -> _state.update { it.copy(submitting = false, password = "") }
                is AuthResult.Failure -> _state.update {
                    it.copy(
                        submitting = false, password = "",
                        error = if (r.error == AuthError.InvalidCredentials) "That password isn't correct." else r.error.userMessage(),
                    )
                }
            }
        }
    }
}
