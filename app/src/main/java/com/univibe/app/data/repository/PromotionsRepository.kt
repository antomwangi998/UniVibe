package com.univibe.app.data.repository

import com.univibe.app.data.model.AdvertisingSummary
import com.univibe.app.data.model.Business
import com.univibe.app.data.model.BusinessDraft
import com.univibe.app.data.model.CampaignAnalytics
import com.univibe.app.data.model.CampaignDetail
import com.univibe.app.data.model.CampaignItem
import com.univibe.app.data.model.CheckoutResult
import com.univibe.app.data.model.DayPoint
import com.univibe.app.data.model.HistoryItem
import com.univibe.app.data.model.NewCampaign
import com.univibe.app.data.model.OrderInfo
import com.univibe.app.data.model.PaymentInfo
import com.univibe.app.data.model.PaymentState
import com.univibe.app.data.model.PromoPackage
import com.univibe.app.data.model.PromoPage
import com.univibe.app.data.net.ApiException
import com.univibe.app.data.net.AuthedApi
import com.univibe.app.data.net.send
import java.io.IOException
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

/** Everything the promotion screens need from the server. Money is never computed here: the server prices and verifies. */
interface PromotionsRepository {
    suspend fun summary(): AdvertisingSummary
    suspend fun campaigns(cursor: String?): PromoPage<CampaignItem>
    suspend fun campaign(id: String): CampaignDetail
    suspend fun analytics(id: String): CampaignAnalytics
    suspend fun packages(kind: String): List<PromoPackage>
    suspend fun paymentInfo(): PaymentInfo
    /** Creates a draft and returns its id. */
    suspend fun createCampaign(c: NewCampaign): String
    suspend fun checkout(campaignId: String): CheckoutResult
    /** "I paid, here is the receipt code." The server only records the claim; staff confirm it. */
    suspend fun submitReceipt(orderId: String, receipt: String)
    /** [action] is "pause", "resume" or "cancel". */
    suspend fun control(campaignId: String, action: String)
    suspend fun myBusinesses(): List<Business>
    suspend fun business(id: String): Business
    suspend fun saveBusiness(id: String?, draft: BusinessDraft): String
    suspend fun submitBusiness(id: String)
    suspend fun directory(category: String?, cursor: String?): PromoPage<Business>
}

/** Offline demo: nothing can be bought without a server, so the screens say so instead of pretending. */
object NoPromotionsRepository : PromotionsRepository {
    private fun unavailable(): Nothing = throw UnsupportedOperationException("Promotions need a connection to UniVibe.")
    override suspend fun summary() = unavailable()
    override suspend fun campaigns(cursor: String?) = unavailable()
    override suspend fun campaign(id: String) = unavailable()
    override suspend fun analytics(id: String) = unavailable()
    override suspend fun packages(kind: String) = unavailable()
    override suspend fun paymentInfo() = unavailable()
    override suspend fun createCampaign(c: NewCampaign) = unavailable()
    override suspend fun checkout(campaignId: String) = unavailable()
    override suspend fun submitReceipt(orderId: String, receipt: String) = unavailable()
    override suspend fun control(campaignId: String, action: String) = unavailable()
    override suspend fun myBusinesses() = unavailable()
    override suspend fun business(id: String) = unavailable()
    override suspend fun saveBusiness(id: String?, draft: BusinessDraft) = unavailable()
    override suspend fun submitBusiness(id: String) = unavailable()
    override suspend fun directory(category: String?, cursor: String?) = unavailable()
}

/** A sentence a student can act on, from whatever went wrong. The server's own message is used when it sent one. */
fun Throwable.promotionMessage(): String = when (this) {
    is ApiException -> message?.takeIf { it.isNotBlank() } ?: "That did not work. Try again."
    is IOException -> "No connection. Check your internet and try again."
    is UnsupportedOperationException -> message ?: "Not available here."
    else -> "Something went wrong. Try again."
}

private fun JSONObject.str(name: String): String = if (isNull(name)) "" else optString(name)
private fun JSONObject.nonEmptyStrOrNull(name: String): String? = if (isNull(name)) null else optString(name).ifEmpty { null }
private fun JSONObject.doubleOrNull(name: String): Double? = if (isNull(name) || !has(name)) null else optDouble(name)
private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else List(length()) { optString(it) }
private fun millis(iso: String): Long = runCatching { Instant.parse(iso).toEpochMilli() }.getOrDefault(0L)

internal fun parsePackage(o: JSONObject) = PromoPackage(
    id = o.str("id"), kind = o.str("kind"), name = o.str("name"), description = o.str("description"),
    priceMinor = o.optLong("priceMinor"), price = o.str("price"), durationDays = o.optInt("durationDays", 1),
    placements = o.optJSONArray("placements").strings(), impressionCap = if (o.isNull("impressionCap")) null else o.optInt("impressionCap"),
    frequencyCapPerDay = o.optInt("frequencyCapPerDay", 3),
)

