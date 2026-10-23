package com.univibe.app.features.events

import com.univibe.app.ui.components.VibeIcons
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.univibe.app.core.formatDateTime
import com.univibe.app.core.keyedViewModel
import com.univibe.app.data.model.EventCategoryOptions
import com.univibe.app.features.chats.SheetRow
import com.univibe.app.ui.components.ErrorState
import com.univibe.app.ui.components.InterestChip
import com.univibe.app.ui.components.VibeBottomSheet
import com.univibe.app.ui.components.VibeButton
import com.univibe.app.ui.components.VibeIconButton
import com.univibe.app.ui.components.VibeTextField
import com.univibe.app.ui.components.VibeTopBar
import com.univibe.app.ui.components.accentGradient
import com.univibe.app.ui.theme.Spacing
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EventFormScreen(editId: String?, onBack: () -> Unit, onDone: (eventId: String) -> Unit) {
    val viewModel = keyedViewModel("event-form-${editId ?: "new"}") { EventFormViewModel(editId) }
    val s by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    var venueSheet by remember { mutableStateOf(false) }
    val pickCover = com.univibe.app.ui.components.rememberPhotoPicker("event") { viewModel.setCover(it) }

    LaunchedEffect(s.notice) { s.notice?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show(); viewModel.consumeNotice() } }
    LaunchedEffect(s.doneId) { s.doneId?.let(onDone) }

    Column(Modifier.fillMaxSize().imePadding()) {
        VibeTopBar(
            if (s.editing) "Edit event" else "New event", compactTitle = true,
            navigationIcon = { VibeIconButton(VibeIcons.Back, "Back", onBack) },
        )
        when {
            s.notFound -> ErrorState("You can only edit upcoming events you host.", onBack, title = "Not available")
            s.loading -> Box(Modifier.weight(1f))
            else -> {
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Spacing.md),
                    verticalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    VibeTextField(
                        s.title, viewModel::setTitle, label = "Event title", isError = s.title.isNotEmpty() && !s.titleOk,
                        supportingText = "${s.title.trim().length}/80 (at least 3)",
                    )
                    VibeTextField(
                        s.description, viewModel::setDescription, label = "Description (optional)", singleLine = false, minLines = 3,
                        isError = !s.descriptionOk, supportingText = "${s.description.trim().length}/1000",
                    )
                    CoverPicker(s.coverShown, s.accent, onPick = pickCover, onRemove = viewModel::removeCover)
                    Text("Category", style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        // The list comes from the server so new categories appear without an app update.
                        if (s.categories.isNotEmpty()) s.categories.forEach { c -> InterestChip(c.label, selected = s.categoryCode == c.code, onClick = { viewModel.setCategoryCode(c.code) }) }
                        else EventCategoryOptions.forEach { c -> InterestChip(c, selected = s.category == c, onClick = { viewModel.setCategory(c) }) }
                    }
                    if (!s.editing && s.hostOptions.size > 1) {
                        Text("Hosted by", style = MaterialTheme.typography.titleSmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            s.hostOptions.forEach { h -> InterestChip(h.name, selected = s.hostCommunityId == h.communityId, onClick = { viewModel.setHost(h.communityId) }) }
                        }
                    }
                    Text("When", style = MaterialTheme.typography.titleSmall)
                    DateTimeRow("Starts", s.startMillis, viewModel::setStart)
                    if (!s.startOk) Text("Start at least 5 minutes from now.", style = MaterialTheme.typography.bodySmall, color = colors.error)
                    DateTimeRow("Ends", s.endMillis, viewModel::setEnd)
                    if (!s.orderOk) Text("The event must end after it starts, and last at most 48 hours.", style = MaterialTheme.typography.bodySmall, color = colors.error)
                    Text("Registration", style = MaterialTheme.typography.titleSmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Registration deadline", style = MaterialTheme.typography.bodyLarge)
                            Text("People can't register after this time.", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        }
                        Switch(checked = s.hasDeadline, onCheckedChange = viewModel::setHasDeadline)
                    }
                    if (s.hasDeadline) {
                        DateTimeRow("Registration closes", s.deadlineMillis, viewModel::setDeadline)
                        if (!s.deadlineOk) Text("Pick a time that is still ahead and before the event ends.", style = MaterialTheme.typography.bodySmall, color = colors.error)
                    }
                    VibeTextField(
                        s.externalUrl, viewModel::setExternalUrl, label = "Registration link (optional)", isError = !s.externalUrlOk,
                        supportingText = "A link that starts with https://", capitalize = false,
                    )
                    Text("Format", style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        listOf("in_person" to "In person", "online" to "Online", "hybrid" to "Hybrid").forEach { (code, label) ->
                            InterestChip(label, selected = s.eventType == code, onClick = { viewModel.setEventType(code) })
                        }
                    }
                    if (s.needsLink) {
                        VibeTextField(
                            s.onlineUrl, viewModel::setOnlineUrl, label = "Link to join online", isError = s.onlineUrl.isNotEmpty() && !s.onlineUrlOk,
                            supportingText = "Starts with https://. Only you and people who registered can see it.", capitalize = false,
                        )
                    }
                    if (s.needsVenue) Text("Where", style = MaterialTheme.typography.titleSmall)
                    if (s.needsVenue) PickerRow(VibeIcons.Place, "Venue", s.venueName.ifBlank { "Choose a venue" }) { venueSheet = true }
                    if (s.needsVenue && s.customVenue) {
                        VibeTextField(
                            s.venueName, viewModel::setCustomVenueName, label = "Venue name", isError = s.venueName.isNotEmpty() && !s.venueOk,
                            supportingText = "No map is shown for venues that aren't on the campus list.",
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Limit places", style = MaterialTheme.typography.titleSmall)
                            Text("Going is closed once the event is full.", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        }
                        Switch(checked = s.limitPlaces, onCheckedChange = viewModel::setLimit)
                    }
                    if (s.limitPlaces) {
                        VibeTextField(
                            s.capacityText, viewModel::setCapacityText, label = "Number of places", isError = !s.capacityOk,
                            supportingText = "1 to 5000",
                        )
                    }
                    Text("Colour", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        (0..5).forEach { i ->
                            Box(
                                Modifier.size(44.dp).clip(CircleShape).background(Brush.linearGradient(accentGradient(i)))
                                    .clickable(role = Role.RadioButton, onClickLabel = "Colour ${i + 1}") { viewModel.setAccent(i) },
                                contentAlignment = Alignment.Center,
                            ) { if (s.accent == i) Icon(VibeIcons.Check, contentDescription = "Selected", tint = Color.White) }
                        }
                    }
                    Text(
                        "Events are removed 7 days after they end, together with their RSVPs. Cancelled events stay visible, marked as cancelled, until then.",
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                    )
                    Spacer(Modifier.size(Spacing.sm))
                }
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    VibeButton(
                        if (s.editing) "Save changes" else "Publish event", { viewModel.submit() },
                        Modifier.fillMaxWidth(), enabled = s.canSubmit, loading = s.submitting,
                    )
                    if (!s.editing) TextButton({ viewModel.submit(asDraft = true) }, Modifier.fillMaxWidth(), enabled = s.canSubmit) { Text("Save as draft") }
                }
            }
        }
    }

    if (venueSheet) {
        VibeBottomSheet(onDismiss = { venueSheet = false }) {
            Text("Choose a venue", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm))
            s.venues.forEach { v -> SheetRow(VibeIcons.Place, v.name, false) { viewModel.pickVenue(v); venueSheet = false } }
            SheetRow(VibeIcons.Place, "Somewhere else", false) { viewModel.useCustomVenue(); venueSheet = false }
        }
    }
}

