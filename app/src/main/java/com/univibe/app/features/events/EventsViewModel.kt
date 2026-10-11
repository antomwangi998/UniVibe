package com.univibe.app.features.events

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.univibe.app.data.model.CampusEvent
import com.univibe.app.data.model.EventsTab
import com.univibe.app.data.repository.EventRepository
import com.univibe.app.data.repository.MockEventRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class EventsUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val tab: EventsTab = EventsTab.Upcoming,
    val events: List<CampusEvent> = emptyList(),
    val pendingCancel: CampusEvent? = null,
) {
    val visible: List<CampusEvent>
        get() = if (tab == EventsTab.MyEvents) events.filter { it.rsvped } else events
}

class EventsViewModel(
    private val repository: EventRepository = MockEventRepository(),
) : ViewModel() {
    private val _state = MutableStateFlow(EventsUiState())
    val state: StateFlow<EventsUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val events = repository.getEvents()
                _state.update { it.copy(loading = false, events = events) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = "We couldn't load events right now.") }
            }
        }
    }

    fun setTab(tab: EventsTab) = _state.update { it.copy(tab = tab) }

    /** Joining is immediate; cancelling asks for confirmation first. */
    fun onRsvpClick(event: CampusEvent) {
        if (event.rsvped) _state.update { it.copy(pendingCancel = event) } else setRsvp(event.id, true)
    }

    fun confirmCancel() {
        val event = _state.value.pendingCancel ?: return
        _state.update { it.copy(pendingCancel = null) }
        setRsvp(event.id, false)
    }

    fun dismissCancel() = _state.update { it.copy(pendingCancel = null) }

    private fun setRsvp(id: String, going: Boolean) {
        _state.update { s ->
            s.copy(events = s.events.map {
                if (it.id == id) it.copy(rsvped = going, going = it.going + if (going) 1 else -1) else it
            })
        }
        viewModelScope.launch { runCatching { repository.setRsvp(id, going) } }
    }
}
