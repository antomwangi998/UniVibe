package com.univibe.app.features.stickers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.univibe.app.app.AppContainer
import com.univibe.app.data.model.StickerPack
import com.univibe.app.data.model.StickerRef
import com.univibe.app.data.net.ApiException
import com.univibe.app.data.repository.StickerRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private fun message(e: Exception, fallback: String): String = when {
    e is ApiException && e.code == "premium_required" -> "This sticker pack needs UniVibe Premium."
    e is ApiException && e.status in 400..499 -> e.message ?: fallback
    e is UnsupportedOperationException -> e.message ?: fallback
    else -> fallback
}

data class StickerPickerState(
    val loading: Boolean = true,
    val error: String? = null,
    val packs: List<StickerPack> = emptyList(),
    val recent: List<StickerRef> = emptyList(),
    val favourites: List<StickerRef> = emptyList(),
    val query: String = "",
    /** Null while no search is active. */
    val results: List<StickerRef>? = null,
)

/** What the sticker bottom sheet shows: my installed packs, recent and favourite stickers, and search inside them. */
class StickerPickerViewModel(private val repo: StickerRepository = AppContainer.stickerRepository) : ViewModel() {
    private val _state = MutableStateFlow(StickerPickerState())
    val state: StateFlow<StickerPickerState> = _state.asStateFlow()
    private var searchJob: Job? = null

    init { refresh() }

    fun refresh() {
        _state.update { it.copy(loading = it.packs.isEmpty(), error = null) }
        viewModelScope.launch {
            try {
                val packs = repo.installed()
                val recent = repo.recent()
                val favourites = repo.favourites()
                _state.update { it.copy(loading = false, packs = packs, recent = recent, favourites = favourites) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = message(e, "Couldn't load your stickers.")) }
            }
        }
    }

    fun onQuery(q: String) {
        _state.update { it.copy(query = q, results = if (q.isBlank()) null else it.results) }
        searchJob?.cancel()
        if (q.isBlank()) return
        searchJob = viewModelScope.launch {
            delay(250) // type, pause, then ask: one request per word, not per letter
            try { _state.update { it.copy(results = repo.search(q)) } }
            catch (e: Exception) { _state.update { it.copy(results = emptyList()) } }
        }
    }

    fun toggleFavourite(sticker: StickerRef) {
        val wasFavourite = _state.value.favourites.any { it.id == sticker.id }
        _state.update { s ->
            s.copy(favourites = if (wasFavourite) s.favourites.filterNot { it.id == sticker.id } else listOf(sticker) + s.favourites)
        }
        viewModelScope.launch {
            try { repo.setStickerFavourite(sticker.id, !wasFavourite) }
            catch (e: Exception) { refresh() } // put the list back to what the server says
        }
    }
}

data class StickerStoreState(
    val packs: List<StickerPack> = emptyList(),
    val nextCursor: String? = null,
    val category: String? = null,
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val error: String? = null,
    /** The pack whose page is open. */
    val detail: StickerPack? = null,
    val detailLoading: Boolean = false,
    val busyPackId: String? = null,
    val notice: String? = null,
)

class StickerStoreViewModel(private val repo: StickerRepository = AppContainer.stickerRepository) : ViewModel() {
    private val _state = MutableStateFlow(StickerStoreState())
    val state: StateFlow<StickerStoreState> = _state.asStateFlow()

    init { load() }

    fun selectCategory(category: String?) {
        if (_state.value.category == category) return
        _state.update { it.copy(category = category) }
        load()
    }

    fun load() {
        _state.update { it.copy(loading = true, error = null, packs = emptyList(), nextCursor = null) }
        val category = _state.value.category
        viewModelScope.launch {
            try {
                val page = repo.store(category, null, null)
                // A late answer for a category the person already left is ignored.
                if (_state.value.category == category) _state.update { it.copy(loading = false, packs = page.packs, nextCursor = page.nextCursor) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = message(e, "Couldn't load the sticker store.")) }
            }
        }
    }

    fun loadMore() {
        val s = _state.value
        val cursor = s.nextCursor ?: return
        if (s.loading || s.loadingMore) return
        _state.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            try {
                val page = repo.store(s.category, null, cursor)
                _state.update { cur -> cur.copy(loadingMore = false, packs = cur.packs + page.packs.filter { p -> cur.packs.none { it.id == p.id } }, nextCursor = page.nextCursor) }
            } catch (e: Exception) {
                _state.update { it.copy(loadingMore = false, nextCursor = null, notice = message(e, "Couldn't load more packs.")) }
            }
        }
    }

    fun openPack(pack: StickerPack) {
        _state.update { it.copy(detail = pack, detailLoading = true) }
        viewModelScope.launch {
            try {
                val full = repo.pack(pack.id)
                if (_state.value.detail?.id == pack.id) _state.update { it.copy(detail = full, detailLoading = false) }
            } catch (e: Exception) {
                _state.update { it.copy(detailLoading = false, notice = message(e, "Couldn't open that pack.")) }
            }
        }
    }

    fun closePack() = _state.update { it.copy(detail = null, detailLoading = false) }
    fun clearNotice() = _state.update { it.copy(notice = null) }

    /** Install or uninstall. The server says no to a Premium pack without Premium; the button never decides that. */
    fun toggleInstall(pack: StickerPack) {
        if (_state.value.busyPackId != null) return
        _state.update { it.copy(busyPackId = pack.id) }
        viewModelScope.launch {
            try {
                if (pack.installed) repo.uninstall(pack.id) else repo.install(pack.id)
                val now = !pack.installed
                _state.update { s ->
                    s.copy(
                        busyPackId = null,
                        packs = s.packs.map { if (it.id == pack.id) it.copy(installed = now) else it },
                        detail = s.detail?.let { d -> if (d.id == pack.id) d.copy(installed = now) else d },
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(busyPackId = null, notice = message(e, "Couldn't change that pack.")) }
            }
        }
    }
}
