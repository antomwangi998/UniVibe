package com.univibe.app.features.search

import com.univibe.app.data.campus.CampusScopeStore
import kotlinx.coroutines.flow.drop
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.univibe.app.app.AppContainer
import com.univibe.app.data.repository.MIN_SEARCH_LENGTH
import com.univibe.app.data.repository.RecentSearches
import com.univibe.app.data.repository.SearchOverview
import com.univibe.app.data.repository.SearchRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

const val SEARCH_DEBOUNCE_MS = 300L

data class SearchUiState(
    val query: String = "",
    val results: SearchOverview? = null,
    /** The text [results] belong to, so results for an older query are never shown as if they were current. */
    val resultsFor: String = "",
    val loading: Boolean = false,
    val error: String? = null,
    val recents: List<String> = emptyList(),
) {
    val trimmed: String get() = query.trim()
    val tooShort: Boolean get() = trimmed.length < MIN_SEARCH_LENGTH
}

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class SearchViewModel(
    private val repository: SearchRepository = AppContainer.searchRepository,
    private val recents: RecentSearches = AppContainer.recentSearches,
    private val debounceMs: Long = SEARCH_DEBOUNCE_MS,
) : ViewModel() {
    private val _state = MutableStateFlow(SearchUiState(recents = recents.all()))
    val state: StateFlow<SearchUiState> = _state.asStateFlow()
    private val queries = MutableStateFlow("")

    init {
        viewModelScope.launch {
            // Wait for a pause in typing, skip repeats, and drop a slow answer the moment a newer query starts.
            queries.debounce(debounceMs).map { it.trim() }.distinctUntilChanged().collectLatest { run(it) }
        }
        // The campus filter changed: search the same words again across the newly chosen campuses.
        viewModelScope.launch {
            CampusScopeStore.scope.drop(1).collect {
                val q = queries.value.trim()
                if (q.length >= MIN_SEARCH_LENGTH) run(q)
            }
        }
    }

    fun onQuery(text: String) {
        val capped = text.take(80)
        _state.update { it.copy(query = capped, error = null, loading = capped.trim().length >= MIN_SEARCH_LENGTH) }
        queries.value = capped
    }

    fun clear() = onQuery("")

    private suspend fun run(q: String) {
        if (q.length < MIN_SEARCH_LENGTH) {
            _state.update { it.copy(results = null, resultsFor = "", loading = false, error = null) }
            return
        }
        _state.update { it.copy(loading = true, error = null) }
        try {
            val found = repository.search(q)
            _state.update { it.copy(results = found, resultsFor = q, loading = false) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val message = if (e is IOException) "No connection. Check your internet and try again." else "Search isn't working right now. Try again."
            _state.update { it.copy(loading = false, error = message) }
        }
    }

    fun retry() = viewModelScope.launch { run(_state.value.trimmed) }

    /** Called when the student submits a search or opens a result, so only searches that led somewhere are kept. */
    fun remember() {
        recents.add(_state.value.trimmed)
        _state.update { it.copy(recents = recents.all()) }
    }

    fun useRecent(q: String) = onQuery(q)
    fun forget(q: String) { recents.remove(q); _state.update { it.copy(recents = recents.all()) } }
    fun forgetAll() { recents.clear(); _state.update { it.copy(recents = emptyList()) } }
}
