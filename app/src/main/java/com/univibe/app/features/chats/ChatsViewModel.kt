package com.univibe.app.features.chats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.univibe.app.data.model.Chat
import com.univibe.app.data.repository.ChatRepository
import com.univibe.app.data.repository.MockChatRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ChatFilter(val label: String) { All("All"), Unread("Unread"), Groups("Groups") }

data class ChatsUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val query: String = "",
    val filter: ChatFilter = ChatFilter.All,
    val showFilters: Boolean = false,
    val all: List<Chat> = emptyList(),
) {
    val visible: List<Chat>
        get() = all.filter { chat ->
            val matchesQuery = query.isBlank() ||
                chat.title.contains(query, ignoreCase = true) || chat.lastMessage.contains(query, ignoreCase = true)
            val matchesFilter = when (filter) {
                ChatFilter.All -> true
                ChatFilter.Unread -> chat.unread > 0
                ChatFilter.Groups -> chat.isGroup
            }
            matchesQuery && matchesFilter
        }
    val pinned: List<Chat> get() = visible.filter { it.pinned }
    val others: List<Chat> get() = visible.filter { !it.pinned }
}

class ChatsViewModel(
    private val repository: ChatRepository = MockChatRepository(),
) : ViewModel() {
    private val _state = MutableStateFlow(ChatsUiState())
    val state: StateFlow<ChatsUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val chats = repository.getChats()
                _state.update { it.copy(loading = false, all = chats) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = "We couldn't load your messages.") }
            }
        }
    }

    fun setQuery(query: String) = _state.update { it.copy(query = query) }
    fun setFilter(filter: ChatFilter) = _state.update { it.copy(filter = filter) }
    fun toggleFilters() = _state.update { it.copy(showFilters = !it.showFilters) }

    fun toggleMute(chatId: String) {
        val chat = _state.value.all.firstOrNull { it.id == chatId } ?: return
        val muted = !chat.muted
        _state.update { s -> s.copy(all = s.all.map { if (it.id == chatId) it.copy(muted = muted) else it }) }
        viewModelScope.launch { runCatching { repository.setMuted(chatId, muted) } }
    }

    fun archive(chatId: String) {
        _state.update { s -> s.copy(all = s.all.filter { it.id != chatId }) }
        viewModelScope.launch { runCatching { repository.archive(chatId) } }
    }
}
