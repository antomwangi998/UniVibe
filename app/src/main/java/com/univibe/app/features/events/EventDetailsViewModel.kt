package com.univibe.app.features.events

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.univibe.app.app.AppContainer
import com.univibe.app.data.model.Chat
import com.univibe.app.data.model.Community
import com.univibe.app.data.model.EventActivity
import com.univibe.app.data.model.EventDetails
import com.univibe.app.data.model.EventLink
import com.univibe.app.data.model.EventUpdate
import com.univibe.app.data.model.ShareTarget
import com.univibe.app.data.repository.ChatRepository
import com.univibe.app.data.repository.CommunityRepository
import com.univibe.app.data.model.RsvpState
import com.univibe.app.data.repository.EventRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class EventDetailsUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val notFound: Boolean = false,
    val details: EventDetails? = null,
    /** True while the "leave this event?" confirmation is showing. */
    val confirmLeave: Boolean = false,
    val manageOpen: Boolean = false,
    val confirm: EventConfirm? = null,
    val notice: String? = null,
    val deleted: Boolean = false,
    val updates: List<EventUpdate> = emptyList(),
    val composeUpdate: Boolean = false,
    val posting: Boolean = false,
    /** Non-null while the organizer's activity sheet is open. */
    val activity: List<EventActivity>? = null,
    val share: ShareState? = null,
    /** What the person is reporting (the event or its organizer), while the reason picker is open. */
    val reporting: ReportTarget? = null,
)

enum class ReportTarget { Event, Organizer }

/** The share sheet: the links, plus the chats and communities the event can be sent to. Empty lists just hide those rows. */
data class ShareState(val link: EventLink? = null, val chats: List<Chat> = emptyList(), val communities: List<Community> = emptyList(), val page: SharePage = SharePage.Main, val sending: Boolean = false)

enum class SharePage { Main, Chats, Communities }

enum class EventConfirm { Cancel, Delete }

