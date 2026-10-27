package com.univibe.app.data.model

/** What a student can buy. The price comes from the server; the app never works one out. */
data class PromoPackage(
    val id: String,
    val kind: String,
    val name: String,
    val description: String,
    val priceMinor: Long,
    /** Already formatted by the server, for example "KES 500.00". */
    val price: String,
    val durationDays: Int,
    val placements: List<String>,
    val impressionCap: Int?,
    val frequencyCapPerDay: Int,
)

/** Where to pay while payments are made to a till. [enabled] is false until the owner switches payments on. */
data class PaymentInfo(
    val enabled: Boolean,
    val method: String,
    val number: String,
    val accountName: String,
    val instructions: String,
)

data class AdvertisingSummary(
    val active: Int,
    val waiting: Int,
    val total: Int,
    val impressions: Long,
    val clicks: Long,
    /** Null when nothing has been shown yet. */
    val ctr: Double?,
    val paid: String,
    val refunded: String,
)

data class CampaignItem(
    val id: String,
    val kind: String,
    val headline: String,
    val status: String,
    val reviewStatus: String,
    val startsAtMillis: Long,
    val endsAtMillis: Long,
    val impressions: Long,
    val clicks: Long,
    val paymentStatus: String?,
    val amount: String?,
)

data class OrderInfo(
    val id: String,
    val reference: String,
    val amountMinor: Long,
    val amount: String,
    val status: String,
    val expiresAtMillis: Long,
)

data class PaymentState(val status: String, val failureReason: String?)

data class HistoryItem(val status: String, val by: String, val note: String, val atMillis: Long)

data class CampaignDetail(
    val id: String,
    val kind: String,
    val headline: String,
    val body: String,
    val status: String,
    val reviewStatus: String,
    /** Only filled when staff rejected it, so the advertiser can fix and try again. */
    val reviewNote: String,
    val placements: List<String>,
    val startsAtMillis: Long,
    val endsAtMillis: Long,
    val impressions: Long,
    val clicks: Long,
    val order: OrderInfo?,
    val payment: PaymentState?,
    val history: List<HistoryItem>,
    /** What the server will let this person do next (pause, cancel ...). */
    val actions: List<String>,
)

data class DayPoint(val day: String, val impressions: Int, val clicks: Int)

data class CampaignAnalytics(
    val impressions: Long,
    val clicks: Long,
    val ctr: Double?,
    val reach: Long,
    val reachExact: Boolean,
    /** Only for event promotions. Students who tapped the ad and then RSVPed within 7 days. */
    val rsvpAfterClick: Int?,
    val daily: List<DayPoint>,
    val running: Boolean,
    val heldForReview: Boolean,
    val reasonsNotRunning: List<String>,
    val percentOfCapUsed: Int?,
    val paymentAmount: String?,
    val refunded: String?,
    val definitions: List<Pair<String, String>>,
)

data class Business(
    val id: String,
    val name: String,
    val description: String,
    val category: String,
    val locationText: String,
    val contactPhone: String,
    val contactEmail: String,
    val websiteUrl: String,
    val offerings: String,
    val verified: Boolean,
    /** Only the owner sees these two. */
    val status: String?,
    val reviewNote: String?,
)

data class BusinessDraft(
    val name: String,
    val description: String,
    val category: String,
    val locationText: String,
    val contactPhone: String,
    val contactEmail: String,
    val websiteUrl: String,
    val offerings: String,
)

/** What the form collects. Everything about money is decided by the server from [packageId]. */
data class NewCampaign(
    val kind: String,
    val packageId: String,
    val eventId: String?,
    val businessId: String?,
    val headline: String,
    val body: String,
    val ctaLabel: String?,
    val ctaUrl: String?,
    val placements: List<String>,
    val startsAtMillis: Long,
    val endsAtMillis: Long,
    /** A local photo or video to upload and show on the card (optional). */
    val mediaPath: String? = null,
    val mediaIsVideo: Boolean = false,
)

data class CheckoutResult(val order: OrderInfo, val pay: PaymentInfo)

data class PromoPage<T>(val items: List<T>, val next: String?)
