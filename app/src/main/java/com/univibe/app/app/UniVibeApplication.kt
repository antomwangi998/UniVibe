package com.univibe.app.app

import android.app.Application
import com.univibe.app.BuildConfig
import com.univibe.app.data.auth.KeystoreSecureStore
import com.univibe.app.data.auth.MockAuthRepository
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

    // MOCK data layer: one shared in-memory store so screens stay consistent with each other.
    private val mockStore by lazy { MockStore() }
    val feedRepository: FeedRepository by lazy { MockFeedRepository(mockStore) }
    val commentRepository: CommentRepository by lazy { MockCommentRepository(mockStore) }
    val groupRepository: GroupRepository by lazy { MockGroupRepository(mockStore) }
    val chatRepository: ChatRepository by lazy { MockChatRepository(mockStore) }
    val messageRepository: MessageRepository by lazy { MockMessageRepository(mockStore) }
    val communityRepository: CommunityRepository by lazy { MockCommunityRepository(mockStore) }
    /** One shared instance so RSVPs made on the details screen show up in the list. */
    /** One shared instance so a decision made on a full profile also updates the Discover deck. */
    val discoverRepository: DiscoverRepository by lazy { MockDiscoverRepository() }
    val notificationRepository: NotificationRepository by lazy { MockNotificationRepository() }
    val eventRepository: EventRepository by lazy { MockEventRepository(mockStore) }
    val profileRepository: ProfileRepository by lazy { MockProfileRepository(mockStore, DataStoreProfileLocalStore(app)) }

    val settingsRepository: SettingsRepository by lazy { DataStoreSettingsRepository(app) }

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
