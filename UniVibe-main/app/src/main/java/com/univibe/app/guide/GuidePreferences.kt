package com.univibe.app.guide

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/** Where guide progress is kept. An interface so the controller can be tested without Android. */
interface GuideProgressStore {
    /** Null until the stored value has been read, so nothing flashes on screen before we know. */
    fun welcomeSeen(userId: String): Flow<Boolean?>
    suspend fun markWelcomeSeen(userId: String)
    fun progress(userId: String, tourId: String): Flow<TourProgress>
    suspend fun setProgress(userId: String, tourId: String, progress: TourProgress)
}

private val Context.guideDataStore by preferencesDataStore(name = "univibe_guide")

/**
 * Progress is stored on this phone, per signed-in account, so two people sharing a phone each get their own tour.
 * It holds only a flag and a step number, never any account data.
 */
class GuidePreferences(private val context: Context) : GuideProgressStore {
    private fun welcomeKey(userId: String) = booleanPreferencesKey("welcome_seen_$userId")
    private fun tourKey(userId: String, tourId: String) = stringPreferencesKey("tour_${userId}_$tourId")

    private val data get() = context.guideDataStore.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }

    override fun welcomeSeen(userId: String): Flow<Boolean?> = data.map { it[welcomeKey(userId)] ?: false }

    override suspend fun markWelcomeSeen(userId: String) { context.guideDataStore.edit { it[welcomeKey(userId)] = true } }

    override fun progress(userId: String, tourId: String): Flow<TourProgress> =
        data.map { TourProgress.decode(it[tourKey(userId, tourId)]) }

    override suspend fun setProgress(userId: String, tourId: String, progress: TourProgress) {
        context.guideDataStore.edit { it[tourKey(userId, tourId)] = progress.encode() }
    }
}
