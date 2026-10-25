package com.univibe.app.features.events

import com.univibe.app.app.AppContainer
import com.univibe.app.ui.components.VibeIcons
import android.content.ActivityNotFoundException
import android.content.Context
import android.provider.CalendarContract
import android.widget.Toast
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
import com.univibe.app.ui.components.VibeBottomSheet
import com.univibe.app.features.chats.SheetRow
import com.univibe.app.data.model.EventStatus
import com.univibe.app.data.model.CampusEvent
import com.univibe.app.core.formatDate
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.EventBusy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
import com.univibe.app.ui.components.VibeButton
import com.univibe.app.ui.components.EmptyState
import com.univibe.app.ui.components.ErrorState
import com.univibe.app.ui.components.LoadingSkeleton
import com.univibe.app.ui.components.StudentAvatar
import com.univibe.app.ui.components.VibeButton
import com.univibe.app.ui.components.VibeButtonStyle
import com.univibe.app.ui.components.VibeCard
import com.univibe.app.ui.components.VibeIconButton
import com.univibe.app.ui.components.VenueMap
import com.univibe.app.ui.components.VibeTopBar
import com.univibe.app.ui.components.openInMaps
import com.univibe.app.ui.components.accentGradient
import com.univibe.app.ui.theme.Spacing

