package com.univibe.app.app

import android.app.Application
import com.univibe.app.BuildConfig
import com.univibe.app.data.auth.KeystoreSecureStore
import com.univibe.app.data.auth.MockAuthRepository
import com.univibe.app.data.auth.RemoteAuthRepository
import com.univibe.app.data.net.UrlConnectionTransport
import com.univibe.app.data.settings.DataStoreSettingsRepository
import com.univibe.app.data.settings.SettingsRepository
import com.univibe.app.data.profile.DataStoreProfileLocalStore
import com.univibe.app.data.repository.ChatRepository
import com.univibe.app.data.repository.CommentRepository
import com.univibe.app.data.repository.CommunityRepository
import com.univibe.app.data.repository.DiscoverRepository
import com.univibe.app.data.repository.EventRepository
import com.univibe.app.data.repository.MockDiscoverRepository
import com.univibe.app.data.repository.GroupRepository
import com.univibe.app.data.repository.MockEventRepository
import com.univibe.app.data.repository.MockGroupRepository
import com.univibe.app.data.repository.MockNotificationRepository
import com.univibe.app.data.repository.NotificationRepository
import com.univibe.app.data.repository.FeedRepository
import com.univibe.app.data.repository.MessageRepository
import com.univibe.app.data.repository.MockChatRepository
import com.univibe.app.data.repository.MockCommentRepository
import com.univibe.app.data.repository.MockCommunityRepository
import com.univibe.app.data.repository.MockFeedRepository
import com.univibe.app.data.repository.MockMessageRepository
import com.univibe.app.data.repository.MockProfileRepository
import com.univibe.app.data.repository.MockStore
import com.univibe.app.data.repository.ProfileRepository
import com.univibe.app.data.net.AuthedApi
import com.univibe.app.data.net.MediaUploader
import com.univibe.app.data.net.UrlConnectionFileUploader
import com.univibe.app.data.repository.MockData
import com.univibe.app.data.repository.RemoteCommentRepository
import com.univibe.app.data.repository.ProfileSaveException
import com.univibe.app.data.repository.RemoteDiscoverRepository
import com.univibe.app.data.repository.RemoteFeedRepository
import com.univibe.app.data.repository.RemoteNotificationRepository
import com.univibe.app.data.repository.RemoteProfileBackend
import com.univibe.app.domain.auth.AuthRepository
import com.univibe.app.domain.auth.AuthResult
import com.univibe.app.domain.auth.AuthState
import com.univibe.app.domain.auth.AuthUser
import com.univibe.app.domain.auth.userMessage
import com.univibe.app.push.PushRegistrar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class UniVibeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppContainer.init(this)
    }
}

/** Minimal service locator; swap in Hilt/Koin only if the project outgrows this. */
object AppContainer {
    private lateinit var app: Application

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun init(application: Application) {
        app = application
        // The mock screens (feed, chats, comments) show "you" from this identity, so keep it on the real student.
        appScope.launch { authRepository.session.collect { MockData.applyAccount(accountOf(it)) } }
        // Once signed in, make sure this device's push address is registered with the server (cheap no-op when unchanged).
        appScope.launch {
            authRepository.session.collect { state ->
                if (state is AuthState.Authenticated) appScope.launch { pushRegistrar.registerIfNeeded() }
            }
        }
    }

    private fun accountOf(state: AuthState): AuthUser? = when (state) {
        is AuthState.Authenticated -> state.user
        is AuthState.OnboardingRequired -> state.user
        else -> null
    }

    // MOCK data layer: one shared in-memory store so screens stay consistent with each other.
    // With a real backend the sample social graph (people followed, clubs joined) is not seeded: a new student starts empty.
    private val mockStore by lazy { MockStore(demo = BuildConfig.API_BASE_URL.isBlank()) }
    /** The server connection, when the app runs against a real backend (null in offline demo mode). */
    private val api: AuthedApi? get() = authRepository as? AuthedApi

    val feedRepository: FeedRepository by lazy {
        api?.let { RemoteFeedRepository(it, MediaUploader(it, UrlConnectionFileUploader())) { accountOf(authRepository.session.value) } }
            ?: MockFeedRepository(mockStore)
    }
    val commentRepository: CommentRepository by lazy { api?.let { RemoteCommentRepository(it) } ?: MockCommentRepository(mockStore) }
    val groupRepository: GroupRepository by lazy { MockGroupRepository(mockStore) }
    val chatRepository: ChatRepository by lazy { MockChatRepository(mockStore) }
    val messageRepository: MessageRepository by lazy { MockMessageRepository(mockStore) }
    val communityRepository: CommunityRepository by lazy { MockCommunityRepository(mockStore) }
    /** One shared instance so RSVPs made on the details screen show up in the list. */
    /** One shared instance so a decision made on a full profile also updates the Discover deck. */
    // With a backend these are the real thing (no sample people or sample notifications); the mocks are offline demo only.
    val discoverRepository: DiscoverRepository by lazy {
        api?.let { RemoteDiscoverRepository(it) { accountOf(authRepository.session.value) } } ?: MockDiscoverRepository()
    }
    val notificationRepository: NotificationRepository by lazy { api?.let { RemoteNotificationRepository(it) } ?: MockNotificationRepository() }
    val eventRepository: EventRepository by lazy { MockEventRepository(mockStore) }
    val profileRepository: ProfileRepository by lazy {
        MockProfileRepository(
            mockStore, DataStoreProfileLocalStore(app),
            account = { accountOf(authRepository.session.value) },
            saveAccount = { edit ->
                when (val result = authRepository.updateProfile(edit)) {
                    is AuthResult.Success -> true
                    // Say why (for example "uploads are unavailable") instead of a vague failure.
                    is AuthResult.Failure -> throw ProfileSaveException(result.error.userMessage())
                }
            },
            backend = api?.let { RemoteProfileBackend(it) },
        )
    }

    val pushRegistrar: PushRegistrar by lazy {
        PushRegistrar(app, api = { api }, userId = { accountOf(authRepository.session.value)?.id })
    }

    val settingsRepository: SettingsRepository by lazy { DataStoreSettingsRepository(app) }

    val authRepository: AuthRepository by lazy {
        if (BuildConfig.API_BASE_URL.isBlank()) {
            // The mock must never ship: release builds refuse to start without a real backend.
            check(BuildConfig.DEBUG) { "No backend configured. Set univibe.apiBaseUrl and provide a RemoteAuthRepository." }
            MockAuthRepository(KeystoreSecureStore(app))
        } else {
            RemoteAuthRepository(
                BuildConfig.API_BASE_URL, UrlConnectionTransport(), KeystoreSecureStore(app), files = UrlConnectionFileUploader(),
                beforeLogOut = { pushRegistrar.unregister() },
            )
        }
    }
}
