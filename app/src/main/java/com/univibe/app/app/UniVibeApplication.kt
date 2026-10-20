package com.univibe.app.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.univibe.app.BuildConfig
import com.univibe.app.data.auth.CredentialManagerGoogleProvider
import com.univibe.app.data.auth.GoogleCredentialProvider
import com.univibe.app.data.auth.KeystoreSecureStore
import com.univibe.app.data.auth.MockAuthRepository
import com.univibe.app.data.auth.RemoteAuthRepository
import com.univibe.app.data.net.UrlConnectionTransport
import com.univibe.app.data.settings.AccountSecurityRepository
import com.univibe.app.data.settings.LocalNotificationPrefsRepository
import com.univibe.app.data.settings.LocalPrivacyRepository
import com.univibe.app.data.settings.NotificationPrefsRepository
import com.univibe.app.data.settings.RemoteNotificationPrefsRepository
import com.univibe.app.data.settings.PrivacyRepository
import com.univibe.app.data.settings.RemotePrivacyRepository
import com.univibe.app.data.settings.DataStoreSettingsRepository
import com.univibe.app.data.settings.RemoteAccountSecurityRepository
import com.univibe.app.data.settings.UnavailableAccountSecurityRepository
import com.univibe.app.data.settings.SettingsRepository
import com.univibe.app.data.profile.DataStoreProfileLocalStore
import com.univibe.app.data.repository.ChatRepository
import com.univibe.app.data.repository.CommentRepository
import com.univibe.app.data.repository.CommunityMembershipStore
import com.univibe.app.data.repository.CommunityRepository
import com.univibe.app.data.repository.MembershipActions
import com.univibe.app.data.repository.RemoteCommunityRepository
import com.univibe.app.data.repository.RepositoryMembershipActions
import com.univibe.app.data.repository.DiscoverContentRepository
import com.univibe.app.data.repository.DiscoverRepository
import com.univibe.app.data.repository.FeedBackedDiscoverContent
import com.univibe.app.data.repository.PrefsRecentSearches
import com.univibe.app.data.repository.RecentSearches
import com.univibe.app.data.repository.RemoteDiscoverContentRepository
import com.univibe.app.data.repository.RemoteSearchRepository
import com.univibe.app.data.repository.SearchRepository
import com.univibe.app.data.repository.UnavailableSearchRepository
import com.univibe.app.data.repository.RemoteEventRepository
import com.univibe.app.data.repository.EventRepository
import com.univibe.app.data.repository.MockDiscoverRepository
import com.univibe.app.data.repository.GroupRepository
import com.univibe.app.data.repository.RemoteGroupRepository
import com.univibe.app.data.repository.UnavailableChatRepository
import com.univibe.app.data.repository.UnavailableGroupRepository
import com.univibe.app.data.repository.UnavailableMessageRepository
import com.univibe.app.data.repository.MockEventRepository
import com.univibe.app.data.repository.MockNotificationRepository
import com.univibe.app.data.repository.NotificationRepository
import com.univibe.app.data.repository.FeedRepository
import com.univibe.app.data.repository.MessageRepository
import com.univibe.app.data.repository.MockCommentRepository
import com.univibe.app.data.repository.MockCommunityRepository
import com.univibe.app.data.repository.MockFeedRepository
import com.univibe.app.data.repository.MockProfileRepository
import com.univibe.app.data.repository.MockStore
import com.univibe.app.data.repository.RemoteChatRepository
import com.univibe.app.data.repository.RemoteMessageRepository
import com.univibe.app.data.repository.ProfileRepository
import com.univibe.app.data.net.AuthedApi
import com.univibe.app.data.net.ChatSocket
import com.univibe.app.data.net.MediaUploader
import com.univibe.app.data.net.OkHttpSocketFactory
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
import com.univibe.app.data.prefs.DraftStore

class UniVibeApplication : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        DraftStore.init(this)
        AppContainer.init(this)
    }

    /**
     * One shared image loader: photos fade in instead of popping, and a photo already on disk is reused even if the
     * server marks it uncacheable (the links are signed and change hourly; PhotoImage keys the cache without the signature).
     */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .crossfade(160)
        .respectCacheHeaders(false)
        .build()
}

