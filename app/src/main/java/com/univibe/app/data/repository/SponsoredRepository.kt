package com.univibe.app.data.repository

import com.univibe.app.data.net.AuthedApi
import com.univibe.app.data.net.send
import com.univibe.app.features.promotions.sponsoredEventLine
import org.json.JSONObject

/**
 * One paid card, exactly as the server decided to show it. The server has already checked payment, approval, dates, caps,
 * blocks and what this student may see, so the app only draws it. [impressionToken] proves to the server that this
 * particular card was served to this particular student.
 */
data class SponsoredCard(
    val campaignId: String,
    val advertiser: String,
    val advertiserVerified: Boolean,
    val headline: String,
    val body: String,
    val ctaLabel: String?,
    val ctaUrl: String?,
    val targetType: String?,
    val targetId: String?,
    val eventLine: String?,
    val impressionToken: String,
    /** Optional, real data only: the server sends them when the campaign or business has them. */
    val advertiserLogoUrl: String? = null,
    val imageUrl: String? = null,
    val imageWidth: Int? = null,
    val imageHeight: Int? = null,
    val category: String? = null,
    /** True when [imageUrl] is a video rather than a photo. */
    val imageIsVideo: Boolean = false,
    val likes: Int = 0,
    val comments: Int = 0,
    val liked: Boolean = false,
)

data class SponsoredComment(val id: String, val body: String, val authorName: String, val authorHandle: String, val mine: Boolean)

/** Advertising, businesses and sponsored cards live under the versioned API; the older routes (posts, people) do not. */
internal const val PROMO_API = "/api/v1"

interface SponsoredRepository {
    /** The card for this placement, or null. Null is the normal answer: the app then shows nothing. */
    suspend fun card(placement: String): SponsoredCard?
    suspend fun impression(card: SponsoredCard)
    suspend fun click(card: SponsoredCard)
    suspend fun report(card: SponsoredCard, reason: String)
    suspend fun setLiked(campaignId: String, liked: Boolean)
    suspend fun comments(campaignId: String): List<SponsoredComment>
    suspend fun addComment(campaignId: String, body: String): SponsoredComment
    suspend fun deleteComment(campaignId: String, commentId: String)
}

/** Offline demo and signed-out builds: no ads, no pretend ads. */
object NoSponsoredRepository : SponsoredRepository {
    override suspend fun card(placement: String): SponsoredCard? = null
    override suspend fun impression(card: SponsoredCard) = Unit
    override suspend fun click(card: SponsoredCard) = Unit
    override suspend fun report(card: SponsoredCard, reason: String) = Unit
    override suspend fun setLiked(campaignId: String, liked: Boolean) = Unit
    override suspend fun comments(campaignId: String): List<SponsoredComment> = emptyList()
    override suspend fun addComment(campaignId: String, body: String): SponsoredComment = throw IllegalStateException("Comments need a server.")
    override suspend fun deleteComment(campaignId: String, commentId: String) = Unit
}

fun parseSponsoredComment(o: JSONObject): SponsoredComment? {
    val author = o.optJSONObject("author")
    return SponsoredComment(
        id = o.optString("id").ifEmpty { return null },
        body = o.optString("body"),
        authorName = author?.optString("name").orEmpty().ifEmpty { "Student" },
        authorHandle = author?.optString("handle").orEmpty(),
        mine = o.optBoolean("mine", false),
    )
}

fun parseSponsoredCard(o: JSONObject): SponsoredCard? {
    val token = o.optString("impressionToken").ifEmpty { return null }
    val target = o.optJSONObject("target")
    val cta = o.optJSONObject("cta")
    val advertiser = o.optJSONObject("advertiser")
    val event = o.optJSONObject("event")
    val image = o.optJSONObject("image")
    val business = o.optJSONObject("business")
    val engagement = o.optJSONObject("engagement")
    return SponsoredCard(
        campaignId = o.optString("campaignId").ifEmpty { return null },
        advertiser = advertiser?.optString("name").orEmpty().ifEmpty { "Advertiser" },
        advertiserVerified = advertiser?.optBoolean("verified", false) ?: false,
        headline = o.optString("headline").ifEmpty { return null },
        body = o.optString("body"),
        ctaLabel = cta?.optString("label")?.ifEmpty { null },
        ctaUrl = cta?.optString("url")?.takeIf { it.startsWith("https://") },
        targetType = target?.optString("type")?.ifEmpty { null },
        targetId = target?.optString("id")?.ifEmpty { null },
        eventLine = event?.let { sponsoredEventLine(it.optString("startsAt").ifEmpty { null }, it.optString("venue").ifEmpty { null }) },
        impressionToken = token,
        advertiserLogoUrl = advertiser?.optString("logoUrl")?.takeIf { it.startsWith("https://") },
        imageUrl = image?.optString("url")?.takeIf { it.startsWith("https://") },
        imageWidth = image?.optInt("width", 0)?.takeIf { it > 0 },
        imageHeight = image?.optInt("height", 0)?.takeIf { it > 0 },
        category = business?.optString("category")?.ifEmpty { null },
        imageIsVideo = image?.optString("kind") == "video",
        likes = engagement?.optInt("likes", 0) ?: 0,
        comments = engagement?.optInt("comments", 0) ?: 0,
        liked = engagement?.optBoolean("liked", false) ?: false,
    )
}

class RemoteSponsoredRepository(private val api: AuthedApi) : SponsoredRepository {
    override suspend fun card(placement: String): SponsoredCard? {
        val items = JSONObject(api.send("GET", "$PROMO_API/sponsored?placement=$placement")).optJSONArray("items") ?: return null
        return if (items.length() == 0) null else parseSponsoredCard(items.getJSONObject(0))
    }

    override suspend fun impression(card: SponsoredCard) {
        api.send("POST", "$PROMO_API/sponsored/impression", JSONObject().put("token", card.impressionToken))
    }

    override suspend fun click(card: SponsoredCard) {
        api.send("POST", "$PROMO_API/sponsored/click", JSONObject().put("token", card.impressionToken))
    }

    override suspend fun report(card: SponsoredCard, reason: String) {
        api.send("POST", "$PROMO_API/sponsored/${card.campaignId}/report", JSONObject().put("reason", reason))
    }

    override suspend fun setLiked(campaignId: String, liked: Boolean) {
        api.send(if (liked) "POST" else "DELETE", "$PROMO_API/sponsored/$campaignId/like")
    }

    override suspend fun comments(campaignId: String): List<SponsoredComment> {
        val items = JSONObject(api.send("GET", "$PROMO_API/sponsored/$campaignId/comments")).optJSONArray("items") ?: return emptyList()
        return (0 until items.length()).mapNotNull { parseSponsoredComment(items.getJSONObject(it)) }
    }

    override suspend fun addComment(campaignId: String, body: String): SponsoredComment =
        parseSponsoredComment(JSONObject(api.send("POST", "$PROMO_API/sponsored/$campaignId/comments", JSONObject().put("body", body))))
            ?: throw IllegalStateException("Unexpected reply.")

    override suspend fun deleteComment(campaignId: String, commentId: String) {
        api.send("DELETE", "$PROMO_API/sponsored/$campaignId/comments/$commentId")
    }
}
