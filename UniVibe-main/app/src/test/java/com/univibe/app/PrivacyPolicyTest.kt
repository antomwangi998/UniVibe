package com.univibe.app

import com.univibe.app.features.legal.LegalContact
import com.univibe.app.features.legal.PrivacyPolicy
import com.univibe.app.features.legal.TermsOfService
import com.univibe.app.features.settings.SettingsSearch
import com.univibe.app.navigation.SettingsRoutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyPolicyTest {
    private val text = PrivacyPolicy.plainText()
    private val lower = text.lowercase()

    @Test fun noPlaceholdersAreLeftForReadersToSee() {
        assertFalse("Unfilled [PLACEHOLDER] found", Regex("\\[[A-Z /_]+\\]").containsMatchIn(text))
        assertFalse(lower.contains("todo"))
        assertFalse(lower.contains("draft for"))
        assertFalse(lower.contains("legal advice"))
    }

    @Test fun contactDetailsAreTheOnesTheOwnerGave() {
        assertTrue(text.contains("info@univibe.swiftpesanet.co.ke"))
        assertTrue(text.contains("0729612106"))
        assertTrue(text.contains("Antony Mwangi"))
        assertTrue(text.contains("SwiftNet"))
        assertTrue(text.endsWith(LegalContact.COPYRIGHT))
    }

    @Test fun sectionsAreNumberedInOrderAndClausesMatchTheirSection() {
        PrivacyPolicy.sections.forEachIndexed { i, s -> assertEquals(i + 1, s.number) }
        PrivacyPolicy.sections.forEach { s ->
            s.clauses.filter { it.label != "•" }.forEach { assertTrue("${it.label} not in section ${s.number}", it.label.startsWith("${s.number}.")) }
        }
    }

    @Test fun sameVersionAndDateAsTheTerms() {
        assertEquals(TermsOfService.VERSION, PrivacyPolicy.VERSION)
        assertEquals(TermsOfService.UPDATED, PrivacyPolicy.UPDATED)
    }

    @Test fun doesNotPromiseWhatUniVibeLacks() {
        assertTrue(lower.contains("not end-to-end encrypted"))
        assertFalse(lower.contains("subscription"))
        assertFalse(lower.contains("creator"))
        assertFalse(lower.contains("two-factor"))
        assertFalse(lower.contains("we encrypt your messages"))
        assertTrue(lower.contains("does not currently offer temporary deactivation"))
    }

    @Test fun namesTheProvidersThatReallyReceiveData() {
        listOf("Render", "Neon", "Cloudflare R2", "Resend", "Firebase Cloud Messaging", "OpenStreetMap").forEach {
            assertTrue("Missing provider $it", text.contains(it))
        }
    }

    @Test fun statesTheRetentionPeriodsTheServerEnforces() {
        // Keep in step with backend/src/jobs.ts purgeOldRecords and backend/src/security.ts.
        assertTrue(text.contains("180 days"))
        assertTrue(text.contains("90 days"))
        assertTrue(text.contains("7 days"))
        assertTrue(text.contains("30 days"))
        assertTrue(text.contains("10 minutes"))
        assertTrue(text.contains("15 minutes"))
    }

    @Test fun pointsAtScreensThatExist() {
        assertTrue(text.contains("Settings → Account → Delete account"))
        assertTrue(text.contains("Settings → Security → Login activity"))
        assertTrue(text.contains("Settings → Privacy & safety"))
        assertTrue(text.contains("Settings → Notifications"))
        assertTrue(text.contains("Settings → Account status"))
    }

    @Test fun isFoundBySettingsSearch() {
        assertEquals(SettingsRoutes.PRIVACY_POLICY, SettingsSearch.search("privacy policy").first().route)
        assertEquals(SettingsRoutes.PRIVACY_POLICY, SettingsSearch.search("data protection").first().route)
    }

    @Test fun termsAndPrivacySettingsKeepTheirOwnRoutes() {
        assertEquals(SettingsRoutes.TERMS, SettingsSearch.search("terms of service").first().route)
        assertEquals(SettingsRoutes.MESSAGE_POLICY, SettingsSearch.search("who can message you").first().route)
    }
}
