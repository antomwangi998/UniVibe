package com.univibe.app.features.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.univibe.app.app.AppContainer
import com.univibe.app.domain.auth.AuthRepository
import com.univibe.app.domain.auth.AuthResult
import com.univibe.app.domain.auth.OnboardingProfile
import com.univibe.app.domain.auth.userMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

const val ONBOARDING_STEPS = 4
const val MAX_BIO = 160

data class OnboardingUiState(
    val step: Int = 0,
    val course: String = "",
    val interests: Set<String> = emptySet(),
    val bio: String = "",
    val hasPhoto: Boolean = false,
    val submitting: Boolean = false,
    val error: String? = null,
) {
    val isLast: Boolean get() = step == ONBOARDING_STEPS - 1
    val canContinue: Boolean get() = bio.length <= MAX_BIO
}

class OnboardingViewModel(
    private val repository: AuthRepository = AppContainer.authRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(OnboardingUiState())
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    fun onCourse(v: String) = _state.update { it.copy(course = v.take(60)) }
    fun toggleInterest(name: String) = _state.update { s ->
        s.copy(interests = if (name in s.interests) s.interests - name else s.interests + name)
    }
    fun onBio(v: String) = _state.update { it.copy(bio = v, error = null) }
    fun onPhotoPicked(picked: Boolean) = _state.update { it.copy(hasPhoto = picked) }

    fun back() = _state.update { if (it.step > 0) it.copy(step = it.step - 1, error = null) else it }

    /** Next step, or finish on the last one. Optional steps may be skipped without saving their input. */
    fun next() {
        val s = _state.value
        if (s.submitting) return
        if (!s.isLast) { _state.update { it.copy(step = it.step + 1) }; return }
        finish(skipped = false)
    }

    fun skip() {
        val s = _state.value
        if (s.submitting) return
        if (!s.isLast) {
            _state.update {
                when (it.step) {
                    1 -> it.copy(step = it.step + 1, course = "")
                    2 -> it.copy(step = it.step + 1, interests = emptySet())
                    else -> it.copy(step = it.step + 1)
                }
            }
        } else {
            finish(skipped = true)
        }
    }

    private fun finish(skipped: Boolean) {
        val s = _state.value
        viewModelScope.launch {
            _state.update { it.copy(submitting = true, error = null) }
            val profile = OnboardingProfile(
                course = s.course, interests = s.interests.toList(),
                bio = if (skipped) "" else s.bio, hasPhoto = if (skipped) false else s.hasPhoto,
            )
            when (val r = repository.completeOnboarding(profile)) {
                is AuthResult.Success -> _state.update { it.copy(submitting = false) }
                is AuthResult.Failure -> _state.update { it.copy(submitting = false, error = r.error.userMessage()) }
            }
        }
    }
}
