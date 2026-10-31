package com.univibe.app.features.themes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.univibe.app.app.AppContainer
import com.univibe.app.data.model.CHAT_DIM_MAX
import com.univibe.app.data.model.CHAT_DIM_MIN
import com.univibe.app.data.model.ThemeAsset
import com.univibe.app.data.model.ThemeCategory
import com.univibe.app.data.net.ApiException
import com.univibe.app.data.repository.ThemeRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class StudioTab { Discover, Favourites }

data class ThemesStudioState(
    val tab: StudioTab = StudioTab.Discover,
    val categories: List<ThemeCategory> = emptyList(),
    val category: String? = null,
    val query: String = "",
    val items: List<ThemeAsset> = emptyList(),
    val nextCursor: String? = null,
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val error: String? = null,
    /** The image whose page is open. Looking at it saves nothing: only Apply does. */
    val detail: ThemeAsset? = null,
    val dim: Int = 40,
    val applying: Boolean = false,
    val notice: String? = null,
)

private fun failure(e: Exception, fallback: String): String = when {
    e is ApiException && e.code == "premium_required" -> "That image needs UniVibe Premium."
    e is ApiException && e.status in 400..499 -> e.message ?: fallback
    e is UnsupportedOperationException -> e.message ?: fallback
    else -> fallback
}

/** Discover, search, favourite and apply catalogue images. Previewing is free and changes nothing; Apply saves through the server. */
class ThemesStudioViewModel(private val repo: ThemeRepository = AppContainer.themeRepository) : ViewModel() {
    private val _state = MutableStateFlow(ThemesStudioState())
    val state: StateFlow<ThemesStudioState> = _state.asStateFlow()
    private var loadJob: Job? = null

    init {
        viewModelScope.launch { runCatching { repo.categories() }.onSuccess { c -> _state.update { it.copy(categories = c) } } }
        load()
    }

    fun selectTab(tab: StudioTab) { if (_state.value.tab != tab) { _state.update { it.copy(tab = tab) }; load() } }
    fun selectCategory(slug: String?) { if (_state.value.category != slug) { _state.update { it.copy(category = slug) }; load() } }

    fun onQuery(q: String) {
        _state.update { it.copy(query = q) }
        loadJob?.cancel()
        loadJob = viewModelScope.launch { delay(300); fetch(reset = true) } // type, pause, then ask
    }

    fun load() { loadJob?.cancel(); loadJob = viewModelScope.launch { fetch(reset = true) } }

    private suspend fun fetch(reset: Boolean) {
        val s = _state.value
        if (reset) _state.update { it.copy(loading = true, error = null, items = emptyList(), nextCursor = null) }
        try {
            val page = if (s.tab == StudioTab.Favourites) repo.favourites(if (reset) null else s.nextCursor)
            else repo.assets(s.category, s.query, if (reset) null else s.nextCursor)
            // A late answer for a filter the person already left is ignored.
            val now = _state.value
            if (now.tab != s.tab || now.category != s.category || now.query != s.query) return
            _state.update { cur ->
                cur.copy(
                    loading = false, loadingMore = false, nextCursor = page.nextCursor,
                    items = if (reset) page.items else cur.items + page.items.filter { n -> cur.items.none { it.id == n.id } },
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(loading = false, loadingMore = false, error = if (reset) failure(e, "Couldn't load themes.") else null, notice = if (reset) null else failure(e, "Couldn't load more.")) }
        }
    }

    fun loadMore() {
        val s = _state.value
        if (s.nextCursor == null || s.loading || s.loadingMore) return
        _state.update { it.copy(loadingMore = true) }
        viewModelScope.launch { fetch(reset = false) }
    }

    fun open(asset: ThemeAsset) = _state.update { it.copy(detail = asset, dim = 40) }
    fun close() = _state.update { it.copy(detail = null, applying = false) }
    fun setDim(v: Int) = _state.update { it.copy(dim = v.coerceIn(CHAT_DIM_MIN, CHAT_DIM_MAX)) }
    fun clearNotice() = _state.update { it.copy(notice = null) }

    fun toggleFavourite(asset: ThemeAsset) {
        val want = !asset.favourited
        fun flip(a: ThemeAsset) = if (a.id == asset.id) a.copy(favourited = want) else a
        _state.update { s ->
            s.copy(
                items = if (s.tab == StudioTab.Favourites && !want) s.items.filterNot { it.id == asset.id } else s.items.map(::flip),
                detail = s.detail?.let(::flip),
            )
        }
        viewModelScope.launch {
            try { repo.setFavourite(asset.id, want) }
            catch (e: Exception) { _state.update { it.copy(notice = failure(e, "Couldn't update your favourites.")) }; load() }
        }
    }

    /** Saves [asset] as the background for every chat. The server refuses a Premium image without Premium. */
    fun applyAsChatBackground(asset: ThemeAsset) {
        val s = _state.value
        if (s.applying || asset.locked || !asset.canBeChatBackground) return
        _state.update { it.copy(applying = true) }
        viewModelScope.launch {
            try {
                repo.saveChatBackground(null, asset.id, s.dim)
                _state.update { it.copy(applying = false, detail = null, notice = "Chat background updated for all your chats.") }
            } catch (e: Exception) {
                _state.update { it.copy(applying = false, notice = failure(e, "Couldn't apply that image.")) }
            }
        }
    }
}
