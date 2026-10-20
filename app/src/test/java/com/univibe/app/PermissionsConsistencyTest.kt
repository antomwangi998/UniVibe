package com.univibe.app

import com.univibe.app.features.legal.PrivacyPolicy
import com.univibe.app.features.legal.TermsOfService
import com.univibe.app.features.settings.SettingsSearch
import com.univibe.app.navigation.SettingsRoutes
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards against the Permissions screen, the Terms and the Privacy Policy drifting away from what the app really asks for.
 * If this fails after you add a permission to the manifest, update Settings → Permissions and both legal texts, then this list.
 */
class PermissionsConsistencyTest {
    private val declared: Set<String> by lazy {
        val manifest = listOf("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml").map(::File).first { it.exists() }
        Regex("<uses-permission[^>]*android:name=\"([^\"]+)\"").findAll(manifest.readText()).map { it.groupValues[1].substringAfterLast('.') }.toSet()
    }

    @Test fun onlyKnownPermissionsAreDeclared() {
        assertEquals(setOf("INTERNET", "ACCESS_NETWORK_STATE", "POST_NOTIFICATIONS", "RECORD_AUDIO"), declared)
    }

    @Test fun legalTextsExplainTheMicrophone() {
        assertTrue("RECORD_AUDIO" in declared)
        for (text in listOf(TermsOfService.plainText(), PrivacyPolicy.plainText())) {
            val lower = text.lowercase()
            assertTrue(lower.contains("voice message"))
            assertFalse(lower.contains("camera or microphone"))
            assertFalse(lower.contains("camera, microphone"))
            assertFalse(lower.contains("contacts, camera or microphone"))
        }
    }

    @Test fun microphoneAndStorageAreSearchable() {
        assertEquals(SettingsRoutes.PERMISSIONS, SettingsSearch.search("microphone").first().route)
        assertEquals(SettingsRoutes.DATA_STORAGE, SettingsSearch.search("clear cache").first().route)
        assertEquals(SettingsRoutes.DATA_STORAGE, SettingsSearch.search("storage").first().route)
    }
}
