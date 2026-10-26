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
)

interface SponsoredRepository {
    /** The card for this placement, or null. Null is the normal answer: the app then shows nothing. */
    suspend fun card(placement: String): SponsoredCard?
    suspend fun impression(card: SponsoredCard)
    suspend fun click(card: SponsoredCard)
    suspend fun report(card: SponsoredCard, reason: String)
}

/** Offline demo and signed-out builds: no ads, no pretend ads. */
object NoSponsoredRepository : SponsoredRepository {
    override suspend fun card(placement: String): SponsoredCard? = null
    override suspend fun impression(card: SponsoredCard) = Unit
    override suspend fun click(card: SponsoredCard) = Unit
    override suspend fun report(card: SponsoredCard, reason: String) = Unit
}

fun parseSponsoredCard(o: JSONObject): SponsoredCard? {
    val token = o.optString("impressionToken").ifEmpty { return null }
    val target = o.optJSONObject("target")
    val cta = o.optJSONObject("cta")
    val advertiser = o.optJSONObject("advertiser")
    val event = o.optJSONObject("event")
    val image = o.optJSONObject("image")
    val business = o.optJSONObject("business")
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
    )
}

class RemoteSponsoredRepository(private val api: AuthedApi) : SponsoredRepository {
    override suspend fun card(placement: String): SponsoredCard? {
        val items = JSONObject(api.send("GET", "/sponsored?placement=$placement")).optJSONArray("items") ?: return null
        return if (items.length() == 0) null else parseSponsoredCard(items.getJSONObject(0))
    }

    override suspend fun impression(card: SponsoredCard) {
        api.send("POST", "/sponsored/impression", JSONObject().put("token", card.impressionToken))
    }

    override suspend fun click(card: SponsoredCard) {
        api.send("POST", "/sponsored/click", JSONObject().put("token", card.impressionToken))
    }

    override suspend fun report(card: SponsoredCard, reason: String) {
        api.send("POST", "/sponsored/${card.campaignId}/report", JSONObject().put("reason", reason))
    }
}
