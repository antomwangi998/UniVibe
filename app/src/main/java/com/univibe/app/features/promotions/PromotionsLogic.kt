package com.univibe.app.features.promotions

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The rules and wording of the promotion screens, with no Android in them so each one can be tested alone.
 * Prices, payment checks and approval all happen on the server; nothing here decides money.
 */

/** One thing a student can promote. [serverKind] is the campaign kind, [packageKind] the kind of package that pays for it. */
data class PromoKind(
    val serverKind: String,
    val packageKind: String,
    val title: String,
    val subtitle: String,
    val needsEvent: Boolean,
    val needsBusiness: Boolean,
)

val PROMO_KINDS = listOf(
    PromoKind("event_promotion", "event_promotion", "Promote an event", "Reach more students for one of your events", needsEvent = true, needsBusiness = false),
    PromoKind("sponsored_post", "sponsored_post", "Advertise a business", "A labelled card in the Home and Discover feeds", needsEvent = false, needsBusiness = true),
    PromoKind("business_listing", "featured_business", "Feature a business listing", "Shown first in the business directory", needsEvent = false, needsBusiness = true),
    PromoKind("discover_feature", "featured_discover", "Feature in Discover", "A labelled card in Discover", needsEvent = false, needsBusiness = true),
)

val BUSINESS_CATEGORIES = listOf(
    "food" to "Food", "retail" to "Shops", "services" to "Services", "education" to "Education", "health" to "Health",
    "tech" to "Tech", "entertainment" to "Entertainment", "housing" to "Housing", "transport" to "Transport", "other" to "Other",
)

fun categoryLabel(code: String): String = BUSINESS_CATEGORIES.firstOrNull { it.first == code }?.second ?: code.replaceFirstChar { it.uppercase() }

fun placementLabel(code: String): String = when (code) {
    "home_feed" -> "Home feed"
    "discover_feed" -> "Discover"
    "discover_events" -> "Discover events"
    "business_directory" -> "Business directory"
    else -> code.replace('_', ' ')
}

fun statusLabel(status: String): String = when (status) {
    "draft" -> "Draft"
    "pending_payment" -> "Waiting for payment"
    "pending_review" -> "In review"
    "approved" -> "Approved"
    "scheduled" -> "Scheduled"
    "active" -> "Running"
    "paused" -> "Paused"
    "completed" -> "Finished"
    "rejected" -> "Not approved"
    "cancelled" -> "Cancelled"
    "refund_pending" -> "Refund pending"
    "refunded" -> "Refunded"
    else -> status.replace('_', ' ')
}

/** What the status means for the advertiser and what happens next, in plain words. */
fun statusExplanation(status: String, paymentStatus: String?): String = when (status) {
    "draft" -> "Not paid for yet. Continue to payment when you are ready."
    "pending_payment" -> when (paymentStatus) {
        "awaiting_verification" -> "We received your receipt and are checking it against the M-Pesa statement. This usually does not take long."
        "failed" -> "We could not confirm that payment. See the reason below and send the correct receipt."
        else -> "Pay using the details below, then send us the M-Pesa receipt code."
    }
    "pending_review" -> "Payment is confirmed. We are checking the promotion before it can run."
    "approved" -> "Approved. It starts as soon as its dates allow."
    "scheduled" -> "Approved and waiting for its start date."
    "active" -> "Your promotion is running."
    "paused" -> "Your promotion is paused and not being shown."
    "completed" -> "This promotion has finished."
    "rejected" -> "This promotion was not approved. If you paid, a refund is being arranged."
    "cancelled" -> "This promotion was cancelled."
    "refund_pending" -> "A refund is being arranged. We will tell you when it is sent."
    "refunded" -> "Your payment was refunded."
    else -> ""
}

// ------------------------------------------------------------------ scheduling

const val DAY_MILLIS = 86_400_000L
private const val HOUR_MILLIS = 3_600_000L

enum class StartOption(val label: String, val offsetMillis: Long) {
    Soon("As soon as approved", 10 * 60_000L),
    Tomorrow("Tomorrow", DAY_MILLIS),
    InThreeDays("In 3 days", 3 * DAY_MILLIS),
}

/** The lengths offered for a package that runs up to [maxDays]. Always at least one. */
fun lengthOptions(maxDays: Int): List<Int> {
    val max = maxDays.coerceAtLeast(1)
    return (listOf(1, 3, 7, 14, 30, 60, 90).filter { it <= max } + max).distinct().sorted()
}

/** The end: [days] after the start, but never after the event it promotes. */
fun endMillis(startMillis: Long, days: Int, eventEndMillis: Long?): Long {
    val wanted = startMillis + days * DAY_MILLIS
    return if (eventEndMillis != null) minOf(wanted, eventEndMillis) else wanted
}

/** Null when the schedule can work, otherwise what to tell the person. */
fun scheduleProblem(startMillis: Long, endMillis: Long): String? =
    if (endMillis - startMillis < HOUR_MILLIS) "There is less than an hour between the start and the end. Choose a later event or an earlier start." else null

// ------------------------------------------------------------------ checking what was typed

fun campaignProblem(headline: String, body: String, ctaLabel: String, ctaUrl: String): String? {
    val h = headline.trim()
    if (h.length < 3) return "Write a headline of at least 3 characters."
    if (h.length > 80) return "The headline can be at most 80 characters."
    if (body.trim().length > 300) return "The description can be at most 300 characters."
    val label = ctaLabel.trim()
    val url = ctaUrl.trim()
    if (label.isEmpty() && url.isEmpty()) return null
    if (label.isEmpty() || url.isEmpty()) return "A button needs both a label and a link."
    if (label.length < 2 || label.length > 24) return "The button label must be 2 to 24 characters."
    if (!url.startsWith("https://") || url.length > 500) return "The button link must start with https://"
    return null
}

private val PHONE = Regex("^\\+?[0-9 ]{7,20}$")

fun businessProblem(name: String, description: String, phone: String, email: String, website: String): String? {
    if (name.trim().length < 2) return "Enter the business name."
    if (name.trim().length > 80) return "The name can be at most 80 characters."
    if (description.trim().length > 1000) return "The description can be at most 1000 characters."
    if (phone.isNotBlank() && !PHONE.matches(phone.trim())) return "Enter a valid phone number, for example 0712 345 678."
    if (email.isNotBlank() && (!email.contains('@') || email.trim().length > 254)) return "Enter a valid email address."
    if (website.isNotBlank() && (!website.trim().startsWith("https://") || website.trim().length > 500)) return "The website must start with https://"
    return null
}

private val RECEIPT = Regex("^[A-Z0-9]{8,20}$")

/** An M-Pesa receipt code as the server expects it (upper case, no spaces), or null if it does not look like one. */
fun normalizeReceipt(input: String): String? = input.trim().uppercase(Locale.ROOT).replace(" ", "").takeIf { RECEIPT.matches(it) }

// ------------------------------------------------------------------ showing numbers

/** Click-through rate for display. A dash when nothing has been shown, never a made-up 0%. */
fun ctrLabel(ctr: Double?): String = if (ctr == null) "-" else String.format(Locale.US, "%.2f%%", ctr * 100)

fun countLabel(n: Long): String = String.format(Locale.US, "%,d", n)

private val DATE_TIME = DateTimeFormatter.ofPattern("d MMM, h:mm a", Locale.getDefault())

fun dateTimeLabel(millis: Long): String = DATE_TIME.withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(millis))

fun whoLabel(by: String): String = when (by) {
    "advertiser" -> "You"
    "admin" -> "UniVibe team"
    else -> "Automatic"
}
