package com.univibe.app.features.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.univibe.app.app.AppContainer
import com.univibe.app.data.net.ApiException
import com.univibe.app.data.settings.BlockedUser
import com.univibe.app.data.settings.PrivacyChange
import com.univibe.app.data.settings.PrivacyRepository
import com.univibe.app.data.settings.ServerPrivacy
import com.univibe.app.data.settings.NotificationPrefs
import com.univibe.app.data.settings.NotificationPrefsChange
import com.univibe.app.data.settings.NotificationPrefsRepository
import com.univibe.app.data.settings.updated
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException

sealed interface PrivacyState {
    data object Loading : PrivacyState
    data class Failed(val message: String) : PrivacyState
    /** [synced] is false in the offline preview build, where nothing reaches a server. */
    data class Ready(val privacy: ServerPrivacy, val synced: Boolean, val saving: Boolean = false, val error: String? = null) : PrivacyState
}

class PrivacyViewModel(
    private val repo: PrivacyRepository = AppContainer.privacyRepository,
) : ViewModel() {
    private val _state = MutableStateFlow<PrivacyState>(PrivacyState.Loading)
    val state: StateFlow<PrivacyState> = _state.asStateFlow()

    init { load() }

    fun load() {
        _state.value = PrivacyState.Loading
        viewModelScope.launch {
            _state.value = try {
                PrivacyState.Ready(repo.load(), repo.syncedWithServer)
            } catch (e: IOException) {
                PrivacyState.Failed(describeFailure(e))
            } catch (e: ApiException) {
                PrivacyState.Failed(describeFailure(e))
            }
            // Refresh the block list too so a second device sees the same people. Not needed to show this screen.
            try { repo.blockedUsers() } catch (e: IOException) {} catch (e: ApiException) {}
        }
    }

    /**
     * Shows the new value at once, saves it, and puts the old value back with a message if the server says no.
     * Changes are ignored while one is saving, so the same request is never sent twice.
     */
    fun change(c: PrivacyChange) {
        val before = _state.value as? PrivacyState.Ready ?: return
        if (before.saving) return
        _state.value = before.copy(privacy = before.privacy.updated(c), saving = true, error = null)
        viewModelScope.launch {
            _state.value = try {
                before.copy(privacy = repo.save(c), saving = false)
            } catch (e: IOException) {
                before.copy(saving = false, error = "Couldn't save that. ${describeFailure(e)}")
            } catch (e: ApiException) {
                before.copy(saving = false, error = "Couldn't save that. ${describeFailure(e)}")
            }
        }
    }
}

sealed interface BlockedState {
    data object Loading : BlockedState
    data class Failed(val message: String) : BlockedState
    data class Ready(val users: List<BlockedUser>, val working: String? = null, val error: String? = null) : BlockedState
}

class BlockedUsersViewModel(
    private val repo: PrivacyRepository = AppContainer.privacyRepository,
) : ViewModel() {
    private val _state = MutableStateFlow<BlockedState>(BlockedState.Loading)
    val state: StateFlow<BlockedState> = _state.asStateFlow()

    init { load() }

    fun load() {
        _state.value = BlockedState.Loading
        viewModelScope.launch {
            _state.value = try {
                BlockedState.Ready(repo.blockedUsers().sortedBy { it.name })
            } catch (e: IOException) {
                BlockedState.Failed(describeFailure(e))
            } catch (e: ApiException) {
                BlockedState.Failed(describeFailure(e))
            }
        }
    }

    fun unblock(id: String) {
        val before = _state.value as? BlockedState.Ready ?: return
        if (before.working != null) return
        _state.value = before.copy(working = id, error = null)
        viewModelScope.launch {
            _state.value = try {
                repo.unblock(id)
                BlockedState.Ready(before.users.filterNot { it.id == id })
            } catch (e: IOException) {
                before.copy(working = null, error = "Couldn't unblock. ${describeFailure(e)}")
            } catch (e: ApiException) {
                before.copy(working = null, error = "Couldn't unblock. ${describeFailure(e)}")
            }
        }
    }
}

sealed interface NotificationPrefsState {
    data object Loading : NotificationPrefsState
    data class Failed(val message: String) : NotificationPrefsState
    data class Ready(
        val prefs: NotificationPrefs,
        val synced: Boolean,
        val saving: Boolean = false,
        val error: String? = null,
    ) : NotificationPrefsState
}

class NotificationPrefsViewModel(
    private val repo: NotificationPrefsRepository = AppContainer.notificationPrefsRepository,
) : ViewModel() {
    private val _state = MutableStateFlow<NotificationPrefsState>(NotificationPrefsState.Loading)
    val state: StateFlow<NotificationPrefsState> = _state.asStateFlow()

    init { load() }

    fun load() {
        _state.value = NotificationPrefsState.Loading
        viewModelScope.launch {
            _state.value = try {
                NotificationPrefsState.Ready(repo.load(), repo.syncedWithServer)
            } catch (e: IOException) {
                NotificationPrefsState.Failed(describeFailure(e))
            } catch (e: ApiException) {
                NotificationPrefsState.Failed(describeFailure(e))
            }
        }
    }

    /** Same rules as privacy: show it at once, save it, restore the old value with a message if the save fails. */
    fun change(c: NotificationPrefsChange) {
        val before = _state.value as? NotificationPrefsState.Ready ?: return
        if (before.saving) return
        _state.value = before.copy(prefs = before.prefs.updated(c), saving = true, error = null)
        viewModelScope.launch {
            _state.value = try {
                before.copy(prefs = repo.save(c), saving = false)
            } catch (e: IOException) {
                before.copy(saving = false, error = "Couldn't save that. ${describeFailure(e)}")
            } catch (e: ApiException) {
                before.copy(saving = false, error = "Couldn't save that. ${describeFailure(e)}")
            }
        }
    }
}
