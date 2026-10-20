package com.univibe.app.features.events

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.univibe.app.app.AppContainer
import com.univibe.app.data.model.EventCategoryInfo
import com.univibe.app.data.model.EventCategoryOptions
import com.univibe.app.data.model.NewEvent
import com.univibe.app.data.model.Venue
import com.univibe.app.data.repository.CommunityRepository
import com.univibe.app.data.repository.EventRepository
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Who hosts the event: you, or a community you moderate. */
data class HostOption(val communityId: String?, val name: String)

private const val HOUR = 3_600_000L

data class EventFormUiState(
    val loading: Boolean = false,
    val notFound: Boolean = false,
    val editing: Boolean = false,
    val title: String = "",
    val description: String = "",
    val category: String = EventCategoryOptions.first(),
    /** Categories the server offers. Empty means the built-in list above is used. */
    val categories: List<EventCategoryInfo> = emptyList(),
    val categoryCode: String = "",
    val eventType: String = "in_person",
    val onlineUrl: String = "",
    val hasDeadline: Boolean = false,
    val deadlineMillis: Long = 0L,
    val externalUrl: String = "",
    val accent: Int = 0,
    val hostOptions: List<HostOption> = listOf(HostOption(null, "You")),
    val hostCommunityId: String? = null,
    val startMillis: Long = 0L,
    val endMillis: Long = 0L,
    /** The start the event had when it was opened for editing; an unchanged start is always accepted. */
    val originalStart: Long = 0L,
    val venues: List<Venue> = emptyList(),
    val venueName: String = "",
    val venueLat: Double? = null,
    val venueLng: Double? = null,
    val customVenue: Boolean = false,
    val limitPlaces: Boolean = false,
    val capacityText: String = "",
    val submitting: Boolean = false,
    val notice: String? = null,
    val doneId: String? = null,
) {
    private val now get() = System.currentTimeMillis()
    val titleOk get() = title.trim().length in 3..80
    val descriptionOk get() = description.trim().length <= 1000
    val needsVenue get() = eventType != "online"
    val needsLink get() = eventType != "in_person"
    val venueOk get() = !needsVenue || venueName.trim().length in 2..80
    private fun httpsOk(v: String) = v.trim().let { it.startsWith("https://") && it.length in 12..500 && !it.contains(' ') }
    val onlineUrlOk get() = !needsLink || httpsOk(onlineUrl)
    val externalUrlOk get() = externalUrl.isBlank() || httpsOk(externalUrl)
    /** Registration must close before the event ends, and still be ahead of us. */
    val deadlineOk get() = !hasDeadline || (deadlineMillis <= endMillis && deadlineMillis > now)
    val categoryOk get() = categories.isEmpty() || categoryCode.isNotBlank()
    val orderOk get() = endMillis > startMillis && endMillis - startMillis <= 48 * HOUR
    val startOk get() = (editing && startMillis == originalStart) || startMillis >= now + 5 * 60_000L
    val capacity: Int? get() = if (limitPlaces) capacityText.toIntOrNull() else null
    val capacityOk get() = !limitPlaces || (capacity != null && capacity!! in 1..5000)
    val canSubmit get() = titleOk && descriptionOk && venueOk && onlineUrlOk && externalUrlOk && deadlineOk && categoryOk && orderOk && startOk && capacityOk && !submitting && doneId == null
}

