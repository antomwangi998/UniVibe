package com.univibe.app.app

import android.app.Application
import com.univibe.app.BuildConfig
import com.univibe.app.data.auth.KeystoreSecureStore
import com.univibe.app.data.auth.MockAuthRepository
import com.univibe.app.domain.auth.AuthRepository

class UniVibeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppContainer.init(this)
    }
}

/** Minimal service locator; swap in Hilt/Koin only if the project outgrows this. */
object AppContainer {
    private lateinit var app: Application

    fun init(application: Application) { app = application }

    val authRepository: AuthRepository by lazy {
        if (BuildConfig.API_BASE_URL.isBlank()) {
            // The mock must never ship: release builds refuse to start without a real backend.
            check(BuildConfig.DEBUG) { "No backend configured. Set univibe.apiBaseUrl and provide a RemoteAuthRepository." }
            MockAuthRepository(KeystoreSecureStore(app))
        } else {
            error("RemoteAuthRepository is not implemented yet. See docs/AUTH_BACKEND.md.")
        }
    }
}
