package com.univibe.app.features.discover

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.univibe.app.app.AppContainer
import com.univibe.app.domain.auth.AuthState
import com.univibe.app.data.model.CampusEvent
import com.univibe.app.data.model.EventCategoryInfo
import com.univibe.app.data.model.EventCategoryOptions
import com.univibe.app.data.model.EventDiscoverFilter
import com.univibe.app.data.model.EventSection
import com.univibe.app.data.model.RsvpState
import com.univibe.app.data.repository.EventRepository
import com.univibe.app.ui.components.EmptyState
import com.univibe.app.ui.components.ErrorState
import com.univibe.app.ui.components.EventCard
import com.univibe.app.ui.components.InterestChip
import com.univibe.app.ui.components.SponsoredSlot
import com.univibe.app.ui.components.LoadingSkeleton
import com.univibe.app.ui.components.VibeIcons
import com.univibe.app.ui.components.VibePullToRefresh
import com.univibe.app.ui.components.rememberBottomClearance
import com.univibe.app.ui.theme.Spacing
import kotlinx.coroutines.Job
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
    /** True when the server ranks, filters and sections events; false means the older on-device filtering below. */
    val serverMode: Boolean = false,
    val sections: List<EventSection> = emptyList(),
    val categories: List<EventCategoryInfo> = emptyList(),
    val filter: EventDiscoverFilter = EventDiscoverFilter(),
    /** Server results while a filter is on. */
    val results: List<CampusEvent> = emptyList(),
    val nextCursor: String? = null,
    val loadingMore: Boolean = false,
    val canSave: Boolean = false,
    /** True when the student has a campus, so a "My campus" filter makes sense. */
    val hasCampus: Boolean = false,
) {
    /** Nothing to show yet, in whichever mode is active. */
    val isEmpty: Boolean get() = if (!serverMode) false else if (filter.isActive) results.isEmpty() else sections.all { it.events.isEmpty() }

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

    private var loadJob: Job? = null

    /** Loads once, the first time the Events category is opened. */
    fun ensureLoaded() { if (!_state.value.loaded && !_state.value.loading) load() }

    private fun hasContent(s: DiscoverEventsState) = if (s.serverMode) !s.isEmpty else s.events.isNotEmpty()

    fun load() {
        loadJob?.cancel()
        val server = repository.supportsDiscover
        _state.update { it.copy(serverMode = server, canSave = repository.supportsSaved, hasCampus = studentHasCampus(), loading = !hasContent(it), refreshing = hasContent(it), error = null) }
        loadJob = viewModelScope.launch {
            try {
                if (server) {
                    val categories = _state.value.categories.ifEmpty { repository.eventCategories() }
                    val filter = _state.value.filter
                    if (filter.isActive) {
                        val page = repository.discoverEvents(filter)
                        _state.update { it.copy(categories = categories, results = page.events, nextCursor = page.nextCursor, loading = false, refreshing = false, loaded = true) }
                    } else {
                        val sections = repository.discoverSections()
                        _state.update { it.copy(categories = categories, sections = sections, loading = false, refreshing = false, loaded = true) }
                    }
                } else {
                    val events = repository.getEvents()
                    _state.update { it.copy(events = events, loading = false, refreshing = false, loaded = true) }
                }
            } catch (e: Exception) {
                val message = if (e is IOException) "No connection. Check your internet and try again." else "We couldn't load events right now."
                _state.update { if (!hasContent(it)) it.copy(loading = false, refreshing = false, error = message) else it.copy(loading = false, refreshing = false, notice = "Couldn't refresh events.") }
            }
        }
    }

    fun loadMore() {
        val s = _state.value
        val cursor = s.nextCursor ?: return
        if (!s.serverMode || s.loadingMore || !s.filter.isActive) return
        _state.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            try {
                val page = repository.discoverEvents(s.filter, cursor)
                // The server re-ranks each page, so anything already shown is dropped here.
                _state.update { cur ->
                    val known = cur.results.map { it.id }.toSet()
                    cur.copy(results = cur.results + page.events.filter { it.id !in known }, nextCursor = page.nextCursor, loadingMore = false)
                }
            } catch (e: Exception) {
                _state.update { it.copy(loadingMore = false, nextCursor = null, notice = "Couldn't load more events.") }
            }
        }
    }

    private fun applyFilter(change: (EventDiscoverFilter) -> EventDiscoverFilter) {
        _state.update { it.copy(filter = change(it.filter), results = emptyList(), nextCursor = null) }
        load()
    }

    fun toggleCategoryCode(code: String) = applyFilter { f -> f.copy(categories = if (code in f.categories) f.categories - code else f.categories + code) }
    fun toggleOnline() = applyFilter { it.copy(onlineOnly = !it.onlineOnly) }
    fun toggleRegistrationOpen() = applyFilter { it.copy(registrationOpen = !it.registrationOpen) }
    fun toggleSavedOnly() = applyFilter { it.copy(savedOnly = !it.savedOnly) }
    fun toggleMyCampus() = applyFilter { it.copy(myCampus = !it.myCampus) }

    private fun studentHasCampus() = ((AppContainer.authRepository.session.value as? AuthState.Authenticated)?.user?.campusId).orEmpty().isNotEmpty()

    /** Hides one event from recommendations. It disappears at once and comes back if the server refuses. */
    fun notInterested(event: CampusEvent) {
        _state.update { s ->
            s.copy(
                sections = s.sections.map { sec -> sec.copy(events = sec.events.filter { it.id != event.id }) }.filter { it.events.isNotEmpty() },
                results = s.results.filter { it.id != event.id },
                notice = "Hidden from your recommendations.",
            )
        }
        viewModelScope.launch {
            try { repository.setNotInterested(event.id, true) } catch (e: Exception) {
                _state.update { it.copy(notice = "Couldn't hide that event.") }
                load()
            }
        }
    }

    fun setWindow(w: EventWhen) {
        _state.update { it.copy(window = w) }
        if (!_state.value.serverMode) return
        val zone = ZoneId.systemDefault()
        // The window only narrows what to ask the server for; the server still decides what is upcoming.
        val to = when (w) {
            EventWhen.Upcoming -> null
            EventWhen.Today -> LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            EventWhen.Week -> System.currentTimeMillis() + 7L * 24 * 3_600_000
        }
        applyFilter { it.copy(toMillis = to) }
    }
    fun setCategory(c: String) = _state.update { it.copy(category = c) }
    fun consumeNotice() = _state.update { it.copy(notice = null) }

    /** Going <-> not going, applied at once and undone with the server's reason if it refuses. */
    fun toggleGoing(event: CampusEvent) {
        val going = event.rsvp != RsvpState.Going
        val next = if (going) RsvpState.Going else RsvpState.None
        fun change(it: CampusEvent) = if (it.id != event.id) it else it.copy(rsvp = next, going = (it.going + if (going) 1 else -1).coerceAtLeast(0))
        _state.update { s ->
            s.copy(
                events = s.events.map(::change), results = s.results.map(::change),
                sections = s.sections.map { sec -> sec.copy(events = sec.events.map(::change)) },
            )
        }
        viewModelScope.launch {
            try { repository.setRsvp(event.id, next) } catch (e: Exception) {
                _state.update { it.copy(notice = e.message?.takeIf { m -> m.length < 90 } ?: "Couldn't save your RSVP.") }
                load()
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
    if (state.serverMode) { ServerEventsList(state, listState, vm, onOpenEvent); return }
    LocalEventsList(state, listState, vm, onOpenEvent)
}

/** One event with the reason it is recommended and a way to say "not interested". */
@Composable
private fun DiscoverEventItem(event: CampusEvent, vm: DiscoverEventsViewModel, onOpenEvent: (String) -> Unit) {
    Column(Modifier.padding(horizontal = Spacing.md)) {
        event.reason?.let {
            Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = Spacing.xs))
        }
        EventCard(
            event,
            // Joining is one tap; leaving goes through the event page so it is never done by accident.
            onRsvpClick = { if (event.rsvp == RsvpState.Going) onOpenEvent(event.id) else vm.toggleGoing(event) },
            onClick = { onOpenEvent(event.id) },
        )
        if (!event.mine && !event.isPast && !event.cancelled) {
            TextButton(onClick = { vm.notInterested(event) }) { Text("Not interested", style = MaterialTheme.typography.labelMedium) }
        }
    }
}

