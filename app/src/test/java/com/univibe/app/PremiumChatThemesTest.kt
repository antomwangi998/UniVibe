package com.univibe.app

import com.univibe.app.data.prefs.EntitlementsStore
import com.univibe.app.features.chats.PremiumChatThemes
import com.univibe.app.features.chats.PremiumPacks
import com.univibe.app.features.chats.chatThemeById
import com.univibe.app.features.chats.isPremiumLook
import com.univibe.app.features.chats.premiumLookPaused
import com.univibe.app.features.chats.themeShown
import com.univibe.app.features.chats.wallpaperShown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PremiumChatThemesTest {
    @Test fun lockedPremiumLookFallsBackToFree() {
        assertEquals("default", themeShown("p_aurora", entitled = false))
        assertEquals("none", wallpaperShown("p_aurora", entitled = false))
    }

    @Test fun unlockedPremiumLookIsShown() {
        assertEquals("p_neon", themeShown("p_neon", entitled = true))
        assertEquals("p_neon", wallpaperShown("p_neon", entitled = true))
    }

    @Test fun freeLooksAreNeverAffected() {
        assertEquals("ocean", themeShown("ocean", entitled = false))
        assertEquals("aurora", wallpaperShown("aurora", entitled = false))
        assertEquals("photo", wallpaperShown("photo", entitled = false))
    }

    @Test fun pausedLookIsReportedOnlyWhenLockedAndPremium() {
        assertTrue(premiumLookPaused("p_stars", "none", entitled = false))
        assertFalse(premiumLookPaused("p_stars", "none", entitled = true))
        assertFalse(premiumLookPaused("ocean", "dusk", entitled = false))
    }

    @Test fun everyPackHasAThemeAndPremiumId() {
        for (p in PremiumPacks) {
            assertTrue(isPremiumLook(p.id))
            assertEquals(p.id, chatThemeById(p.id).id)
        }
        assertEquals(PremiumPacks.map { it.id }, PremiumChatThemes.map { it.id })
    }

    @Test fun storeHoldsOnlyWhatTheServerSaidAndClears() {
        EntitlementsStore.clear()
        assertFalse(EntitlementsStore.has("premium.chat_themes"))
        EntitlementsStore.update(listOf("premium.chat_themes"))
        assertTrue(EntitlementsStore.has("premium.chat_themes"))
        EntitlementsStore.clear()
        assertFalse(EntitlementsStore.has("premium.chat_themes"))
    }
}
