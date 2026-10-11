package com.univibe.app.features.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.univibe.app.data.model.MyProfile
import com.univibe.app.data.repository.MockProfileRepository
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
    val interests: List<String> = emptyList(),
    val editingInterests: Boolean = false,
    val discoverable: Boolean = true,
    val showOnline: Boolean = true,
    val allowMessages: Boolean = true,
)

class ProfileViewModel(
    private val repository: ProfileRepository = MockProfileRepository(),
) : ViewModel() {
    private val _state = MutableStateFlow(ProfileUiState())
    val state: StateFlow<ProfileUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val profile = repository.getMyProfile()
                _state.update { it.copy(loading = false, profile = profile, interests = profile.student.interests) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = "We couldn't load your profile.") }
            }
        }
    }

    fun toggleEditInterests() = _state.update { it.copy(editingInterests = !it.editingInterests) }

    fun toggleInterest(name: String) = _state.update { s ->
        s.copy(interests = if (name in s.interests) s.interests - name else s.interests + name)
    }

    // Local-only for now; real privacy settings must be enforced server-side.
    fun setDiscoverable(value: Boolean) = _state.update { it.copy(discoverable = value) }
    fun setShowOnline(value: Boolean) = _state.update { it.copy(showOnline = value) }
    fun setAllowMessages(value: Boolean) = _state.update { it.copy(allowMessages = value) }
}
