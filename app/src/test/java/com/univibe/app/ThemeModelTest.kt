package com.univibe.app

import com.univibe.app.data.model.CHAT_DIM_MAX
import com.univibe.app.data.model.CHAT_DIM_MIN
import com.univibe.app.data.repository.toThemeAsset
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeModelTest {
    private val free = """{"id":"a1","title":"Forest","description":"","category":{"slug":"nature","name":"Nature"},"creator":null,
        "sourceAttribution":"UniVibe","license":"univibe_original","premium":false,"locked":false,
        "destinations":["profile_cover","chat_background"],"width":800,"height":1200,"favourited":true,
        "thumbUrl":"https://x/t.webp","fullUrl":"https://x/f.webp"}"""

    @Test fun parsesAFreeImage() {
        val a = JSONObject(free).toThemeAsset()
        assertEquals("Nature", a.categoryName)
        assertTrue(a.favourited)
        assertTrue(a.canBeChatBackground)
        assertFalse(a.locked)
        assertEquals("https://x/f.webp", a.fullUrl)
    }

    @Test fun aLockedPremiumImageHasAPreviewButNoFullLink() {
        val a = JSONObject(free.replace("\"premium\":false,\"locked\":false", "\"premium\":true,\"locked\":true").replace("\"fullUrl\":\"https://x/f.webp\"", "\"fullUrl\":null")).toThemeAsset()
        assertTrue(a.locked)
        assertNull(a.fullUrl)
        assertEquals("https://x/t.webp", a.thumbUrl)
    }

    @Test fun onlyWebLinksAreAccepted() {
        val a = JSONObject(free.replace("https://x/t.webp", "file:///etc/passwd")).toThemeAsset()
        assertNull(a.thumbUrl)
    }

    @Test fun cardShapeStaysInAUsableRange() {
        val tall = JSONObject(free.replace("\"width\":800,\"height\":1200", "\"width\":100,\"height\":2000")).toThemeAsset()
        val wide = JSONObject(free.replace("\"width\":800,\"height\":1200", "\"width\":4000,\"height\":100")).toThemeAsset()
        val unknown = JSONObject(free.replace("\"width\":800,\"height\":1200", "\"width\":0,\"height\":0")).toThemeAsset()
        assertEquals(0.5f, tall.ratio, 0.001f)
        assertEquals(1.6f, wide.ratio, 0.001f)
        assertEquals(1f, unknown.ratio, 0.001f)
    }

    @Test fun anImageWithoutTheChatDestinationCannotBeAChatBackground() {
        val a = JSONObject(free.replace("\"profile_cover\",\"chat_background\"", "\"profile_cover\"")).toThemeAsset()
        assertFalse(a.canBeChatBackground)
    }

    @Test fun dimIsBoundedSoTextStaysReadable() {
        assertTrue(CHAT_DIM_MIN in 1..CHAT_DIM_MAX)
        assertTrue(CHAT_DIM_MAX <= 80) // the server rejects more
    }
}
