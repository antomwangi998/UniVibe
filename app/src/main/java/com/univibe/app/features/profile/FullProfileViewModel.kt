package com.univibe.app.features.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.univibe.app.app.AppContainer
import com.univibe.app.data.model.Community
import com.univibe.app.data.model.FeedFilter
import com.univibe.app.data.model.Post
import com.univibe.app.data.model.StudentProfile
import com.univibe.app.data.repository.CommunityRepository
import com.univibe.app.data.repository.DiscoverRepository
import com.univibe.app.data.repository.FeedRepository
import com.univibe.app.data.repository.MessageRepository
import com.univibe.app.data.repository.ProfileRepository
import com.univibe.app.data.settings.PrivacyRepository
import com.univibe.app.data.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class FullProfileUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val notFound: Boolean = false,
    val profile: StudentProfile? = null,
    val isMe: Boolean = false,
    val following: Boolean = false,
    /** True only while this student is in your Discover deck and you haven't decided yet. */
    val canDecide: Boolean = false,
    val deciding: Boolean = false,
    val communities: List<Community> = emptyList(),
    val posts: List<Post> = emptyList(),
    val opening: Boolean = false,
    val menuOpen: Boolean = false,
    val notice: String? = null,
)

/** Full profile of one student: reached from Discover cards, followers lists, posts and deep links. */
class FullProfileViewModel(
    private val studentId: String,
    private val repository: ProfileRepository = AppContainer.profileRepository,
    private val discover: DiscoverRepository = AppContainer.discoverRepository,
    private val communityRepository: CommunityRepository = AppContainer.communityRepository,
    private val feed: FeedRepository = AppContainer.feedRepository,
    private val messages: MessageRepository = AppContainer.messageRepository,
    private val settings: SettingsRepository = AppContainer.settingsRepository,
    private val privacy: PrivacyRepository = AppContainer.privacyRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(FullProfileUiState())
    val state: StateFlow<FullProfileUiState> = _state.asStateFlow()

    init { load() }

    fun pullRefresh() = load(pull = true)

    fun load(pull: Boolean = false) {
        viewModelScope.launch {
            _state.update { if (pull) it.copy(refreshing = true) else it.copy(loading = true, notFound = false) }
            try {
                val profile = repository.getStudentProfile(studentId)
                if (profile == null) {
                    _state.update { it.copy(loading = false, refreshing = false, notFound = true) }
                    return@launch
                }
                val isMe = repository.getMyProfile().student.id == studentId
                val following = repository.getFollowing().any { it.student.id == studentId }
                // Being in the deck is what allows Pass / Connect here, so discovery opt-in rules stay in the data layer.
                val candidate = if (isMe) null else runCatching { discover.getCandidates() }.getOrDefault(emptyList()).firstOrNull { it.id == studentId }
                val allCommunities = runCatching { communityRepository.getCommunities() }.getOrDefault(emptyList())
                val communities = profile.communityIds.mapNotNull { id -> allCommunities.firstOrNull { it.id == id } }
                val posts = runCatching { feed.getPostsBy(studentId, 3) }.getOrDefault(emptyList())
                _state.update {
                    it.copy(
                        loading = false, refreshing = false,
                        profile = if (candidate != null) profile.copy(student = candidate) else profile,
                        isMe = isMe, following = following, canDecide = candidate != null,
                        communities = communities, posts = posts,
                    )
                }
            } catch (e: Exception) {
                _state.update { if (pull && it.profile != null) it.copy(refreshing = false) else it.copy(loading = false, refreshing = false, notFound = true) }
            }
        }
    }

    fun toggleFollow() {
        val now = !_state.value.following
        _state.update { it.copy(following = now) }
        viewModelScope.launch { runCatching { repository.setFollowing(studentId, now) } }
    }

    fun message(onReady: (String) -> Unit) {
        val student = _state.value.profile?.student ?: return
        if (_state.value.opening) return
        viewModelScope.launch {
            _state.update { it.copy(opening = true) }
            val id = runCatching { messages.openChatWith(student) }.getOrNull()
            _state.update { it.copy(opening = false) }
            if (id != null) onReady(id) else _state.update {
                // Another campus: the server asks for a follow first, so a stranger cannot cold-message across campuses.
                val home = com.univibe.app.data.campus.CampusScopeStore.homeId
                val otherCampus = home != null && student.universityCode.isNotBlank() && student.universityCode != home
                it.copy(notice = if (otherCampus && !it.following) "Follow ${student.name} first to message someone at another campus." else "Couldn't open the chat. Try again.")
            }
        }
    }

    /** Records the decision, then lets the screen leave. A "connect" only matches if they say yes too. */
    fun decide(liked: Boolean, onDone: () -> Unit) {
        val student = _state.value.profile?.student ?: return
        if (!_state.value.canDecide || _state.value.deciding) return
        viewModelScope.launch {
            _state.update { it.copy(deciding = true) }
            val ok = runCatching { discover.recordDecision(student.id, liked) }.isSuccess
            _state.update { it.copy(deciding = false) }
            if (ok) {
                _state.update { it.copy(canDecide = false) }
                onDone()
            } else {
                _state.update { it.copy(notice = "Couldn't save that. Try again.") }
            }
        }
    }

    fun openMenu(open: Boolean) = _state.update { it.copy(menuOpen = open) }
    fun consumeNotice() = _state.update { it.copy(notice = null) }

    fun block() {
        val student = _state.value.profile?.student ?: return
        _state.update { it.copy(menuOpen = false) }
        viewModelScope.launch {
            try {
                privacy.block(student.id, student.name)
                _state.update { it.copy(canDecide = false, notice = "${student.name} blocked. You can undo this in Settings.") }
            } catch (e: java.io.IOException) {
                _state.update { it.copy(notice = "Couldn't block ${student.name}. Check your connection and try again.") }
            } catch (e: com.univibe.app.data.net.ApiException) {
                _state.update { it.copy(notice = "Couldn't block ${student.name}. Try again.") }
            }
        }
    }

    fun report() = _state.update { it.copy(menuOpen = false, notice = "Thanks. We'll review this profile.") }
}
