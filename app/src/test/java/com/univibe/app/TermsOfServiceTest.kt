package com.univibe.app

import com.univibe.app.features.legal.LegalContact
import com.univibe.app.features.legal.TermsOfService
import com.univibe.app.features.settings.SettingsSearch
import com.univibe.app.navigation.SettingsRoutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TermsOfServiceTest {
    private val text = TermsOfService.plainText()

    @Test fun noPlaceholdersAreLeftForReadersToSee() {
        assertFalse("Unfilled [PLACEHOLDER] found", Regex("\\[[A-Z /_]+\\]").containsMatchIn(text))
        assertFalse(text.contains("TODO", ignoreCase = true))
        assertFalse(text.contains("draft for", ignoreCase = true))
        assertFalse(text.contains("legal advice", ignoreCase = true))
    }

    @Test fun contactDetailsAreTheOnesTheOwnerGave() {
        assertTrue(text.contains("info@univibe.swiftpesanet.co.ke"))
        assertTrue(text.contains("0729612106"))
        assertTrue(text.contains("Antony Mwangi"))
        assertTrue(text.contains("SwiftNet"))
        assertEquals("© UniVibe 2026. All rights reserved.", LegalContact.COPYRIGHT)
    }

    @Test fun sectionsAreNumberedInOrderAndClausesMatchTheirSection() {
        TermsOfService.sections.forEachIndexed { i, s -> assertEquals(i + 1, s.number) }
        TermsOfService.sections.forEach { s ->
            s.clauses.filter { it.label != "•" }.forEach { assertTrue("${it.label} not in section ${s.number}", it.label.startsWith("${s.number}.")) }
        }
    }

    @Test fun doesNotPromiseFeaturesUniVibeLacks() {
        val lower = text.lowercase()
        // These may only appear in a sentence saying the feature is NOT offered.
        assertTrue(lower.contains("not end-to-end encrypted"))
        assertFalse(lower.contains("calls"))
        assertFalse(lower.contains("subscription"))
        assertFalse(lower.contains("creator"))
        assertFalse(lower.contains("read receipts"))
        assertTrue(lower.contains("do not currently offer two-factor"))
        assertTrue(lower.contains("does not currently offer temporary deactivation"))
    }

    @Test fun pointsAtScreensThatExist() {
        assertTrue(text.contains("Settings → Account → Delete account"))
        assertTrue(text.contains("Settings → Security → Login activity"))
        assertTrue(text.contains("Settings → Account status"))
    }

    @Test fun isFoundBySettingsSearch() {
        assertEquals(SettingsRoutes.TERMS, SettingsSearch.search("terms of service").first().route)
        assertEquals(SettingsRoutes.TERMS, SettingsSearch.search("terms and conditions").first().route)
    }
}