/** Create an event, or edit one you host ([editId] != null). Edits can't change the host. */
class EventFormViewModel(
    private val editId: String?,
    private val repository: EventRepository = AppContainer.eventRepository,
    private val communities: CommunityRepository = AppContainer.communityRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(initialState(editId))
    val state: StateFlow<EventFormUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val venues = runCatching { repository.getVenues() }.getOrDefault(emptyList())
            val hosts = runCatching { communities.getCommunities() }.getOrDefault(emptyList()).filter { it.isStaff }
                .map { HostOption(it.id, it.name) }
            val categories = runCatching { repository.eventCategories() }.getOrDefault(emptyList())
            _state.update { it.copy(venues = venues, categories = categories, hostOptions = listOf(HostOption(null, "You")) + hosts) }
            if (editId != null) {
                val d = runCatching { repository.getEvent(editId) }.getOrNull()
                val e = d?.event
                if (d == null || e == null || !e.mine || e.isPast || e.cancelled) { _state.update { it.copy(loading = false, notFound = true) }; return@launch }
                val known = venues.firstOrNull { it.name == e.location }
                _state.update {
                    it.copy(
                        loading = false, title = e.title, description = d.description, category = e.category, categoryCode = d.categoryCode, accent = e.accent,
                        eventType = d.eventType, onlineUrl = d.onlineUrl.orEmpty(), externalUrl = d.externalUrl.orEmpty(),
                        hasDeadline = d.registrationDeadlineMillis != null, deadlineMillis = d.registrationDeadlineMillis ?: (e.startMillis - HOUR),
                        hostCommunityId = e.hostCommunityId, startMillis = e.startMillis, endMillis = e.endMillis, originalStart = e.startMillis,
                        venueName = e.location, venueLat = d.latitude, venueLng = d.longitude, customVenue = known == null && d.latitude == null,
                        limitPlaces = d.capacity != null, capacityText = d.capacity?.toString().orEmpty(),
                    )
                }
            }
        }
    }

    fun setTitle(v: String) = _state.update { it.copy(title = v) }
    fun setDescription(v: String) = _state.update { it.copy(description = v) }
    fun setCategory(v: String) = _state.update { it.copy(category = v) }
    fun setCategoryCode(v: String) = _state.update { it.copy(categoryCode = v) }
    fun setEventType(v: String) = _state.update { it.copy(eventType = v) }
    fun setOnlineUrl(v: String) = _state.update { it.copy(onlineUrl = v.trim()) }
    fun setExternalUrl(v: String) = _state.update { it.copy(externalUrl = v.trim()) }
    fun setHasDeadline(on: Boolean) = _state.update { it.copy(hasDeadline = on, deadlineMillis = if (on && it.deadlineMillis == 0L) it.startMillis - HOUR else it.deadlineMillis) }
    fun setDeadline(millis: Long) = _state.update { it.copy(deadlineMillis = millis) }
    fun setAccent(v: Int) = _state.update { it.copy(accent = v) }
    fun setHost(id: String?) = _state.update { it.copy(hostCommunityId = id) }
    fun consumeNotice() = _state.update { it.copy(notice = null) }

    /** Moving the start keeps the length of the event. */
    fun setStart(millis: Long) = _state.update { s ->
        val length = (s.endMillis - s.startMillis).takeIf { it > 0 } ?: (2 * HOUR)
        s.copy(startMillis = millis, endMillis = millis + length)
    }
    fun setEnd(millis: Long) = _state.update { it.copy(endMillis = millis) }

    fun pickVenue(v: Venue) = _state.update { it.copy(venueName = v.name, venueLat = v.latitude, venueLng = v.longitude, customVenue = false) }
    fun useCustomVenue() = _state.update { if (it.customVenue) it else it.copy(venueName = "", venueLat = null, venueLng = null, customVenue = true) }
    /** A typed venue has no map position, so the map section is hidden for it. */
    fun setCustomVenueName(v: String) = _state.update { it.copy(venueName = v, venueLat = null, venueLng = null) }

    fun setLimit(on: Boolean) = _state.update { it.copy(limitPlaces = on) }
    fun setCapacityText(v: String) = _state.update { it.copy(capacityText = v.filter { c -> c.isDigit() }.take(4)) }

    fun submit() {
        val s = _state.value
        if (!s.canSubmit) return
        viewModelScope.launch {
            _state.update { it.copy(submitting = true) }
            try {
                val draft = NewEvent(
                    s.title.trim(), s.description.trim(), s.category, s.startMillis, s.endMillis, if (s.needsVenue) s.venueName.trim() else s.venueName.trim().ifBlank { "" },
                    s.venueLat, s.venueLng, s.capacity, s.accent, s.hostCommunityId,
                    categoryCode = s.categoryCode.ifBlank { null }, eventType = s.eventType,
                    onlineUrl = s.onlineUrl.takeIf { s.needsLink && it.isNotBlank() }, externalUrl = s.externalUrl.ifBlank { null },
                    registrationDeadlineMillis = s.deadlineMillis.takeIf { s.hasDeadline }, timezone = ZoneId.systemDefault().id,
                )
                val id = if (editId != null) { repository.updateEvent(editId, draft); editId } else repository.createEvent(draft)
                _state.update { it.copy(submitting = false, doneId = id) }
            } catch (e: Exception) {
                _state.update { it.copy(submitting = false, notice = e.message?.takeIf { m -> m.length < 90 } ?: "Couldn't save the event. Try again.") }
            }
        }
    }

    private companion object {
        fun initialState(editId: String?): EventFormUiState {
            // Default: tomorrow at 6 pm for two hours, in the device time zone.
            val start = LocalDate.now().plusDays(1).atTime(18, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            return EventFormUiState(editing = editId != null, loading = editId != null, startMillis = start, endMillis = start + 2 * HOUR, originalStart = start)
        }
    }
}
