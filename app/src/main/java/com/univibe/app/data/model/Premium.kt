package com.univibe.app.data.model

/** A plan the server has switched on. Prices are the server's text; nothing in the app computes money. */
data class PremiumPlan(
    val id: String,
    val code: String,
    val name: String,
    val description: String,
    val priceMinor: Long,
    val price: String,
    val periodDays: Int,
    val entitlements: List<String>,
)

/** What the server says about this person right now. [active] false means no live period. */
data class PremiumStatus(val active: Boolean, val planName: String, val endsAtMillis: Long, val daysLeft: Int)

data class PremiumOverview(
    val plans: List<PremiumPlan>,
    val payment: PaymentInfo,
    val status: PremiumStatus,
    /** Keys the server says are live. The app displays these; it never grants them. */
    val entitlements: List<String>,
)

data class BillingItem(
    val id: String,
    val reference: String,
    val status: String,
    val planName: String,
    val amountMinor: Long,
    val amount: String,
    val createdAtMillis: Long,
)
