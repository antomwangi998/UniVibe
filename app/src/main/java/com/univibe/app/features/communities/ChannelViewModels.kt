package com.univibe.app.features.communities

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.univibe.app.app.AppContainer
import com.univibe.app.data.model.Channel
import com.univibe.app.data.model.ChannelDirectory
import com.univibe.app.data.model.ChannelDraft
import com.univibe.app.data.model.ChannelMessage
import com.univibe.app.data.model.ChannelType
import com.univibe.app.data.model.MembershipState
import com.univibe.app.data.model.latestSeq
import com.univibe.app.data.model.tombstoned
import com.univibe.app.data.model.upserted
import com.univibe.app.data.model.upsertedAll
import com.univibe.app.data.model.withReaction
import com.univibe.app.data.net.ApiException
import com.univibe.app.data.net.ChatEvent
import com.univibe.app.data.repository.ChannelRepository
import com.univibe.app.data.repository.SearchFilters
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.UUID

/** The server says the student no longer has this channel (removed, restricted, archived, made staff-only): close, don't show stale content. */
private fun Throwable.lostAccess() = this is ApiException && (status == 404 || code == "not_a_member" || code == "suspended")

private fun Throwable.userText(fallback: String = "That didn't work. Try again."): String = when (this) {
    is IOException -> "No connection. Try again when you're back online."
    is ApiException -> message?.takeIf { it.isNotBlank() && it.length < 120 } ?: fallback
    else -> fallback
}

// ------------------------------------------------------------------ channel list (the Discussions tab)

data class ChannelListUiState(
    val loading: Boolean = true,
    val directory: ChannelDirectory? = null,
    val error: String? = null,
    /** The student is not (or no longer) a member: the server refuses to list channels. */
    val notAMember: Boolean = false,
    val creating: Boolean = false,
    val notice: String? = null,
)

@OptIn(FlowPreview::class)
class ChannelListViewModel(
    private val communityId: String,
    private val repository: ChannelRepository = AppContainer.channelRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(ChannelListUiState())
    val state: StateFlow<ChannelListUiState> = _state.asStateFlow()
    private val nudges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    init {
        load()
        // New messages elsewhere change unread counts; layout changes change the list. Re-read, at most every half second.
        viewModelScope.launch { nudges.debounce(500).collect { load(silent = true) } }
        viewModelScope.launch {
            repository.live.collect { e ->
                when (e) {
                    is ChatEvent.ChannelMessageNew -> if (e.communityId == communityId) nudges.tryEmit(Unit)
                    is ChatEvent.ChannelChanged -> if (e.communityId == communityId) nudges.tryEmit(Unit)
                    is ChatEvent.Connected -> nudges.tryEmit(Unit)
                    is ChatEvent.CommunityMembership -> if (e.communityId == communityId) nudges.tryEmit(Unit)
                    else -> Unit
                }
            }
        }
    }

    fun load(silent: Boolean = false) {
        viewModelScope.launch {
            if (!silent) _state.update { it.copy(loading = true, error = null) }
            try {
                _state.update { it.copy(loading = false, directory = repository.directory(communityId), notAMember = false, error = null) }
            } catch (e: Exception) {
                if (e is ApiException && (e.code == "not_a_member" || e.code == "suspended")) _state.update { it.copy(loading = false, notAMember = true, directory = null) }
                else if (!silent) _state.update { it.copy(loading = false, error = e.userText("We couldn't load the channels.")) }
            }
        }
    }

    fun create(name: String, description: String, type: ChannelType, onDone: (String) -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(creating = true) }
            try {
                val id = repository.createChannel(communityId, name, description, type)
                _state.update { it.copy(creating = false) }
                load(silent = true)
                onDone(id)
            } catch (e: Exception) {
                _state.update { it.copy(creating = false, notice = e.userText("We couldn't create the channel.")) }
            }
        }
    }

    fun consumeNotice() = _state.update { it.copy(notice = null) }
}

