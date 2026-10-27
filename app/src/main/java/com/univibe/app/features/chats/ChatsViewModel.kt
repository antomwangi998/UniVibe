package com.univibe.app.features.chats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.univibe.app.data.model.Chat
import com.univibe.app.data.repository.ChatRepository
import com.univibe.app.app.AppContainer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ChatFilter(val label: String) { All("All"), Unread("Unread"), Groups("Groups"), Archived("Archived") }

data class ChatsUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val error: String? = null,
    val query: String = "",
    val filter: ChatFilter = ChatFilter.All,
    val showFilters: Boolean = false,
    val all: List<Chat> = emptyList(),
    /** Loaded when the Archived filter is chosen; the main list never contains these. */
    val archived: List<Chat> = emptyList(),
    /** The conversation whose long-press menu is open. */
    val menuChatId: String? = null,
    /** Conversations with a change in flight: further taps on them are ignored, and refreshes keep our version. */
    val busy: Set<String> = emptySet(),
    /** Who is typing, per conversation. Comes from live events and expires by itself. */
    val typing: Map<String, Set<String>> = emptyMap(),
    val notice: String? = null,
) {
    val menuChat: Chat? get() = menuChatId?.let { id -> all.firstOrNull { it.id == id } ?: archived.firstOrNull { it.id == id } }
    val visible: List<Chat>
        get() = (if (filter == ChatFilter.Archived) archived else all).filter { chat ->
            val matchesQuery = query.isBlank() ||
                chat.title.contains(query, ignoreCase = true) || chat.lastMessage.contains(query, ignoreCase = true)
            val matchesFilter = when (filter) {
                ChatFilter.All -> true
                ChatFilter.Unread -> chat.hasUnread
                ChatFilter.Groups -> chat.isGroup
                ChatFilter.Archived -> true
            }
            matchesQuery && matchesFilter
        }
    // Archived chats are one flat list: pinning is a main-list idea.
    val pinned: List<Chat> get() = if (filter == ChatFilter.Archived) emptyList() else visible.filter { it.pinned }
    val others: List<Chat> get() = if (filter == ChatFilter.Archived) visible else visible.filter { !it.pinned }
}

