package com.univibe.app

import com.univibe.app.data.repository.parseSponsoredCard
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SponsoredCardTest {
    private fun card(extra: String = "") = JSONObject(
        """{"campaignId":"c1","label":"Sponsored","headline":"Hack night","body":"Free pizza",
            "advertiser":{"name":"Tech Club","verified":true},
            "cta":{"label":"Sign up","url":"https://example.com/join"},
            "target":{"type":"event","id":"e1"},"event":{"title":"Hack night","venue":"Lab 3"},
            "impressionToken":"tok.sig"$extra}""",
    )

    @Test fun readsEveryField() {
        val c = parseSponsoredCard(card())!!
        assertEquals("Tech Club", c.advertiser)
        assertEquals(true, c.advertiserVerified)
        assertEquals("https://example.com/join", c.ctaUrl)
        assertEquals("event", c.targetType)
        assertEquals("Lab 3", c.eventLine)
    }

    @Test fun dropsALinkThatIsNotHttps() {
        val o = card()
        o.getJSONObject("cta").put("url", "http://example.com")
        assertNull(parseSponsoredCard(o)!!.ctaUrl)
        o.getJSONObject("cta").put("url", "javascript:alert(1)")
        assertNull(parseSponsoredCard(o)!!.ctaUrl)
    }

    @Test fun refusesACardWithoutATokenOrHeadline() {
        assertNull(parseSponsoredCard(card().also { it.remove("impressionToken") }))
        assertNull(parseSponsoredCard(card().also { it.put("headline", "") }))
        assertNotNull(parseSponsoredCard(card()))
    }
}
