package com.univibe.app.features.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.univibe.app.app.AppContainer
import com.univibe.app.data.media.PhotoStore
import com.univibe.app.data.model.MyProfile
import com.univibe.app.data.repository.ProfileRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DiscoverProfileUiState(
    val loading: Boolean = true,
    val profile: MyProfile? = null,
    val importing: Boolean = false,
    val confirmRemove: Boolean = false,
    val notice: String? = null,
)

class DiscoverProfileViewModel(
    private val repository: ProfileRepository = AppContainer.profileRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(DiscoverProfileUiState())
    val state: StateFlow<DiscoverProfileUiState> = _state.asStateFlow()

    init { load() }

    private fun load() {
        viewModelScope.launch {
            try {
                val profile = repository.getMyProfile()
                _state.update { it.copy(loading = false, profile = profile) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, notice = "Couldn't load your profile.") }
            }
        }
    }

    fun setImporting(importing: Boolean) = _state.update { it.copy(importing = importing) }
    fun consumeNotice() = _state.update { it.copy(notice = null) }
    fun askRemove(show: Boolean) = _state.update { it.copy(confirmRemove = show) }

    /** [path] is a private, re-encoded copy of the picked image (null means the pick failed). */
    fun setPhoto(path: String?) {
        if (path == null) {
            _state.update { it.copy(notice = "Couldn't use that image.") }
            return
        }
        viewModelScope.launch {
            try {
                repository.setDiscoverPhoto(path)
                _state.update { s -> s.copy(profile = s.profile?.copy(discoverPhotoPath = path)) }
            } catch (e: Exception) {
                PhotoStore.delete(path) // never keep a file nothing references
                _state.update { it.copy(notice = "Couldn't save your photo. Try again.") }
            }
        }
    }

    fun removePhoto() {
        _state.update { it.copy(confirmRemove = false) }
        viewModelScope.launch {
            try {
                repository.setDiscoverPhoto(null)
                _state.update { s -> s.copy(profile = s.profile?.copy(discoverPhotoPath = null)) }
            } catch (e: Exception) {
                _state.update { it.copy(notice = "Couldn't remove your photo. Try again.") }
            }
        }
    }
}
