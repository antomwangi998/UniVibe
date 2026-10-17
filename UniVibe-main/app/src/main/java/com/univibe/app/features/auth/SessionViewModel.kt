package com.univibe.app.features.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.univibe.app.app.AppContainer
import com.univibe.app.domain.auth.AuthRepository
import com.univibe.app.domain.auth.AuthState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Owns the app-level auth state that decides which part of the app is shown. */
class SessionViewModel(
    private val repository: AuthRepository = AppContainer.authRepository,
) : ViewModel() {
    val state: StateFlow<AuthState> = repository.session

    init { viewModelScope.launch { repository.restoreSession() } }

    fun logOut() { viewModelScope.launch { repository.logOut() } }
}
