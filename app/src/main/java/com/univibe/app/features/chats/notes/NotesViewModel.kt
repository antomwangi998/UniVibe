package com.univibe.app.features.chats.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.univibe.app.app.AppContainer
import com.univibe.app.data.model.Note
import com.univibe.app.data.model.NoteAudience
import com.univibe.app.data.model.NoteDraftCheck
import com.univibe.app.data.model.NotesSnapshot
import com.univibe.app.data.model.StoryTrayEntry
import com.univibe.app.data.model.checkNoteDraft
import com.univibe.app.data.model.cleanNoteDraft
import com.univibe.app.data.net.ApiException
import com.univibe.app.data.repository.NoteRepository
import com.univibe.app.data.repository.StoryRepository
import com.univibe.app.data.repository.UnavailableStoryRepository
import com.univibe.app.domain.auth.AuthState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

/** The signed-in student as the Notes row shows them. Their avatar is the real profile photo, never a stand-in icon. */
data class NotesMe(val id: String, val name: String, val avatarUrl: String?)

/**
 * One person in the row (never me). A Note and a Story are independent: a person can have either, both, or (never listed)
 * neither. [story] drives the ring; [note] drives the bubble.
 */
data class RowPerson(
    val authorId: String,
    val name: String,
    val avatarUrl: String?,
    val note: Note?,
    val story: StoryTrayEntry?,
) {
    val online: Boolean get() = note?.online == true
    val hasUnviewedStory: Boolean get() = story?.hasUnviewed == true
}

/** Which Notes sheet is open, if any. */
sealed interface NotesSheet {
    data object None : NotesSheet
    /** Write a new Note, or edit the current one. */
    data object Compose : NotesSheet
    /** My own active Note: read it, then edit or delete. */
    data object Mine : NotesSheet
    /** Someone else's Note, by author id. */
    data class Theirs(val authorId: String) : NotesSheet
}

data class NotesUiState(
    val loading: Boolean = true,
    /** Set only when the first load failed and there is nothing to show. Later failures keep what we have. */
    val error: String? = null,
    val me: NotesMe? = null,
    val mine: Note? = null,
    val others: List<Note> = emptyList(),
    /** Everyone with an active Story I may see, including me. Empty until the first answer; kept when a refresh fails. */
    val stories: List<StoryTrayEntry> = emptyList(),
    val maxChars: Int = 60,
    val ttlHours: Int = 24,
    /** Server-corrected "now" in epoch ms. It moves when a Note expires, so expired Notes disappear without a reload. */
    val nowMs: Long = 0L,
    val sheet: NotesSheet = NotesSheet.None,
    val draft: String = "",
    val draftAudience: NoteAudience = NoteAudience.Campus,
    val publishing: Boolean = false,
    val publishError: String? = null,
    val notice: String? = null,
) {
    val myActive: Note? get() = mine?.takeIf { it.isActiveAt(nowMs) }
    val visibleOthers: List<Note> get() = others.filter { it.isActiveAt(nowMs) }
    val theirs: Note? get() = (sheet as? NotesSheet.Theirs)?.let { s -> visibleOthers.firstOrNull { it.authorId == s.authorId } }
    val draftCheck: NoteDraftCheck get() = checkNoteDraft(draft, maxChars)
    val canPublish: Boolean get() = draftCheck is NoteDraftCheck.Ok && !publishing
    val hasAnyNote: Boolean get() = myActive != null || visibleOthers.isNotEmpty()
    /** My own active Story, if any (ring around my avatar, plus badge kept). */
    val myStory: StoryTrayEntry? get() = stories.firstOrNull { it.isMine || it.authorId == me?.id }

    /**
     * The people shown after me. With [withStories] false (the Story viewer is not available) only people with a Note
     * appear and no ring is shown, so nothing in the row promises something that cannot be opened. Order: unseen Stories
     * first, then whoever posted most recently (a Note or a Story).
     */
    fun people(withStories: Boolean): List<RowPerson> {
        val notes = visibleOthers.associateBy { it.authorId }
        val storyBy = if (withStories) stories.filter { !it.isMine && it.authorId != me?.id }.associateBy { it.authorId } else emptyMap()
        return (notes.keys + storyBy.keys).map { id ->
            val n = notes[id]; val st = storyBy[id]
            RowPerson(id, n?.authorName ?: st!!.authorName, n?.avatarUrl ?: st?.avatarUrl, n, st)
        }.sortedWith(
            compareByDescending<RowPerson> { it.hasUnviewedStory }
                .thenByDescending { maxOf(it.note?.createdAtMs ?: 0L, it.story?.latestAtMs ?: 0L) },
        )
    }
}

