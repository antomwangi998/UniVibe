package com.univibe.app.features.chats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.univibe.app.app.AppContainer
import com.univibe.app.data.model.Chat
import com.univibe.app.data.model.GroupInfo
import com.univibe.app.data.model.Message
import com.univibe.app.data.model.Reaction
import com.univibe.app.data.model.ReplyRef
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
    val highlightId: String? = null,
    val notice: String? = null,
) {
    val canSend get() = input.isNotBlank() && input.length <= MAX_MESSAGE
    /** False in admins-only groups for non-admins. The server enforces this as well. */
    val canPost get() = group?.canMessage ?: true
    val title get() = group?.name ?: chat?.title ?: "Chat"
}

fun replyPreview(m: Message): String =
    if (m.text.isBlank() && m.imagePath != null) "Photo" else m.text.take(120)

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

    fun onInput(v: String) = _state.update { it.copy(input = v) }
    fun consumeNotice() = _state.update { it.copy(notice = null) }

    fun send() {
        val s = _state.value
        if (!s.canSend || !s.canPost) return
        val reply = s.replyTo?.let { ReplyRef(it.id, if (it.isMine) "You" else it.senderName, replyPreview(it)) }
        _state.update { it.copy(input = "", replyTo = null) }
        viewModelScope.launch {
            try {
                messages.send(chatId, s.input, null, reply)
            } catch (e: Exception) {
                // Give the text back so nothing is lost.
                _state.update { it.copy(input = s.input, replyTo = s.replyTo, notice = e.message?.takeIf { m -> m.length < 90 } ?: "Couldn't send. Try again.") }
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
            catch (e: Exception) { _state.update { it.copy(notice = "Couldn't send the photo.") } }
        }
    }

    fun openMenu(message: Message?) = _state.update { it.copy(menuMessage = message) }

    fun startReply(m: Message) = _state.update { it.copy(replyTo = m, menuMessage = null) }
    fun cancelReply() = _state.update { it.copy(replyTo = null) }

    /** Tapping your current reaction again removes it. */
    fun react(m: Message, reaction: Reaction) {
        _state.update { it.copy(menuMessage = null) }
        val next = if (m.myReaction == reaction) null else reaction
        viewModelScope.launch { runCatching { messages.react(chatId, m.id, next) } }
    }

    fun highlight(id: String?) = _state.update { it.copy(highlightId = id) }

    fun deleteMessage() {
        val m = _state.value.menuMessage ?: return
        _state.update { it.copy(menuMessage = null, replyTo = if (it.replyTo?.id == m.id) null else it.replyTo) }
        viewModelScope.launch { runCatching { messages.deleteMessage(chatId, m.id) } }
    }
}
