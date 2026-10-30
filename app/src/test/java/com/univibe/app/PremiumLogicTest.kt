package com.univibe.app

import com.univibe.app.data.model.PremiumPlan
import com.univibe.app.features.premium.billingStatusLabel
import com.univibe.app.features.premium.comparisonRows
import com.univibe.app.features.premium.daysLeftLabel
import com.univibe.app.features.premium.periodLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PremiumLogicTest {
    private fun plan(vararg keys: String) = PremiumPlan("1", "c", "n", "", 9900, "KES 99.00", 30, keys.toList())

    @Test fun daysLeftWording() {
        assertEquals("Ends today", daysLeftLabel(0))
        assertEquals("1 day left", daysLeftLabel(1))
        assertEquals("12 days left", daysLeftLabel(12))
    }

    @Test fun periodWording() {
        assertEquals("1 month", periodLabel(30))
        assertEquals("1 year", periodLabel(365))
        assertEquals("45 days", periodLabel(45))
    }

    @Test fun waitingOrderIsNeverCalledPaid() {
        assertEquals("Waiting for payment", billingStatusLabel("pending_payment"))
        assertEquals("Paid", billingStatusLabel("paid"))
    }

    @Test fun comparisonDeduplicatesAndHidesUnknownKeys() {
        val rows = comparisonRows(listOf(plan("premium.chat_themes", "premium.mystery"), plan("premium.chat_themes", "premium.profile_themes")))
        assertEquals(listOf("premium.chat_themes", "premium.profile_themes"), rows.map { it.key })
        assertNull(com.univibe.app.features.premium.featureRow("premium.mystery"))
    }
}
