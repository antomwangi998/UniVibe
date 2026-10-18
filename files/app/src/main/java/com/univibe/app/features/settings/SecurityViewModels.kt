package com.univibe.app.features.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.univibe.app.app.AppContainer
import com.univibe.app.data.net.ApiException
import com.univibe.app.data.settings.AccountSecurityRepository
import com.univibe.app.data.settings.AccountStatus
import com.univibe.app.data.settings.LoginSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException

/** One plain sentence for a failed request. Anything unexpected is not caught here, so bugs are never hidden. */
internal fun describeFailure(e: Exception): String = when {
    e is IOException -> "No connection. Check your internet and try again."
    e is ApiException && e.code == "relogin_required" -> "Log out and log in again on this device to manage your sessions."
    e is ApiException && e.status == 401 -> "Your session expired. Please log in again."
    e is ApiException -> e.message ?: "Something went wrong."
    else -> "Something went wrong."
}

sealed interface LoginActivityState {
    data object Loading : LoginActivityState
    /** The offline preview build has no server to ask. */
    data object Unavailable : LoginActivityState
    data class Failed(val message: String) : LoginActivityState
    data class Ready(
        val sessions: List<LoginSession>,
        /** False when this device's login predates session tracking, so it cannot tell which row is "this device". */
        val currentKnown: Boolean,
        val working: Boolean = false,
        val notice: String? = null,
        val error: String? = null,
    ) : LoginActivityState {
        val others: List<LoginSession> get() = sessions.filterNot { it.current }
    }
}

class LoginActivityViewModel(
    private val repo: AccountSecurityRepository = AppContainer.accountSecurity,
) : ViewModel() {
    private val _state = MutableStateFlow<LoginActivityState>(LoginActivityState.Loading)
    val state: StateFlow<LoginActivityState> = _state.asStateFlow()

    init { load() }

    fun load() {
        if (!repo.available) { _state.value = LoginActivityState.Unavailable; return }
        _state.value = LoginActivityState.Loading
        viewModelScope.launch {
            _state.value = try {
                val list = repo.sessions()
                LoginActivityState.Ready(list.items, currentKnown = list.currentSessionId != null)
            } catch (e: IOException) {
                LoginActivityState.Failed(describeFailure(e))
            } catch (e: ApiException) {
                LoginActivityState.Failed(describeFailure(e))
            }
        }
    }

    /** Ends one OTHER login. The current login is ended with Log out, which also clears this device. */
    fun endSession(id: String) = act { repo.endSession(id); "Device logged out." }

    fun endOthers() = act {
        val n = repo.endOtherSessions()
        if (n == 0) "No other devices were logged in." else "Logged out of $n other ${if (n == 1) "device" else "devices"}."
    }

    /** Runs one change, then shows what the server says now. A failure restores the list as it was and says why. */
    private fun act(call: suspend () -> String) {
        val before = _state.value as? LoginActivityState.Ready ?: return
        if (before.working) return // no duplicate requests
        _state.value = before.copy(working = true, error = null, notice = null)
        viewModelScope.launch {
            try {
                val notice = call()
                val list = repo.sessions()
                _state.value = LoginActivityState.Ready(list.items, list.currentSessionId != null, notice = notice)
            } catch (e: IOException) {
                _state.value = before.copy(working = false, error = describeFailure(e))
            } catch (e: ApiException) {
                _state.value = before.copy(working = false, error = describeFailure(e))
            }
        }
    }
}

sealed interface AccountStatusState {
    data object Loading : AccountStatusState
    data object Unavailable : AccountStatusState
    data class Failed(val message: String) : AccountStatusState
    data class Ready(val status: AccountStatus) : AccountStatusState
}

class AccountStatusViewModel(
    private val repo: AccountSecurityRepository = AppContainer.accountSecurity,
) : ViewModel() {
    private val _state = MutableStateFlow<AccountStatusState>(AccountStatusState.Loading)
    val state: StateFlow<AccountStatusState> = _state.asStateFlow()

    init { load() }

    fun load() {
        if (!repo.available) { _state.value = AccountStatusState.Unavailable; return }
        _state.value = AccountStatusState.Loading
        viewModelScope.launch {
            _state.value = try {
                AccountStatusState.Ready(repo.accountStatus())
            } catch (e: IOException) {
                AccountStatusState.Failed(describeFailure(e))
            } catch (e: ApiException) {
                AccountStatusState.Failed(describeFailure(e))
            }
        }
    }
}