// ------------------------------------------------------------------ conversation

/** A message on its way to the server: shown at the bottom with its state, kept until the server confirms or the student discards it. */
data class PendingSend(val draft: ChannelDraft, val failed: Boolean = false, val error: String? = null)

const val MAX_PHOTOS = 4

data class ChannelUiState(
    val loading: Boolean = true,
    val channel: Channel? = null,
    /** Oldest first. */
    val messages: List<ChannelMessage> = emptyList(),
    val pending: List<PendingSend> = emptyList(),
    val cursor: String? = null,
    val endReached: Boolean = false,
    val loadingOlder: Boolean = false,
    val accessLost: Boolean = false,
    val error: String? = null,
    val replyTo: ChannelMessage? = null,
    val editing: ChannelMessage? = null,
    val text: String = "",
    val title: String = "",
    val photos: List<String> = emptyList(),
    val pins: List<ChannelMessage>? = null,
    val searching: Boolean = false,
    val search: SearchFilters = SearchFilters(),
    val results: List<ChannelMessage>? = null,
    val searchLoading: Boolean = false,
    val notice: String? = null,
) {
    /** What the timeline shows: in question and announcement channels only top-level items (answers live in the thread). */
    val timeline: List<ChannelMessage>
        get() = if (channel?.type?.threadedTimeline == true) messages.filter { it.replyTo == null } else messages
    val canSendNow: Boolean
        get() = channel?.access?.let { if (replyTo != null) it.reply else it.send } == true
}

