package com.univibe.app

import com.univibe.app.data.model.StickerRef
import com.univibe.app.data.repository.toStickerRef
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StickerModelTest {
    private fun sticker(url: String? = "https://cdn.example/s.webp", available: Boolean = true, w: Int? = 512, h: Int? = 256) =
        StickerRef("s1", "p1", "LOL", url, "https://cdn.example/t.webp", w, h, animated = false, available = available)

    @Test fun aPremiumStickerWithoutALinkIsLockedNotMissing() {
        assertTrue(sticker(url = null).locked)
        assertFalse(sticker().locked)
    }

    @Test fun aRemovedStickerIsNeverTreatedAsLocked() {
        assertFalse(sticker(url = null, available = false).locked)
    }

    @Test fun ratioComesFromTheFileAndFallsBackToSquare() {
        assertEquals(2f, sticker().ratio, 0.001f)
        assertEquals(1f, sticker(w = null, h = null).ratio, 0.001f)
    }

    @Test fun parsesAServerSticker() {
        val s = JSONObject(
            """{"id":"a","packId":"p","label":"Bruh","url":"https://x/y.webp","thumbUrl":"https://x/t.webp","width":512,"height":512,"animated":true}""",
        ).toStickerRef()
        assertEquals("a", s.id)
        assertEquals("p", s.packId)
        assertTrue(s.animated)
        assertTrue(s.available)
        assertEquals("https://x/y.webp", s.url)
    }

    @Test fun aLockedStickerArrivesWithOnlyTheStill() {
        val s = JSONObject("""{"id":"a","packId":"p","label":null,"url":null,"thumbUrl":"https://x/t.webp","width":128,"height":128,"animated":true}""").toStickerRef()
        assertNull(s.url)
        assertTrue(s.locked)
    }

    @Test fun anUnavailableMessageStickerKeepsWorkingWithoutLinks() {
        val s = JSONObject("""{"id":"a","packId":null,"label":null,"url":null,"thumbUrl":null,"width":null,"height":null,"animated":false,"available":false}""").toStickerRef()
        assertFalse(s.available)
        assertFalse(s.locked)
        assertNull(s.thumbUrl)
    }

    @Test fun onlyWebLinksAreAccepted() {
        val s = JSONObject("""{"id":"a","url":"javascript:alert(1)","thumbUrl":"file:///etc/passwd","animated":false}""").toStickerRef()
        assertNull(s.url)
        assertNull(s.thumbUrl)
    }
}
