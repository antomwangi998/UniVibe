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
        assertEquals(listOf(SettingsCategories.PRIVACY, "Read receipts"), r.path)
        assertEquals(SettingsRoutes.PRIVACY, r.route)
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
