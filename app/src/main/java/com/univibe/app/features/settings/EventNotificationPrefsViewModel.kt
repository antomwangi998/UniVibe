package com.univibe.app.features.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.univibe.app.app.AppContainer
import com.univibe.app.data.model.EventNotificationPrefs
import com.univibe.app.data.model.EventNotificationPrefsChange
import com.univibe.app.data.model.updated
import com.univibe.app.data.repository.EventRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** [prefs] is null until loaded, and stays null when the server can't store them, so the section is simply not shown. */
data class EventNotificationPrefsUi(val prefs: EventNotificationPrefs? = null, val saving: Boolean = false, val error: String? = null)

class EventNotificationPrefsViewModel(
    private val repository: EventRepository = AppContainer.eventRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(EventNotificationPrefsUi())
    val state: StateFlow<EventNotificationPrefsUi> = _state.asStateFlow()

    init {
        if (repository.supportsEventNotificationPrefs) viewModelScope.launch {
            runCatching { repository.eventNotificationPrefs() }.onSuccess { p -> _state.update { it.copy(prefs = p) } }
        }
    }

    /** Shown at once, saved, and put back with a message if the save fails. */
    fun change(c: EventNotificationPrefsChange) {
        val before = _state.value
        val current = before.prefs ?: return
        if (before.saving) return
        _state.value = before.copy(prefs = current.updated(c), saving = true, error = null)
        viewModelScope.launch {
            _state.value = try {
                before.copy(prefs = repository.saveEventNotificationPrefs(c), saving = false)
            } catch (e: Exception) {
                before.copy(prefs = current, saving = false, error = "Couldn't save that. Check your connection and try again.")
            }
        }
    }

    /** One switch per reminder time; at least none is allowed (no reminders). */
    fun toggleOffset(minutes: Int) {
        val current = _state.value.prefs ?: return
        val next = if (minutes in current.reminderOffsets) current.reminderOffsets - minutes else (current.reminderOffsets + minutes).sortedDescending()
        change(EventNotificationPrefsChange(reminderOffsets = next))
    }
}
