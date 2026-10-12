package com.univibe.app.features.events

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.univibe.app.app.AppContainer
import com.univibe.app.data.model.EventDetails
import com.univibe.app.data.model.RsvpState
import com.univibe.app.data.repository.EventRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class EventDetailsUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val notFound: Boolean = false,
    val details: EventDetails? = null,
    /** True while the "leave this event?" confirmation is showing. */
    val confirmLeave: Boolean = false,
)

class EventDetailsViewModel(
    private val eventId: String,
    private val repository: EventRepository = AppContainer.eventRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(EventDetailsUiState())
    val state: StateFlow<EventDetailsUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null, notFound = false) }
            try {
                val details = repository.getEvent(eventId)
                _state.update { it.copy(loading = false, details = details, notFound = details == null) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = "We couldn't load this event right now.") }
            }
        }
    }

    /** Leaving "Going" asks first. Joining is blocked when the event is full or over. */
    fun toggleGoing() {
        val d = _state.value.details ?: return
        when {
            d.event.isPast -> Unit
            d.event.rsvp == RsvpState.Going -> _state.update { it.copy(confirmLeave = true) }
            d.isFull -> Unit
            else -> apply(RsvpState.Going)
        }
    }

    fun toggleInterested() {
        val d = _state.value.details ?: return
        if (d.event.isPast) return
        apply(if (d.event.rsvp == RsvpState.Interested) RsvpState.None else RsvpState.Interested)
    }

    fun confirmLeave() {
        _state.update { it.copy(confirmLeave = false) }
        apply(RsvpState.None)
    }

    fun dismissLeave() = _state.update { it.copy(confirmLeave = false) }

    private fun apply(newState: RsvpState) {
        val d = _state.value.details ?: return
        val e = d.event
        var going = e.going
        var interested = e.interested
        if (e.rsvp == RsvpState.Going) going--
        if (e.rsvp == RsvpState.Interested) interested--
        if (newState == RsvpState.Going) going++
        if (newState == RsvpState.Interested) interested++
        _state.update { it.copy(details = d.copy(event = e.copy(rsvp = newState, going = going, interested = interested))) }
        viewModelScope.launch { runCatching { repository.setRsvp(e.id, newState) } }
    }
}
