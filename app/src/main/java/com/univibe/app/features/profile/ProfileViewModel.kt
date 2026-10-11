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

data class ProfileUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val profile: MyProfile? = null,
    val discoverable: Boolean = true,
    val showOnline: Boolean = true,
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
                _state.update { it.copy(loading = false, profile = profile) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = "We couldn't load your profile.") }
            }
        }
    }

    // Local-only for now; real privacy settings must be enforced server-side.
    fun setDiscoverable(value: Boolean) = _state.update { it.copy(discoverable = value) }
    fun setShowOnline(value: Boolean) = _state.update { it.copy(showOnline = value) }
}
