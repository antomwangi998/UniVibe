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

data class ChatsUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val query: String = "",
    val all: List<Chat> = emptyList(),
) {
    val visible: List<Chat>
        get() = if (query.isBlank()) all else all.filter {
            it.title.contains(query, ignoreCase = true) || it.lastMessage.contains(query, ignoreCase = true)
        }
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
}
