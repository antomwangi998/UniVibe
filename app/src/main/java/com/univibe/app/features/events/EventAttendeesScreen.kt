package com.univibe.app.features.events

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.univibe.app.ui.components.VerifiedName
import com.univibe.app.app.AppContainer
import com.univibe.app.core.keyedViewModel
import com.univibe.app.data.model.EventAttendee
import com.univibe.app.data.repository.EventRepository
import com.univibe.app.ui.components.EmptyState
import com.univibe.app.ui.components.ErrorState
import com.univibe.app.ui.components.LoadingSkeleton
import com.univibe.app.ui.components.StudentAvatar
import com.univibe.app.ui.components.VibeIconButton
import com.univibe.app.ui.components.VibeIcons
import com.univibe.app.ui.components.VibeTopBar
import com.univibe.app.ui.theme.Spacing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AttendeesUiState(
    val loading: Boolean = true, val error: Boolean = false, val forbidden: Boolean = false, val people: List<EventAttendee> = emptyList(),
    val nextCursor: String? = null, val loadingMore: Boolean = false, val moreFailed: Boolean = false,
)

class EventAttendeesViewModel(private val eventId: String, private val repository: EventRepository = AppContainer.eventRepository) : ViewModel() {
    private val _state = MutableStateFlow(AttendeesUiState())
    val state: StateFlow<AttendeesUiState> = _state.asStateFlow()

    init { load() }

    /** Fetches the next 50 guests. A failure keeps what is on screen and shows a retry row instead of an error page. */
    fun loadMore() {
        val s = _state.value
        val cursor = s.nextCursor ?: return
        if (s.loadingMore) return
        _state.update { it.copy(loadingMore = true, moreFailed = false) }
        viewModelScope.launch {
            try {
                val page = repository.attendees(eventId, cursor)
                // A guest can appear on two pages if people join while you scroll, so duplicates are dropped.
                _state.update { st -> st.copy(loadingMore = false, people = (st.people + page.items).distinctBy { it.student.id }, nextCursor = page.nextCursor) }
            } catch (e: Exception) { _state.update { it.copy(loadingMore = false, moreFailed = true) } }
        }
    }

    fun load() {
        _state.update { it.copy(loading = true, error = false) }
        viewModelScope.launch {
            try {
                val page = repository.attendees(eventId)
                _state.update { it.copy(loading = false, people = page.items, nextCursor = page.nextCursor) }
            } catch (e: Exception) {
                // The server says "attendee list is not available" when the organizer keeps it private; show that, not a generic error.
                val hidden = e.message?.contains("not available", ignoreCase = true) == true
                _state.update { it.copy(loading = false, error = !hidden, forbidden = hidden) }
            }
        }
    }
}

/** Who is going. The organizer also sees who has checked in. */
@Composable
fun EventAttendeesScreen(eventId: String, onBack: () -> Unit, onOpenStudent: (String) -> Unit) {
    val viewModel = keyedViewModel("event-attendees-$eventId") { EventAttendeesViewModel(eventId) }
    val s by viewModel.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        VibeTopBar("Who's going", compactTitle = true, navigationIcon = { VibeIconButton(VibeIcons.Back, "Back", onBack) })
        when {
            s.loading -> LoadingSkeleton(Modifier.padding(Spacing.md), itemCount = 4)
            s.forbidden -> EmptyState(VibeIcons.Events, "List not available", "The organizer keeps the guest list private.")
            s.error -> ErrorState("We couldn't load the guest list.", onRetry = viewModel::load)
            s.people.isEmpty() -> EmptyState(VibeIcons.Events, "Nobody yet", "People who register will show up here.")
            else -> LazyColumn(contentPadding = PaddingValues(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                items(s.people, key = { it.student.id }) { p ->
                    Row(
                        Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "Open profile") { onOpenStudent(p.student.id) }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                    ) {
                        StudentAvatar(p.student.name, size = 44.dp, accent = p.student.accent, imagePath = p.student.avatarUrl, look = p.student.look)
                        Column(Modifier.weight(1f)) {
                            VerifiedName(p.student.name, p.student.verified, look = p.student.look, style = MaterialTheme.typography.titleSmall)
                            Text(p.student.handle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (p.checkedIn == true) Text("Checked in", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
                if (s.nextCursor != null) item(key = "more") {
                    // Showing this row asks for the next page, so scrolling to the end keeps loading.
                    if (s.moreFailed) TextButton(onClick = viewModel::loadMore) { Text("Couldn't load more. Tap to retry") }
                    else { LaunchedEffect(s.nextCursor) { viewModel.loadMore() }; Text("Loading…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
}
