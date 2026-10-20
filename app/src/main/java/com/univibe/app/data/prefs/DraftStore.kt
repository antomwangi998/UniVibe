package com.univibe.app.data.prefs

import android.content.Context
import android.content.SharedPreferences

/**
 * Unsent text (the home composer, new posts, comments and chat messages) kept on this device so closing the app or
 * leaving a screen never loses it. Stored in app-private storage; cleared when the draft is sent and on sign-out.
 * Does nothing until [init] runs, so unit tests and previews are unaffected.
 */
object DraftStore {
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences("univibe_drafts", Context.MODE_PRIVATE)
    }

    fun get(key: String): String = prefs?.getString(key, null).orEmpty()

    fun set(key: String, text: String) {
        val p = prefs ?: return
        if (text.isBlank()) p.edit().remove(key).apply() else p.edit().putString(key, text.take(5000)).apply()
    }

    fun clear(key: String) = set(key, "")

    fun clearAll() { prefs?.edit()?.clear()?.apply() }
}
