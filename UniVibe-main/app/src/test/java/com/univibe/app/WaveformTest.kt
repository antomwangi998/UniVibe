package com.univibe.app

import com.univibe.app.features.chats.WAVE_BARS
import com.univibe.app.features.chats.waveformFromAmplitudes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WaveformTest {
    @Test fun emptyInputGivesNoBars() {
        assertTrue(waveformFromAmplitudes(emptyList()).isEmpty())
    }

    @Test fun alwaysTheRequestedNumberOfBarsInRange() {
        for (n in listOf(1, 3, 39, 40, 41, 500, 3000)) {
            val w = waveformFromAmplitudes(List(n) { (it * 37) % 30_000 })
            assertEquals(WAVE_BARS, w.size)
            assertTrue(w.all { it in 4..100 })
        }
    }

    @Test fun loudestSampleIsFullHeightAndSilenceIsTheMinimum() {
        val w = waveformFromAmplitudes(List(80) { if (it == 40) 20_000 else 0 })
        assertEquals(100, w.max())
        assertEquals(4, w.min())
    }

    @Test fun quietRoomNoiseIsNotStretchedToFullHeight() {
        val w = waveformFromAmplitudes(List(80) { 300 })
        assertTrue(w.all { it < 50 })
    }

    @Test fun aVeryShortMessageIsSpreadAcrossAllBars() {
        val w = waveformFromAmplitudes(listOf(0, 20_000, 0))
        assertEquals(WAVE_BARS, w.size)
        assertTrue(w.any { it == 100 })
    }
}