@Composable
fun EventDetailsScreen(eventId: String, onBack: () -> Unit, onEdit: () -> Unit = {}, onOpenAttendees: () -> Unit = {}, onModerate: () -> Unit = {}) {
    val viewModel = keyedViewModel("event-$eventId") { EventDetailsViewModel(eventId) }
    val s by viewModel.state.collectAsStateWithLifecycle()
    val details = s.details
    val ctx = LocalContext.current
    LaunchedEffect(s.notice) { s.notice?.let { Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show(); viewModel.consumeNotice() } }
    LaunchedEffect(s.deleted) { if (s.deleted) onBack() }

    if (details == null) {
        Column(Modifier.fillMaxSize()) {
            VibeTopBar("Event", compactTitle = true, navigationIcon = { BackButton(onBack) })
            when {
                s.loading -> LoadingSkeleton(Modifier.padding(Spacing.md), itemCount = 2)
                s.notFound -> EmptyState(VibeIcons.Events, "Event not found", "It may have been removed, or the link is wrong.", actionLabel = "Back to events", onAction = onBack)
                else -> ErrorState(s.error ?: "Something went wrong.", viewModel::load)
            }
        }
        return
    }

    val event = details.event
    val status = event.status()
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Hero(details)
                Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    StatusBanner(event)
                    if (event.draft) Text("Draft. Only you can see it until you publish.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary)
                    if (event.hidden) Text("Moderators have hidden this event. Only you can see it.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    Text(event.title, style = MaterialTheme.typography.headlineSmall)
                    HostRow(details)
                    InfoCard(details)
                    if (status == EventStatus.Upcoming || status == EventStatus.Live) {
                        val addedRevision = remember(event.id, s.details?.event?.revision) { calendarRevisionAdded(ctx, event.id) }
                        if (addedRevision != null && addedRevision < event.revision) {
                            CalendarChangedNote { addToCalendar(ctx, event, details.description); rememberCalendarRevision(ctx, event.id, event.revision) }
                        }
                        TextButton(onClick = { addToCalendar(ctx, event, details.description); rememberCalendarRevision(ctx, event.id, event.revision) }) {
                            Icon(VibeIcons.Events, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Add to calendar")
                        }
                    }
                    if (details.description.isNotBlank()) {
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                            Text("About this event", style = MaterialTheme.typography.titleMedium)
                            Text(details.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    UpdatesSection(s.updates)
                    if (viewModel.supportsSharing && !event.draft && (event.mine || (event.rsvp == RsvpState.Going && event.going > 0))) {
                        TextButton(onClick = onOpenAttendees) { Text("See who's going (${event.going})") }
                    }
                    if (details.agenda.isNotEmpty()) Agenda(details)
                    VenueSection(details)
                }
            }
            if (event.draft && event.mine) {
                VibeButton("Publish event", viewModel::publish, Modifier.fillMaxWidth().navigationBarsPadding().padding(Spacing.md))
            } else if (!event.draft) {
                ActionBar(details, onGoing = viewModel::toggleGoing, onInterested = viewModel::toggleInterested)
            }
        }
        // Floating controls over the hero.
        val context = LocalContext.current
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = Spacing.md, vertical = Spacing.sm),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            BackButton(onBack)
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                if (event.mine && status != EventStatus.Ended) VibeIconButton(VibeIcons.More, "Manage event", { viewModel.openManage(true) })
                else if (!event.mine) VibeIconButton(VibeIcons.More, "More options", { viewModel.openManage(true) })
                if (viewModel.supportsSaving && !event.draft) VibeIconButton(if (event.saved) VibeIcons.BookmarkFilled else VibeIcons.Bookmark, if (event.saved) "Remove from saved" else "Save event", viewModel::toggleSaved)
                if (!event.hidden && !event.draft && status != EventStatus.Cancelled) VibeIconButton(VibeIcons.Share, "Share event", {
                    if (viewModel.supportsSharing) viewModel.openShare() else systemShare(context, event, null)
                })
            }
        }
    }

    if (s.manageOpen) {
        VibeBottomSheet(onDismiss = { viewModel.openManage(false) }) {
            if (event.mine) {
                SheetRow(VibeIcons.Edit, "Edit event", false) { viewModel.openManage(false); onEdit() }
                if (event.draft) SheetRow(VibeIcons.Check, "Publish event", false) { viewModel.publish() }
                if (viewModel.supportsSharing && !event.hidden && (status == EventStatus.Upcoming || status == EventStatus.Live)) SheetRow(UpdateIcon, "Post an update", false) { viewModel.composeUpdate(true) }
                if (viewModel.supportsSharing && status == EventStatus.Live) SheetRow(VibeIcons.Check, "Mark as completed", false) { viewModel.completeEvent() }
                if (viewModel.supportsSharing) SheetRow(ActivityIcon, "Activity", false) { viewModel.openActivity() }
                if (status != EventStatus.Cancelled) SheetRow(VibeIcons.EventBusy, "Cancel event", true) { viewModel.confirm(EventConfirm.Cancel) }
                SheetRow(VibeIcons.Delete, "Delete event", true) { viewModel.confirm(EventConfirm.Delete) }
            } else if (viewModel.supportsSharing) {
                if (event.remindersOn != null && (status == EventStatus.Upcoming)) SheetRow(UpdateIcon, if (event.remindersOn == true) "Turn reminders off" else "Turn reminders on", false) { viewModel.toggleReminders() }
                if (s.moderator) SheetRow(ReportIcon, "Review as moderator", false) { viewModel.openManage(false); onModerate() }
                SheetRow(ReportIcon, "Report event", true) { viewModel.reporting(ReportTarget.Event) }
                if (details.organizerId != null) SheetRow(OrganizerReportIcon, "Report organizer", true) { viewModel.reporting(ReportTarget.Organizer) }
            }
        }
    }
    when (s.confirm) {
        EventConfirm.Cancel -> ConfirmDialog(
            title = "Cancel this event?", message = "It stays visible as cancelled, and the people who RSVPed are told. This can't be undone.",
            confirmLabel = "Cancel event", dismissLabel = "Keep event", destructive = true, onConfirm = viewModel::doConfirmed, onDismiss = { viewModel.confirm(null) },
        )
        EventConfirm.Delete -> ConfirmDialog(
            title = "Delete this event?", message = "It's removed for everyone. If people have RSVPed you'll need to cancel it first.",
            confirmLabel = "Delete", dismissLabel = "Keep event", destructive = true, onConfirm = viewModel::doConfirmed, onDismiss = { viewModel.confirm(null) },
        )
        null -> Unit
    }

    if (s.composeUpdate) PostUpdateDialog(s.posting, viewModel::postUpdate) { viewModel.composeUpdate(false) }
    s.activity?.let { ActivitySheet(it, viewModel::closeActivity) }
    s.reporting?.let { ReportReasonSheet(it, viewModel::report) { viewModel.reporting(null) } }
    s.share?.let { sh ->
        ShareSheet(sh, onSystemShare = { systemShare(ctx, event, sh.link?.webUrl); viewModel.closeShare() }, onPage = viewModel::sharePage, onSend = viewModel::shareTo, onDismiss = viewModel::closeShare)
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

private fun addToCalendar(context: Context, e: CampusEvent, description: String) {
    // Opens the calendar app's own "new event" screen: no calendar permission needed, and nothing is saved until you confirm.
    val intent = Intent(Intent.ACTION_INSERT).apply {
        data = CalendarContract.Events.CONTENT_URI
        putExtra(CalendarContract.Events.TITLE, e.title)
        putExtra(CalendarContract.Events.EVENT_LOCATION, e.location)
        putExtra(CalendarContract.Events.DESCRIPTION, description)
        putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, e.startMillis)
        putExtra(CalendarContract.EXTRA_EVENT_END_TIME, e.endMillis)
    }
    try { context.startActivity(intent) } catch (ex: ActivityNotFoundException) { Toast.makeText(context, "No calendar app found on this device.", Toast.LENGTH_SHORT).show() }
}

