package com.univibe.app

import androidx.compose.ui.graphics.Color
import com.univibe.app.data.model.ProfileLook
import com.univibe.app.features.profile.AccentPresets
import com.univibe.app.features.profile.ProfileBadges
import com.univibe.app.features.profile.ProfileFrames
import com.univibe.app.features.profile.ProfileThemes
import com.univibe.app.features.profile.canSaveLook
import com.univibe.app.features.profile.lookAccent
import com.univibe.app.features.profile.parseAccent
import com.univibe.app.features.profile.themeColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileLookTest {
    /** Must equal the lists in backend/src/modules/monetization/profile_look.ts and migration 058. */
    @Test fun idsMatchTheServerLists() {
        assertEquals(listOf("aurora", "ember", "tide", "noir", "bloom", "gold", "nature", "midnight", "galaxy", "minimal"), ProfileThemes.map { it.id })
        assertEquals(listOf("ring", "double", "dashed", "halo"), ProfileFrames.map { it.id })
        assertEquals(listOf("spark", "book", "moon", "coffee", "flame", "bolt"), ProfileBadges.map { it.id })
    }

    @Test fun everyThemeHasColoursAndEveryPresetParses() {
        ProfileThemes.forEach { assertEquals(3, themeColors(it.id)?.size) }
        AccentPresets.forEach { assertNotNull(parseAccent(it)) }
    }

    @Test fun badAccentsAreIgnored() {
        assertNull(parseAccent(null)); assertNull(parseAccent("red")); assertNull(parseAccent("#12345")); assertNull(parseAccent("#GGGGGG"))
        assertEquals(Color(0xFF1A9E8F), parseAccent("#1A9E8F"))
    }

    @Test fun unknownThemeShowsTheDefault() = assertNull(themeColors("from-the-future"))

    @Test fun accentPrefersChosenColourThenTheme() {
        val fallback = Color.Red
        assertEquals(Color(0xFF112233), lookAccent(ProfileLook(theme = "gold", accent = "#112233"), fallback))
        assertEquals(themeColors("gold")!!.last(), lookAccent(ProfileLook(theme = "gold"), fallback))
        assertEquals(fallback, lookAccent(null, fallback))
    }

    @Test fun savingNeedsWhatItUses() {
        assertTrue(canSaveLook(ProfileLook(), themesUnlocked = false, framesUnlocked = false))
        assertFalse(canSaveLook(ProfileLook(theme = "aurora"), themesUnlocked = false, framesUnlocked = true))
        assertFalse(canSaveLook(ProfileLook(frame = "ring"), themesUnlocked = true, framesUnlocked = false))
        assertTrue(canSaveLook(ProfileLook(theme = "aurora", frame = "ring"), themesUnlocked = true, framesUnlocked = true))
    }
}

class ProfileLookParsingTest {
    @Test fun serverLookIsReadFromAnyPersonObject() {
        val json = org.json.JSONObject("""{"id":"u1","fullName":"Amina Otieno","handle":"amina","look":{"theme":"aurora","frame":"ring","badge":"moon"}}""")
        val look = com.univibe.app.data.repository.parseLook(json.optJSONObject("look"))
        assertEquals("aurora", look?.theme); assertEquals("ring", look?.frame); assertEquals("moon", look?.badge); assertNull(look?.accent)
    }

    @Test fun absentOrEmptyLookIsNull() {
        assertNull(com.univibe.app.data.repository.parseLook(null))
        assertNull(com.univibe.app.data.repository.parseLook(org.json.JSONObject("{}")))
        assertNull(com.univibe.app.data.repository.parseLook(org.json.JSONObject("""{"theme":null,"frame":null}""")))
    }
}
