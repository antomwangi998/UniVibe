package com.univibe.app

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.univibe.app.data.settings.AppSettings
import com.univibe.app.ui.theme.DarkBackground
import com.univibe.app.ui.theme.DarkSurface
import com.univibe.app.ui.theme.LightBackground
import com.univibe.app.ui.theme.LightSurface
import com.univibe.app.ui.theme.uniVibeTypography
import com.univibe.app.ui.theme.withHighContrast
import kotlin.math.max
import kotlin.math.min
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppearanceAccessibilityTest {
    private fun contrast(a: Color, b: Color): Double {
        val la = a.luminance() + 0.05
        val lb = b.luminance() + 0.05
        return max(la, lb) / min(la, lb)
    }

    @Test fun highContrastTextMeetsAaaOnBothThemes() {
        val dark = darkColorScheme().withHighContrast(darkTheme = true)
        val light = lightColorScheme().withHighContrast(darkTheme = false)
        for (bg in listOf(DarkSurface, DarkBackground)) assertTrue(contrast(dark.onSurfaceVariant, bg) >= 7.0)
        for (bg in listOf(LightSurface, LightBackground)) assertTrue(contrast(light.onSurfaceVariant, bg) >= 7.0)
    }

    @Test fun highContrastBordersMeetThreeToOneOnBothThemes() {
        val dark = darkColorScheme().withHighContrast(darkTheme = true)
        val light = lightColorScheme().withHighContrast(darkTheme = false)
        for (bg in listOf(DarkSurface, DarkBackground)) {
            assertTrue(contrast(dark.outline, bg) >= 3.0)
            assertTrue(contrast(dark.outlineVariant, bg) >= 3.0)
        }
        for (bg in listOf(LightSurface, LightBackground)) {
            assertTrue(contrast(light.outline, bg) >= 3.0)
            assertTrue(contrast(light.outlineVariant, bg) >= 3.0)
        }
    }

    @Test fun highContrastLeavesTheBrandColoursAlone() {
        val base = darkColorScheme(primary = Color(0xFF00C7B7))
        val hc = base.withHighContrast(darkTheme = true)
        assertEquals(base.primary, hc.primary)
        assertEquals(base.surface, hc.surface)
        assertEquals(base.error, hc.error)
    }

    @Test fun boldTextMovesEachWeightOneStepUp() {
        val normal = uniVibeTypography(FontFamily.Default, boldText = false)
        val bold = uniVibeTypography(FontFamily.Default, boldText = true)
        assertEquals(FontWeight.Normal, normal.bodyLarge.fontWeight)
        assertEquals(FontWeight.Medium, bold.bodyLarge.fontWeight)
        assertEquals(FontWeight.Medium, normal.labelMedium.fontWeight)
        assertEquals(FontWeight.SemiBold, bold.labelMedium.fontWeight)
        // Already-heavy styles stay as they are, and sizes never change.
        assertEquals(FontWeight.SemiBold, bold.titleMedium.fontWeight)
        assertEquals(normal.bodyLarge.fontSize, bold.bodyLarge.fontSize)
    }

    @Test fun newPreferencesStartAtTheirNeutralValues() {
        val d = AppSettings()
        assertFalse(d.boldText)
        assertFalse(d.highContrast)
        assertTrue(d.hapticFeedback)
    }
}
