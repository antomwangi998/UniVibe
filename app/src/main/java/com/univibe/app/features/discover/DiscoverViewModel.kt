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

/** No location-based filter on purpose: precise location needs explicit consent. */
enum class DiscoverFilter(val label: String) {
    All("All"), SameCourse("Same course"), New("New"), Common("Common interests")
}

data class DiscoverUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val remaining: List<Student> = emptyList(),
    val total: Int = 0,
    val filter: DiscoverFilter = DiscoverFilter.All,
    val showInfo: Boolean = false,
) {
    val deck: List<Student>
        get() = when (filter) {
            DiscoverFilter.All -> remaining
            DiscoverFilter.SameCourse -> remaining.filter { it.sameCourse }
            DiscoverFilter.New -> remaining.filter { it.isNew }
            DiscoverFilter.Common -> remaining.filter { it.shared.isNotEmpty() }
        }
    val reviewed: Int get() = total - remaining.size
}

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
                _state.update { it.copy(loading = false, remaining = deck, total = deck.size) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = "We couldn't load students right now.") }
            }
        }
    }

    fun setFilter(filter: DiscoverFilter) = _state.update { it.copy(filter = filter) }
    fun showInfo(show: Boolean) = _state.update { it.copy(showInfo = show) }

    fun decide(liked: Boolean) {
        val top = _state.value.deck.firstOrNull() ?: return
        _state.update { s -> s.copy(remaining = s.remaining.filter { it.id != top.id }) }
        viewModelScope.launch { runCatching { repository.recordDecision(top.id, liked) } }
    }
}
