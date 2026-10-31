package com.univibe.app.data.repository

import com.univibe.app.data.model.ServerChatBackground
import com.univibe.app.data.model.ThemeAsset
import com.univibe.app.data.model.ThemeCategory
import com.univibe.app.data.model.ThemePage
import com.univibe.app.data.net.AuthedApi
import com.univibe.app.data.net.send
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/** The Personalization Studio catalogue and my saved chat background. The server decides what is Premium and what is mine. */
interface ThemeRepository {
    suspend fun categories(): List<ThemeCategory>
    suspend fun assets(category: String?, query: String?, cursor: String?): ThemePage
    suspend fun favourites(cursor: String?): ThemePage
    suspend fun setFavourite(assetId: String, favourite: Boolean)
    /** My saved catalogue background for [chatId] (or my default for every chat when null). Null when none is saved or it can't be shown. */
    suspend fun chatBackground(chatId: String?): ServerChatBackground?
    /**
     * Saves a catalogue image as the background of every chat ([chatId] null) or one chat. A null [assetId] saves "no catalogue
     * picture" for that scope, which also overrides a default saved for all chats.
     */
    suspend fun saveChatBackground(chatId: String?, assetId: String?, dim: Int)
}

object NoThemeRepository : ThemeRepository {
    private fun unavailable(): Nothing = throw UnsupportedOperationException("Themes need a connection to UniVibe.")
    override suspend fun categories() = unavailable()
    override suspend fun assets(category: String?, query: String?, cursor: String?) = unavailable()
    override suspend fun favourites(cursor: String?) = unavailable()
    override suspend fun setFavourite(assetId: String, favourite: Boolean) = unavailable()
    override suspend fun chatBackground(chatId: String?) = unavailable()
    override suspend fun saveChatBackground(chatId: String?, assetId: String?, dim: Int) = unavailable()
}

private fun webUrl(s: String?): String? = s?.takeIf { it.startsWith("https://", true) || it.startsWith("http://", true) }

internal fun JSONObject.toThemeAsset(): ThemeAsset {
    val category = optJSONObject("category")
    val dest = optJSONArray("destinations")
    return ThemeAsset(
        id = getString("id"), title = optString("title", ""), description = optString("description", ""),
        categorySlug = category?.optString("slug", "") ?: "", categoryName = category?.optString("name", "") ?: "",
        creatorHandle = optJSONObject("creator")?.strOrNull("handle"), sourceAttribution = optString("sourceAttribution", ""),
        premium = optBoolean("premium", false), locked = optBoolean("locked", false),
        destinations = if (dest == null) emptyList() else List(dest.length()) { dest.getString(it) },
        width = optInt("width", 0), height = optInt("height", 0), favourited = optBoolean("favourited", false),
        thumbUrl = webUrl(strOrNull("thumbUrl")), fullUrl = webUrl(strOrNull("fullUrl")),
    )
}

private fun JSONObject.toThemePage(): ThemePage {
    val items = optJSONArray("items") ?: JSONArray()
    return ThemePage(List(items.length()) { items.getJSONObject(it).toThemeAsset() }, strOrNull("nextCursor"))
}

class RemoteThemeRepository(private val api: AuthedApi) : ThemeRepository {
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private suspend fun get(path: String) = JSONObject(api.send("GET", "/api/v1$path"))

    override suspend fun categories(): List<ThemeCategory> {
        val a = get("/personalization/categories").optJSONArray("categories") ?: JSONArray()
        return List(a.length()) { a.getJSONObject(it).let { c -> ThemeCategory(c.getString("slug"), c.getString("name")) } }
    }

    override suspend fun assets(category: String?, query: String?, cursor: String?): ThemePage {
        val params = buildList {
            add("limit=24")
            category?.let { add("category=${enc(it)}") }
            query?.takeIf { it.isNotBlank() }?.let { add("q=${enc(it.trim())}") }
            cursor?.let { add("cursor=${enc(it)}") }
        }.joinToString("&")
        return get("/personalization/assets?$params").toThemePage()
    }

    override suspend fun favourites(cursor: String?): ThemePage =
        get("/me/personalization/favourites?limit=24" + (cursor?.let { "&cursor=${enc(it)}" } ?: "")).toThemePage()

    override suspend fun setFavourite(assetId: String, favourite: Boolean) {
        api.send(if (favourite) "PUT" else "DELETE", "/api/v1/personalization/assets/${enc(assetId)}/favourite", if (favourite) JSONObject() else null)
    }

    override suspend fun chatBackground(chatId: String?): ServerChatBackground? {
        val o = get("/me/chat-appearance" + (chatId?.let { "?conversationId=${enc(it)}" } ?: ""))
        val wallpaper = o.optJSONObject("wallpaper") ?: return null
        val url = webUrl(wallpaper.strOrNull("fullUrl")) ?: return null
        val shown = o.optJSONObject("shown")
        return ServerChatBackground(wallpaper.getString("id"), url, shown?.optInt("dim", 0) ?: 0)
    }

    override suspend fun saveChatBackground(chatId: String?, assetId: String?, dim: Int) {
        val body = JSONObject().put("dim", dim.coerceIn(0, 80))
        assetId?.let { body.put("wallpaperAssetId", it) }
        chatId?.let { body.put("conversationId", it) }
        api.send("PUT", "/api/v1/me/chat-appearance", body)
    }
}
