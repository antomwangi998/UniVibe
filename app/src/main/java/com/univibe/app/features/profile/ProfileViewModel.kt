package com.univibe.app.features.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.univibe.app.app.AppContainer
import com.univibe.app.data.model.Community
import com.univibe.app.data.model.CommunityRole
import com.univibe.app.data.model.MyProfile
import com.univibe.app.data.model.Post
import com.univibe.app.data.repository.CommunityRepository
import com.univibe.app.data.repository.FeedRepository
import com.univibe.app.data.repository.ProfileRepository
import com.univibe.app.domain.auth.AuthRepository
import com.univibe.app.domain.auth.AuthState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

val AllInterests = listOf(
    "Music", "Travel", "Reading", "Movies", "Tech", "Sports",
    "Photography", "Entrepreneurship", "Gaming", "Art",
)

data class ProfileUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val error: String? = null,
    val profile: MyProfile? = null,
    val groups: List<Community> = emptyList(),
    /** The most recently saved post, for the preview card. Null when none, or while it has not loaded. */
    val latestSaved: Post? = null,
    /** True once the saved-post preview has been read (so an empty card is not shown while it is still loading). */
    val savedLoaded: Boolean = false,
    /** My Premium look as other students see it right now (null when none or not unlocked). */
    val look: com.univibe.app.data.model.ProfileLook? = null,
)

class ProfileViewModel(
    private val repository: ProfileRepository = AppContainer.profileRepository,
    private val communities: CommunityRepository = AppContainer.communityRepository,
    private val auth: AuthRepository = AppContainer.authRepository,
    private val feed: FeedRepository = AppContainer.feedRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(ProfileUiState())
    val state: StateFlow<ProfileUiState> = _state.asStateFlow()

    init { load() }

    fun refresh() = load(silent = true)
    fun pullRefresh() = load(silent = true, pull = true)

    fun load(silent: Boolean = false, pull: Boolean = false) {
        viewModelScope.launch {
            if (!silent) _state.update { it.copy(loading = true, error = null) }
            if (pull) _state.update { it.copy(refreshing = true) }
            try {
                // Never show the badge from mock profile data: it must follow what the server told the auth layer.
                val account = (auth.session.value as? AuthState.Authenticated)?.user
                val loaded = repository.getMyProfile()
                val profile = if (account == null) loaded
                else loaded.copy(student = loaded.student.copy(verified = account.studentVerified))
                val groups = communities.getCommunities().filter { it.role != CommunityRole.None }
                _state.update { it.copy(loading = false, refreshing = false, profile = profile, groups = groups) }
                // My look, as others see it. A failure leaves the standard profile, never an error.
                runCatching { AppContainer.premiumRepository.myProfileLook() }.onSuccess { l -> _state.update { it.copy(look = l.shown) } }
                // One small read for the preview; a failure here must not break the profile, so it keeps what it had.
                runCatching { feed.getSavedPostsPage(null, 1) }.onSuccess { page ->
                    _state.update { it.copy(latestSaved = page.firstOrNull(), savedLoaded = true) }
                }
            } catch (e: Exception) {
                _state.update { it.copy(refreshing = false) }
                if (silent) return@launch
                _state.update { it.copy(loading = false, error = "We couldn't load your profile.") }
            }
        }
    }
}