class ChannelViewModel(
    private val communityId: String,
    private val channelId: String,
    private val repository: ChannelRepository = AppContainer.channelRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(ChannelUiState())
    val state: StateFlow<ChannelUiState> = _state.asStateFlow()
    val myId: String? get() = repository.myId

    init {
        load()
        viewModelScope.launch { repository.live.collect(::onLive) }
    }

    // ---------- loading

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = it.messages.isEmpty(), error = null) }
            try {
                val channel = repository.directory(communityId).channels.firstOrNull { it.id == channelId }
                if (channel == null) { _state.update { it.copy(loading = false, accessLost = true) }; return@launch }
                val page = repository.history(communityId, channelId, null)
                _state.update {
                    it.copy(
                        loading = false, channel = channel, messages = it.messages.upsertedAll(page.items),
                        cursor = if (it.messages.isEmpty()) page.nextCursor else it.cursor,
                        endReached = if (it.messages.isEmpty()) page.nextCursor == null else it.endReached,
                    )
                }
                markRead()
            } catch (e: Exception) {
                if (e.lostAccess()) _state.update { it.copy(loading = false, accessLost = true) }
                else _state.update { it.copy(loading = false, error = if (it.messages.isEmpty()) e.userText("We couldn't load this channel.") else null, notice = if (it.messages.isNotEmpty()) e.userText() else null) }
            }
        }
    }

    /** Older messages, as the student scrolls up. The list is anchored at the bottom, so the position holds. */
    fun loadOlder() {
        val s = _state.value
        if (s.loadingOlder || s.endReached || s.cursor == null) return
        viewModelScope.launch {
            _state.update { it.copy(loadingOlder = true) }
            try {
                val page = repository.history(communityId, channelId, s.cursor)
                _state.update { it.copy(loadingOlder = false, messages = it.messages.upsertedAll(page.items), cursor = page.nextCursor, endReached = page.nextCursor == null) }
            } catch (e: Exception) {
                if (e.lostAccess()) _state.update { it.copy(loadingOlder = false, accessLost = true) }
                else _state.update { it.copy(loadingOlder = false, notice = e.userText()) }
            }
        }
    }

    /** After the socket was down: fetch what was missed, and refresh the newest page so edits, pins and deletions show too. */
    private suspend fun resync() {
        try {
            val missed = repository.catchUp(communityId, channelId, _state.value.messages.latestSeq())
            val latest = repository.history(communityId, channelId, null)
            val channel = repository.directory(communityId).channels.firstOrNull { it.id == channelId }
            if (channel == null) { _state.update { it.copy(accessLost = true) }; return }
            _state.update { it.copy(channel = channel, messages = it.messages.upsertedAll(missed + latest.items)) }
            markRead()
        } catch (e: Exception) {
            if (e.lostAccess()) _state.update { it.copy(accessLost = true) }
        }
    }

    private fun markRead() {
        val seq = _state.value.messages.latestSeq()
        if (seq == 0L) return
        viewModelScope.launch { runCatching { repository.markRead(communityId, channelId, seq) } }
    }

    // ---------- live events

    private fun onLive(e: ChatEvent) {
        when (e) {
            is ChatEvent.Connected -> viewModelScope.launch { resync() }
            is ChatEvent.ChannelMessageNew -> if (e.channelId == channelId) {
                val m = com.univibe.app.data.repository.parseChannelMessage(e.message)
                _state.update { st ->
                    val parent = m.replyTo?.id
                    if (parent != null && st.channel?.type?.threadedTimeline == true) {
                        // An answer or comment: the timeline shows only its count.
                        st.copy(messages = st.messages.map { if (it.id == parent) it.copy(replyCount = it.replyCount + 1) else it })
                    } else st.copy(messages = st.messages.upserted(m))
                }
                if (m.author?.id != repository.myId) markRead()
            }
            is ChatEvent.ChannelMessageUpdated -> if (e.channelId == channelId) {
                val m = com.univibe.app.data.repository.parseChannelMessage(e.message)
                _state.update { it.copy(messages = it.messages.upserted(m, keepMine = true), pins = it.pins?.let { p -> refreshPins(p, m) }) }
            }
            is ChatEvent.ChannelMessageDeleted -> if (e.channelId == channelId) {
                _state.update { it.copy(messages = it.messages.tombstoned(e.messageId), pins = it.pins?.filter { p -> p.id != e.messageId }) }
            }
            is ChatEvent.ChannelReaction -> if (e.channelId == channelId) {
                val counts = e.reactions.keys().asSequence().associateWith { e.reactions.optInt(it, 0) }
                _state.update { it.copy(messages = it.messages.withReaction(e.messageId, e.userId, e.reaction, e.active, counts, repository.myId)) }
            }
            is ChatEvent.ChannelChanged -> if (e.communityId == communityId && (e.channelId == null || e.channelId == channelId)) {
                viewModelScope.launch {
                    try {
                        val channel = repository.directory(communityId).channels.firstOrNull { it.id == channelId }
                        _state.update { if (channel == null) it.copy(accessLost = true) else it.copy(channel = channel) }
                    } catch (ex: Exception) { if (ex.lostAccess()) _state.update { it.copy(accessLost = true) } }
                }
            }
            is ChatEvent.CommunityMembership -> if (e.communityId == communityId && MembershipState.parse(e.membership) != MembershipState.Joined) {
                // Removed or restricted while the screen is open: stop accepting messages at once.
                _state.update { it.copy(accessLost = true) }
            }
            else -> Unit
        }
    }

    private fun refreshPins(pins: List<ChannelMessage>, m: ChannelMessage): List<ChannelMessage> =
        if (m.pinned) pins.upserted(m, keepMine = true) else pins.filter { it.id != m.id }

    // ---------- composer

    fun setText(v: String) = _state.update { it.copy(text = v.take(4000)) }
    fun setTitle(v: String) = _state.update { it.copy(title = v.take(120)) }
    fun addPhoto(path: String) = _state.update { if (it.photos.size >= MAX_PHOTOS) it.copy(notice = "You can attach up to $MAX_PHOTOS photos.") else it.copy(photos = it.photos + path) }
    fun removePhoto(path: String) = _state.update { it.copy(photos = it.photos - path) }
    fun startReply(m: ChannelMessage) = _state.update { it.copy(replyTo = m, editing = null) }
    fun cancelReply() = _state.update { it.copy(replyTo = null) }
    fun startEdit(m: ChannelMessage) = _state.update { it.copy(editing = m, replyTo = null, text = m.content.orEmpty(), title = m.title.orEmpty(), photos = emptyList()) }
    fun cancelEdit() = _state.update { it.copy(editing = null, text = "", title = "") }
    fun consumeNotice() = _state.update { it.copy(notice = null) }

    fun send() {
        val s = _state.value
        val ch = s.channel ?: return
        s.editing?.let { saveEdit(it); return }
        val draft = ChannelDraft(
            title = if (ch.type.needsTitle && s.replyTo == null) s.title.trim().ifBlank { null } else null,
            content = s.text.trim(), replyToId = s.replyTo?.id, photoPaths = s.photos, clientId = UUID.randomUUID().toString(),
        )
        if (draft.content.isEmpty() && draft.photoPaths.isEmpty() && draft.title == null) return
        _state.update { it.copy(text = "", title = "", photos = emptyList(), replyTo = null, pending = it.pending + PendingSend(draft)) }
        deliver(draft)
    }

    fun retry(clientId: String) {
        val p = _state.value.pending.firstOrNull { it.draft.clientId == clientId } ?: return
        _state.update { st -> st.copy(pending = st.pending.map { if (it.draft.clientId == clientId) it.copy(failed = false, error = null) else it }) }
        deliver(p.draft)
    }

    fun discard(clientId: String) = _state.update { st -> st.copy(pending = st.pending.filter { it.draft.clientId != clientId }) }

    private fun deliver(draft: ChannelDraft) {
        viewModelScope.launch {
            try {
                val sent = repository.send(communityId, channelId, draft)
                _state.update { st ->
                    val parent = sent.replyTo?.id
                    val threaded = st.channel?.type?.threadedTimeline == true && parent != null
                    st.copy(
                        pending = st.pending.filter { it.draft.clientId != draft.clientId },
                        messages = if (threaded) st.messages.map { if (it.id == parent) it.copy(replyCount = it.replyCount + 1) else it } else st.messages.upserted(sent),
                    )
                }
                markRead()
            } catch (e: Exception) {
                if (e.lostAccess()) { _state.update { it.copy(accessLost = true) }; return@launch }
                // Rules changed while writing (locked, archived, moderators only): refresh what the composer may do.
                if (e is ApiException && e.status == 403) load()
                _state.update { st -> st.copy(pending = st.pending.map { if (it.draft.clientId == draft.clientId) it.copy(failed = true, error = e.userText()) else it }) }
            }
        }
    }

    private fun saveEdit(m: ChannelMessage) {
        val s = _state.value
        viewModelScope.launch {
            try {
                val updated = repository.edit(communityId, channelId, m.id, if (m.title != null) s.title.trim() else null, s.text.trim())
                _state.update { it.copy(messages = it.messages.upserted(updated), editing = null, text = "", title = "") }
            } catch (e: Exception) {
                if (e.lostAccess()) _state.update { it.copy(accessLost = true) } else _state.update { it.copy(notice = e.userText()) }
            }
        }
    }

    // ---------- message actions

    fun toggleReaction(m: ChannelMessage, reaction: String) {
        val on = reaction !in m.myReactions
        viewModelScope.launch {
            try {
                val r = repository.react(communityId, channelId, m.id, reaction, on)
                _state.update { it.copy(messages = it.messages.map { x -> if (x.id == m.id) x.copy(reactions = r.reactions, myReactions = r.mine) else x }) }
            } catch (e: Exception) {
                if (e.lostAccess()) _state.update { it.copy(accessLost = true) } else _state.update { it.copy(notice = e.userText()) }
            }
        }
    }

    fun togglePin(m: ChannelMessage) {
        viewModelScope.launch {
            try {
                val updated = repository.pin(communityId, channelId, m.id, !m.pinned)
                _state.update { it.copy(messages = it.messages.upserted(updated), pins = it.pins?.let { p -> refreshPins(p, updated) }, notice = if (updated.pinned) "Pinned" else "Unpinned") }
            } catch (e: Exception) {
                if (e.lostAccess()) _state.update { it.copy(accessLost = true) } else _state.update { it.copy(notice = e.userText()) }
            }
        }
    }

    fun delete(m: ChannelMessage) {
        viewModelScope.launch {
            try {
                repository.delete(communityId, channelId, m.id)
                _state.update { it.copy(messages = it.messages.tombstoned(m.id), pins = it.pins?.filter { p -> p.id != m.id }) }
            } catch (e: Exception) {
                if (e.lostAccess()) _state.update { it.copy(accessLost = true) } else _state.update { it.copy(notice = e.userText()) }
            }
        }
    }

    fun report(m: ChannelMessage, reason: String) {
        viewModelScope.launch {
            try { repository.report(m.id, reason); _state.update { it.copy(notice = "Thanks, we'll take a look.") } }
            catch (e: Exception) { _state.update { it.copy(notice = e.userText("We couldn't send your report.")) } }
        }
    }

    fun setLocked(locked: Boolean) = manage { repository.updateChannel(communityId, channelId, locked = locked) }
    fun setArchived(archived: Boolean) = manage { repository.updateChannel(communityId, channelId, archived = archived) }

    private fun manage(block: suspend () -> Unit) {
        viewModelScope.launch {
            try { block(); load() } catch (e: Exception) { _state.update { it.copy(notice = e.userText()) } }
        }
    }

    // ---------- pins and search

    fun showPins(show: Boolean) {
        if (!show) { _state.update { it.copy(pins = null) }; return }
        viewModelScope.launch {
            try { _state.update { it.copy(pins = repository.pins(communityId, channelId)) } }
            catch (e: Exception) { if (e.lostAccess()) _state.update { it.copy(accessLost = true) } else _state.update { it.copy(notice = e.userText()) } }
        }
    }

    fun setSearching(on: Boolean) = _state.update { if (on) it.copy(searching = true) else it.copy(searching = false, search = SearchFilters(), results = null) }

    fun search(filters: SearchFilters) {
        _state.update { it.copy(search = filters) }
        if (filters.text.trim().length < 2 && !filters.pinnedOnly && !filters.withAttachments) { _state.update { it.copy(results = null) }; return }
        viewModelScope.launch {
            _state.update { it.copy(searchLoading = true) }
            try {
                val page = repository.search(communityId, channelId, filters, null)
                // Ignore an answer that arrives after the student already typed something else.
                _state.update { if (it.search == filters) it.copy(results = page.items, searchLoading = false) else it }
            } catch (e: Exception) {
                if (e.lostAccess()) _state.update { it.copy(accessLost = true, searchLoading = false) } else _state.update { it.copy(searchLoading = false, notice = e.userText()) }
            }
        }
    }

    /** Marks an answer accepted (or clears it) from the thread or the timeline. */
    fun acceptAnswer(question: ChannelMessage, answerId: String?) {
        viewModelScope.launch {
            try {
                val updated = repository.acceptAnswer(communityId, channelId, question.id, answerId)
                _state.update { it.copy(messages = it.messages.upserted(updated)) }
            } catch (e: Exception) {
                if (e.lostAccess()) _state.update { it.copy(accessLost = true) } else _state.update { it.copy(notice = e.userText()) }
            }
        }
    }
}

