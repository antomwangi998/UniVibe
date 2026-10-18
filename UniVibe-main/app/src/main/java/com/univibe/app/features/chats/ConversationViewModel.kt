package com.univibe.app.features.chats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.univibe.app.app.AppContainer
import com.univibe.app.data.model.Chat
import com.univibe.app.data.model.EDIT_WINDOW_MILLIS
import com.univibe.app.data.model.GroupInfo
import com.univibe.app.data.model.Message
import com.univibe.app.data.model.Reaction
import com.univibe.app.data.model.ReplyRef
import com.univibe.app.data.model.Student
import com.univibe.app.data.repository.ChatRepository
import com.univibe.app.data.repository.GroupRepository
import com.univibe.app.data.repository.MessageRepository
import com.univibe.app.data.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

const val MAX_MESSAGE = 1000
const val MAX_FORWARD_TARGETS = 5

data class ConversationUiState(
    val loading: Boolean = true,
    val notFound: Boolean = false,
    val chat: Chat? = null,
    /** Present for group chats; carries who may post. */
    val group: GroupInfo? = null,
    val messages: List<Message> = emptyList(),
    val typing: Boolean = false,
    val input: String = "",
    val showReceipts: Boolean = true,
    val menuMessage: Message? = null,
    val replyTo: Message? = null,
    val editing: Message? = null,
    val pinnedId: String? = null,
    /** Earlier messages exist on the server that are not loaded yet. */
    val canLoadOlder: Boolean = false,
    val highlightId: String? = null,
    /** One-shot: the screen scrolls to this message, then calls consumeJump. */
    val jumpTo: String? = null,
    val searching: Boolean = false,
    val query: String = "",
    val searchIndex: Int = 0,
    val forwarding: Message? = null,
    val forwardTargets: List<Chat> = emptyList(),
    val forwardSelected: Set<String> = emptySet(),
    val forwardBusy: Boolean = false,
    val viewerPath: String? = null,
    val notice: String? = null,
) {
    val canSend get() = input.isNotBlank() && input.length <= MAX_MESSAGE
    /** False in admins-only groups for non-admins. The server enforces this as well. */
    val canPost get() = group?.canMessage ?: true
    val title get() = group?.name ?: chat?.title ?: "Chat"
    val pinned: Message? get() = pinnedId?.let { id -> messages.firstOrNull { it.id == id } }
    /** Anyone in a 1:1 chat; only admins in a group. */
    val canPin get() = group?.iAmAdmin ?: true

    /** Search results, newest first. */
    val matches: List<Message>
        get() = if (searching && query.isNotBlank()) messages.filter { it.text.contains(query.trim(), ignoreCase = true) }.asReversed() else emptyList()

    /** The "@name" being typed at the end of the text (group chats only), without the "@". */
    val mentionQuery: String?
        get() {
            if (group == null) return null
            val i = input.lastIndexOf('@')
            if (i < 0 || (i > 0 && !input[i - 1].isWhitespace())) return null
            val q = input.substring(i + 1)
            return if (q.any { it.isWhitespace() }) null else q
        }

    val mentionSuggestions: List<Student>
        get() {
            val q = mentionQuery ?: return emptyList()
            return group?.members.orEmpty()
                .filter { !it.isMe && (it.student.handle.removePrefix("@").startsWith(q, ignoreCase = true) || it.student.name.contains(q, ignoreCase = true)) }
                .take(5).map { it.student }
        }
}

fun replyPreview(m: Message): String =
    if (m.text.isBlank() && m.imagePath != null) "Photo" else m.text.take(120)

fun canEditMessage(m: Message, now: Long = System.currentTimeMillis()): Boolean =
    m.isMine && m.text.isNotBlank() && now - m.timeMillis <= EDIT_WINDOW_MILLIS

