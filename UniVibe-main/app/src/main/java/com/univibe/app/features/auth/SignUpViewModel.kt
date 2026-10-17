package com.univibe.app.features.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.univibe.app.app.AppContainer
import com.univibe.app.domain.auth.AuthRepository
import com.univibe.app.domain.auth.AuthResult
import com.univibe.app.domain.auth.AuthValidators
import com.univibe.app.domain.auth.PasswordStrength
import com.univibe.app.domain.auth.SignUpRequest
import com.univibe.app.domain.auth.University
import com.univibe.app.domain.auth.userMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SignUpUiState(
    val fullName: String = "",
    val email: String = "",
    val admission: String = "",
    val universities: List<University> = emptyList(),
    val universityId: String? = null,
    val password: String = "",
    val confirm: String = "",
    val acceptedTerms: Boolean = false,
    val showErrors: Boolean = false,
    val submitting: Boolean = false,
    val error: String? = null,
) {
    val nameError get() = if (showErrors) AuthValidators.fullName(fullName) else null
    val emailError get() = if (showErrors || email.isNotEmpty() && showErrors) AuthValidators.email(email) else null
    val admissionError get() = if (showErrors) AuthValidators.admissionNumber(admission) else null
    val passwordError get() = if (showErrors) AuthValidators.password(password) else null
    val confirmError get() = if (showErrors || confirm.isNotEmpty()) AuthValidators.confirmPassword(password, confirm) else null
    val strength: PasswordStrength get() = AuthValidators.passwordStrength(password)
    val termsError get() = if (showErrors && !acceptedTerms) "Accept the Terms and Privacy Policy to continue." else null
    val university: University? get() = universities.firstOrNull { it.id == universityId }

    /** Soft hint only; the server decides whether the email proves university membership. */
    val domainHint: String?
        get() = if (AuthValidators.email(email) == null && university?.matchesEmail(email) == false)
            "This doesn't look like a ${university?.name} email. You'll need one to be verified as a student." else null

    val isValid: Boolean
        get() = AuthValidators.fullName(fullName) == null && AuthValidators.email(email) == null &&
            AuthValidators.admissionNumber(admission) == null && AuthValidators.password(password) == null &&
            AuthValidators.confirmPassword(password, confirm) == null && acceptedTerms && universityId != null
}

class SignUpViewModel(
    private val repository: AuthRepository = AppContainer.authRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(SignUpUiState())
    val state: StateFlow<SignUpUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val list = runCatching { repository.universities() }.getOrDefault(emptyList())
            _state.update { it.copy(universities = list, universityId = list.singleOrNull()?.id ?: it.universityId) }
        }
    }

    fun onName(v: String) = _state.update { it.copy(fullName = v, error = null) }
    fun onEmail(v: String) = _state.update { it.copy(email = v, error = null) }
    fun onAdmission(v: String) = _state.update { it.copy(admission = v, error = null) }
    fun onUniversity(id: String) = _state.update { it.copy(universityId = id) }
    fun onPassword(v: String) = _state.update { it.copy(password = v, error = null) }
    fun onConfirm(v: String) = _state.update { it.copy(confirm = v, error = null) }
    fun onTerms(v: Boolean) = _state.update { it.copy(acceptedTerms = v) }

    fun submit() {
        val s = _state.value
        if (s.submitting) return // prevent duplicate submissions
        if (!s.isValid) { _state.update { it.copy(showErrors = true) }; return }
        viewModelScope.launch {
            _state.update { it.copy(submitting = true, error = null) }
            val request = SignUpRequest(
                fullName = s.fullName, email = s.email, admissionNumber = s.admission.ifBlank { null },
                universityId = s.universityId!!, password = s.password, acceptedTerms = s.acceptedTerms,
            )
            when (val result = repository.signUp(request)) {
                is AuthResult.Success -> _state.update { it.copy(submitting = false, password = "", confirm = "") }
                is AuthResult.Failure -> _state.update { it.copy(submitting = false, error = result.error.userMessage()) }
            }
        }
    }
}
