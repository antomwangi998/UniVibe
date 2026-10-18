package com.univibe.app.features.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.univibe.app.BuildConfig
import com.univibe.app.app.AppContainer
import com.univibe.app.domain.auth.AuthError
import com.univibe.app.domain.auth.AuthRepository
import com.univibe.app.domain.auth.AuthResult
import com.univibe.app.domain.auth.AuthValidators
import com.univibe.app.domain.auth.DevAuthHints
import com.univibe.app.domain.auth.userMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class VerifyUiState(
    val code: String = "",
    val submitting: Boolean = false,
    val resending: Boolean = false,
    val refreshing: Boolean = false,
    val cooldown: Int = 60,
    val error: String? = null,
    val info: String? = null,
    val devHint: String? = null,
)

class VerifyEmailViewModel(
    private val repository: AuthRepository = AppContainer.authRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(VerifyUiState())
    val state: StateFlow<VerifyUiState> = _state.asStateFlow()
    private var ticker: Job? = null

    init {
        startCooldown(60) // a code was just sent when we arrived here
        refreshDevHint()
    }

    fun onCode(value: String) = _state.update {
        it.copy(code = value.filter(Char::isDigit).take(AuthValidators.CODE_LENGTH), error = null)
    }

    fun verify() {
        val s = _state.value
        if (s.submitting) return
        AuthValidators.verificationCode(s.code)?.let { msg -> _state.update { it.copy(error = msg) }; return }
        viewModelScope.launch {
            _state.update { it.copy(submitting = true, error = null, info = null) }
            when (val r = repository.verifyEmail(s.code)) {
                is AuthResult.Success -> _state.update { it.copy(submitting = false) }
                is AuthResult.Failure -> _state.update { it.copy(submitting = false, code = "", error = r.error.userMessage()) }
            }
        }
    }

    fun resend() {
        val s = _state.value
        if (s.resending || s.cooldown > 0) return
        viewModelScope.launch {
            _state.update { it.copy(resending = true, error = null, info = null) }
            when (val r = repository.resendVerification()) {
                is AuthResult.Success -> {
                    startCooldown(r.value)
                    _state.update { it.copy(resending = false, info = "We sent a new code.", code = "") }
                    refreshDevHint()
                }
                is AuthResult.Failure -> {
                    val e = r.error
                    if (e is AuthError.RateLimited) startCooldown(e.retryAfterSeconds)
                    _state.update {
                        it.copy(resending = false, error = if (e is AuthError.RateLimited) null else "We couldn't resend the code. ${e.userMessage()}")
                    }
                }
            }
        }
    }

    fun refreshStatus() {
        if (_state.value.refreshing) return
        viewModelScope.launch {
            _state.update { it.copy(refreshing = true, error = null, info = null) }
            when (val r = repository.refreshVerificationStatus()) {
                is AuthResult.Success ->
                    _state.update { it.copy(refreshing = false, info = if (r.value) null else "Not verified yet. Enter the code we sent you.") }
                is AuthResult.Failure -> _state.update { it.copy(refreshing = false, error = r.error.userMessage()) }
            }
        }
    }

    fun logOut() { viewModelScope.launch { repository.logOut() } }

    private fun refreshDevHint() {
        if (!BuildConfig.DEBUG) return
        val code = (repository as? DevAuthHints)?.latestVerificationCode()
        _state.update { it.copy(devHint = code) }
    }

    private fun startCooldown(seconds: Int) {
        ticker?.cancel()
        _state.update { it.copy(cooldown = seconds) }
        ticker = viewModelScope.launch {
            var left = seconds
            while (left > 0) {
                delay(1000)
                left--
                _state.update { it.copy(cooldown = left) }
            }
        }
    }
}
