package com.univibe.app.features.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.univibe.app.app.AppContainer
import com.univibe.app.data.settings.AppSettings
import com.univibe.app.data.settings.SettingsRepository
import com.univibe.app.domain.auth.AuthError
import com.univibe.app.domain.auth.AuthRepository
import com.univibe.app.domain.auth.AuthResult
import com.univibe.app.domain.auth.AuthState
import com.univibe.app.domain.auth.AuthUser
import com.univibe.app.domain.auth.AuthValidators
import com.univibe.app.domain.auth.userMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val settingsRepository: SettingsRepository = AppContainer.settingsRepository,
    auth: AuthRepository = AppContainer.authRepository,
) : ViewModel() {
    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    val user: StateFlow<AuthUser?> = auth.session
        .map { (it as? AuthState.Authenticated)?.user }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun update(change: (AppSettings) -> AppSettings) {
        viewModelScope.launch { settingsRepository.update(change) }
    }

    fun unblock(id: String) = update { s -> s.copy(blocked = s.blocked.filterNot { it.id == id }) }
}

data class ChangePasswordUiState(
    val current: String = "",
    val new: String = "",
    val confirm: String = "",
    val submitting: Boolean = false,
    val currentError: String? = null,
    val newError: String? = null,
    val confirmError: String? = null,
    val error: String? = null,
    val success: Boolean = false,
)

class ChangePasswordViewModel(
    private val auth: AuthRepository = AppContainer.authRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(ChangePasswordUiState())
    val state: StateFlow<ChangePasswordUiState> = _state.asStateFlow()

    fun onCurrent(v: String) = _state.update { it.copy(current = v, currentError = null, error = null, success = false) }
    fun onNew(v: String) = _state.update { it.copy(new = v, newError = null, error = null, success = false) }
    fun onConfirm(v: String) = _state.update { it.copy(confirm = v, confirmError = null, error = null, success = false) }

    fun submit() {
        val s = _state.value
        if (s.submitting) return
        val currentError = if (s.current.isEmpty()) "Enter your current password." else null
        val newError = AuthValidators.password(s.new)
        val confirmError = AuthValidators.confirmPassword(s.new, s.confirm)
        if (currentError != null || newError != null || confirmError != null) {
            _state.update { it.copy(currentError = currentError, newError = newError, confirmError = confirmError) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(submitting = true, error = null, success = false) }
            when (val r = auth.changePassword(s.current, s.new)) {
                is AuthResult.Success -> _state.value = ChangePasswordUiState(success = true)
                is AuthResult.Failure -> _state.update {
                    if (r.error == AuthError.InvalidCredentials) it.copy(submitting = false, currentError = "Your current password is incorrect.")
                    else it.copy(submitting = false, error = r.error.userMessage())
                }
            }
        }
    }
}

data class DeleteAccountUiState(
    val password: String = "",
    val understood: Boolean = false,
    val submitting: Boolean = false,
    val error: String? = null,
) {
    val canSubmit get() = password.isNotEmpty() && understood && !submitting
}

class DeleteAccountViewModel(
    private val auth: AuthRepository = AppContainer.authRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(DeleteAccountUiState())
    val state: StateFlow<DeleteAccountUiState> = _state.asStateFlow()

    fun onPassword(v: String) = _state.update { it.copy(password = v, error = null) }
    fun onUnderstood(v: Boolean) = _state.update { it.copy(understood = v) }

    fun submit() {
        val s = _state.value
        if (!s.canSubmit) return
        viewModelScope.launch {
            _state.update { it.copy(submitting = true, error = null) }
            when (val r = auth.deleteAccount(s.password)) {
                is AuthResult.Success -> Unit // session becomes SignedOut and the gate leaves this screen
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