class EventDetailsViewModel(
    private val eventId: String,
    private val repository: EventRepository = AppContainer.eventRepository,
    private val chats: ChatRepository = AppContainer.chatRepository,
    private val communities: CommunityRepository = AppContainer.communityRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(EventDetailsUiState())
    val state: StateFlow<EventDetailsUiState> = _state.asStateFlow()

    /** True when the server offers organizer updates, activity, links, sharing and reports. */
    val supportsSharing: Boolean get() = repository.supportsOrganizerTools

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null, notFound = false) }
            try {
                val details = repository.getEvent(eventId)
                _state.update { it.copy(loading = false, details = details, notFound = details == null) }
                if (details != null) loadUpdates()
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = "We couldn't load this event right now.") }
            }
        }
    }

    /** Leaving "Going" asks first. Joining is blocked when the event is full or over. */
    fun toggleGoing() {
        val d = _state.value.details ?: return
        when {
            d.event.isPast -> Unit
            d.event.rsvp == RsvpState.Going -> _state.update { it.copy(confirmLeave = true) }
            d.isFull -> Unit
            else -> apply(RsvpState.Going)
        }
    }

    fun toggleInterested() {
        val d = _state.value.details ?: return
        if (d.event.isPast) return
        apply(if (d.event.rsvp == RsvpState.Interested) RsvpState.None else RsvpState.Interested)
    }

    fun confirmLeave() {
        _state.update { it.copy(confirmLeave = false) }
        apply(RsvpState.None)
    }

    fun dismissLeave() = _state.update { it.copy(confirmLeave = false) }

    private fun loadUpdates() {
        if (!repository.supportsOrganizerTools) return
        viewModelScope.launch { runCatching { repository.eventUpdates(eventId) }.onSuccess { list -> _state.update { it.copy(updates = list) } } }
    }

    fun completeEvent() {
        _state.update { it.copy(manageOpen = false) }
        viewModelScope.launch {
            try { repository.completeEvent(eventId); _state.update { it.copy(notice = "Marked as completed") }; load() }
            catch (e: Exception) { _state.update { it.copy(notice = e.message?.takeIf { m -> m.length < 90 } ?: "Couldn't do that. Try again.") } }
        }
    }

    fun toggleReminders() {
        val d = _state.value.details ?: return
        val on = !(d.event.remindersOn ?: true)
        _state.update { it.copy(manageOpen = false, details = d.copy(event = d.event.copy(remindersOn = on))) }
        viewModelScope.launch {
            try { repository.setEventReminders(eventId, on); _state.update { it.copy(notice = if (on) "Reminders on for this event" else "Reminders off for this event") } }
            catch (e: Exception) { _state.update { it.copy(notice = "Couldn't change reminders.") }; load() }
        }
    }

    fun composeUpdate(show: Boolean) = _state.update { it.copy(composeUpdate = show, manageOpen = false) }

    fun postUpdate(body: String) {
        val text = body.trim()
        if (text.isEmpty() || _state.value.posting) return
        _state.update { it.copy(posting = true) }
        viewModelScope.launch {
            try {
                repository.postEventUpdate(eventId, text)
                _state.update { it.copy(posting = false, composeUpdate = false, notice = "Update sent to everyone going or interested") }
                loadUpdates()
            } catch (e: Exception) {
                _state.update { it.copy(posting = false, notice = e.message?.takeIf { m -> m.length < 90 } ?: "Couldn't post the update. Try again.") }
            }
        }
    }

    fun openActivity() {
        _state.update { it.copy(manageOpen = false, activity = emptyList()) }
        viewModelScope.launch {
            try { repository.eventActivity(eventId).let { list -> _state.update { it.copy(activity = list) } } }
            catch (e: Exception) { _state.update { it.copy(activity = null, notice = "Couldn't load the activity.") } }
        }
    }

    fun closeActivity() = _state.update { it.copy(activity = null) }

    /** Loads the links first so the sheet can show them; chats and communities are only fetched when asked for. */
    fun openShare() {
        _state.update { it.copy(share = ShareState()) }
        viewModelScope.launch {
            val link = runCatching { repository.eventLink(eventId) }.getOrNull()
            _state.update { s -> s.share?.let { s.copy(share = it.copy(link = link)) } ?: s }
        }
    }

    fun closeShare() = _state.update { it.copy(share = null) }

    fun sharePage(page: SharePage) {
        _state.update { s -> s.share?.let { s.copy(share = it.copy(page = page)) } ?: s }
        viewModelScope.launch {
            try {
                when (page) {
                    SharePage.Chats -> chats.getChats().let { list -> _state.update { s -> s.share?.let { s.copy(share = it.copy(chats = list)) } ?: s } }
                    SharePage.Communities -> communities.getCommunities().filter { it.canPost }.let { list -> _state.update { s -> s.share?.let { s.copy(share = it.copy(communities = list)) } ?: s } }
                    SharePage.Main -> Unit
                }
            } catch (e: Exception) { _state.update { it.copy(notice = "Couldn't load your list. Try again.") } }
        }
    }

    fun shareTo(target: ShareTarget, targetId: String) {
        if (_state.value.share?.sending == true) return
        _state.update { s -> s.share?.let { s.copy(share = it.copy(sending = true)) } ?: s }
        viewModelScope.launch {
            try {
                repository.shareEvent(eventId, target, targetId, "")
                _state.update { it.copy(share = null, notice = if (target == ShareTarget.Chat) "Sent in chat" else "Shared in the community") }
            } catch (e: Exception) {
                _state.update { s -> s.copy(share = s.share?.copy(sending = false), notice = e.message?.takeIf { m -> m.length < 120 } ?: "Couldn't share. Try again.") }
            }
        }
    }

    fun reporting(target: ReportTarget?) = _state.update { it.copy(reporting = target, manageOpen = false) }

    fun report(reason: String) {
        val d = _state.value.details ?: return
        val what = _state.value.reporting ?: return
        _state.update { it.copy(reporting = null) }
        viewModelScope.launch {
            try {
                if (what == ReportTarget.Event) repository.report("event", eventId, reason)
                else repository.report("user", d.organizerId ?: return@launch, reason)
                _state.update { it.copy(notice = "Thanks. We'll review this.") }
            } catch (e: Exception) { _state.update { it.copy(notice = "Couldn't send the report. Try again.") } }
        }
    }

    fun openManage(show: Boolean) = _state.update { it.copy(manageOpen = show) }
    fun confirm(c: EventConfirm?) = _state.update { it.copy(confirm = c, manageOpen = false) }
    fun consumeNotice() = _state.update { it.copy(notice = null) }

    fun doConfirmed() {
        val c = _state.value.confirm ?: return
        _state.update { it.copy(confirm = null) }
        viewModelScope.launch {
            try {
                when (c) {
                    EventConfirm.Cancel -> { repository.cancelEvent(eventId); _state.update { it.copy(notice = "Event cancelled") }; load() }
                    EventConfirm.Delete -> { repository.deleteEvent(eventId); _state.update { it.copy(deleted = true) } }
                }
            } catch (e: Exception) {
                _state.update { it.copy(notice = e.message?.takeIf { m -> m.length < 90 } ?: "That didn't work. Try again.") }
            }
        }
    }

    private fun apply(newState: RsvpState) {
        val d = _state.value.details ?: return
        val e = d.event
        var going = e.going
        var interested = e.interested
        if (e.rsvp == RsvpState.Going) going--
        if (e.rsvp == RsvpState.Interested) interested--
        if (newState == RsvpState.Going) going++
        if (newState == RsvpState.Interested) interested++
        _state.update { it.copy(details = d.copy(event = e.copy(rsvp = newState, going = going, interested = interested))) }
        viewModelScope.launch {
            try { repository.setRsvp(e.id, newState) }
            catch (ex: Exception) {
                _state.update { it.copy(notice = ex.message?.takeIf { m -> m.length < 90 } ?: "Couldn't save your RSVP.") }
                load() // show what the server really has
            }
        }
    }
}
