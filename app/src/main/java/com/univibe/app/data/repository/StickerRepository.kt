package com.univibe.app.data.repository

import com.univibe.app.data.model.StickerPack
import com.univibe.app.data.model.StickerPage
import com.univibe.app.data.model.StickerRef
import com.univibe.app.data.net.AuthedApi
import com.univibe.app.data.net.send
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/** Sticker packs and my installed stickers. The server decides what is free, Premium, installed or sendable; the app shows it. */
interface StickerRepository {
    suspend fun store(category: String?, query: String?, cursor: String?): StickerPage
    suspend fun pack(id: String): StickerPack
    suspend fun install(packId: String)
    suspend fun uninstall(packId: String)
    suspend fun setPackFavourite(packId: String, favourite: Boolean)
    /** My installed packs, each with its stickers: what the picker shows. */
    suspend fun installed(): List<StickerPack>
    suspend fun favourites(): List<StickerRef>
    suspend fun recent(): List<StickerRef>
    suspend fun search(query: String): List<StickerRef>
    suspend fun setStickerFavourite(stickerId: String, favourite: Boolean)
}

/** Offline demo: stickers live on the server, so the screen says so instead of pretending. */
object NoStickerRepository : StickerRepository {
    private fun unavailable(): Nothing = throw UnsupportedOperationException("Stickers need a connection to UniVibe.")
    override suspend fun store(category: String?, query: String?, cursor: String?) = unavailable()
    override suspend fun pack(id: String) = unavailable()
    override suspend fun install(packId: String) = unavailable()
    override suspend fun uninstall(packId: String) = unavailable()
    override suspend fun setPackFavourite(packId: String, favourite: Boolean) = unavailable()
    override suspend fun installed() = unavailable()
    override suspend fun favourites() = unavailable()
    override suspend fun recent() = unavailable()
    override suspend fun search(query: String) = unavailable()
    override suspend fun setStickerFavourite(stickerId: String, favourite: Boolean) = unavailable()
}

private fun webUrl(s: String?): String? = s?.takeIf { it.startsWith("https://", true) || it.startsWith("http://", true) }

/** A sticker object from the server (a pack page, the picker, or a message). */
internal fun JSONObject.toStickerRef(): StickerRef = StickerRef(
    id = getString("id"),
    packId = strOrNull("packId"),
    label = strOrNull("label"),
    url = webUrl(strOrNull("url")),
    thumbUrl = webUrl(strOrNull("thumbUrl")),
    width = optInt("width", 0).takeIf { it > 0 },
    height = optInt("height", 0).takeIf { it > 0 },
    animated = optBoolean("animated", false),
    available = optBoolean("available", true),
)

private fun JSONArray?.toStickers(): List<StickerRef> =
    if (this == null) emptyList() else List(length()) { getJSONObject(it).toStickerRef() }

private fun JSONObject.toStickerPack(): StickerPack = StickerPack(
    id = getString("id"), name = getString("name"), description = optString("description", ""), category = optString("category", ""),
    premium = optBoolean("premium", false), locked = optBoolean("locked", false), stickerCount = optInt("stickerCount", 0),
    creatorHandle = optJSONObject("creator")?.strOrNull("handle"),
    installed = optBoolean("installed", false), favourite = optBoolean("favourite", false),
    coverThumbUrl = webUrl(strOrNull("coverThumbUrl")),
    stickers = optJSONArray("stickers").toStickers(),
)

class RemoteStickerRepository(private val api: AuthedApi) : StickerRepository {
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private suspend fun get(path: String) = JSONObject(api.send("GET", "/api/v1$path"))

    override suspend fun store(category: String?, query: String?, cursor: String?): StickerPage {
        val params = buildList {
            add("limit=20")
            category?.let { add("category=${enc(it)}") }
            query?.takeIf { it.isNotBlank() }?.let { add("q=${enc(it.trim())}") }
            cursor?.let { add("cursor=${enc(it)}") }
        }.joinToString("&")
        val o = get("/stickers/packs?$params")
        val items = o.optJSONArray("items") ?: JSONArray()
        return StickerPage(List(items.length()) { items.getJSONObject(it).toStickerPack() }, o.strOrNull("nextCursor"))
    }

    override suspend fun pack(id: String): StickerPack {
        val o = get("/stickers/packs/${enc(id)}")
        return o.getJSONObject("pack").toStickerPack().copy(stickers = o.optJSONArray("stickers").toStickers())
    }

    override suspend fun install(packId: String) { api.send("PUT", "/api/v1/me/stickers/packs/${enc(packId)}", JSONObject()) }
    override suspend fun uninstall(packId: String) { api.send("DELETE", "/api/v1/me/stickers/packs/${enc(packId)}") }
    override suspend fun setPackFavourite(packId: String, favourite: Boolean) {
        api.send(if (favourite) "PUT" else "DELETE", "/api/v1/stickers/packs/${enc(packId)}/favourite", if (favourite) JSONObject() else null)
    }

    override suspend fun installed(): List<StickerPack> {
        val a = get("/me/stickers/packs").optJSONArray("packs") ?: JSONArray()
        return List(a.length()) { a.getJSONObject(it).toStickerPack() }
    }

    override suspend fun favourites(): List<StickerRef> = get("/me/stickers/favourites").optJSONArray("items").toStickers()
    override suspend fun recent(): List<StickerRef> = get("/me/stickers/recent").optJSONArray("items").toStickers()
    override suspend fun search(query: String): List<StickerRef> = get("/me/stickers/search?q=${enc(query.trim())}").optJSONArray("items").toStickers()

    override suspend fun setStickerFavourite(stickerId: String, favourite: Boolean) {
        api.send(if (favourite) "PUT" else "DELETE", "/api/v1/me/stickers/favourites/${enc(stickerId)}", if (favourite) JSONObject() else null)
    }
}
