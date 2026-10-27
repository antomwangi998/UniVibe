package com.univibe.app

import com.univibe.app.data.repository.parseSponsoredCard
import com.univibe.app.features.promotions.sponsoredDefaultCta
import com.univibe.app.features.promotions.sponsoredEventLine
import com.univibe.app.features.promotions.sponsoredMediaAspect
import java.time.ZoneOffset
import java.util.Locale
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

    @Test fun readsAVideoAndTheLikeAndCommentCounts() {
        val c = parseSponsoredCard(
            card(""","image":{"kind":"video","url":"https://files.example/v.mp4","width":720,"height":1280},"engagement":{"likes":12,"comments":3,"liked":true}"""),
        )!!
        assertEquals(true, c.imageIsVideo)
        assertEquals("https://files.example/v.mp4", c.imageUrl)
        assertEquals(12, c.likes)
        assertEquals(3, c.comments)
        assertEquals(true, c.liked)
    }

    @Test fun aCardWithoutMediaOrEngagementIsAPlainTextCard() {
        val c = parseSponsoredCard(card())!!
        assertNull(c.imageUrl)
        assertEquals(false, c.imageIsVideo)
        assertEquals(0, c.likes)
        assertEquals(false, c.liked)
    }

    @Test fun aPictureThatIsNotHttpsIsNotKept() {
        assertNull(parseSponsoredCard(card(""","image":{"kind":"image","url":"http://files.example/p.jpg"}"""))!!.imageUrl)
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

    @Test fun readsOptionalLogoImageAndCategoryOnlyWhenSent() {
        val plain = parseSponsoredCard(card())!!
        assertNull(plain.advertiserLogoUrl); assertNull(plain.imageUrl); assertNull(plain.category)
        val o = card()
        o.getJSONObject("advertiser").put("logoUrl", "https://cdn.example.com/l.png")
        o.put("image", JSONObject("""{"url":"https://cdn.example.com/i.jpg","width":1200,"height":800}"""))
        o.put("business", JSONObject("""{"category":"food"}"""))
        val c = parseSponsoredCard(o)!!
        assertEquals("https://cdn.example.com/l.png", c.advertiserLogoUrl)
        assertEquals("https://cdn.example.com/i.jpg", c.imageUrl)
        assertEquals(1200, c.imageWidth); assertEquals("food", c.category)
    }

    @Test fun dropsMediaThatIsNotHttps() {
        val o = card()
        o.put("image", JSONObject("""{"url":"http://x.com/i.jpg","width":10,"height":10}"""))
        assertNull(parseSponsoredCard(o)!!.imageUrl)
    }

    @Test fun eventLineUsesOnlyWhatExists() {
        val z = ZoneOffset.UTC; val l = Locale.US
        assertEquals("Sun 22 Nov, 6:00 PM · KICC", sponsoredEventLine("2026-11-22T18:00:00Z", "KICC", z, l))
        assertEquals("KICC", sponsoredEventLine(null, "KICC", z, l))
        assertEquals("Sun 22 Nov, 6:00 PM", sponsoredEventLine("2026-11-22T18:00:00Z", null, z, l))
        assertNull(sponsoredEventLine("garbage", "", z, l))
    }

    @Test fun mediaAspectIsClampedAndDefaults() {
        assertEquals(16f / 9f, sponsoredMediaAspect(null, null), 0.001f)
        assertEquals(1f, sponsoredMediaAspect(400, 1200), 0.001f)
        assertEquals(2f, sponsoredMediaAspect(3000, 1000), 0.001f)
        assertEquals(1.5f, sponsoredMediaAspect(1200, 800), 0.001f)
    }

    @Test fun defaultButtonLabelOnlyForRealTargets() {
        assertEquals("View event", sponsoredDefaultCta("event"))
        assertNull(sponsoredDefaultCta(null)); assertNull(sponsoredDefaultCta("other"))
    }
}