class ChatsViewModel(
    private val repository: ChatRepository = AppContainer.chatRepository,
    /** Receives the main chat list whenever it is fresh, for the tab badge. Failing to reach the app container (unit tests) is harmless. */
    private val onChatsSettled: (List<Chat>) -> Unit = { chats -> runCatching { AppContainer.chatUnread.publish(chats) } },
) : ViewModel() {
    private val _state = MutableStateFlow(ChatsUiState())
    val state: StateFlow<ChatsUiState> = _state.asStateFlow()

    init {
        load()
        // The server says when something changed; a short pause lets a burst of events become one reload.
        viewModelScope.launch { repository.changes().conflate().collect { delay(300); load(silent = true) } }
        // The teal dot follows live presence. Only people who share their activity with this student ever send it.
        viewModelScope.launch {
            repository.presence().collect { p ->
                _state.update { s ->
                    val apply = { c: Chat -> if (c.studentId == p.userId) c.copy(online = p.online, lastActiveMs = if (p.online) c.lastActiveMs else System.currentTimeMillis()) else c }
                    s.copy(all = s.all.map(apply), archived = s.archived.map(apply))
                }
            }
        }
        viewModelScope.launch { repository.typingByChat().collect { t -> _state.update { it.copy(typing = t) } } }
    }

    fun load(silent: Boolean = false, pull: Boolean = false) {
        viewModelScope.launch {
            if (!silent) _state.update { it.copy(loading = true, error = null) }
            if (pull) _state.update { it.copy(refreshing = true) }
            try {
                val chats = repository.getChats()
                _state.update { s ->
                    // A chat we are changing right now keeps our version: the answer may have been computed before the change.
                    val local = s.all.associateBy { it.id }
                    val merged = chats.mapNotNull { c -> if (c.id in s.busy) local[c.id] else c }
                    s.copy(loading = false, refreshing = false, all = merged)
                }
                // The Chats list just read the server's counts: hand the same list to the tab badge (no second request).
                publishUnread()
            } catch (e: Exception) {
                _state.update { it.copy(refreshing = false) }
                if (silent) return@launch
                _state.update { it.copy(loading = false, error = "We couldn't load your messages.") }
            }
        }
    }

    fun pullRefresh() { load(silent = true, pull = true); if (_state.value.filter == ChatFilter.Archived) loadArchived() }

    fun refresh() { load(silent = true); if (_state.value.filter == ChatFilter.Archived) loadArchived() }

    private fun loadArchived() {
        viewModelScope.launch {
            try { val list = repository.getArchivedChats(); _state.update { it.copy(archived = list) } }
            catch (e: Exception) { _state.update { it.copy(notice = "Couldn't load archived chats.") } }
        }
    }

    /** Moves the chat back to the main list. Put back in the archive on failure. */
    fun unarchive(chatId: String) {
        val before = _state.value
        val chat = before.archived.firstOrNull { it.id == chatId } ?: return
        if (chatId in before.busy) return
        _state.update { it.copy(busy = it.busy + chatId, menuChatId = null, archived = it.archived.filter { c -> c.id != chatId }) }
        viewModelScope.launch {
            try {
                repository.unarchive(chatId)
                _state.update { it.copy(busy = it.busy - chatId) }
                load(silent = true)
            } catch (e: Exception) {
                _state.update { s -> s.copy(busy = s.busy - chatId, archived = (s.archived + chat), notice = "Couldn't unarchive the chat. Try again.") }
            }
        }
    }

    fun setQuery(query: String) = _state.update { it.copy(query = query) }
    fun setFilter(filter: ChatFilter) {
        _state.update { it.copy(filter = filter) }
        if (filter == ChatFilter.Archived) loadArchived()
    }
    fun toggleFilters() = _state.update { it.copy(showFilters = !it.showFilters) }

    fun openMenu(chatId: String) = _state.update { it.copy(menuChatId = chatId) }
    fun closeMenu() = _state.update { it.copy(menuChatId = null) }
    fun consumeNotice() = _state.update { it.copy(notice = null) }

    fun toggleMute(chatId: String) {
        val chat = chatOrNull(chatId) ?: return
        val muted = !chat.muted
        change(chatId, { it.copy(muted = muted) }, "Couldn't change notifications. Try again.") { repository.setMuted(chatId, muted) }
    }

    fun togglePinned(chatId: String) {
        val chat = chatOrNull(chatId) ?: return
        val pinned = !chat.pinned
        change(chatId, { it.copy(pinned = pinned) }, if (pinned) "Couldn't pin the chat. Try again." else "Couldn't unpin the chat. Try again.") {
            repository.setPinned(chatId, pinned)
        }
    }

    /**
     * Unread -> read uses the server's read call (the same one opening the chat makes). Read -> unread only sets the
     * server's "marked unread" flag; it never rewinds read receipts, and the next read clears it.
     */
    fun toggleUnread(chatId: String) {
        val chat = chatOrNull(chatId) ?: return
        if (chat.hasUnread) {
            change(chatId, { it.copy(unread = 0, markedOnly = false) }, "Couldn't mark as read. Try again.") { repository.markRead(chatId) }
        } else {
            change(chatId, { it.copy(unread = 1, markedOnly = true) }, "Couldn't mark as unread. Try again.") { repository.markUnread(chatId) }
        }
    }

    fun archive(chatId: String) {
        change(chatId, { null }, "Couldn't archive the chat. Try again.") { repository.archive(chatId) }
    }

    /** Gives the tab badge this list's unread total, unless a change is still in flight (then our rows may be ahead of the server). */
    private fun publishUnread() {
        if (_state.value.busy.isEmpty()) onChatsSettled(_state.value.all)
    }

    private fun chatOrNull(chatId: String): Chat? = _state.value.all.firstOrNull { it.id == chatId }

    /**
     * Applies [optimistic] at once (null removes the row), then asks the server. A second change to the same chat is ignored
     * while one is running. On failure only this chat is put back, and only if nobody changed it in the meantime.
     */
    private fun change(chatId: String, optimistic: (Chat) -> Chat?, failure: String, call: suspend () -> Unit) {
        val before = _state.value
        val previous = before.all.firstOrNull { it.id == chatId } ?: return
        if (chatId in before.busy) return
        val index = before.all.indexOfFirst { it.id == chatId }
        val applied = optimistic(previous)
        _state.update { s ->
            s.copy(
                busy = s.busy + chatId, menuChatId = null,
                all = if (applied == null) s.all.filter { it.id != chatId } else s.all.map { if (it.id == chatId) applied else it },
            )
        }
        viewModelScope.launch {
            try {
                call()
                _state.update { it.copy(busy = it.busy - chatId) }
                publishUnread()
            } catch (e: Exception) {
                _state.update { s ->
                    val now = s.all.firstOrNull { it.id == chatId }
                    val untouched = if (applied == null) now == null else now == applied
                    val restored = when {
                        !untouched -> s.all // something newer arrived for this chat; keep it
                        now == null -> s.all.toMutableList().also { it.add(index.coerceIn(0, it.size), previous) }
                        else -> s.all.map { if (it.id == chatId) previous else it }
                    }
                    s.copy(busy = s.busy - chatId, all = restored, notice = failure)
                }
                publishUnread()
            }
        }
    }
}
