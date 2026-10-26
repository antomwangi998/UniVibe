package com.univibe.app.data.model

/**
 * Wording for notices about a person's own promotions. The server sends only a `kind`; the words live here so the
 * in-app list and the push alert say the same thing and no free text from the server is ever shown.
 */
object PromotionNotice {
    private val text = mapOf(
        "receipt_submitted" to "A payment receipt is waiting for your check.",
        "payment_confirmed" to "Your payment was confirmed. Your promotion is now in review.",
        "payment_rejected" to "We could not confirm your payment. Open the promotion for details.",
        "campaign_approved" to "Your promotion was approved.",
        "campaign_rejected" to "Your promotion was not approved. Open it to see why.",
        "campaign_paused" to "Your promotion was paused.",
        "campaign_stopped" to "Your promotion has ended early.",
        "refund_approved" to "Your refund was approved.",
        "refund_declined" to "Your refund request was declined.",
        "refund_paid" to "Your refund was sent.",
        "business_approved" to "Your business listing was approved.",
        "business_rejected" to "Your business listing needs changes. Open it to see why.",
        "business_suspended" to "Your business listing was suspended.",
        "business_restored" to "Your business listing is live again.",
        "business_verified" to "Your business is now verified.",
    )

    const val TITLE = "Promotions"
    const val GENERIC = "There is an update about your promotion."

    /** The sentence for a kind, or a generic one for a kind this version does not know. */
    fun body(kind: String?): String = text[kind] ?: GENERIC
}
