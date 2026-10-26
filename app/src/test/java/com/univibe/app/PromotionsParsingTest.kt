package com.univibe.app

import com.univibe.app.data.repository.parseAnalytics
import com.univibe.app.data.repository.parseBusiness
import com.univibe.app.data.repository.parseCampaignDetail
import com.univibe.app.data.repository.parseCampaignItem
import com.univibe.app.data.repository.parsePackage
import com.univibe.app.data.repository.parsePaymentInfo
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PromotionsParsingTest {
    @Test fun readsAPackageExactlyAsTheServerPricedIt() {
        val p = parsePackage(JSONObject("""{"id":"k1","code":"event-week","kind":"event_promotion","name":"Event boost","description":"","currency":"KES","priceMinor":50000,"price":"KES 500.00","durationDays":7,"placements":["home_feed","discover_events"],"impressionCap":null,"frequencyCapPerDay":3}"""))
        assertEquals(50000L, p.priceMinor)
        assertEquals("KES 500.00", p.price)
        assertEquals(listOf("home_feed", "discover_events"), p.placements)
        assertNull(p.impressionCap)
    }

    @Test fun readsPaymentDetailsAndTreatsMissingAsOff() {
        assertFalse(parsePaymentInfo(JSONObject("""{"enabled":false,"method":"till","number":"","accountName":"","instructions":""}""")).enabled)
        val on = parsePaymentInfo(JSONObject("""{"enabled":true,"method":"paybill","number":"123456","accountName":"UniVibe","instructions":"Use the order reference"}"""))
        assertTrue(on.enabled)
        assertEquals("123456", on.number)
        assertFalse(parsePaymentInfo(JSONObject("{}")).enabled)
    }

    @Test fun readsACampaignWithItsOrderPaymentAndHistory() {
        val d = parseCampaignDetail(JSONObject("""{"id":"c1","kind":"event_promotion","headline":"Hack night","body":"","status":"pending_payment","reviewStatus":"pending","reviewNote":"",
            "placements":["home_feed"],"startsAt":"2026-11-01T00:00:00.000Z","endsAt":"2026-11-08T00:00:00.000Z","impressions":0,"clicks":0,
            "order":{"id":"o1","reference":"UV-ABCD2345","amountMinor":50000,"amount":"KES 500.00","status":"pending_payment","expiresAt":"2026-11-03T00:00:00.000Z","paidAt":null},
            "payment":{"status":"failed","failureReason":"Amount was short","submittedAt":"2026-11-02T00:00:00.000Z","confirmedAt":null},
            "history":[{"status":"draft","by":"advertiser","note":"","at":"2026-10-30T00:00:00.000Z"}],"actions":["cancelled"]}"""))
        assertEquals("UV-ABCD2345", d.order?.reference)
        assertEquals("failed", d.payment?.status)
        assertEquals("Amount was short", d.payment?.failureReason)
        assertEquals(1, d.history.size)
        assertTrue(d.startsAtMillis > 0 && d.endsAtMillis > d.startsAtMillis)
        assertEquals(listOf("cancelled"), d.actions)
    }

    @Test fun aDraftHasNoOrderOrPayment() {
        val d = parseCampaignDetail(JSONObject("""{"id":"c1","kind":"event_promotion","headline":"x","body":"","status":"draft","reviewStatus":"pending","reviewNote":"","placements":[],
            "startsAt":"2026-11-01T00:00:00.000Z","endsAt":"2026-11-02T00:00:00.000Z","impressions":0,"clicks":0,"order":null,"payment":null,"history":[],"actions":[]}"""))
        assertNull(d.order)
        assertNull(d.payment)
    }

    @Test fun listItemsKeepAnEmptyPaymentAsNull() {
        val i = parseCampaignItem(JSONObject("""{"id":"c1","kind":"sponsored_post","headline":"x","status":"draft","reviewStatus":"pending","startsAt":"2026-11-01T00:00:00.000Z","endsAt":"2026-11-02T00:00:00.000Z","impressions":3,"clicks":1,"paymentStatus":null,"amount":null}"""))
        assertNull(i.paymentStatus)
        assertNull(i.amount)
        assertEquals(3L, i.impressions)
    }

    @Test fun analyticsKeepAnUnshownAdsRateEmptyAndReadTheReasons() {
        val a = parseAnalytics(JSONObject("""{"totals":{"impressions":0,"clicks":0,"ctr":null,"reach":0,"reachExact":true},"conversions":null,
            "daily":[{"day":"2026-11-01","impressions":0,"clicks":0,"ctr":null}],
            "delivery":{"running":false,"heldForReview":false,"reasonsNotRunning":["Payment has not been confirmed."],"percentOfCapUsed":null},
            "payment":{"amount":"KES 500.00","refunded":"KES 0.00"},"definitions":{"impression":"A card was on screen."}}"""))
        assertNull(a.ctr)
        assertNull(a.rsvpAfterClick)
        assertFalse(a.running)
        assertEquals(listOf("Payment has not been confirmed."), a.reasonsNotRunning)
        assertNull(a.percentOfCapUsed)
        assertEquals("impression", a.definitions.first().first)
    }

    @Test fun analyticsReadRsvpsAfterClick() {
        val a = parseAnalytics(JSONObject("""{"totals":{"impressions":10,"clicks":2,"ctr":0.2,"reach":8,"reachExact":false},"conversions":{"kind":"rsvp_after_click","count":1},
            "daily":[],"delivery":{"running":true,"heldForReview":false,"reasonsNotRunning":[],"percentOfCapUsed":40},"payment":null,"definitions":{}}"""))
        assertEquals(0.2, a.ctr ?: -1.0, 0.0001)
        assertEquals(1, a.rsvpAfterClick)
        assertFalse(a.reachExact)
        assertEquals(40, a.percentOfCapUsed)
    }

    @Test fun ownerOnlyFieldsStayNullInThePublicDirectory() {
        val pub = parseBusiness(JSONObject("""{"id":"b1","name":"Mama Pizza","description":"","category":"food","locationText":null,"contactPhone":null,"contactEmail":null,"websiteUrl":null,"openingHours":{},"offerings":"","verified":true,"sponsored":false}"""))
        assertNull(pub.status)
        assertNull(pub.reviewNote)
        assertTrue(pub.verified)
        assertEquals("", pub.websiteUrl)
        val mine = parseBusiness(JSONObject("""{"id":"b1","name":"Mama Pizza","description":"","category":"food","verified":false,"status":"rejected","reviewNote":"Add a clearer description"}"""))
        assertEquals("rejected", mine.status)
        assertEquals("Add a clearer description", mine.reviewNote)
    }
}
