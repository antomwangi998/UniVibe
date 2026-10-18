package com.univibe.app.features.communities

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.univibe.app.app.AppContainer
import com.univibe.app.data.model.Community
import com.univibe.app.data.model.CommunityMember
import com.univibe.app.data.model.CommunityRole
import com.univibe.app.data.model.Post
import com.univibe.app.data.model.ReportItem
import com.univibe.app.data.model.Student
import com.univibe.app.data.repository.CommunityRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private fun Exception.userMessage(fallback: String) = message?.takeIf { it.length < 90 } ?: fallback

// ------------------------------------------------------------------ list
data class CommunitiesUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val all: List<Community> = emptyList(),
    val query: String = "",
    val category: String = "All",
    val pendingLeave: Community? = null,
    val notice: String? = null,
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
    fun consumeNotice() = _state.update { it.copy(notice = null) }

    /** Public: join at once. Private: send or withdraw a request. Joined: leave (asks first). */
    fun onMembershipClick(c: Community) {
        when {
            c.isMember -> if (c.role == CommunityRole.Owner) _state.update { it.copy(notice = "You own this community. Open it to manage or delete it.") }
                          else _state.update { it.copy(pendingLeave = c) }
            c.joinRequested -> change(c.id, join = false)
            else -> change(c.id, join = true)
        }
    }

    fun confirmLeave() {
        val c = _state.value.pendingLeave ?: return
        _state.update { it.copy(pendingLeave = null) }
        change(c.id, join = false)
    }
    fun dismissLeave() = _state.update { it.copy(pendingLeave = null) }

    private fun change(id: String, join: Boolean) {
        viewModelScope.launch {
            try { repository.setMembership(id, join) } catch (e: Exception) { _state.update { it.copy(notice = e.userMessage("Couldn't update your membership.")) } }
            load(silent = true)
        }
    }
}

// ------------------------------------------------------------------ details + members + moderation
enum class CommunityTab { Posts, Members, About }

sealed interface CommunityConfirm {
    data object Leave : CommunityConfirm
    data object Delete : CommunityConfirm
    data class RemoveMember(val member: CommunityMember) : CommunityConfirm
}

data class CommunityDetailUiState(
    val loading: Boolean = true,
    val notFound: Boolean = false,
    val community: Community? = null,
    val tab: CommunityTab = CommunityTab.Posts,
    val posts: List<Post> = emptyList(),
    val postsLoading: Boolean = true,
    val members: List<CommunityMember> = emptyList(),
    val membersError: String? = null,
    val requests: List<Student> = emptyList(),
    val reports: List<ReportItem> = emptyList(),
    val memberMenu: CommunityMember? = null,
    val confirm: CommunityConfirm? = null,
    val busy: Boolean = false,
    val notice: String? = null,
    val deleted: Boolean = false,
)

class CommunityDetailViewModel(
    private val id: String,
    private val repository: CommunityRepository = AppContainer.communityRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(CommunityDetailUiState())
    val state: StateFlow<CommunityDetailUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            val c = runCatching { repository.getCommunity(id) }.getOrNull()
            if (c == null) { _state.update { it.copy(loading = false, notFound = true) }; return@launch }
            _state.update { it.copy(loading = false, community = c) }
            val posts = runCatching { repository.getPosts(id) }.getOrDefault(emptyList())
            val members = runCatching { repository.getMembers(id) }
            val requests = if (c.isStaff) runCatching { repository.getJoinRequests(id) }.getOrDefault(emptyList()) else emptyList()
            val reports = if (c.isStaff) runCatching { repository.getReports(id) }.getOrDefault(emptyList()) else emptyList()
            _state.update {
                it.copy(
                    posts = posts, postsLoading = false,
                    members = members.getOrDefault(emptyList()),
                    membersError = members.exceptionOrNull()?.let { e -> (e as? Exception)?.userMessage("Couldn't load members.") },
                    requests = requests, reports = reports,
                )
            }
        }
    }

    private fun change(success: String? = null, block: suspend () -> Unit) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            try { block(); _state.update { it.copy(notice = success) } }
            catch (e: Exception) { _state.update { it.copy(notice = e.userMessage("That didn't work. Try again.")) } }
            _state.update { it.copy(busy = false) }
            load()
        }
    }

    fun setTab(tab: CommunityTab) = _state.update { it.copy(tab = tab) }
    fun consumeNotice() = _state.update { it.copy(notice = null) }
    fun memberMenu(m: CommunityMember?) = _state.update { it.copy(memberMenu = m) }
    fun confirm(c: CommunityConfirm?) = _state.update { it.copy(confirm = c, memberMenu = null) }

    fun onMembershipClick() {
        val c = _state.value.community ?: return
        when {
            c.role == CommunityRole.Owner -> _state.update { it.copy(notice = "You own this community. Delete it from About if you want to leave.") }
            c.isMember -> confirm(CommunityConfirm.Leave)
            c.joinRequested -> change("Request withdrawn") { repository.setMembership(id, false) }
            else -> change(if (c.privacy.name == "Private") "Request sent" else "Welcome!") { repository.setMembership(id, true) }
        }
    }

    fun setMuted(muted: Boolean) = change { repository.setMuted(id, muted) }

    fun doConfirmed() {
        when (val c = _state.value.confirm) {
            CommunityConfirm.Leave -> { confirm(null); change("You left the community") { repository.setMembership(id, false) } }
            CommunityConfirm.Delete -> {
                confirm(null)
                viewModelScope.launch {
                    try { repository.deleteCommunity(id); _state.update { it.copy(deleted = true) } }
                    catch (e: Exception) { _state.update { it.copy(notice = e.userMessage("Couldn't delete the community.")) } }
                }
            }
            is CommunityConfirm.RemoveMember -> { confirm(null); change("${c.member.student.name.substringBefore(' ')} removed") { repository.removeMember(id, c.member.student.id) } }
            null -> Unit
        }
    }

    fun makeModerator(m: CommunityMember) { memberMenu(null); change("${m.student.name.substringBefore(' ')} is now a moderator") { repository.setMemberRole(id, m.student.id, CommunityRole.Moderator) } }
    fun removeModerator(m: CommunityMember) { memberMenu(null); change("Moderator removed") { repository.setMemberRole(id, m.student.id, CommunityRole.Member) } }

    fun answerRequest(s: Student, approve: Boolean) =
        change(if (approve) "${s.name.substringBefore(' ')} approved" else "Request declined") { repository.resolveJoinRequest(id, s.id, approve) }

    fun resolve(reportId: String, remove: Boolean) {
        _state.update { s -> s.copy(reports = s.reports.filterNot { it.id == reportId }, notice = if (remove) "Post removed." else "Report dismissed.") }
        viewModelScope.launch { runCatching { repository.resolveReport(id, reportId, remove) } }
    }

    fun reportCommunity() = _state.update { it.copy(notice = "Thanks. We'll review this community.") }
}
