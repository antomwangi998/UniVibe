package com.univibe.app

import com.univibe.app.data.model.Poll
import com.univibe.app.data.model.PollOption
import com.univibe.app.data.model.PostLimits
import com.univibe.app.data.net.parsePoll
import com.univibe.app.ui.components.compactCount
import com.univibe.app.ui.components.pollTimeLeft
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PollExpiryTest {
    private val options = listOf(PollOption("A", 1), PollOption("B", 2))

    @Test fun pollWithoutEndNeverEnds() {
        assertFalse(Poll(options).hasEnded(Long.MAX_VALUE))
    }

    @Test fun pollEndsAtItsEndTime() {
        val poll = Poll(options, endsAtMillis = 1_000L)
        assertFalse(poll.hasEnded(999L))
        assertTrue(poll.hasEnded(1_000L))
    }

    @Test fun longestPollIsThreeDaysAndListedLongestFirst() {
        assertEquals(72, PostLimits.MAX_POLL_HOURS)
        assertEquals(listOf(72, 48, 24, 12, 6, 1), PostLimits.POLL_DURATIONS.map { it.first })
        assertEquals("3 days", PostLimits.POLL_DURATIONS.first().second)
    }

    @Test fun timeLeftReadsNaturally() {
        val h = 3_600_000L
        assertEquals("2d 4h left", pollTimeLeft(2 * 24 * h + 4 * h))
        assertEquals("3d left", pollTimeLeft(3 * 24 * h))
        assertEquals("5h 10m left", pollTimeLeft(5 * h + 10 * 60_000L))
        assertEquals("12m left", pollTimeLeft(12 * 60_000L))
        assertEquals("Less than a minute left", pollTimeLeft(20_000L))
    }

    @Test fun serverSecondsLeftBecomesAnEndTime() {
        val before = System.currentTimeMillis()
        val poll = parsePoll(JSONObject("""{"options":[{"text":"A","votes":0},{"text":"B","votes":0}],"myVote":null,"secondsLeft":3600,"ended":false}"""))!!
        assertTrue(poll.endsAtMillis!! >= before + 3_600_000L)
        assertFalse(poll.hasEnded(before))
        val over = parsePoll(JSONObject("""{"options":[{"text":"A","votes":0},{"text":"B","votes":0}],"myVote":null,"secondsLeft":0,"ended":true}"""))!!
        assertTrue(over.hasEnded())
        assertNull(parsePoll(null))
    }

    @Test fun countsStayShort() {
        assertEquals("999", compactCount(999))
        assertEquals("1K", compactCount(1_000))
        assertEquals("1.2K", compactCount(1_234))
        assertEquals("12K", compactCount(12_345))
        assertEquals("1.5M", compactCount(1_500_000))
    }
}
