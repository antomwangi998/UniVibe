package com.univibe.app.features.events

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.univibe.app.core.keyedViewModel
import com.univibe.app.data.model.EventDetails
import com.univibe.app.data.model.RsvpState
import com.univibe.app.ui.components.ConfirmDialog
import com.univibe.app.ui.components.EmptyState
import com.univibe.app.ui.components.ErrorState
import com.univibe.app.ui.components.LoadingSkeleton
import com.univibe.app.ui.components.StudentAvatar
import com.univibe.app.ui.components.VibeButton
import com.univibe.app.ui.components.VibeButtonStyle
import com.univibe.app.ui.components.VibeCard
import com.univibe.app.ui.components.VibeIconButton
import com.univibe.app.ui.components.VibeTopBar
import com.univibe.app.ui.components.accentGradient
import com.univibe.app.ui.theme.Spacing

@Composable
fun EventDetailsScreen(eventId: String, onBack: () -> Unit) {
    val viewModel = keyedViewModel("event-$eventId") { EventDetailsViewModel(eventId) }
    val s by viewModel.state.collectAsStateWithLifecycle()
    val details = s.details

    if (details == null) {
        Column(Modifier.fillMaxSize()) {
            VibeTopBar("Event", compactTitle = true, navigationIcon = { BackButton(onBack) })
            when {
                s.loading -> LoadingSkeleton(Modifier.padding(Spacing.md), itemCount = 2)
                s.notFound -> EmptyState(Icons.Outlined.CalendarMonth, "Event not found", "It may have been removed, or the link is wrong.", actionLabel = "Back to events", onAction = onBack)
                else -> ErrorState(s.error ?: "Something went wrong.", viewModel::load)
            }
        }
        return
    }

    val event = details.event
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Hero(details)
                Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    Text(event.title, style = MaterialTheme.typography.headlineSmall)
                    HostRow(details)
                    InfoCard(details)
                    if (details.description.isNotBlank()) {
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                            Text("About this event", style = MaterialTheme.typography.titleMedium)
                            Text(details.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (details.agenda.isNotEmpty()) Agenda(details)
                }
            }
            ActionBar(details, onGoing = viewModel::toggleGoing, onInterested = viewModel::toggleInterested)
        }
        // Floating controls over the hero.
        val context = LocalContext.current
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = Spacing.md, vertical = Spacing.sm),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            BackButton(onBack)
            VibeIconButton(Icons.Outlined.Share, "Share event", {
                val text = "${event.title}\n${event.timeLabel}\n${event.location}"
                val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }
                context.startActivity(Intent.createChooser(send, "Share event"))
            })
        }
    }

    if (s.confirmLeave) {
        ConfirmDialog(
            title = "Cancel RSVP?",
            message = "You won't be listed as going to ${event.title}. You can RSVP again any time.",
            confirmLabel = "Cancel RSVP",
            dismissLabel = "Keep RSVP",
            destructive = true,
            onConfirm = viewModel::confirmLeave,
            onDismiss = viewModel::dismissLeave,
        )
    }
}

@Composable
private fun BackButton(onBack: () -> Unit) = VibeIconButton(Icons.AutoMirrored.Outlined.ArrowBack, "Back", onBack)

@Composable
private fun Hero(details: EventDetails) {
    val colors = MaterialTheme.colorScheme
    val event = details.event
    Box(
        Modifier.fillMaxWidth().height(230.dp)
            .clip(RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp))
            .background(Brush.linearGradient(accentGradient(event.accent))),
    ) {
        Surface(
            Modifier.align(Alignment.BottomStart).padding(Spacing.md),
            shape = RoundedCornerShape(14.dp), color = colors.surface,
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(event.month, style = MaterialTheme.typography.labelSmall, color = colors.primary)
                Text(event.day, style = MaterialTheme.typography.titleLarge)
            }
        }
        Surface(
            Modifier.align(Alignment.BottomEnd).padding(Spacing.md),
            shape = CircleShape, color = colors.primaryContainer, contentColor = colors.onPrimaryContainer,
        ) {
            Text(event.category, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
        }
    }
}

@Composable
private fun HostRow(details: EventDetails) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StudentAvatar(details.host, size = 40.dp, accent = details.hostAccent)
        Spacer(Modifier.width(Spacing.sm + 4.dp))
        Column {
            Text("Hosted by", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(details.host, style = MaterialTheme.typography.titleSmall)
        }
    }
}

@Composable
private fun InfoCard(details: EventDetails) {
    val event = details.event
    VibeCard {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            InfoLine(Icons.Outlined.Schedule, "When", event.timeLabel)
            InfoLine(Icons.Outlined.Place, "Where", event.location)
            InfoLine(Icons.Outlined.Groups, "Attending", "${event.going} going · ${event.interested} interested")
            details.capacity?.let { capacity ->
                val progress = (event.going.toFloat() / capacity).coerceIn(0f, 1f)
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                    Text(
                        when {
                            event.isPast -> "$capacity places"
                            details.spotsLeft == 0 -> "Full"
                            else -> "${details.spotsLeft} of $capacity places left"
                        },
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun InfoLine(icon: ImageVector, label: String, value: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(Spacing.sm + 4.dp))
        Column {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun Agenda(details: EventDetails) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text("Schedule", style = MaterialTheme.typography.titleMedium)
        VibeCard {
            details.agenda.forEachIndexed { index, item ->
                if (index > 0) HorizontalDivider(Modifier.padding(vertical = Spacing.sm), color = MaterialTheme.colorScheme.outlineVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(item.time, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(84.dp))
                    Text(item.title, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun ActionBar(details: EventDetails, onGoing: () -> Unit, onInterested: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val event = details.event
    Surface(color = colors.background) {
        Column {
        HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.55f))
        Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(Spacing.md)) {
            if (event.isPast) {
                Text("This event has ended.", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, modifier = Modifier.align(Alignment.Center))
            } else {
                val going = event.rsvp == RsvpState.Going
                val interested = event.rsvp == RsvpState.Interested
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    VibeButton(
                        text = "Interested", onClick = onInterested,
                        style = if (interested) VibeButtonStyle.Primary else VibeButtonStyle.Secondary,
                        leadingIcon = if (interested) Icons.Outlined.Check else Icons.Outlined.StarOutline,
                        modifier = Modifier.weight(1f).semantics { selected = interested },
                    )
                    VibeButton(
                        text = if (details.isFull) "Event full" else "Going", onClick = onGoing,
                        style = if (going) VibeButtonStyle.Primary else VibeButtonStyle.Secondary,
                        enabled = !details.isFull,
                        leadingIcon = if (going) Icons.Outlined.Check else null,
                        modifier = Modifier.weight(1f).semantics { selected = going },
                    )
                }
            }
        }
    }
    }
}
