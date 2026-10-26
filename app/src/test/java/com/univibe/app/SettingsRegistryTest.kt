package com.univibe.app

import com.univibe.app.features.settings.SettingsCategories
import com.univibe.app.features.settings.SettingsRegistry
import com.univibe.app.features.settings.SettingsSearch
import com.univibe.app.navigation.AppRoutes
import com.univibe.app.navigation.SettingsRoutes
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsRegistryTest {
    private fun titles(q: String) = SettingsSearch.search(q).map { it.title }

    @Test fun everyEntryOpensARealRoute() {
        val known = (SettingsRoutes::class.java.declaredFields + AppRoutes::class.java.declaredFields)
            .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java }
            .map { it.get(null) as String }.toSet()
        val missing = SettingsRegistry.entries.filter { it.route !in known }
        assertTrue("Entries with unknown routes: ${missing.map { it.id }}", missing.isEmpty())
    }

    @Test fun idsAreUniqueAndEveryEntryIsDescribed() {
        val ids = SettingsRegistry.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(SettingsRegistry.entries.all { it.title.isNotBlank() && it.description.isNotBlank() && it.path.isNotEmpty() })
    }

    @Test fun findsByTitleKeywordAndDescription() {
        assertTrue("Read receipts" in titles("read receipts"))
        assertTrue("Theme" in titles("dark mode"))
        assertTrue("Login activity" in titles("sign out everywhere"))
        assertTrue("Blocked students" in titles("blocked users"))
        assertTrue("Quiet hours" in titles("do not disturb"))
    }

    @Test fun isCaseInsensitiveAndMatchesPartialWords() {
        assertTrue("Read receipts" in titles("READ rec"))
        assertTrue("Change password" in titles("passw"))
    }

    @Test fun everyWordMustMatch() {
        assertTrue(titles("read zebra").isEmpty())
    }

    @Test fun titleMatchesOutrankDescriptionMatches() {
        assertEquals("Change password", titles("change password").first())
    }

    @Test fun blankQueryReturnsNothing() {
        assertTrue(SettingsSearch.search("   ").isEmpty())
    }

    @Test fun resultsKnowTheirBreadcrumb() {
        val r = SettingsSearch.search("read receipts").first()
        assertEquals(listOf(SettingsCategories.PRIVACY, "Messages", "Read receipts"), r.path)
        assertEquals(SettingsRoutes.READ_RECEIPTS, r.route)
    }

    @Test fun privacyControlsOpenTheirOwnScreen() {
        assertEquals(SettingsRoutes.MESSAGE_POLICY, SettingsSearch.search("who can message").first().route)
        assertEquals(SettingsRoutes.ACTIVITY_STATUS, SettingsSearch.search("online status").first().route)
        assertEquals(SettingsRoutes.PRIVACY_VISIBILITY, SettingsSearch.search("appear in discover").first().route)
    }

    @Test fun notificationControlsOpenTheirOwnScreen() {
        assertEquals(SettingsRoutes.NOTIFICATIONS_ACTIVITY, SettingsSearch.search("story reactions").first().route)
        assertEquals(SettingsRoutes.NOTIFICATIONS_EVENTS, SettingsSearch.search("event reminders").first().route)
        assertEquals(SettingsRoutes.NOTIFICATIONS_QUIET, SettingsSearch.search("do not disturb").first().route)
        assertEquals(SettingsRoutes.NOTIFICATIONS_PUSH, SettingsSearch.search("phone alerts").first().route)
    }

    @Test fun appearanceAndAccessibilityControlsOpenTheirOwnScreen() {
        assertEquals(SettingsRoutes.APPEARANCE_THEME, SettingsSearch.search("dark mode").first().route)
        assertEquals(SettingsRoutes.APPEARANCE_FONT, SettingsSearch.search("typeface").first().route)
        assertEquals(SettingsRoutes.ACCESSIBILITY_TEXT_SIZE, SettingsSearch.search("text size").first().route)
        assertEquals(SettingsRoutes.ACCESSIBILITY_DISPLAY, SettingsSearch.search("bold text").first().route)
        assertEquals(SettingsRoutes.ACCESSIBILITY_DISPLAY, SettingsSearch.search("high contrast").first().route)
        assertEquals(SettingsRoutes.ACCESSIBILITY_MOTION, SettingsSearch.search("reduce motion").first().route)
        assertEquals(SettingsRoutes.ACCESSIBILITY_INTERACTION, SettingsSearch.search("vibration").first().route)
    }

    @Test fun campusAndLegalControlsOpenTheirOwnScreen() {
        assertEquals(SettingsRoutes.CAMPUS_IDENTITY, SettingsSearch.search("verified student").first().route)
        assertEquals(SettingsRoutes.DEFAULT_FEED, SettingsSearch.search("default feed").first().route)
        assertEquals(SettingsRoutes.LEGAL, SettingsSearch.search("legal").first().route)
        val terms = SettingsSearch.search("terms of service").first()
        assertEquals(listOf(SettingsCategories.LEGAL, "Terms of Service"), terms.path)
        assertEquals(SettingsRoutes.TERMS, terms.route)
    }

    @Test fun accountControlsAreDistinctAndReachable() {
        assertEquals(SettingsRoutes.DEACTIVATE, SettingsSearch.search("deactivate").first().route)
        assertEquals(SettingsRoutes.DEACTIVATE, SettingsSearch.search("take a break").first().route)
        assertEquals(SettingsRoutes.DATA_EXPORT, SettingsSearch.search("download my information").first().route)
        assertEquals(SettingsRoutes.DELETE, SettingsSearch.search("delete account").first().route)
        // Deactivating and deleting are different actions on different screens.
        assertTrue(SettingsRoutes.DEACTIVATE != SettingsRoutes.DELETE)
    }

    @Test fun nestedPrivacyRoutesSitUnderTheirParents() {
        assertTrue(SettingsRoutes.READ_RECEIPTS.startsWith(SettingsRoutes.PRIVACY_MESSAGES + "/"))
        assertTrue(SettingsRoutes.MESSAGE_POLICY.startsWith(SettingsRoutes.PRIVACY_MESSAGES + "/"))
        assertTrue(SettingsRoutes.PRIVACY_MESSAGES.startsWith(SettingsRoutes.PRIVACY + "/"))
    }

    @Test fun nestedScreensAreReachable() {
        assertEquals(SettingsRoutes.LOGIN_ACTIVITY, SettingsSearch.search("login activity").first().route)
        assertEquals(SettingsRoutes.STATUS, SettingsSearch.search("suspended").first().route)
    }

    @Test fun nothingIsListedForFeaturesThatDoNotExist() {
        val banned = listOf("two-factor", "payments", "creator", "calls", "trusted devices", "digest")
        assertTrue(SettingsRegistry.entries.none { e -> banned.any { b -> e.title.lowercase().contains(b) } })
    }
}
