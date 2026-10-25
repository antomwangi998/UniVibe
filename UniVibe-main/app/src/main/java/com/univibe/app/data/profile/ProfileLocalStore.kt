package com.univibe.app.data.profile

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** The signed-in student's own profile edits. Null means "not edited, use the default". */
data class ProfileEdits(
    val name: String? = null,
    val bio: String? = null,
    val course: String? = null,
    val year: String? = null,
    val interests: List<String>? = null,
    val avatarPath: String? = null,
    val coverPath: String? = null,
    val photos: List<String> = emptyList(),
    val discoverPhotoPath: String? = null,
)

interface ProfileLocalStore {
    val edits: Flow<ProfileEdits>
    suspend fun update(change: (ProfileEdits) -> ProfileEdits)
}

class InMemoryProfileLocalStore : ProfileLocalStore {
    private val state = MutableStateFlow(ProfileEdits())
    override val edits: Flow<ProfileEdits> = state
    override suspend fun update(change: (ProfileEdits) -> ProfileEdits) { state.update(change) }
}

private val Context.profileStore by preferencesDataStore(name = "profile_local")

class DataStoreProfileLocalStore(private val context: Context) : ProfileLocalStore {
    private object K {
        val name = stringPreferencesKey("name")
        val bio = stringPreferencesKey("bio")
        val course = stringPreferencesKey("course")
        val year = stringPreferencesKey("year")
        val interests = stringPreferencesKey("interests")
        val avatar = stringPreferencesKey("avatar")
        val cover = stringPreferencesKey("cover")
        val photos = stringPreferencesKey("photos")
        val discoverPhoto = stringPreferencesKey("discover_photo")
    }

    override val edits: Flow<ProfileEdits> = context.profileStore.data
        .catch { if (it is java.io.IOException) emit(emptyPreferences()) else throw it }
        .map { it.toEdits() }

    override suspend fun update(change: (ProfileEdits) -> ProfileEdits) {
        context.profileStore.edit { prefs ->
            val e = change(prefs.toEdits())
            fun put(key: Preferences.Key<String>, value: String?) { if (value == null) prefs.remove(key) else prefs[key] = value }
            put(K.name, e.name); put(K.bio, e.bio); put(K.course, e.course); put(K.year, e.year)
            put(K.interests, e.interests?.joinToString("\u001F"))
            put(K.avatar, e.avatarPath); put(K.cover, e.coverPath)
            put(K.photos, e.photos.joinToString("\n"))
            put(K.discoverPhoto, e.discoverPhotoPath)
        }
    }

    private fun Preferences.toEdits() = ProfileEdits(
        name = this[K.name], bio = this[K.bio], course = this[K.course], year = this[K.year],
        interests = this[K.interests]?.split('\u001F')?.filter { it.isNotEmpty() },
        avatarPath = this[K.avatar], coverPath = this[K.cover],
        photos = this[K.photos].orEmpty().split('\n').filter { it.isNotEmpty() },
        discoverPhotoPath = this[K.discoverPhoto],
    )
}
