package com.univibe.app.features.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.univibe.app.app.AppContainer
import com.univibe.app.domain.auth.AuthRepository
import com.univibe.app.domain.auth.AuthResult
import com.univibe.app.domain.auth.AuthValidators
import com.univibe.app.domain.auth.userMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LoginUiState(
    val email: String = "",
    val password: String = "",
    val rememberMe: Boolean = true,
    val submitting: Boolean = false,
    val emailError: String? = null,
    val passwordError: String? = null,
    val error: String? = null,
)

class LoginViewModel(
    private val repository: AuthRepository = AppContainer.authRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    fun onEmail(value: String) = _state.update { it.copy(email = value, emailError = null, error = null) }
    fun onPassword(value: String) = _state.update { it.copy(password = value, passwordError = null, error = null) }
    fun onRemember(value: Boolean) = _state.update { it.copy(rememberMe = value) }

    fun submit() {
        val s = _state.value
        if (s.submitting) return // prevent duplicate submissions
        val emailError = AuthValidators.email(s.email)
        val passwordError = if (s.password.isEmpty()) "Enter your password." else null
        if (emailError != null || passwordError != null) {
            _state.update { it.copy(emailError = emailError, passwordError = passwordError) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(submitting = true, error = null) }
            when (val result = repository.logIn(s.email, s.password, s.rememberMe)) {
                is AuthResult.Success -> _state.update { it.copy(submitting = false, password = "") }
                is AuthResult.Failure -> _state.update { it.copy(submitting = false, password = "", error = result.error.userMessage()) }
            }
        }
    }
}
