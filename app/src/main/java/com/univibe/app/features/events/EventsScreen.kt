package com.univibe.app.features.events

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.univibe.app.data.model.EventsTab
import com.univibe.app.ui.components.ConfirmDialog
import com.univibe.app.ui.components.EmptyState
import com.univibe.app.ui.components.ErrorState
import com.univibe.app.ui.components.EventCard
import com.univibe.app.ui.components.InterestChip
import com.univibe.app.ui.components.LoadingSkeleton
import com.univibe.app.ui.components.VibeTopBar
import com.univibe.app.ui.theme.Spacing

@Composable
fun EventsScreen(viewModel: EventsViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        VibeTopBar("Events")
        Row(
            Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            InterestChip("Upcoming", selected = state.tab == EventsTab.Upcoming, onClick = { viewModel.setTab(EventsTab.Upcoming) })
            InterestChip("My Events", selected = state.tab == EventsTab.MyEvents, onClick = { viewModel.setTab(EventsTab.MyEvents) })
        }
        when {
            state.loading -> LoadingSkeleton(Modifier.padding(Spacing.md), itemCount = 2)
            state.error != null -> ErrorState(state.error!!, onRetry = viewModel::load)
            state.visible.isEmpty() -> EmptyState(
                icon = Icons.Outlined.CalendarMonth,
                title = "No events yet",
                message = "Events you RSVP to will show up here.",
                actionLabel = "Browse upcoming",
                onAction = { viewModel.setTab(EventsTab.Upcoming) },
            )
            else -> LazyColumn(
                contentPadding = PaddingValues(Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                items(state.visible, key = { it.id }) { event ->
                    EventCard(event, onRsvpClick = { viewModel.onRsvpClick(event) })
                }
            }
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
