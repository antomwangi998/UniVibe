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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    /** [afterChange] runs after the new password was accepted (the screen offers to update the saved login). */
    fun submit(afterChange: suspend (email: String, password: String) -> Unit = { _, _ -> }) {
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
                is AuthResult.Success -> {
                    _state.value = ChangePasswordUiState(success = true)
                    val email = (auth.session.value as? AuthState.Authenticated)?.user?.email
                    if (email != null) runCatching { afterChange(email, s.new) }
                }
                is AuthResult.Failure -> _state.update {
                    if (r.error == AuthError.InvalidCredentials) it.copy(submitting = false, currentError = "Your current password is incorrect.")
                    else it.copy(submitting = false, error = r.error.userMessage())
                }
            }
        }
    }
}

data class DeactivateAccountUiState(
    val password: String = "",
    val understood: Boolean = false,
    val submitting: Boolean = false,
    val error: String? = null,
) {
    val canSubmit get() = password.isNotEmpty() && understood && !submitting
}

/** Hiding an account is not deleting it: the server keeps everything, ends every login, and logging in again brings it back. */
class DeactivateAccountViewModel(
    private val auth: AuthRepository = AppContainer.authRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(DeactivateAccountUiState())
    val state: StateFlow<DeactivateAccountUiState> = _state.asStateFlow()

    fun onPassword(v: String) = _state.update { it.copy(password = v, error = null) }
    fun onUnderstood(v: Boolean) = _state.update { it.copy(understood = v) }

    fun submit() {
        val s = _state.value
        if (!s.canSubmit) return
        viewModelScope.launch {
            _state.update { it.copy(submitting = true, error = null) }
            when (val r = auth.deactivateAccount(s.password)) {
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

data class DataExportUiState(
    /** Asking the server for the copy. */
    val working: Boolean = false,
    /** The copy is in memory and waiting to be saved to a file the person picks. */
    val ready: Boolean = false,
    val saving: Boolean = false,
    val saved: Boolean = false,
    val error: String? = null,
)

/**
 * Requests a copy of the person's own data and writes it to a file they choose. The copy lives only in memory: it is
 * dropped once saved and when this screen goes away, and is never written anywhere the person did not pick.
 */
class DataExportViewModel(
    private val auth: AuthRepository = AppContainer.authRepository,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val _state = MutableStateFlow(DataExportUiState())
    val state: StateFlow<DataExportUiState> = _state.asStateFlow()
    private var content: String? = null

    fun request() {
        if (_state.value.working) return
        viewModelScope.launch {
            _state.update { it.copy(working = true, error = null, saved = false) }
            when (val r = auth.exportMyData()) {
                is AuthResult.Success -> { content = r.value; _state.update { it.copy(working = false, ready = true) } }
                is AuthResult.Failure -> _state.update { it.copy(working = false, ready = false, error = r.error.userMessage()) }
            }
        }
    }

    /** Writes the copy to the stream the person's file picker opened. A failure keeps the copy so they can try again. */
    fun save(openStream: () -> java.io.OutputStream?) {
        val data = content ?: return
        if (_state.value.saving) return
        viewModelScope.launch {
            _state.update { it.copy(saving = true, error = null) }
            val ok = withContext(io) {
                try { openStream()?.use { it.write(data.toByteArray(Charsets.UTF_8)) } != null } catch (e: java.io.IOException) { false }
            }
            if (ok) {
                content = null
                _state.update { it.copy(saving = false, ready = false, saved = true) }
            } else {
                _state.update { it.copy(saving = false, error = "Couldn't save the file. Choose another place and try again.") }
            }
        }
    }

    override fun onCleared() { content = null }
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
