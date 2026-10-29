package com.univibe.app.data.repository

import com.univibe.app.data.model.BillingItem
import com.univibe.app.data.model.CheckoutResult
import com.univibe.app.data.model.PremiumOverview
import com.univibe.app.data.model.PremiumPlan
import com.univibe.app.data.model.PremiumStatus
import com.univibe.app.data.model.PromoPage
import com.univibe.app.data.net.AuthedApi
import com.univibe.app.data.net.send
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

/** Premium plans, status and purchase. The server prices, verifies and grants; the app only asks and shows. */
interface PremiumRepository {
    suspend fun overview(): PremiumOverview
    suspend fun billing(cursor: String?): PromoPage<BillingItem>
    /** [key] makes a repeated tap return the same order instead of creating another. */
    suspend fun checkout(planId: String, key: String): CheckoutResult
    /** "I paid, here is the receipt code." Only records a claim; staff confirm it. */
    suspend fun submitReceipt(orderId: String, receipt: String)
}

/** Offline demo: nothing can be bought without the server, so the screen says so instead of pretending. */
object NoPremiumRepository : PremiumRepository {
    private fun unavailable(): Nothing = throw UnsupportedOperationException("Premium needs a connection to UniVibe.")
    override suspend fun overview() = unavailable()
    override suspend fun billing(cursor: String?) = unavailable()
    override suspend fun checkout(planId: String, key: String) = unavailable()
    override suspend fun submitReceipt(orderId: String, receipt: String) = unavailable()
}

class RemotePremiumRepository(private val api: AuthedApi) : PremiumRepository {
    private suspend fun get(path: String) = JSONObject(api.send("GET", PROMO_API + path))

    override suspend fun overview(): PremiumOverview {
        val plans = get("/monetization/premium/plans?audience=user")
        val me = get("/me/entitlements")
        val list = plans.optJSONArray("plans") ?: JSONArray()
        val sub = me.optJSONObject("subscription")
        return PremiumOverview(
            plans = List(list.length()) { parsePlan(list.getJSONObject(it)) },
            payment = parsePaymentInfo(plans.optJSONObject("payment") ?: JSONObject()),
            status = if (sub != null && sub.optBoolean("active")) {
                PremiumStatus(true, sub.optString("planName"), isoMillis(sub.optString("endsAt")), sub.optInt("daysLeft"))
            } else PremiumStatus(false, "", 0L, 0),
            entitlements = me.optJSONArray("entitlements").toStrings(),
        )
    }

    override suspend fun billing(cursor: String?): PromoPage<BillingItem> {
        val o = get("/me/billing-history" + if (cursor != null) "?cursor=" + java.net.URLEncoder.encode(cursor, "UTF-8") else "")
        val items = o.getJSONArray("items")
        return PromoPage(
            List(items.length()) {
                val r = items.getJSONObject(it)
                BillingItem(r.optString("id"), r.optString("reference"), r.optString("status"), r.optString("planName"), r.optLong("amountMinor"), r.optString("amount"), isoMillis(r.optString("createdAt")))
            },
            if (o.isNull("nextCursor")) null else o.optString("nextCursor").ifEmpty { null },
        )
    }

    override suspend fun checkout(planId: String, key: String): CheckoutResult {
        val o = JSONObject(api.send("POST", "$PROMO_API/monetization/premium/checkout", JSONObject().put("planId", planId).put("createKey", key)))
        return CheckoutResult(parseOrder(o.getJSONObject("order")), parsePaymentInfo(o.getJSONObject("pay")))
    }

    override suspend fun submitReceipt(orderId: String, receipt: String) {
        api.send("POST", "$PROMO_API/orders/$orderId/payment", JSONObject().put("receipt", receipt))
    }
}

private fun isoMillis(iso: String): Long = runCatching { Instant.parse(iso).toEpochMilli() }.getOrDefault(0L)
private fun JSONArray?.toStrings(): List<String> = if (this == null) emptyList() else List(length()) { optString(it) }

internal fun parsePlan(o: JSONObject) = PremiumPlan(
    id = o.optString("id"), code = o.optString("code"), name = o.optString("name"), description = o.optString("description"),
    priceMinor = o.optLong("priceMinor"), price = o.optString("price"), periodDays = o.optInt("periodDays", 30),
    entitlements = o.optJSONArray("entitlements").toStrings(),
)
