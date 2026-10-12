package com.univibe.app.features.events

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.univibe.app.app.AppContainer
import com.univibe.app.data.model.CampusEvent
import com.univibe.app.data.model.EventsTab
import com.univibe.app.data.model.RsvpState
import com.univibe.app.data.repository.EventRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

val EventCategories = listOf("All", "Workshop", "Party", "Sports", "Academic")

data class EventsUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val tab: EventsTab = EventsTab.Upcoming,
    val query: String = "",
    val category: String = "All",
    val events: List<CampusEvent> = emptyList(),
    /** Event whose RSVP sheet is open. */
    val rsvpFor: CampusEvent? = null,
    /** Event awaiting "cancel RSVP" confirmation. */
    val pendingCancel: CampusEvent? = null,
) {
    val visible: List<CampusEvent>
        get() = events.asSequence()
            .filter {
                when (tab) {
                    EventsTab.Upcoming -> !it.isPast
                    EventsTab.MyEvents -> it.rsvp != RsvpState.None
                    EventsTab.Past -> it.isPast
                }
            }
            .filter { category == "All" || it.category == category }
            .filter { query.isBlank() || it.title.contains(query, true) || it.location.contains(query, true) }
            .toList()
}

class EventsViewModel(
    private val repository: EventRepository = AppContainer.eventRepository,
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

    /** Quiet reload when returning to the list (e.g. after RSVPing on the details screen). */
    fun refresh() {
        if (_state.value.loading) return
        viewModelScope.launch {
            runCatching { repository.getEvents() }.onSuccess { events -> _state.update { it.copy(events = events, error = null) } }
        }
    }

    fun setTab(tab: EventsTab) = _state.update { it.copy(tab = tab) }
    fun setQuery(query: String) = _state.update { it.copy(query = query) }
    fun setCategory(category: String) = _state.update { it.copy(category = category) }

    fun openRsvp(event: CampusEvent) = _state.update { it.copy(rsvpFor = event) }
    fun closeRsvp() = _state.update { it.copy(rsvpFor = null) }

    /** Leaving "Going" asks for confirmation; every other change applies immediately. */
    fun chooseRsvp(newState: RsvpState) {
        val event = _state.value.rsvpFor ?: return
        _state.update { it.copy(rsvpFor = null) }
        if (event.rsvp == RsvpState.Going && newState == RsvpState.None) {
            _state.update { it.copy(pendingCancel = event) }
        } else {
            applyRsvp(event.id, newState)
        }
    }

    fun confirmCancel() {
        val event = _state.value.pendingCancel ?: return
        _state.update { it.copy(pendingCancel = null) }
        applyRsvp(event.id, RsvpState.None)
    }

    fun dismissCancel() = _state.update { it.copy(pendingCancel = null) }

    private fun applyRsvp(id: String, newState: RsvpState) {
        _state.update { s ->
            s.copy(events = s.events.map { e ->
                if (e.id != id) e else {
                    var going = e.going
                    var interested = e.interested
                    if (e.rsvp == RsvpState.Going) going--
                    if (e.rsvp == RsvpState.Interested) interested--
                    if (newState == RsvpState.Going) going++
                    if (newState == RsvpState.Interested) interested++
                    e.copy(rsvp = newState, going = going, interested = interested)
                }
            })
        }
        viewModelScope.launch { runCatching { repository.setRsvp(id, newState) } }
    }
}
