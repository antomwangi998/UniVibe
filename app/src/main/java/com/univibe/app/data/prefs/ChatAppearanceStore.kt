package com.univibe.app.data.prefs

import android.content.Context
import android.content.SharedPreferences
import com.univibe.app.data.media.PhotoStore

/** One conversation's look: an accent theme and a wallpaper. Ids are those in `ChatAppearance.kt`; "default" and "none" mean unchanged. */
data class StoredChatAppearance(
    val themeId: String = "default",
    val wallpaperId: String = "none",
    /** The picked picture when [wallpaperId] is "photo"; a private copy in app storage. */
    val wallpaperPhoto: String? = null,
)

/**
 * Per-conversation theme and wallpaper, kept on this device only (like drafts). Each person sets their own look for a
 * chat; the other person never sees it. Does nothing until [init] runs, so unit tests and previews are unaffected.
 */
object ChatAppearanceStore {
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences("univibe_chat_appearance", Context.MODE_PRIVATE)
    }

    fun get(chatId: String): StoredChatAppearance {
        val p = prefs ?: return StoredChatAppearance()
        return StoredChatAppearance(
            themeId = p.getString("theme:$chatId", null) ?: "default",
            wallpaperId = p.getString("wall:$chatId", null) ?: "none",
            wallpaperPhoto = p.getString("photo:$chatId", null),
        )
    }

    fun set(chatId: String, value: StoredChatAppearance) {
        val p = prefs ?: return
        p.edit().apply {
            if (value.themeId == "default") remove("theme:$chatId") else putString("theme:$chatId", value.themeId)
            if (value.wallpaperId == "none") remove("wall:$chatId") else putString("wall:$chatId", value.wallpaperId)
            if (value.wallpaperPhoto == null) remove("photo:$chatId") else putString("photo:$chatId", value.wallpaperPhoto)
        }.apply()
    }

    /** On sign-out: forget every chat's look and delete the wallpaper pictures with it. */
    fun clearAll() {
        val p = prefs ?: return
        p.all.filterKeys { it.startsWith("photo:") }.values.forEach { PhotoStore.delete(it as? String) }
        p.edit().clear().apply()
    }
}
