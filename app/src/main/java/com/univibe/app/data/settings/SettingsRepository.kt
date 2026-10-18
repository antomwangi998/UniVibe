package com.univibe.app.data.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import com.univibe.app.data.model.FeedFilter
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

interface SettingsRepository {
    val settings: Flow<AppSettings>
    suspend fun update(change: (AppSettings) -> AppSettings)
}

class InMemorySettingsRepository(initial: AppSettings = AppSettings()) : SettingsRepository {
    private val state = MutableStateFlow(initial)
    override val settings: Flow<AppSettings> = state
    override suspend fun update(change: (AppSettings) -> AppSettings) { state.update(change) }
}

private val Context.userSettingsStore by preferencesDataStore(name = "user_settings")

/** Persists [AppSettings] with DataStore. */
class DataStoreSettingsRepository(private val context: Context) : SettingsRepository {
    override val settings: Flow<AppSettings> = context.userSettingsStore.data
        .catch { if (it is java.io.IOException) emit(emptyPreferences()) else throw it }
        .map { it.toSettings() }

    override suspend fun update(change: (AppSettings) -> AppSettings) {
        context.userSettingsStore.edit { prefs -> prefs.write(change(prefs.toSettings())) }
    }

    private object K {
        val discoverable = booleanPreferencesKey("discoverable")
        val showOnline = booleanPreferencesKey("show_online")
        val readReceipts = booleanPreferencesKey("read_receipts")
        val messagePolicy = stringPreferencesKey("message_policy")
        val blocked = stringSetPreferencesKey("blocked")
        val notifications = booleanPreferencesKey("notifications")
        val likes = booleanPreferencesKey("notify_likes")
        val comments = booleanPreferencesKey("notify_comments")
        val messages = booleanPreferencesKey("notify_messages")
        val events = booleanPreferencesKey("notify_events")
        val communities = booleanPreferencesKey("notify_communities")
        val quiet = booleanPreferencesKey("quiet_hours")
        val digest = booleanPreferencesKey("email_digest")
        val reduceMotion = booleanPreferencesKey("reduce_motion")
        val textScale = stringPreferencesKey("text_scale")
        val feedFilter = stringPreferencesKey("feed_filter")
    }

    private fun Preferences.toSettings(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            discoverable = this[K.discoverable] ?: d.discoverable,
            showOnline = this[K.showOnline] ?: d.showOnline,
            showReadReceipts = this[K.readReceipts] ?: d.showReadReceipts,
            messagePolicy = this[K.messagePolicy]?.let { runCatching { MessagePolicy.valueOf(it) }.getOrNull() } ?: d.messagePolicy,
            blocked = this[K.blocked].orEmpty().mapNotNull {
                val parts = it.split('\u001F', limit = 2)
                if (parts.size == 2) BlockedUser(parts[0], parts[1]) else null
            }.sortedBy { it.name },
            notificationsEnabled = this[K.notifications] ?: d.notificationsEnabled,
            notifyLikes = this[K.likes] ?: d.notifyLikes,
            notifyComments = this[K.comments] ?: d.notifyComments,
            notifyMessages = this[K.messages] ?: d.notifyMessages,
            notifyEvents = this[K.events] ?: d.notifyEvents,
            notifyCommunities = this[K.communities] ?: d.notifyCommunities,
            quietHours = this[K.quiet] ?: d.quietHours,
            emailDigest = this[K.digest] ?: d.emailDigest,
            reduceMotion = this[K.reduceMotion] ?: d.reduceMotion,
            textScale = this[K.textScale]?.let { runCatching { TextScale.valueOf(it) }.getOrNull() } ?: d.textScale,
            feedFilter = this[K.feedFilter]?.let { runCatching { FeedFilter.valueOf(it) }.getOrNull() } ?: d.feedFilter,
        )
    }

    private fun androidx.datastore.preferences.core.MutablePreferences.write(s: AppSettings) {
        this[K.discoverable] = s.discoverable
        this[K.showOnline] = s.showOnline
        this[K.readReceipts] = s.showReadReceipts
        this[K.messagePolicy] = s.messagePolicy.name
        this[K.blocked] = s.blocked.map { "${it.id}\u001F${it.name}" }.toSet()
        this[K.notifications] = s.notificationsEnabled
        this[K.likes] = s.notifyLikes
        this[K.comments] = s.notifyComments
        this[K.messages] = s.notifyMessages
        this[K.events] = s.notifyEvents
        this[K.communities] = s.notifyCommunities
        this[K.quiet] = s.quietHours
        this[K.digest] = s.emailDigest
        this[K.reduceMotion] = s.reduceMotion
        this[K.textScale] = s.textScale.name
        this[K.feedFilter] = s.feedFilter.name
    }
}
