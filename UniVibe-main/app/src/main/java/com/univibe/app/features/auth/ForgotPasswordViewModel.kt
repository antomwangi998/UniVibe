package com.univibe.app.features.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.univibe.app.BuildConfig
import com.univibe.app.app.AppContainer
import com.univibe.app.domain.auth.AuthRepository
import com.univibe.app.domain.auth.AuthResult
import com.univibe.app.domain.auth.AuthValidators
import com.univibe.app.domain.auth.DevAuthHints
import com.univibe.app.domain.auth.userMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ResetStep { RequestCode, EnterNewPassword, Done }

data class ForgotUiState(
    val step: ResetStep = ResetStep.RequestCode,
    val email: String = "",
    val code: String = "",
    val password: String = "",
    val confirm: String = "",
    val submitting: Boolean = false,
    val emailError: String? = null,
    val codeError: String? = null,
    val passwordError: String? = null,
    val confirmError: String? = null,
    val error: String? = null,
    val info: String? = null,
    val devHint: String? = null,
)

class ForgotPasswordViewModel(
    private val repository: AuthRepository = AppContainer.authRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(ForgotUiState())
    val state: StateFlow<ForgotUiState> = _state.asStateFlow()

    fun onEmail(v: String) = _state.update { it.copy(email = v, emailError = null, error = null) }
    fun onCode(v: String) = _state.update { it.copy(code = v.filter(Char::isDigit).take(AuthValidators.CODE_LENGTH), codeError = null, error = null) }
    fun onPassword(v: String) = _state.update { it.copy(password = v, passwordError = null, error = null) }
    fun onConfirm(v: String) = _state.update { it.copy(confirm = v, confirmError = null, error = null) }

    fun requestCode() {
        val s = _state.value
        if (s.submitting) return
        AuthValidators.email(s.email)?.let { msg -> _state.update { it.copy(emailError = msg) }; return }
        viewModelScope.launch {
            _state.update { it.copy(submitting = true, error = null) }
            when (val r = repository.requestPasswordReset(s.email)) {
                is AuthResult.Success -> _state.update {
                    it.copy(
                        submitting = false, step = ResetStep.EnterNewPassword,
                        // Same wording whether or not the account exists.
                        info = "If an account exists for ${s.email.trim()}, we've sent a 6-digit code. It expires in 10 minutes.",
                        devHint = if (BuildConfig.DEBUG) (repository as? DevAuthHints)?.latestResetCode(s.email) else null,
                    )
                }
                is AuthResult.Failure -> _state.update { it.copy(submitting = false, error = r.error.userMessage()) }
            }
        }
    }

    fun resetPassword() {
        val s = _state.value
        if (s.submitting) return
        val codeError = AuthValidators.verificationCode(s.code)
        val passwordError = AuthValidators.password(s.password)
        val confirmError = AuthValidators.confirmPassword(s.password, s.confirm)
        if (codeError != null || passwordError != null || confirmError != null) {
            _state.update { it.copy(codeError = codeError, passwordError = passwordError, confirmError = confirmError) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(submitting = true, error = null) }
            when (val r = repository.resetPassword(s.email, s.code, s.password)) {
                is AuthResult.Success -> _state.update { it.copy(submitting = false, step = ResetStep.Done, password = "", confirm = "", code = "") }
                is AuthResult.Failure -> _state.update { it.copy(submitting = false, error = r.error.userMessage()) }
            }
        }
    }

    fun backToEmail() = _state.update { it.copy(step = ResetStep.RequestCode, code = "", password = "", confirm = "", error = null, info = null) }
}
