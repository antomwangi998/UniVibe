package com.univibe.app.features.notifications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.univibe.app.app.AppContainer
import com.univibe.app.data.model.AppNotification
import com.univibe.app.data.model.NotificationTarget
import com.univibe.app.data.repository.NotificationRepository
import com.univibe.app.data.settings.SettingsRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class NotificationsUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val items: List<AppNotification> = emptyList(),
    val blocked: Set<String> = emptySet(),
) {
    /** Nothing from people you blocked, even if the server still sent it. */
    val visible: List<AppNotification>
        get() = items.filter { n -> val id = n.actor?.id; id == null || id !in blocked }
    val unread: List<AppNotification> get() = visible.filter { !it.read }
    val earlier: List<AppNotification> get() = visible.filter { it.read }
}

class NotificationsViewModel(
    private val repository: NotificationRepository = AppContainer.notificationRepository,
    private val settings: SettingsRepository = AppContainer.settingsRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(NotificationsUiState())
    val state: StateFlow<NotificationsUiState> = _state.asStateFlow()

    init {
        load()
        viewModelScope.launch { repository.changes().conflate().collect { delay(300); load(silent = true) } }
        viewModelScope.launch {
            settings.settings.collect { s -> _state.update { it.copy(blocked = s.blocked.map { b -> b.id }.toSet()) } }
        }
    }

    fun load(silent: Boolean = false) {
        viewModelScope.launch {
            if (!silent) _state.update { it.copy(loading = true, error = null) }
            try {
                val items = repository.getNotifications()
                _state.update { it.copy(loading = false, error = null, items = items) }
            } catch (e: Exception) {
                if (!silent) _state.update { it.copy(loading = false, error = "We couldn't load your notifications.") }
            }
        }
    }

    /** Marks the item read, then lets the screen navigate to where it points. */
    fun open(n: AppNotification, navigate: (NotificationTarget) -> Unit) {
        if (!n.read) {
            _state.update { s -> s.copy(items = s.items.map { if (it.id == n.id) it.copy(read = true) else it }) }
            viewModelScope.launch { runCatching { repository.markRead(n.id) } }
        }
        navigate(n.target)
    }

    fun markAllRead() {
        _state.update { s -> s.copy(items = s.items.map { it.copy(read = true) }) }
        viewModelScope.launch { runCatching { repository.markAllRead() } }
    }
}
