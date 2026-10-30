package com.univibe.app.features.premium

import com.univibe.app.data.model.PremiumPlan

/** Plain-language names for the keys the server sends. An unknown key is hidden, never shown raw. */
private val FEATURE_TEXT = mapOf(
    "premium.profile_themes" to ("Profile themes" to "Themes, accent colours and badges that other students see on your profile"),
    "premium.profile_frames" to ("Profile frames" to "Frames around your profile photo, visible to everyone"),
    "premium.chat_themes" to ("Chat themes" to "Extra backgrounds for your conversations"),
    "premium.sticker_packs" to ("Sticker packs" to "Extra stickers and reactions"),
    "premium.creator_analytics" to ("Post insights" to "How your posts are doing"),
    "premium.post_scheduling" to ("Post scheduling" to "Write now, post later"),
    "premium.advanced_feed_controls" to ("Feed controls" to "More ways to tune your feed"),
)

data class FeatureRow(val key: String, val title: String, val detail: String)

fun featureRow(key: String): FeatureRow? = FEATURE_TEXT[key]?.let { FeatureRow(key, it.first, it.second) }

/** Every feature any offered plan includes, in a stable order, for the Free vs Premium comparison. */
fun comparisonRows(plans: List<PremiumPlan>): List<FeatureRow> =
    plans.flatMap { it.entitlements }.distinct().mapNotNull(::featureRow)

fun daysLeftLabel(days: Int): String = when {
    days <= 0 -> "Ends today"
    days == 1 -> "1 day left"
    else -> "$days days left"
}

/** What a purchase does, in words: one period, no automatic renewal. */
fun periodLabel(days: Int): String = when {
    days in 28..31 -> "1 month"
    days in 360..370 -> "1 year"
    else -> "$days days"
}

/** Status words for a billing line. A waiting order is never called paid. */
fun billingStatusLabel(status: String): String = when (status) {
    "paid" -> "Paid"
    "pending_payment" -> "Waiting for payment"
    "expired" -> "Expired"
    "cancelled" -> "Cancelled"
    "refunded" -> "Refunded"
    else -> status.replace('_', ' ').replaceFirstChar { it.uppercase() }
}