/** Minimal service locator; swap in Hilt/Koin only if the project outgrows this. */
object AppContainer {
    private lateinit var app: Application

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun init(application: Application) {
        app = application
        // Channels exist from the first launch, so they show up in system settings and a push never arrives without one.
        com.univibe.app.push.NotificationChannels.ensure(application)
        // The mock screens (feed, chats, comments) show "you" from this identity, so keep it on the real student.
        appScope.launch { authRepository.session.collect { MockData.applyAccount(accountOf(it)) } }
        // Unsent drafts belong to the account that wrote them: a deliberate sign-out clears them.
        appScope.launch { authRepository.session.collect { if (it is AuthState.SignedOut && it.reason == null) com.univibe.app.data.prefs.DraftStore.clearAll() } }
        // Saved screens belong to the signed-in account: switching accounts or signing out on purpose wipes them.
        appScope.launch {
            authRepository.session.collect { state ->
                kotlinx.coroutines.withContext(Dispatchers.IO) {
                    when {
                        state is AuthState.Authenticated -> { offlineCache.useAccount(state.user.id); outbox.useAccount(state.user.id) }
                        state is AuthState.SignedOut && state.reason == null -> { offlineCache.clear(); outbox.clear() }
                    }
                }
                if (state is AuthState.Authenticated) appScope.launch { outboxRunner.flush() }
            }
        }
        // Send what was queued offline: when a network appears, and every half minute while anything is waiting.
        (feedRepository as? RemoteFeedRepository)?.let { r ->
            outboxRunner.register("like") { r.deliverQueued(it) }
            outboxRunner.register("save") { r.deliverQueued(it) }
        }
        (messageRepository as? RemoteMessageRepository)?.let { r -> outboxRunner.register("message") { r.deliverQueued(it) } }
        runCatching {
            val connectivity = application.getSystemService(android.net.ConnectivityManager::class.java)
            connectivity.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) { appScope.launch { kotlinx.coroutines.delay(1_500); outboxRunner.flush() } }
            })
        }
        appScope.launch { while (true) { kotlinx.coroutines.delay(30_000); if (outbox.count.value > 0) outboxRunner.flush() } }
        // Once signed in, make sure this device's push address is registered with the server (cheap no-op when unchanged).
        appScope.launch {
            authRepository.session.collect { state ->
                if (state is AuthState.Authenticated) {
                    chatSocket?.start() // live messages, typing, seen and notifications while signed in
                    appScope.launch { pushRegistrar.registerIfNeeded() }
                } else {
                    chatSocket?.stop()
                    communityMembership.clear()
                }
            }
        }
        // Membership changes made elsewhere (approved by a moderator, removed, joined on another device) update every screen.
        appScope.launch {
            chatSocket?.events?.collect { e ->
                if (e is com.univibe.app.data.net.ChatEvent.CommunityMembership) {
                    communityMembership.apply(e.communityId, com.univibe.app.data.model.MembershipState.parse(e.membership), e.memberCount)
                }
            }
        }
        // The bell badge follows the server: any notification change (or a reconnect) re-reads its count.
        appScope.launch { notificationRepository.changes().collect { runCatching { notificationRepository.refreshUnread() } } }
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

    /** The live connection (wss://.../api/v1/ws), only when the app talks to a real server. */
    private val chatSocket: ChatSocket? by lazy {
        val authed = api ?: return@lazy null
        val base = BuildConfig.API_BASE_URL.trim().trimEnd('/')
        if (base.isEmpty()) return@lazy null
        ChatSocket(
            url = base.replaceFirst("http", "ws") + "/api/v1/ws", // https -> wss, http -> ws
            factory = OkHttpSocketFactory(),
            token = { stale -> authed.currentAccessToken(stale) },
            scope = appScope,
        )
    }

    val feedRepository: FeedRepository by lazy {
        api?.let { RemoteFeedRepository(it, MediaUploader(it, UrlConnectionFileUploader())) { accountOf(authRepository.session.value) }.also { r -> r.outbox = outbox } }
            ?: MockFeedRepository(mockStore)
    }
    val commentRepository: CommentRepository by lazy { api?.let { RemoteCommentRepository(it) } ?: MockCommentRepository(mockStore) }
    val groupRepository: GroupRepository by lazy {
        api?.let {
            RemoteGroupRepository(it, MediaUploader(it, UrlConnectionFileUploader()), following = { profileRepository.getFollowing().map { f -> f.student } }) { accountOf(authRepository.session.value)?.id }
        } ?: UnavailableGroupRepository
    }
    val chatRepository: ChatRepository by lazy {
        api?.let { RemoteChatRepository(it, chatSocket) { accountOf(authRepository.session.value)?.id } } ?: UnavailableChatRepository
    }
    val noteRepository: com.univibe.app.data.repository.NoteRepository by lazy {
        api?.let { com.univibe.app.data.repository.RemoteNoteRepository(it, chatSocket) } ?: com.univibe.app.data.repository.UnavailableNoteRepository
    }
    val storyRepository: com.univibe.app.data.repository.StoryRepository by lazy {
        api?.let { com.univibe.app.data.repository.RemoteStoryRepository(it, chatSocket) } ?: com.univibe.app.data.repository.UnavailableStoryRepository
    }
    val messageRepository: MessageRepository by lazy {
        api?.let { RemoteMessageRepository(it, MediaUploader(it, UrlConnectionFileUploader()), chatSocket) { accountOf(authRepository.session.value)?.id }.also { r -> r.outbox = outbox } }
            ?: UnavailableMessageRepository
    }
    val communityRepository: CommunityRepository by lazy {
        api?.let {
            RemoteCommunityRepository(it, myId = { accountOf(authRepository.session.value)?.id }) { id, state, count ->
                // Every membership answer from the server lands in the shared store, so all screens agree.
                communityMembership.apply(id, state, count)
            }
        } ?: MockCommunityRepository(mockStore)
    }
    /** The one source of truth for "am I in this community?" on Home, Discover, search and community pages. */
    val communityMembership: CommunityMembershipStore by lazy {
        val actions = (communityRepository as? MembershipActions) ?: RepositoryMembershipActions(communityRepository)
        CommunityMembershipStore(actions, appScope)
    }
    /** One shared instance so RSVPs made on the details screen show up in the list. */
    /** One shared instance so a decision made on a full profile also updates the Discover deck. */
    // With a backend these are the real thing (no sample people or sample notifications); the mocks are offline demo only.
    val discoverRepository: DiscoverRepository by lazy {
        api?.let { RemoteDiscoverRepository(it) { accountOf(authRepository.session.value) } } ?: MockDiscoverRepository()
    }
    /** Discover's posts, trending, photos, videos and topics. Offline demo builds them from the sample feed. */
    val discoverContentRepository: DiscoverContentRepository by lazy {
        api?.let { RemoteDiscoverContentRepository(it) } ?: FeedBackedDiscoverContent(feedRepository)
    }
    /** Search the campus (students, posts, events). Needs the server; the offline demo says so. */
    val searchRepository: SearchRepository by lazy { api?.let { RemoteSearchRepository(it) } ?: UnavailableSearchRepository }
    val recentSearches: RecentSearches by lazy { PrefsRecentSearches(app) }
    val notificationRepository: NotificationRepository by lazy { api?.let { RemoteNotificationRepository(it, chatSocket) } ?: MockNotificationRepository() }
    val eventRepository: EventRepository by lazy { api?.let { RemoteEventRepository(it) } ?: MockEventRepository(mockStore) }
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

    /** Privacy choices and blocking. Real server: stored and enforced there. Offline preview build: this device only. */
    val privacyRepository: PrivacyRepository by lazy { api?.let { RemotePrivacyRepository(it, settingsRepository) } ?: LocalPrivacyRepository(settingsRepository) }

    /** Which notifications and push alerts you get. Real server: stored and enforced there. Offline preview build: this session only. */
    val notificationPrefsRepository: NotificationPrefsRepository by lazy { api?.let { RemoteNotificationPrefsRepository(it) } ?: LocalNotificationPrefsRepository() }

    /** Login sessions and account status. Real server only: the offline preview build reports them as unavailable. */
    val accountSecurity: AccountSecurityRepository by lazy { api?.let { RemoteAccountSecurityRepository(it) } ?: UnavailableAccountSecurityRepository }

    /** Credential Manager: Sign in with Google and the password manager. Google sign-in is off while no server client id is set. */
    val credentialProvider: GoogleCredentialProvider by lazy { CredentialManagerGoogleProvider(BuildConfig.GOOGLE_SERVER_CLIENT_ID) }

    /** Saved copies of the last-seen screens' data, so the app opens with something to show offline. See [com.univibe.app.data.net.OfflineCache]. */
    val offlineCache: com.univibe.app.data.net.OfflineCache by lazy { com.univibe.app.data.net.OfflineCache(java.io.File(app.cacheDir, "offline")) }

    /** Likes, saves and chat messages made offline, sent when the connection is back. See [com.univibe.app.data.net.Outbox]. */
    val outbox: com.univibe.app.data.net.Outbox by lazy { com.univibe.app.data.net.Outbox(java.io.File(app.filesDir, "outbox.json")) }
    val outboxRunner: com.univibe.app.data.net.OutboxRunner by lazy { com.univibe.app.data.net.OutboxRunner(outbox) }

    val authRepository: AuthRepository by lazy {
        if (BuildConfig.API_BASE_URL.isBlank()) {
            // The mock must never ship: release builds refuse to start without a real backend.
            check(BuildConfig.DEBUG) { "No backend configured. Set univibe.apiBaseUrl and provide a RemoteAuthRepository." }
            MockAuthRepository(KeystoreSecureStore(app))
        } else {
            RemoteAuthRepository(
                BuildConfig.API_BASE_URL, com.univibe.app.data.net.CachingTransport(UrlConnectionTransport(), offlineCache), KeystoreSecureStore(app), files = UrlConnectionFileUploader(),
                beforeLogOut = {
                    pushRegistrar.unregister()
                    // So Google does not silently sign this person straight back in.
                    credentialProvider.clearSignInState(app)
                },
                googleEnabled = credentialProvider.isConfigured,
            )
        }
    }
}