@Composable
private fun PickerRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(16.dp), color = colors.surface, border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(role = Role.Button, onClickLabel = "Change ${label.lowercase()}", onClick = onClick),
    ) {
        Row(Modifier.heightIn(min = 56.dp).padding(horizontal = Spacing.md), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = colors.primary)
            Column(Modifier.weight(1f).padding(start = Spacing.md)) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                Text(value, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

/** Tap to pick a date, then a time. Both pickers use the device time zone. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateTimeRow(label: String, millis: Long, onChange: (Long) -> Unit) {
    var step by remember { mutableStateOf(0) } // 0 closed, 1 date, 2 time
    var pickedDate by remember { mutableStateOf<LocalDate?>(null) }
    val zone = ZoneId.systemDefault()
    val current = Instant.ofEpochMilli(millis).atZone(zone)

    PickerRow(VibeIcons.Events, label, formatDateTime(millis)) { step = 1 }

    if (step == 1) {
        val todayUtc = LocalDate.now(zone).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val state = rememberDatePickerState(
            initialSelectedDateMillis = current.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : SelectableDates { override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis >= todayUtc },
        )
        DatePickerDialog(
            onDismissRequest = { step = 0 },
            confirmButton = {
                TextButton(onClick = {
                    val picked = state.selectedDateMillis
                    if (picked == null) step = 0 else { pickedDate = Instant.ofEpochMilli(picked).atZone(ZoneOffset.UTC).toLocalDate(); step = 2 }
                }) { Text("Next") }
            },
            dismissButton = { TextButton(onClick = { step = 0 }) { Text("Cancel") } },
        ) { DatePicker(state = state) }
    }
    if (step == 2) {
        val time = rememberTimePickerState(initialHour = current.hour, initialMinute = current.minute, is24Hour = false)
        AlertDialog(
            onDismissRequest = { step = 0 },
            confirmButton = {
                TextButton(onClick = {
                    val date = pickedDate ?: current.toLocalDate()
                    onChange(LocalDateTime.of(date, LocalTime.of(time.hour, time.minute)).atZone(zone).toInstant().toEpochMilli())
                    step = 0
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { step = 0 }) { Text("Cancel") } },
            text = { TimePicker(state = time) },
        )
    }
}
