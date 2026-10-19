package com.univibe.app.features.discover

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.univibe.app.app.AppContainer
import com.univibe.app.data.model.CampusEvent
import com.univibe.app.data.model.EventCategoryOptions
import com.univibe.app.data.model.RsvpState
import com.univibe.app.data.repository.EventRepository
import com.univibe.app.ui.components.EmptyState
import com.univibe.app.ui.components.ErrorState
import com.univibe.app.ui.components.EventCard
import com.univibe.app.ui.components.InterestChip
import com.univibe.app.ui.components.LoadingSkeleton
import com.univibe.app.ui.components.VibeIcons
import com.univibe.app.ui.components.VibePullToRefresh
import com.univibe.app.ui.components.rememberBottomClearance
import com.univibe.app.ui.theme.Spacing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId

enum class EventWhen(val label: String) { Upcoming("Upcoming"), Today("Today"), Week("This week") }

data class DiscoverEventsState(
    val events: List<CampusEvent> = emptyList(),
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val error: String? = null,
    val loaded: Boolean = false,
    val window: EventWhen = EventWhen.Upcoming,
    /** "All" or one of [EventCategoryOptions]. */
    val category: String = "All",
    val notice: String? = null,
) {
    fun visible(now: Long = System.currentTimeMillis()): List<CampusEvent> {
        val zone = ZoneId.systemDefault()
        val endOfToday = LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val inAWeek = now + 7L * 24 * 3_600_000
        return events.asSequence()
            .filter { !it.isPast && !it.cancelled }
            .filter {
                when (window) {
                    EventWhen.Upcoming -> true
                    EventWhen.Today -> it.startMillis < endOfToday // starts today, or is already on
                    EventWhen.Week -> it.startMillis < inAWeek
                }
            }
            .filter { category == "All" || it.category == category }
            .sortedBy { it.startMillis }
            .toList()
    }
}

/** Upcoming events inside Discover. The events themselves, and their RSVPs, are the Events tab's: same repository. */
class DiscoverEventsViewModel(
    private val repository: EventRepository = AppContainer.eventRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(DiscoverEventsState())
    val state: StateFlow<DiscoverEventsState> = _state.asStateFlow()

    /** Loads once, the first time the Events category is opened. */
    fun ensureLoaded() { if (!_state.value.loaded && !_state.value.loading) load() }

    fun load() {
        _state.update { it.copy(loading = it.events.isEmpty(), refreshing = it.events.isNotEmpty(), error = null) }
        viewModelScope.launch {
            try {
                val events = repository.getEvents()
                _state.update { it.copy(events = events, loading = false, refreshing = false, loaded = true) }
            } catch (e: Exception) {
                val message = if (e is IOException) "No connection. Check your internet and try again." else "We couldn't load events right now."
                _state.update { if (it.events.isEmpty()) it.copy(loading = false, refreshing = false, error = message) else it.copy(loading = false, refreshing = false, notice = "Couldn't refresh events.") }
            }
        }
    }

    fun setWindow(w: EventWhen) = _state.update { it.copy(window = w) }
    fun setCategory(c: String) = _state.update { it.copy(category = c) }
    fun consumeNotice() = _state.update { it.copy(notice = null) }

    /** Going <-> not going, applied at once and undone with the server's reason if it refuses. */
    fun toggleGoing(event: CampusEvent) {
        val going = event.rsvp != RsvpState.Going
        val next = if (going) RsvpState.Going else RsvpState.None
        _state.update { s ->
            s.copy(events = s.events.map {
                if (it.id != event.id) it else it.copy(rsvp = next, going = (it.going + if (going) 1 else -1).coerceAtLeast(0))
            })
        }
        viewModelScope.launch {
            try { repository.setRsvp(event.id, next) } catch (e: Exception) {
                _state.update { it.copy(notice = e.message?.takeIf { m -> m.length < 90 } ?: "Couldn't save your RSVP.") }
                runCatching { repository.getEvents() }.onSuccess { events -> _state.update { it.copy(events = events) } }
            }
        }
    }
}

@Composable
internal fun DiscoverEventsList(
    state: DiscoverEventsState,
    listState: androidx.compose.foundation.lazy.LazyListState,
    vm: DiscoverEventsViewModel,
    onOpenEvent: (String) -> Unit,
) {
    val visible = state.visible()
    VibePullToRefresh(refreshing = state.refreshing, onRefresh = vm::load) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(bottom = rememberBottomClearance()),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            item(key = "filters") {
                androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Row(Modifier.padding(horizontal = Spacing.md), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        EventWhen.entries.forEach { w -> InterestChip(w.label, selected = state.window == w, onClick = { vm.setWindow(w) }) }
                    }
                    LazyRow(contentPadding = PaddingValues(horizontal = Spacing.md), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        items(listOf("All") + EventCategoryOptions, key = { it }) { c ->
                            InterestChip(c, selected = state.category == c, onClick = { vm.setCategory(c) })
                        }
                    }
                }
            }
            when {
                state.loading -> item(key = "loading") { Box(Modifier.padding(horizontal = Spacing.md)) { LoadingSkeleton() } }
                state.error != null -> item(key = "error") { Box(Modifier.padding(horizontal = Spacing.md)) { ErrorState(state.error, onRetry = vm::load) } }
                visible.isEmpty() -> item(key = "empty") {
                    EmptyState(
                        icon = VibeIcons.Events, title = "No events found",
                        message = "Nothing matches right now. Try another day range or category.",
                        modifier = Modifier.padding(horizontal = Spacing.md),
                    )
                }
                else -> items(visible, key = { it.id }) { event ->
                    Box(Modifier.padding(horizontal = Spacing.md)) {
                        EventCard(
                            event,
                            // Joining is one tap; leaving goes through the event page so it is never done by accident.
                            onRsvpClick = { if (event.rsvp == RsvpState.Going) onOpenEvent(event.id) else vm.toggleGoing(event) },
                            onClick = { onOpenEvent(event.id) },
                        )
                    }
                }
            }
        }
    }
}