internal fun parsePaymentInfo(o: JSONObject) = PaymentInfo(
    enabled = o.optBoolean("enabled", false), method = o.str("method").ifEmpty { "till" }, number = o.str("number"),
    accountName = o.str("accountName"), instructions = o.str("instructions"),
)

internal fun parseOrder(o: JSONObject) = OrderInfo(
    id = o.str("id"), reference = o.str("reference"), amountMinor = o.optLong("amountMinor"), amount = o.str("amount"),
    status = o.str("status"), expiresAtMillis = millis(o.str("expiresAt")),
)

internal fun parseCampaignItem(o: JSONObject) = CampaignItem(
    id = o.str("id"), kind = o.str("kind"), headline = o.str("headline"), status = o.str("status"), reviewStatus = o.str("reviewStatus"),
    startsAtMillis = millis(o.str("startsAt")), endsAtMillis = millis(o.str("endsAt")), impressions = o.optLong("impressions"),
    clicks = o.optLong("clicks"), paymentStatus = o.nonEmptyStrOrNull("paymentStatus"), amount = o.nonEmptyStrOrNull("amount"),
)

internal fun parseCampaignDetail(o: JSONObject): CampaignDetail {
    val history = o.optJSONArray("history")
    return CampaignDetail(
        id = o.str("id"), kind = o.str("kind"), headline = o.str("headline"), body = o.str("body"), status = o.str("status"),
        reviewStatus = o.str("reviewStatus"), reviewNote = o.str("reviewNote"), placements = o.optJSONArray("placements").strings(),
        startsAtMillis = millis(o.str("startsAt")), endsAtMillis = millis(o.str("endsAt")), impressions = o.optLong("impressions"), clicks = o.optLong("clicks"),
        order = o.optJSONObject("order")?.let(::parseOrder),
        payment = o.optJSONObject("payment")?.let { PaymentState(it.str("status"), it.nonEmptyStrOrNull("failureReason")) },
        history = if (history == null) emptyList() else List(history.length()) {
            val h = history.getJSONObject(it)
            HistoryItem(h.str("status"), h.str("by"), h.str("note"), millis(h.str("at")))
        },
        actions = o.optJSONArray("actions").strings(),
    )
}

internal fun parseAnalytics(o: JSONObject): CampaignAnalytics {
    val totals = o.getJSONObject("totals")
    val delivery = o.getJSONObject("delivery")
    val daily = o.optJSONArray("daily")
    val defs = o.optJSONObject("definitions")
    return CampaignAnalytics(
        impressions = totals.optLong("impressions"), clicks = totals.optLong("clicks"), ctr = totals.doubleOrNull("ctr"),
        reach = totals.optLong("reach"), reachExact = totals.optBoolean("reachExact", true),
        rsvpAfterClick = o.optJSONObject("conversions")?.optInt("count"),
        daily = if (daily == null) emptyList() else List(daily.length()) {
            val d = daily.getJSONObject(it)
            DayPoint(d.str("day"), d.optInt("impressions"), d.optInt("clicks"))
        },
        running = delivery.optBoolean("running", false), heldForReview = delivery.optBoolean("heldForReview", false),
        reasonsNotRunning = delivery.optJSONArray("reasonsNotRunning").strings(),
        percentOfCapUsed = if (delivery.isNull("percentOfCapUsed")) null else delivery.optInt("percentOfCapUsed"),
        paymentAmount = o.optJSONObject("payment")?.nonEmptyStrOrNull("amount"), refunded = o.optJSONObject("payment")?.nonEmptyStrOrNull("refunded"),
        definitions = if (defs == null) emptyList() else defs.keys().asSequence().map { it to defs.optString(it) }.toList(),
    )
}

internal fun parseBusiness(o: JSONObject) = Business(
    id = o.str("id"), name = o.str("name"), description = o.str("description"), category = o.str("category"),
    locationText = o.str("locationText"), contactPhone = o.str("contactPhone"), contactEmail = o.str("contactEmail"),
    websiteUrl = o.str("websiteUrl"), offerings = o.str("offerings"), verified = o.optBoolean("verified", false),
    status = o.nonEmptyStrOrNull("status"), reviewNote = o.nonEmptyStrOrNull("reviewNote"),
)

private const val V1 = "/api/v1"

class RemotePromotionsRepository(private val api: AuthedApi) : PromotionsRepository {
    private suspend fun get(path: String) = JSONObject(api.send("GET", V1 + path))
    private suspend fun post(path: String, body: JSONObject = JSONObject()) = JSONObject(api.send("POST", V1 + path, body))