class ConversationViewModel(
    private val chatId: String,
    private val chats: ChatRepository = AppContainer.chatRepository,
    private val messages: MessageRepository = AppContainer.messageRepository,
    private val groups: GroupRepository = AppContainer.groupRepository,
    private val settings: SettingsRepository = AppContainer.settingsRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(ConversationUiState())
    val state: StateFlow<ConversationUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val chat = runCatching { chats.getChat(chatId) }.getOrNull()
            if (chat == null) { _state.update { it.copy(loading = false, notFound = true) }; return@launch }
            val group = if (chat.isGroup) runCatching { groups.getGroup(chatId) }.getOrNull() else null
            _state.update { it.copy(loading = false, chat = chat, group = group) }
            runCatching { messages.markRead(chatId) }
            launch { messages.messages(chatId).collect { list -> _state.update { it.copy(messages = list) } } }
            launch { messages.typing(chatId).collect { t -> _state.update { it.copy(typing = t) } } }
            launch { messages.pinnedMessageId(chatId).collect { id -> _state.update { it.copy(pinnedId = id) } } }
            launch { messages.hasOlder(chatId).collect { more -> _state.update { it.copy(canLoadOlder = more) } } }
            launch { settings.settings.collect { s -> _state.update { it.copy(showReceipts = s.showReadReceipts) } } }
        }
    }

    /** Quiet reload when coming back from group info (name, photo or permissions may have changed). */
    fun refresh() {
        val chat = _state.value.chat ?: return
        viewModelScope.launch {
            val fresh = runCatching { chats.getChat(chatId) }.getOrNull() ?: chat
            val group = if (fresh.isGroup) runCatching { groups.getGroup(chatId) }.getOrNull() else null
            _state.update { it.copy(chat = fresh, group = group) }
        }
    }

    private fun fail(e: Exception, fallback: String) =
        _state.update { it.copy(notice = e.message?.takeIf { m -> m.length < 90 } ?: fallback) }

    fun onInput(v: String) = _state.update { it.copy(input = v) }

    fun loadOlder() {
        viewModelScope.launch {
            try { messages.loadOlder(chatId) } catch (e: Exception) { fail(e, "Couldn't load earlier messages.") }
        }
    }
    fun consumeNotice() = _state.update { it.copy(notice = null) }

    // ---- sending, replying, editing -------------------------------------------------------------

    fun send() {
        val s = _state.value
        if (!s.canSend || !s.canPost) return
        s.editing?.let { m ->
            _state.update { it.copy(input = "", editing = null) }
            viewModelScope.launch {
                try { messages.editMessage(chatId, m.id, s.input) }
                catch (e: Exception) { _state.update { it.copy(input = s.input, editing = m) }; fail(e, "Couldn't save the edit.") }
            }
            return
        }
        val reply = s.replyTo?.let { ReplyRef(it.id, if (it.isMine) "You" else it.senderName, replyPreview(it)) }
        _state.update { it.copy(input = "", replyTo = null) }
        viewModelScope.launch {
            try {
                messages.send(chatId, s.input, null, reply)
            } catch (e: Exception) {
                // Give the text back so nothing is lost.
                _state.update { it.copy(input = s.input, replyTo = s.replyTo) }
                fail(e, "Couldn't send. Try again.")
            }
        }
    }

    fun sendImage(path: String) {
        if (!_state.value.canPost) return
        val s = _state.value
        val reply = s.replyTo?.let { ReplyRef(it.id, if (it.isMine) "You" else it.senderName, replyPreview(it)) }
        _state.update { it.copy(replyTo = null) }
        viewModelScope.launch {
            try { messages.send(chatId, "", path, reply) }
            catch (e: Exception) { fail(e, "Couldn't send the photo.") }
        }
    }

    fun openMenu(message: Message?) = _state.update { it.copy(menuMessage = message) }
    fun startReply(m: Message) = _state.update { it.copy(replyTo = m, editing = null, menuMessage = null) }
    fun cancelReply() = _state.update { it.copy(replyTo = null) }

    fun startEdit(m: Message) {
        if (!canEditMessage(m)) { _state.update { it.copy(menuMessage = null, notice = "Messages can only be edited for 15 minutes.") }; return }
        _state.update { it.copy(editing = m, replyTo = null, input = m.text, menuMessage = null) }
    }

    fun cancelEdit() = _state.update { it.copy(editing = null, input = "") }

    /** Tapping your current reaction again removes it. */
    fun react(m: Message, reaction: Reaction) {
        _state.update { it.copy(menuMessage = null) }
        val next = if (m.myReaction == reaction) null else reaction
        viewModelScope.launch { runCatching { messages.react(chatId, m.id, next) } }
    }

    fun deleteMessage() {
        val m = _state.value.menuMessage ?: return
        _state.update {
            it.copy(
                menuMessage = null,
                replyTo = if (it.replyTo?.id == m.id) null else it.replyTo,
                editing = if (it.editing?.id == m.id) null else it.editing,
            )
        }
        viewModelScope.launch { runCatching { messages.deleteMessage(chatId, m.id) } }
    }

    // ---- mentions -------------------------------------------------------------------------------

    fun pickMention(student: Student) = _state.update { s ->
        val i = s.input.lastIndexOf('@')
        if (i < 0) s else s.copy(input = s.input.substring(0, i) + student.handle + " ")
    }

    // ---- pinning --------------------------------------------------------------------------------

    fun pin(m: Message?) {
        _state.update { it.copy(menuMessage = null) }
        viewModelScope.launch { try { messages.pin(chatId, m?.id) } catch (e: Exception) { fail(e, "Couldn't change the pinned message.") } }
    }

    // ---- jumping and search ---------------------------------------------------------------------

    fun jumpTo(id: String) = _state.update { it.copy(jumpTo = id, highlightId = id) }
    fun consumeJump() = _state.update { it.copy(jumpTo = null) }
    fun highlight(id: String?) = _state.update { it.copy(highlightId = id) }

    fun openSearch() = _state.update { it.copy(searching = true, query = "", searchIndex = 0) }
    fun closeSearch() = _state.update { it.copy(searching = false, query = "", searchIndex = 0, highlightId = null) }

    fun setQuery(q: String) {
        _state.update { it.copy(query = q, searchIndex = 0) }
        _state.value.matches.firstOrNull()?.let { jumpTo(it.id) }
    }

    /** [delta] +1 goes to an older match, -1 to a newer one; wraps around. */
    fun stepSearch(delta: Int) {
        val matches = _state.value.matches
        if (matches.isEmpty()) return
        val next = (_state.value.searchIndex + delta).mod(matches.size)
        _state.update { it.copy(searchIndex = next) }
        jumpTo(matches[next].id)
    }

    // ---- forwarding -----------------------------------------------------------------------------

    fun openForward(m: Message) {
        _state.update { it.copy(menuMessage = null, forwarding = m, forwardSelected = emptySet(), forwardTargets = emptyList()) }
        viewModelScope.launch {
            val list = runCatching { chats.getChats() }.getOrDefault(emptyList()).filter { it.id != chatId }
            _state.update { it.copy(forwardTargets = list) }
        }
    }

    fun closeForward() = _state.update { it.copy(forwarding = null, forwardSelected = emptySet(), forwardBusy = false) }

    fun toggleForward(id: String) = _state.update { s ->
        when {
            id in s.forwardSelected -> s.copy(forwardSelected = s.forwardSelected - id)
            s.forwardSelected.size >= MAX_FORWARD_TARGETS -> s.copy(notice = "You can forward to up to $MAX_FORWARD_TARGETS chats.")
            else -> s.copy(forwardSelected = s.forwardSelected + id)
        }
    }

    fun confirmForward() {
        val s = _state.value
        val m = s.forwarding ?: return
        if (s.forwardSelected.isEmpty() || s.forwardBusy) return
        viewModelScope.launch {
            _state.update { it.copy(forwardBusy = true) }
            try {
                messages.forward(chatId, m.id, s.forwardSelected.toList())
                _state.update { it.copy(forwarding = null, forwardSelected = emptySet(), forwardBusy = false, notice = "Forwarded") }
            } catch (e: Exception) {
                _state.update { it.copy(forwardBusy = false) }
                fail(e, "Couldn't forward. Nothing was sent.")
            }
        }
    }

    // ---- photo viewer ---------------------------------------------------------------------------

    fun openViewer(path: String?) = _state.update { it.copy(viewerPath = path) }
}
