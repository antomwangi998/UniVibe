package com.univibe.app.features.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.univibe.app.app.AppContainer
import com.univibe.app.data.model.Community
import com.univibe.app.data.model.CommunityRole
import com.univibe.app.data.model.MyProfile
import com.univibe.app.data.repository.CommunityRepository
import com.univibe.app.data.repository.ProfileRepository
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
    val error: String? = null,
    val profile: MyProfile? = null,
    val groups: List<Community> = emptyList(),
)

class ProfileViewModel(
    private val repository: ProfileRepository = AppContainer.profileRepository,
    private val communities: CommunityRepository = AppContainer.communityRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(ProfileUiState())
    val state: StateFlow<ProfileUiState> = _state.asStateFlow()

    init { load() }

    fun refresh() = load(silent = true)

    fun load(silent: Boolean = false) {
        viewModelScope.launch {
            if (!silent) _state.update { it.copy(loading = true, error = null) }
            try {
                val profile = repository.getMyProfile()
                val groups = communities.getCommunities().filter { it.role != CommunityRole.None }
                _state.update { it.copy(loading = false, profile = profile, groups = groups) }
            } catch (e: Exception) {
                if (silent) return@launch
                _state.update { it.copy(loading = false, error = "We couldn't load your profile.") }
            }
        }
    }
}
