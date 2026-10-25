package com.univibe.app.features.moderation

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.univibe.app.app.AppContainer
import com.univibe.app.core.formatDateTime
import com.univibe.app.core.keyedViewModel
import com.univibe.app.data.model.EventReportItem
import com.univibe.app.data.model.ModeratorEvent
import com.univibe.app.data.repository.EventRepository
import com.univibe.app.ui.components.EmptyState
import com.univibe.app.ui.components.ErrorState
import com.univibe.app.ui.components.LoadingSkeleton
import com.univibe.app.ui.components.VibeButton
import com.univibe.app.ui.components.VibeButtonStyle
import com.univibe.app.ui.components.VibeCard
import com.univibe.app.ui.components.VibeIconButton
import com.univibe.app.ui.components.VibeIcons
import com.univibe.app.ui.components.VibeTextField
import com.univibe.app.ui.components.VibeTopBar
import com.univibe.app.ui.theme.Spacing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ---------- the queue of open event reports

data class EventReportsUiState(val loading: Boolean = true, val error: Boolean = false, val items: List<EventReportItem> = emptyList())

class EventReportsViewModel(private val repository: EventRepository = AppContainer.eventRepository) : ViewModel() {
    private val _state = MutableStateFlow(EventReportsUiState())
    val state: StateFlow<EventReportsUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        _state.update { it.copy(loading = true, error = false) }
        viewModelScope.launch {
            try { _state.update { it.copy(loading = false, items = repository.eventReports()) } }
            catch (e: Exception) { _state.update { it.copy(loading = false, error = true) } }
        }
    }
}

private fun reasonLabel(code: String) = when (code) {
    "spam" -> "Spam or scam"; "harassment" -> "Harassment"; "hate" -> "Hate"; "sexual" -> "Sexual content"
    "violence" -> "Violence"; "misinformation" -> "False information"; else -> "Something else"
}

/** Moderators only. Open reports about events, newest first. Tapping one opens the event for review. */
@Composable
fun EventReportsScreen(onBack: () -> Unit, onOpenEvent: (String) -> Unit) {
    val viewModel = keyedViewModel("event-reports") { EventReportsViewModel() }
    val s by viewModel.state.collectAsStateWithLifecycle()
    // Coming back from a review: the event you just acted on is gone from this list.
    LaunchedEffect(Unit) { if (!s.loading) viewModel.load() }
    Column(Modifier.fillMaxSize()) {
        VibeTopBar("Reported events", compactTitle = true, navigationIcon = { VibeIconButton(VibeIcons.Back, "Back", onBack) })
        when {
            s.loading -> LoadingSkeleton(Modifier.padding(Spacing.md), itemCount = 3)
            s.error -> ErrorState("We couldn't load the reports.", onRetry = viewModel::load)
            s.items.isEmpty() -> EmptyState(VibeIcons.Shield, "Nothing to review", "Open reports about events will show up here.")
            else -> LazyColumn(contentPadding = PaddingValues(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                items(s.items, key = { it.id }) { r ->
                    VibeCard(onClick = { onOpenEvent(r.eventId) }) {
                        Text(r.title, style = MaterialTheme.typography.titleSmall)
                        Text(
                            listOf(reasonLabel(r.reason), if (r.reportCount > 1) "${r.reportCount} reports" else "1 report", formatDateTime(r.createdAtMillis)).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (r.details.isNotBlank()) Text(r.details, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = Spacing.xs))
                    }
                }
            }
        }
    }
}

// ---------- reviewing one event

data class ModeratorEventUiState(val loading: Boolean = true, val error: Boolean = false, val event: ModeratorEvent? = null, val working: Boolean = false, val notice: String? = null)

class ModeratorEventViewModel(private val eventId: String, private val repository: EventRepository = AppContainer.eventRepository) : ViewModel() {
    private val _state = MutableStateFlow(ModeratorEventUiState())
    val state: StateFlow<ModeratorEventUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        _state.update { it.copy(loading = true, error = false) }
        viewModelScope.launch {
            try { _state.update { it.copy(loading = false, event = repository.moderatorEvent(eventId)) } }
            catch (e: Exception) { _state.update { it.copy(loading = false, error = true) } }
        }
    }

    fun setHidden(hide: Boolean, note: String, userMessage: String) {
        if (_state.value.working) return
        _state.update { it.copy(working = true) }
        viewModelScope.launch {
            try {
                repository.setEventHidden(eventId, hide, note.trim(), userMessage.trim())
                _state.update { it.copy(working = false, notice = if (hide) "Event hidden" else "Event restored", event = it.event?.copy(hidden = hide)) }
            } catch (e: Exception) { _state.update { it.copy(working = false, notice = e.message?.takeIf { m -> m.length < 90 } ?: "That didn't work. Try again.") } }
        }
    }

    fun consumeNotice() = _state.update { it.copy(notice = null) }
}

/**
 * Moderators only. Shows the event and who runs it, then hides it from everyone but the organizer, or brings it back.
 * The internal note stays with moderators; the message is what the organizer reads in Account status.
 */
@Composable
fun ModeratorEventScreen(eventId: String, onBack: () -> Unit) {
    val viewModel = keyedViewModel("moderator-event-$eventId") { ModeratorEventViewModel(eventId) }
    val s by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var note by rememberSaveable { mutableStateOf("") }
    var message by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(s.notice) { s.notice?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show(); viewModel.consumeNotice() } }
    Column(Modifier.fillMaxSize()) {
        VibeTopBar("Review event", compactTitle = true, navigationIcon = { VibeIconButton(VibeIcons.Back, "Back", onBack) })
        val e = s.event
        when {
            s.loading -> LoadingSkeleton(Modifier.padding(Spacing.md), itemCount = 2)
            s.error || e == null -> ErrorState("We couldn't load this event.", onRetry = viewModel::load)
            else -> Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(e.title, style = MaterialTheme.typography.titleLarge)
                Text(
                    listOfNotNull(e.status.replaceFirstChar { it.uppercase() }, if (e.hidden) "Hidden" else null, "${e.reportCount} report${if (e.reportCount == 1) "" else "s"}").joinToString(" · "),
                    style = MaterialTheme.typography.labelLarge, color = if (e.hidden) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (e.venue.isNotBlank()) Text(e.venue, style = MaterialTheme.typography.bodyMedium)
                if (e.description.isNotBlank()) Text(e.description, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Organizer: ${e.organizerName} (@${e.organizerHandle})" + if (e.organizerSuspended) " · suspended" else "",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                VibeTextField(note, { if (it.length <= 500) note = it }, label = "Internal note (moderators only)", singleLine = false, minLines = 2)
                VibeTextField(message, { if (it.length <= 300) message = it }, label = "Message to the organizer (shown in Account status)", singleLine = false, minLines = 2)
            }
        }
        if (e != null && !s.loading && !s.error) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(Spacing.md)) {
                if (e.hidden) VibeButton("Restore event", { viewModel.setHidden(false, note, message) }, Modifier.fillMaxWidth(), enabled = !s.working, loading = s.working)
                else VibeButton("Hide event", { viewModel.setHidden(true, note, message) }, Modifier.fillMaxWidth(), style = VibeButtonStyle.Primary, enabled = !s.working, loading = s.working)
            }
        }
    }
}