@Composable
private fun StatusBanner(e: CampusEvent) {
    val colors = MaterialTheme.colorScheme
    val (text, bg, fg) = when (e.status()) {
        EventStatus.Cancelled -> Triple("This event was cancelled by the host.", colors.errorContainer, colors.onErrorContainer)
        EventStatus.Ended -> Triple("This event has ended. It will be removed on ${formatDate(e.expiresAtMillis)}.", colors.surfaceContainerHigh, colors.onSurfaceVariant)
        EventStatus.Live -> Triple("Happening now", colors.primaryContainer, colors.onPrimaryContainer)
        EventStatus.Upcoming -> return
    }
    Surface(shape = RoundedCornerShape(16.dp), color = bg, contentColor = fg, modifier = Modifier.fillMaxWidth()) {
        Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(Spacing.md))
    }
}

@Composable
private fun BackButton(onBack: () -> Unit) = VibeIconButton(VibeIcons.Back, "Back", onBack)

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
            InfoLine(VibeIcons.Clock, "When", event.timeLabel)
            InfoLine(VibeIcons.Place, "Where", event.location)
            InfoLine(VibeIcons.Groups, "Attending", "${event.going} going · ${event.interested} interested")
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
private fun VenueSection(details: EventDetails) {
    val lat = details.latitude
    val lng = details.longitude
    if (lat == null || lng == null) return
    val context = LocalContext.current
    val venue = details.event.location
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text("Venue", style = MaterialTheme.typography.titleMedium)
        VenueMap(lat, lng, venue, onClick = { openInMaps(context, lat, lng, venue) })
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(VibeIcons.Place, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(Spacing.sm))
            Text(venue, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        }
        VibeButton("Open in Maps", onClick = { openInMaps(context, lat, lng, venue) }, style = VibeButtonStyle.Secondary, modifier = Modifier.fillMaxWidth())
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
            if (event.cancelled) {
                Text("This event was cancelled.", style = MaterialTheme.typography.bodyMedium, color = colors.error, modifier = Modifier.align(Alignment.Center))
            } else if (event.isPast) {
                Text("This event has ended.", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, modifier = Modifier.align(Alignment.Center))
            } else {
                val going = event.rsvp == RsvpState.Going
                val interested = event.rsvp == RsvpState.Interested
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    if (AppContainer.eventRepository.supportsInterested) {
                    VibeButton(
                        text = "Interested", onClick = onInterested,
                        style = if (interested) VibeButtonStyle.Primary else VibeButtonStyle.Secondary,
                        leadingIcon = if (interested) VibeIcons.Check else VibeIcons.Star,
                        modifier = Modifier.weight(1f).semantics { selected = interested },
                    )
                    }
                    VibeButton(
                        text = if (details.isFull) "Event full" else "Going", onClick = onGoing,
                        style = if (going) VibeButtonStyle.Primary else VibeButtonStyle.Secondary,
                        enabled = !details.isFull,
                        leadingIcon = if (going) VibeIcons.Check else null,
                        modifier = Modifier.weight(1f).semantics { selected = going },
                    )
                }
            }
        }
    }
    }
}
