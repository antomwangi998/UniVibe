package com.univibe.app

import com.univibe.app.features.promotions.DAY_MILLIS
import com.univibe.app.features.promotions.PROMO_KINDS
import com.univibe.app.features.promotions.StartOption
import com.univibe.app.features.promotions.businessProblem
import com.univibe.app.features.promotions.campaignProblem
import com.univibe.app.features.promotions.ctrLabel
import com.univibe.app.features.promotions.endMillis
import com.univibe.app.features.promotions.lengthOptions
import com.univibe.app.features.promotions.normalizeReceipt
import com.univibe.app.features.promotions.scheduleProblem
import com.univibe.app.features.promotions.statusExplanation
import com.univibe.app.features.promotions.statusLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PromotionsLogicTest {
    @Test fun everyKindMapsToTheCampaignAndPackageKindTheServerExpects() {
        // Mirrors PACKAGE_FOR_CAMPAIGN in the backend: a mismatch would make the server refuse every purchase.
        val expected = mapOf(
            "event_promotion" to "event_promotion", "business_listing" to "featured_business",
            "sponsored_post" to "sponsored_post", "discover_feature" to "featured_discover",
        )
        assertEquals(expected, PROMO_KINDS.associate { it.serverKind to it.packageKind })
        assertTrue(PROMO_KINDS.single { it.serverKind == "event_promotion" }.needsEvent)
        assertTrue(PROMO_KINDS.filter { it.serverKind != "event_promotion" }.all { it.needsBusiness })
    }

    @Test fun lengthsNeverExceedWhatThePackageAllows() {
        assertEquals(listOf(1, 3, 7), lengthOptions(7))
        assertEquals(listOf(1, 3, 7, 10), lengthOptions(10))
        assertEquals(listOf(1), lengthOptions(1))
        assertEquals(listOf(1), lengthOptions(0))
        assertTrue(lengthOptions(90).last() == 90 && lengthOptions(90).all { it <= 90 })
    }

    @Test fun aPromotionNeverOutlastsItsEvent() {
        val start = 1_000_000L
        assertEquals(start + 7 * DAY_MILLIS, endMillis(start, 7, null))
        assertEquals(start + 2 * DAY_MILLIS, endMillis(start, 7, start + 2 * DAY_MILLIS))
        assertEquals(start + 3 * DAY_MILLIS, endMillis(start, 3, start + 9 * DAY_MILLIS))
    }

    @Test fun aScheduleNeedsAtLeastAnHour() {
        assertNotNull(scheduleProblem(0, 30 * 60_000))
        assertNull(scheduleProblem(0, 60 * 60_000))
        assertTrue(StartOption.Soon.offsetMillis < StartOption.Tomorrow.offsetMillis)
    }

    @Test fun adsAreCheckedBeforeAnythingIsSent() {
        assertNotNull(campaignProblem("Hi", "", "", ""))
        assertNotNull(campaignProblem("x".repeat(81), "", "", ""))
        assertNotNull(campaignProblem("Hack night", "x".repeat(301), "", ""))
        assertNull(campaignProblem("Hack night", "Free pizza", "", ""))
        assertNotNull(campaignProblem("Hack night", "", "Join", ""))
        assertNotNull(campaignProblem("Hack night", "", "", "https://example.com"))
        assertNotNull(campaignProblem("Hack night", "", "Join", "http://example.com"))
        assertNotNull(campaignProblem("Hack night", "", "Join", "javascript:alert(1)"))
        assertNull(campaignProblem("Hack night", "", "Join", "https://example.com/join"))
    }

    @Test fun businessesAreChecked() {
        assertNotNull(businessProblem("A", "", "", "", ""))
        assertNull(businessProblem("Mama Pizza", "Fresh daily", "0712 345 678", "mama@example.com", "https://mama.example"))
        assertNotNull(businessProblem("Mama Pizza", "", "abc", "", ""))
        assertNotNull(businessProblem("Mama Pizza", "", "", "not-an-email", ""))
        assertNotNull(businessProblem("Mama Pizza", "", "", "", "http://mama.example"))
    }

    @Test fun receiptCodesAreTidiedTheWayTheServerExpects() {
        assertEquals("QGH7ABC123", normalizeReceipt(" qgh7 abc123 "))
        assertNull(normalizeReceipt(""))
        assertNull(normalizeReceipt("short"))
        assertNull(normalizeReceipt("has-dash-12345"))
        assertNull(normalizeReceipt("x".repeat(21)))
    }

    @Test fun rateIsADashWhenNothingWasShown() {
        assertEquals("-", ctrLabel(null))
        assertEquals("0.00%", ctrLabel(0.0))
        assertEquals("3.45%", ctrLabel(0.0345))
        assertNotEquals("-", ctrLabel(0.0))
    }

    @Test fun everyCampaignStatusHasWords() {
        val statuses = listOf("draft", "pending_payment", "pending_review", "approved", "scheduled", "active", "paused", "completed", "rejected", "cancelled", "refund_pending", "refunded")
        for (s in statuses) {
            assertTrue("no label for $s", statusLabel(s).isNotBlank() && !statusLabel(s).contains('_'))
            assertTrue("no explanation for $s", statusExplanation(s, null).isNotBlank())
        }
        assertNotEquals(statusExplanation("pending_payment", null), statusExplanation("pending_payment", "awaiting_verification"))
    }
}
