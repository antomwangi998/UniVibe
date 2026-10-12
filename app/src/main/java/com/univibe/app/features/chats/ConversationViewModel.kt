package com.univibe.app.features.chats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.univibe.app.app.AppContainer
import com.univibe.app.data.model.Chat
import com.univibe.app.data.model.Message
import com.univibe.app.data.repository.ChatRepository
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
    val messages: List<Message> = emptyList(),
    val typing: Boolean = false,
    val input: String = "",
    val showReceipts: Boolean = true,
    val menuMessage: Message? = null,
) {
    val canSend get() = input.isNotBlank() && input.length <= MAX_MESSAGE
}

class ConversationViewModel(
    private val chatId: String,
    private val chats: ChatRepository = AppContainer.chatRepository,
    private val messages: MessageRepository = AppContainer.messageRepository,
    private val settings: SettingsRepository = AppContainer.settingsRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(ConversationUiState())
    val state: StateFlow<ConversationUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val chat = runCatching { chats.getChat(chatId) }.getOrNull()
            if (chat == null) { _state.update { it.copy(loading = false, notFound = true) }; return@launch }
            _state.update { it.copy(loading = false, chat = chat) }
            runCatching { messages.markRead(chatId) }
            launch { messages.messages(chatId).collect { list -> _state.update { it.copy(messages = list) } } }
            launch { messages.typing(chatId).collect { t -> _state.update { it.copy(typing = t) } } }
            launch { settings.settings.collect { s -> _state.update { it.copy(showReceipts = s.showReadReceipts) } } }
        }
    }

    fun onInput(v: String) = _state.update { it.copy(input = v) }

    fun send() {
        val s = _state.value
        if (!s.canSend) return
        _state.update { it.copy(input = "") }
        viewModelScope.launch { runCatching { messages.send(chatId, s.input) } }
    }

    fun sendImage(path: String) { viewModelScope.launch { runCatching { messages.send(chatId, "", path) } } }

    fun openMenu(message: Message?) = _state.update { it.copy(menuMessage = message) }

    fun deleteMessage() {
        val m = _state.value.menuMessage ?: return
        _state.update { it.copy(menuMessage = null) }
        viewModelScope.launch { runCatching { messages.deleteMessage(chatId, m.id) } }
    }
}
