package com.univibe.app.features.events

import com.univibe.app.ui.components.CampusScopeBar
import com.univibe.app.ui.components.VibePullToRefresh
import com.univibe.app.app.AppContainer
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import com.univibe.app.ui.components.VibeIcons
import com.univibe.app.ui.components.rememberBottomClearance
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.univibe.app.data.model.EventsTab
import com.univibe.app.data.model.RsvpState
import com.univibe.app.ui.components.ConfirmDialog
import com.univibe.app.ui.components.EmptyState
import com.univibe.app.ui.components.ErrorState
import com.univibe.app.ui.components.EventCard
import com.univibe.app.ui.components.InterestChip
import com.univibe.app.ui.components.LoadingSkeleton
import com.univibe.app.ui.components.VibeBottomSheet
import com.univibe.app.ui.components.VibeTextField
import com.univibe.app.ui.components.VibeIconButton
import com.univibe.app.ui.components.VibeTopBar
import com.univibe.app.ui.theme.Spacing
import com.univibe.app.navigation.rememberTabListState

@Composable
fun EventsScreen(onOpenEvent: (String) -> Unit = {}, onCreateEvent: () -> Unit = {}, viewModel: EventsViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    Column(Modifier.fillMaxSize()) {
        VibeTopBar("Events", actions = { VibeIconButton(VibeIcons.Add, "Create event", onCreateEvent) })
        VibeTextField(
            value = state.query,
            onValueChange = viewModel::setQuery,
            placeholder = "Search events",
            leadingIcon = VibeIcons.Search,
            modifier = Modifier.padding(horizontal = Spacing.md),
        )
        CampusScopeBar(Modifier.padding(top = Spacing.xs))
        Row(
            Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            InterestChip("Upcoming", selected = state.tab == EventsTab.Upcoming, onClick = { viewModel.setTab(EventsTab.Upcoming) })
            InterestChip("My Events", selected = state.tab == EventsTab.MyEvents, onClick = { viewModel.setTab(EventsTab.MyEvents) })
            if (viewModel.supportsSaving) InterestChip("Saved", selected = state.tab == EventsTab.Saved, onClick = { viewModel.setTab(EventsTab.Saved) })
            if (state.drafts.isNotEmpty()) InterestChip("Drafts", selected = state.tab == EventsTab.Drafts, onClick = { viewModel.setTab(EventsTab.Drafts) })
            InterestChip("Past", selected = state.tab == EventsTab.Past, onClick = { viewModel.setTab(EventsTab.Past) })
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = Spacing.md),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            items(EventCategories, key = { it }) { c ->
                InterestChip(c, selected = state.category == c, onClick = { viewModel.setCategory(c) })
            }
        }
        if (state.tab == EventsTab.Past) {
            Text(
                "Past events are removed 7 days after they end.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
            )
        }
        when {
            state.loading -> LoadingSkeleton(Modifier.padding(Spacing.md), itemCount = 2)
            state.error != null -> ErrorState(state.error!!, onRetry = viewModel::load)
            state.visible.isEmpty() -> EmptyState(
                icon = VibeIcons.Events,
                title = when (state.tab) {
                    EventsTab.MyEvents -> "No RSVPs yet"
                    EventsTab.Saved -> "Nothing saved yet"
                    EventsTab.Drafts -> "No drafts"
                    EventsTab.Past -> "No past events"
                    EventsTab.Upcoming -> "No events found"
                },
                message = when (state.tab) {
                    EventsTab.MyEvents -> "Events you mark as Going will show up here."
                    EventsTab.Saved -> "Tap the bookmark on an event to keep it here."
                    EventsTab.Drafts -> "Events you save as drafts stay private until you publish them."
                    EventsTab.Past -> "Events you attended will appear here."
                    EventsTab.Upcoming -> "Try a different search or category."
                },
                actionLabel = if (state.tab == EventsTab.MyEvents) "Browse upcoming" else null,
                onAction = { viewModel.setTab(EventsTab.Upcoming) },
            )
            else -> VibePullToRefresh(refreshing = state.refreshing, onRefresh = viewModel::pullRefresh) { LazyColumn(
                state = rememberTabListState("events"),
                contentPadding = PaddingValues(start = Spacing.md, end = Spacing.md, top = Spacing.md, bottom = rememberBottomClearance()),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                items(state.visible, key = { it.id }) { event ->
                    EventCard(
                        event, onRsvpClick = { viewModel.openRsvp(event) }, onClick = { onOpenEvent(event.id) },
                        onSaveClick = if (viewModel.supportsSaving && !event.draft) ({ viewModel.toggleSaved(event) }) else null,
                    )
                }
            } }
        }
    }

    val context = LocalContext.current
    LaunchedEffect(state.notice) {
        state.notice?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.consumeNotice()
        }
    }

    state.rsvpFor?.let { event ->
        VibeBottomSheet(onDismiss = viewModel::closeRsvp) {
            Text(event.title, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.size(Spacing.sm))
            RsvpOption(VibeIcons.Check, "Going", event.rsvp == RsvpState.Going) { viewModel.chooseRsvp(RsvpState.Going) }
            if (AppContainer.eventRepository.supportsInterested) {
                RsvpOption(VibeIcons.Star, "Interested", event.rsvp == RsvpState.Interested) { viewModel.chooseRsvp(RsvpState.Interested) }
            }
            RsvpOption(VibeIcons.Close, "Not going", event.rsvp == RsvpState.None) { viewModel.chooseRsvp(RsvpState.None) }
        }
    }

    state.pendingCancel?.let { event ->
        ConfirmDialog(
            title = "Cancel RSVP?",
            message = "You won't be listed as going to ${event.title}. You can RSVP again any time.",
            confirmLabel = "Cancel RSVP",
            dismissLabel = "Keep RSVP",
            destructive = true,
            onConfirm = viewModel::confirmCancel,
            onDismiss = viewModel::dismissCancel,
        )
    }
}

@Composable
internal fun RsvpOption(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(Spacing.md))
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (selected) Icon(VibeIcons.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary)
    }
}
