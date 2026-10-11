package com.univibe.app.features.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.univibe.app.data.model.Student
import com.univibe.app.data.repository.DiscoverRepository
import com.univibe.app.data.repository.MockDiscoverRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DiscoverUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val deck: List<Student> = emptyList(),
)

class DiscoverViewModel(
    private val repository: DiscoverRepository = MockDiscoverRepository(),
) : ViewModel() {
    private val _state = MutableStateFlow(DiscoverUiState())
    val state: StateFlow<DiscoverUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val deck = repository.getCandidates()
                _state.update { it.copy(loading = false, deck = deck) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = "We couldn't load students right now.") }
            }
        }
    }

    fun decide(liked: Boolean) {
        val top = _state.value.deck.firstOrNull() ?: return
        _state.update { it.copy(deck = it.deck.drop(1)) }
        viewModelScope.launch { runCatching { repository.recordDecision(top.id, liked) } }
    }
}
