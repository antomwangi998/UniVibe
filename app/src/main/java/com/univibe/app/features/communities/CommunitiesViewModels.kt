package com.univibe.app.features.communities

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.univibe.app.app.AppContainer
import com.univibe.app.data.model.Community
import com.univibe.app.data.model.CommunityRole
import com.univibe.app.data.model.ReportItem
import com.univibe.app.data.repository.CommunityRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ------------------------------------------------------------------ list
data class CommunitiesUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val all: List<Community> = emptyList(),
    val query: String = "",
    val category: String = "All",
    val pendingLeave: Community? = null,
) {
    val categories: List<String> get() = listOf("All") + all.map { it.category }.distinct().sorted()
    val visible: List<Community>
        get() = all.filter { (category == "All" || it.category == category) && (query.isBlank() || it.name.contains(query, true) || it.description.contains(query, true)) }
}

class CommunitiesViewModel(
    private val repository: CommunityRepository = AppContainer.communityRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(CommunitiesUiState())
    val state: StateFlow<CommunitiesUiState> = _state.asStateFlow()

    init { load() }

    fun load(silent: Boolean = false) {
        viewModelScope.launch {
            if (!silent) _state.update { it.copy(loading = true, error = null) }
            try { _state.update { it.copy(loading = false, all = repository.getCommunities()) } }
            catch (e: Exception) { if (!silent) _state.update { it.copy(loading = false, error = "We couldn't load communities.") } }
        }
    }

    fun refresh() = load(silent = true)
    fun setQuery(v: String) = _state.update { it.copy(query = v) }
    fun setCategory(v: String) = _state.update { it.copy(category = v) }

    /** Joining is immediate; leaving asks first. */
    fun onMembershipClick(c: Community) {
        if (c.role == CommunityRole.None) change(c.id, join = true) else _state.update { it.copy(pendingLeave = c) }
    }
    fun confirmLeave() {
        val c = _state.value.pendingLeave ?: return
        _state.update { it.copy(pendingLeave = null) }
        change(c.id, join = false)
    }
    fun dismissLeave() = _state.update { it.copy(pendingLeave = null) }

    private fun change(id: String, join: Boolean) {
        viewModelScope.launch {
            runCatching { repository.setMembership(id, join) }
            load(silent = true)
        }
    }
}

// ------------------------------------------------------------------ details + moderation
data class CommunityDetailUiState(
    val loading: Boolean = true,
    val notFound: Boolean = false,
    val community: Community? = null,
    val reports: List<ReportItem> = emptyList(),
    val pendingLeave: Boolean = false,
    val notice: String? = null,
)

class CommunityDetailViewModel(
    private val id: String,
    private val repository: CommunityRepository = AppContainer.communityRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(CommunityDetailUiState())
    val state: StateFlow<CommunityDetailUiState> = _state.asStateFlow()

    init { load() }

    private fun load() {
        viewModelScope.launch {
            val c = runCatching { repository.getCommunity(id) }.getOrNull()
            if (c == null) { _state.update { it.copy(loading = false, notFound = true) }; return@launch }
            val reports = if (c.role == CommunityRole.Moderator) runCatching { repository.getReports(id) }.getOrDefault(emptyList()) else emptyList()
            _state.update { it.copy(loading = false, community = c, reports = reports) }
        }
    }

    fun onMembershipClick() {
        val c = _state.value.community ?: return
        if (c.role == CommunityRole.None) { viewModelScope.launch { repository.setMembership(id, true); load() } }
        else _state.update { it.copy(pendingLeave = true) }
    }
    fun confirmLeave() { _state.update { it.copy(pendingLeave = false) }; viewModelScope.launch { repository.setMembership(id, false); load() } }
    fun dismissLeave() = _state.update { it.copy(pendingLeave = false) }

    fun setMuted(muted: Boolean) { viewModelScope.launch { repository.setMuted(id, muted); load() } }

    fun resolve(reportId: String, remove: Boolean) {
        _state.update { s -> s.copy(reports = s.reports.filterNot { it.id == reportId }, notice = if (remove) "Post removed." else "Report dismissed.") }
        viewModelScope.launch { runCatching { repository.resolveReport(id, reportId, remove) } }
    }

    fun reportCommunity() = _state.update { it.copy(notice = "Thanks. We'll review this community.") }
    fun consumeNotice() = _state.update { it.copy(notice = null) }
}