// ------------------------------------------------------------------ thread

data class ThreadUiState(
    val loading: Boolean = true,
    val channel: Channel? = null,
    val root: ChannelMessage? = null,
    val replies: List<ChannelMessage> = emptyList(),
    val nextCursor: String? = null,
    val pending: List<PendingSend> = emptyList(),
    val text: String = "",
    val photos: List<String> = emptyList(),
    val accessLost: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
)

class ThreadViewModel(
    private val communityId: String,
    private val channelId: String,
    private val messageId: String,
    private val repository: ChannelRepository = AppContainer.channelRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(ThreadUiState())
    val state: StateFlow<ThreadUiState> = _state.asStateFlow()
    val myId: String? get() = repository.myId

    init {
        load()
        viewModelScope.launch { repository.live.collect(::onLive) }
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = it.root == null, error = null) }
            try {
                val channel = repository.directory(communityId).channels.firstOrNull { it.id == channelId }
                if (channel == null) { _state.update { it.copy(loading = false, accessLost = true) }; return@launch }
                val page = repository.thread(communityId, channelId, messageId, null)
                _state.update { it.copy(loading = false, channel = channel, root = page.root, replies = it.replies.upsertedAll(page.replies), nextCursor = page.nextCursor) }
            } catch (e: Exception) {
                if (e.lostAccess()) _state.update { it.copy(loading = false, accessLost = true) }
                else _state.update { it.copy(loading = false, error = if (it.root == null) e.userText("We couldn't load this thread.") else null) }
            }
        }
    }

    fun loadMore() {
        val cursor = _state.value.nextCursor ?: return
        viewModelScope.launch {
            try {
                val page = repository.thread(communityId, channelId, messageId, cursor)
                _state.update { it.copy(replies = it.replies.upsertedAll(page.replies), nextCursor = page.nextCursor) }
            } catch (e: Exception) { if (e.lostAccess()) _state.update { it.copy(accessLost = true) } }
        }
    }

    private fun onLive(e: ChatEvent) {
        when (e) {
            is ChatEvent.Connected -> load()
            is ChatEvent.ChannelMessageNew -> if (e.channelId == channelId) {
                val m = com.univibe.app.data.repository.parseChannelMessage(e.message)
                if (m.replyTo?.id == messageId) _state.update { it.copy(replies = it.replies.upserted(m), root = it.root?.let { r -> r.copy(replyCount = r.replyCount + 1) }) }
            }
            is ChatEvent.ChannelMessageUpdated -> if (e.channelId == channelId) {
                val m = com.univibe.app.data.repository.parseChannelMessage(e.message)
                _state.update { st ->
                    when {
                        m.id == messageId -> st.copy(root = m.copy(myReactions = st.root?.myReactions ?: emptySet()))
                        st.replies.any { it.id == m.id } -> st.copy(replies = st.replies.upserted(m, keepMine = true))
                        else -> st
                    }
                }
            }
            is ChatEvent.ChannelMessageDeleted -> if (e.channelId == channelId) {
                _state.update { st -> st.copy(replies = st.replies.tombstoned(e.messageId), root = st.root?.let { r -> if (r.id == e.messageId) listOf(r).tombstoned(r.id).first() else r }) }
            }
            is ChatEvent.ChannelReaction -> if (e.channelId == channelId) {
                val counts = e.reactions.keys().asSequence().associateWith { e.reactions.optInt(it, 0) }
                _state.update { st ->
                    st.copy(
                        replies = st.replies.withReaction(e.messageId, e.userId, e.reaction, e.active, counts, repository.myId),
                        root = st.root?.let { r -> listOf(r).withReaction(e.messageId, e.userId, e.reaction, e.active, counts, repository.myId).first() },
                    )
                }
            }
            is ChatEvent.ChannelChanged -> if (e.communityId == communityId) load()
            is ChatEvent.CommunityMembership -> if (e.communityId == communityId && MembershipState.parse(e.membership) != MembershipState.Joined) _state.update { it.copy(accessLost = true) }
            else -> Unit
        }
    }

    fun setText(v: String) = _state.update { it.copy(text = v.take(4000)) }
    fun addPhoto(path: String) = _state.update { if (it.photos.size >= MAX_PHOTOS) it.copy(notice = "You can attach up to $MAX_PHOTOS photos.") else it.copy(photos = it.photos + path) }
    fun removePhoto(path: String) = _state.update { it.copy(photos = it.photos - path) }
    fun consumeNotice() = _state.update { it.copy(notice = null) }

    fun send() {
        val s = _state.value
        val draft = ChannelDraft(null, s.text.trim(), messageId, s.photos, UUID.randomUUID().toString())
        if (draft.content.isEmpty() && draft.photoPaths.isEmpty()) return
        _state.update { it.copy(text = "", photos = emptyList(), pending = it.pending + PendingSend(draft)) }
        deliver(draft)
    }

    fun retry(clientId: String) {
        val p = _state.value.pending.firstOrNull { it.draft.clientId == clientId } ?: return
        _state.update { st -> st.copy(pending = st.pending.map { if (it.draft.clientId == clientId) it.copy(failed = false, error = null) else it }) }
        deliver(p.draft)
    }

    fun discard(clientId: String) = _state.update { st -> st.copy(pending = st.pending.filter { it.draft.clientId != clientId }) }

    private fun deliver(draft: ChannelDraft) {
        viewModelScope.launch {
            try {
                val sent = repository.send(communityId, channelId, draft)
                _state.update { st ->
                    st.copy(
                        pending = st.pending.filter { it.draft.clientId != draft.clientId },
                        replies = if (st.replies.any { it.id == sent.id }) st.replies else st.replies.upserted(sent),
                        // The live event may already have counted this reply; the root is re-read to be exact.
                    )
                }
                load()
            } catch (e: Exception) {
                if (e.lostAccess()) { _state.update { it.copy(accessLost = true) }; return@launch }
                if (e is ApiException && e.status == 403) load()
                _state.update { st -> st.copy(pending = st.pending.map { if (it.draft.clientId == draft.clientId) it.copy(failed = true, error = e.userText()) else it }) }
            }
        }
    }

    fun toggleReaction(m: ChannelMessage, reaction: String) {
        val on = reaction !in m.myReactions
        viewModelScope.launch {
            try {
                val r = repository.react(communityId, channelId, m.id, reaction, on)
                val apply = { x: ChannelMessage -> if (x.id == m.id) x.copy(reactions = r.reactions, myReactions = r.mine) else x }
                _state.update { it.copy(root = it.root?.let(apply), replies = it.replies.map(apply)) }
            } catch (e: Exception) { if (e.lostAccess()) _state.update { it.copy(accessLost = true) } else _state.update { it.copy(notice = e.userText()) } }
        }
    }

    fun delete(m: ChannelMessage) {
        viewModelScope.launch {
            try {
                repository.delete(communityId, channelId, m.id)
                _state.update { st -> st.copy(replies = st.replies.tombstoned(m.id), root = st.root?.let { r -> if (r.id == m.id) listOf(r).tombstoned(r.id).first() else r }) }
            } catch (e: Exception) { if (e.lostAccess()) _state.update { it.copy(accessLost = true) } else _state.update { it.copy(notice = e.userText()) } }
        }
    }

    /** Q&A: mark (or clear) the accepted answer of this thread's question. */
    fun accept(answerId: String?) {
        val root = _state.value.root ?: return
        viewModelScope.launch {
            try {
                val updated = repository.acceptAnswer(communityId, channelId, root.id, answerId)
                _state.update { it.copy(root = updated.copy(myReactions = root.myReactions)) }
                load()
            } catch (e: Exception) { if (e.lostAccess()) _state.update { it.copy(accessLost = true) } else _state.update { it.copy(notice = e.userText()) } }
        }
    }

    fun report(m: ChannelMessage, reason: String) {
        viewModelScope.launch {
            try { repository.report(m.id, reason); _state.update { it.copy(notice = "Thanks, we'll take a look.") } }
            catch (e: Exception) { _state.update { it.copy(notice = e.userText("We couldn't send your report.")) } }
        }
    }
}