/** Sections and filters come from the server, so what the chips offer is what the server can filter. */
@Composable
private fun ServerEventsList(
    state: DiscoverEventsState,
    listState: androidx.compose.foundation.lazy.LazyListState,
    vm: DiscoverEventsViewModel,
    onOpenEvent: (String) -> Unit,
) {
    VibePullToRefresh(refreshing = state.refreshing, onRefresh = vm::load) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(bottom = rememberBottomClearance()),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            item(key = "filters") {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Row(Modifier.padding(horizontal = Spacing.md), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        EventWhen.entries.forEach { w -> InterestChip(w.label, selected = state.window == w, onClick = { vm.setWindow(w) }) }
                    }
                    LazyRow(contentPadding = PaddingValues(horizontal = Spacing.md), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        if (state.hasCampus) item(key = "campus") { InterestChip("My campus", selected = state.filter.myCampus, onClick = vm::toggleMyCampus) }
                        item(key = "online") { InterestChip("Online", selected = state.filter.onlineOnly, onClick = vm::toggleOnline) }
                        item(key = "open") { InterestChip("Spots open", selected = state.filter.registrationOpen, onClick = vm::toggleRegistrationOpen) }
                        if (state.canSave) item(key = "saved") { InterestChip("Saved", selected = state.filter.savedOnly, onClick = vm::toggleSavedOnly) }
                        items(state.categories, key = { "c_" + it.code }) { c ->
                            InterestChip(c.label, selected = c.code in state.filter.categories, onClick = { vm.toggleCategoryCode(c.code) })
                        }
                    }
                }
            }
            // One paid, labelled card under the filters. It shows nothing when the server has no card to give.
            if (!state.loading && state.error == null && !state.isEmpty) item(key = "sponsored") { SponsoredSlot("discover_events") }
            when {
                state.loading -> item(key = "loading") { Box(Modifier.padding(horizontal = Spacing.md)) { LoadingSkeleton() } }
                state.error != null -> item(key = "error") { Box(Modifier.padding(horizontal = Spacing.md)) { ErrorState(state.error, onRetry = vm::load) } }
                state.isEmpty -> item(key = "empty") {
                    EmptyState(
                        icon = VibeIcons.Events, title = "No events found",
                        message = if (state.filter.isActive) "Nothing matches those filters. Try removing one." else "Nothing is coming up that fits you right now.",
                        modifier = Modifier.padding(horizontal = Spacing.md),
                    )
                }
                state.filter.isActive -> {
                    items(state.results, key = { it.id }) { event -> DiscoverEventItem(event, vm, onOpenEvent) }
                    if (state.nextCursor != null) item(key = "more") {
                        LaunchedEffect(state.nextCursor) { vm.loadMore() }
                        Box(Modifier.padding(horizontal = Spacing.md)) { LoadingSkeleton() }
                    }
                }
                else -> state.sections.forEach { section ->
                    item(key = "h_" + section.key) {
                        Text(section.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs))
                    }
                    items(section.events, key = { section.key + "_" + it.id }) { event -> DiscoverEventItem(event, vm, onOpenEvent) }
                }
            }
        }
    }
}

@Composable
private fun LocalEventsList(
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