/**
 * Notes above the Chats list. It owns nothing about conversations, so the chat list and its tests are untouched.
 *
 * Refresh policy: load on open and on every return to the screen; re-read when the server says a Note changed or the
 * socket reconnected (missed events are reconciled by that re-read, never assumed); drop a Note the moment its
 * server-set expiry passes; and only while the live connection is down, re-read every few minutes as a fallback.
 */
class NotesViewModel(
    private val repository: NoteRepository = AppContainer.noteRepository,
    private val session: Flow<AuthState> = AppContainer.authRepository.session,
    private val deviceClock: () -> Long = System::currentTimeMillis,
    private val fallbackPollMs: Long = 5 * 60_000L,
    private val storyRepository: StoryRepository = UnavailableStoryRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(NotesUiState(nowMs = deviceClock()))
    val state: StateFlow<NotesUiState> = _state.asStateFlow()

    private var clockOffsetMs = 0L
    private var expiryJob: Job? = null
    private var loadJob: Job? = null
    private var storiesJob: Job? = null
    /** While my own change is in flight, a refresh must not overwrite it with an answer computed before it. */
    private var mutating = 0

    private fun now() = deviceClock() + clockOffsetMs

    init {
        viewModelScope.launch {
            session.map { s ->
                when (s) {
                    is AuthState.Authenticated -> NotesMe(s.user.id, s.user.fullName, s.user.avatarUrl)
                    else -> null
                }
            }.collect { me -> _state.update { it.copy(me = me) } }
        }
        load()
        // The teal dot follows live presence. Only people who share their activity with me ever send it.
        viewModelScope.launch {
            repository.presence().collect { p ->
                _state.update { s ->
                    s.copy(others = s.others.map { n ->
                        if (n.authorId != p.userId) n
                        else n.copy(online = p.online, lastActiveMs = if (p.online) n.lastActiveMs else now())
                    })
                }
            }
        }
        // The server says when something changed; a short pause lets a burst of events become one reload.
        viewModelScope.launch { repository.changes().conflate().collect { delay(300); load(silent = true) } }
        viewModelScope.launch { storyRepository.changes().conflate().collect { delay(300); loadStories() } }
        // Fallback only: while the live connection is down, ask now and then. When it is up, events do the work.
        viewModelScope.launch {
            repository.connected().collectLatest { up ->
                if (!up) while (true) { delay(fallbackPollMs); load(silent = true) }
            }
        }
    }

    fun load(silent: Boolean = false) {
        loadStories()
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            if (!silent && _state.value.mine == null && _state.value.others.isEmpty()) _state.update { it.copy(loading = true, error = null) }
            try {
                val snapshot = repository.getNotes()
                if (mutating > 0) return@launch
                apply(snapshot)
            } catch (e: Exception) {
                // Keep showing what we have. Only a first load with nothing to show becomes an error.
                _state.update { s ->
                    if (s.mine == null && s.others.isEmpty() && !silent) s.copy(loading = false, error = "Couldn't load Notes.")
                    else s.copy(loading = false)
                }
            }
        }
    }

    /** Stories load on their own, so a slow or failed Stories call never delays or hides Notes (and the other way round). */
    private fun loadStories() {
        storiesJob?.cancel()
        storiesJob = viewModelScope.launch {
            try {
                val tray = storyRepository.tray()
                _state.update { it.copy(stories = tray) }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                // Keep the last known rings. A failed refresh must not make everyone's Story look gone.
            }
        }
    }

    /** Called when the Chats screen comes back to the front. */
    fun refresh() = load(silent = true)

    private fun apply(snapshot: NotesSnapshot) {
        clockOffsetMs = snapshot.clockOffsetMs
        _state.update {
            it.copy(
                loading = false, error = null,
                mine = snapshot.mine, others = snapshot.items.sortedByDescending { n -> n.createdAtMs },
                maxChars = snapshot.maxChars, ttlHours = snapshot.ttlHours, nowMs = now(),
            )
        }
        scheduleExpiry()
    }

    /** Sleeps until the next Note expires (by the server's clock), then removes it and schedules the one after. */
    private fun scheduleExpiry() {
        expiryJob?.cancel()
        val s = _state.value
        val next = (listOfNotNull(s.mine) + s.others).map { it.expiresAtMs }.filter { it > now() }.minOrNull() ?: return
        expiryJob = viewModelScope.launch {
            delay((next - now()).coerceAtLeast(0) + 250)
            _state.update { it.copy(nowMs = now()) }
            scheduleExpiry()
        }
    }

    // ---- sheets and draft

    fun onAddNoteClick() {
        val mine = _state.value.myActive
        if (mine == null) openCompose(existing = null) else _state.update { it.copy(sheet = NotesSheet.Mine) }
    }

    /** The Note bubble (mine): open my Note so I can read, edit or delete it. */
    fun onMyNoteClick() = _state.update { if (it.myActive != null) it.copy(sheet = NotesSheet.Mine) else it }

    /** The Note bubble: always opens the Note. */
    fun onNoteClick(authorId: String) = _state.update { it.copy(sheet = NotesSheet.Theirs(authorId)) }

    /**
     * The avatar when the person has no Story ring (or Stories are not available): opens their Note, the same as the
     * bubble. An avatar with a ring is routed to the Story viewer by the screen before this is called.
     */
    fun onAvatarClick(authorId: String) = onNoteClick(authorId)

    fun editMine() = openCompose(existing = _state.value.myActive)

    private fun openCompose(existing: Note?) = _state.update {
        it.copy(
            sheet = NotesSheet.Compose, draft = existing?.text.orEmpty(),
            draftAudience = existing?.audience ?: it.draftAudience, publishError = null,
        )
    }

    fun setDraft(text: String) = _state.update { it.copy(draft = text, publishError = null) }
    fun setAudience(audience: NoteAudience) = _state.update { it.copy(draftAudience = audience) }
    fun closeSheet() = _state.update { if (it.publishing) it else it.copy(sheet = NotesSheet.None, publishError = null) }
    fun consumeNotice() = _state.update { it.copy(notice = null) }

    // ---- changes (each applied at once, then confirmed by the server)

    fun publish() {
        val s = _state.value
        if (!s.canPublish) return // blank, too long, or already publishing: a double tap cannot send twice
        val text = cleanNoteDraft(s.draft)
        _state.update { it.copy(publishing = true, publishError = null) }
        mutating++
        viewModelScope.launch {
            try {
                val saved = repository.publish(text, s.draftAudience)
                _state.update { it.copy(mine = saved, publishing = false, sheet = NotesSheet.None, draft = "", nowMs = now()) }
                scheduleExpiry()
            } catch (e: Exception) {
                _state.update { it.copy(publishing = false, publishError = publishMessage(e)) }
            } finally {
                mutating--
            }
            load(silent = true) // reconcile with the server
        }
    }

    fun deleteMine() {
        val before = _state.value.mine ?: return
        _state.update { it.copy(mine = null, sheet = NotesSheet.None) }
        mutating++
        viewModelScope.launch {
            try {
                repository.deleteMine()
            } catch (e: Exception) {
                // Put it back only if nothing newer has replaced it meanwhile.
                _state.update { if (it.mine == null) it.copy(mine = before, notice = "Couldn't delete your Note. Try again.") else it }
            } finally {
                mutating--
            }
            load(silent = true)
        }
    }

    private fun publishMessage(e: Exception): String = when {
        e is ApiException && e.status == 400 -> e.message ?: "That Note isn't valid."
        e is ApiException && e.status == 429 -> "You're posting too fast. Try again later."
        e is ApiException && e.status == 401 -> "Please sign in again."
        e is IOException -> "No connection. Your Note wasn't published."
        else -> "Couldn't publish your Note. Try again."
    }
}