    override suspend fun summary(): AdvertisingSummary {
        val o = get("/me/advertising/summary")
        val c = o.getJSONObject("campaigns")
        return AdvertisingSummary(
            active = c.optInt("active"), waiting = c.optInt("waiting"), total = c.optInt("total"),
            impressions = o.optLong("impressions"), clicks = o.optLong("clicks"), ctr = o.doubleOrNull("ctr"),
            paid = o.str("paid"), refunded = o.str("refunded"),
        )
    }

    override suspend fun campaigns(cursor: String?): PromoPage<CampaignItem> {
        val o = get("/me/campaigns" + if (cursor != null) "?cursor=" + java.net.URLEncoder.encode(cursor, "UTF-8") else "")
        val items = o.getJSONArray("items")
        return PromoPage(List(items.length()) { parseCampaignItem(items.getJSONObject(it)) }, o.nonEmptyStrOrNull("nextCursor"))
    }

    override suspend fun campaign(id: String) = parseCampaignDetail(get("/campaigns/$id"))
    override suspend fun analytics(id: String) = parseAnalytics(get("/campaigns/$id/analytics"))

    override suspend fun packages(kind: String): List<PromoPackage> {
        val items = get("/monetization/packages?kind=$kind").getJSONArray("items")
        return List(items.length()) { parsePackage(items.getJSONObject(it)) }
    }

    override suspend fun paymentInfo() = parsePaymentInfo(get("/monetization/payment-info"))

    override suspend fun createCampaign(c: NewCampaign): String {
        val body = JSONObject()
            .put("kind", c.kind).put("packageId", c.packageId).put("headline", c.headline).put("body", c.body)
            .put("placements", JSONArray(c.placements))
            .put("startsAt", Instant.ofEpochMilli(c.startsAtMillis).toString()).put("endsAt", Instant.ofEpochMilli(c.endsAtMillis).toString())
        c.eventId?.let { body.put("eventId", it) }
        c.businessId?.let { body.put("businessId", it) }
        if (c.ctaLabel != null && c.ctaUrl != null) body.put("ctaLabel", c.ctaLabel).put("ctaUrl", c.ctaUrl)
        return post("/campaigns", body).getString("id")
    }

    override suspend fun checkout(campaignId: String): CheckoutResult {
        val o = post("/campaigns/$campaignId/checkout")
        return CheckoutResult(parseOrder(o.getJSONObject("order")), parsePaymentInfo(o.getJSONObject("pay")))
    }

    override suspend fun submitReceipt(orderId: String, receipt: String) {
        api.send("POST", V1 + "/orders/$orderId/payment", JSONObject().put("receipt", receipt))
    }

    override suspend fun control(campaignId: String, action: String) {
        require(action == "pause" || action == "resume" || action == "cancel")
        api.send("POST", V1 + "/campaigns/$campaignId/$action", JSONObject())
    }

    override suspend fun myBusinesses(): List<Business> {
        val items = get("/me/businesses").getJSONArray("items")
        return List(items.length()) { parseBusiness(items.getJSONObject(it)) }
    }

    override suspend fun business(id: String) = parseBusiness(get("/businesses/$id"))

    override suspend fun saveBusiness(id: String?, draft: BusinessDraft): String {
        val body = JSONObject().put("name", draft.name).put("description", draft.description).put("category", draft.category).put("offerings", draft.offerings)
        // Empty optional fields are left out: the server treats a missing value as "none".
        if (draft.locationText.isNotBlank()) body.put("locationText", draft.locationText)
        if (draft.contactPhone.isNotBlank()) body.put("contactPhone", draft.contactPhone)
        if (draft.contactEmail.isNotBlank()) body.put("contactEmail", draft.contactEmail)
        if (draft.websiteUrl.isNotBlank()) body.put("websiteUrl", draft.websiteUrl)
        return if (id == null) post("/businesses", body).getString("id") else { api.send("PUT", V1 + "/businesses/$id", body); id }
    }

    override suspend fun submitBusiness(id: String) { api.send("POST", V1 + "/businesses/$id/submit", JSONObject()) }

    override suspend fun directory(category: String?, cursor: String?): PromoPage<Business> {
        val q = listOfNotNull(category?.let { "category=$it" }, cursor?.let { "cursor=" + java.net.URLEncoder.encode(it, "UTF-8") }).joinToString("&")
        val o = get("/businesses" + if (q.isEmpty()) "" else "?$q")
        val items = o.getJSONArray("items")
        return PromoPage(List(items.length()) { parseBusiness(items.getJSONObject(it)) }, o.nonEmptyStrOrNull("nextCursor"))
    }
}
