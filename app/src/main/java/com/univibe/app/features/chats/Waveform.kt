package com.univibe.app.features.chats

import kotlin.math.sqrt

/** How many bars a voice message's waveform has. */
const val WAVE_BARS = 40

/** Microphone peaks below this are room noise; they never get stretched up to look like speech. */
private const val AMPLITUDE_FLOOR = 2_000

/**
 * Turns microphone peaks (0..32767, one per sample) into [bars] bar heights from 4 to 100, loudest = 100.
 * Heights follow a square-root curve so quiet speech is still visible. Fewer samples than bars (a very short
 * message) are spread across the bars. Empty in, empty out.
 */
fun waveformFromAmplitudes(samples: List<Int>, bars: Int = WAVE_BARS): List<Int> {
    if (samples.isEmpty() || bars <= 0) return emptyList()
    val n = samples.size
    val peak = maxOf(samples.max(), AMPLITUDE_FLOOR)
    return List(bars) { i ->
        val from = i * n / bars
        val to = maxOf(from + 1, (i + 1) * n / bars).coerceAtMost(n)
        var top = 0
        for (k in from until to) if (samples[k] > top) top = samples[k]
        (sqrt(top.toDouble() / peak) * 100).toInt().coerceIn(4, 100)
    }
}
