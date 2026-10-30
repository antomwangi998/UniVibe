package com.univibe.app.features.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.univibe.app.app.AppContainer
import com.univibe.app.data.model.ProfileLook
import com.univibe.app.data.repository.PremiumRepository
import com.univibe.app.data.repository.promotionMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProfileLookState(
    val loading: Boolean = true,
    val error: String? = null,
    val saved: ProfileLook = ProfileLook(),
    val draft: ProfileLook = ProfileLook(),
    val themesUnlocked: Boolean = false,
    val framesUnlocked: Boolean = false,
    val saving: Boolean = false,
    val notice: String? = null,
) {
    val dirty: Boolean get() = draft != saved
    /** Previewing is always free; saving needs what the draft uses to be unlocked. */
    val canSave: Boolean get() = dirty && canSaveLook(draft, themesUnlocked, framesUnlocked)
    val locked: Boolean get() = !themesUnlocked && !framesUnlocked
}

class ProfileLookViewModel(private val repository: PremiumRepository = AppContainer.premiumRepository) : ViewModel() {
    private val _state = MutableStateFlow(ProfileLookState())
    val state: StateFlow<ProfileLookState> = _state.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val m = repository.myProfileLook()
                _state.update { it.copy(loading = false, saved = m.saved, draft = m.saved, themesUnlocked = m.themesUnlocked, framesUnlocked = m.framesUnlocked) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.promotionMessage()) }
            }
        }
    }

    fun pickTheme(id: String?) = _state.update { it.copy(draft = it.draft.copy(theme = id), notice = null) }
    fun pickAccent(hex: String?) = _state.update { it.copy(draft = it.draft.copy(accent = hex), notice = null) }
    fun pickFrame(id: String?) = _state.update { it.copy(draft = it.draft.copy(frame = id), notice = null) }
    fun pickBadge(id: String?) = _state.update { it.copy(draft = it.draft.copy(badge = id), notice = null) }
    fun clearAll() = _state.update { it.copy(draft = ProfileLook(), notice = null) }
    fun consumeNotice() = _state.update { it.copy(notice = null) }

    /** The server decides; if it says no (for example Premium just ended) the draft stays and the message is shown. */
    fun save() {
        val s = _state.value
        if (s.saving || !s.canSave) return
        _state.update { it.copy(saving = true, notice = null) }
        viewModelScope.launch {
            try {
                repository.saveProfileLook(s.draft)
                _state.update { it.copy(saving = false, saved = s.draft, notice = "Saved. Other students see your new look.") }
            } catch (e: Exception) {
                _state.update { it.copy(saving = false, notice = e.promotionMessage()) }
            }
        }
    }
}
