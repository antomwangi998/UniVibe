package com.univibe.app

import com.univibe.app.data.model.CampusEvent
import com.univibe.app.data.model.EventStatus
import com.univibe.app.data.repository.parseCampusEvent
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EventStage67Test {
    private val hour = 3_600_000L
    private val now get() = System.currentTimeMillis()

    private fun event(start: Long, end: Long, phase: String? = null, cancelled: Boolean = false) =
        CampusEvent("e1", "Jam", "", "", "", "Hall", 0, 0, "Social", 0, startMillis = start, endMillis = end, cancelled = cancelled, serverPhase = phase)

    @Test fun deviceClockDecidesWhenTheServerSaysNothing() {
        assertEquals(EventStatus.Upcoming, event(now + hour, now + 2 * hour).status())
        assertEquals(EventStatus.Live, event(now - hour, now + hour).status())
        assertEquals(EventStatus.Ended, event(now - 3 * hour, now - hour).status())
    }

    @Test fun serverPhaseWinsForEndedAndLive() {
        // An organizer closed the event early: the server says ended while the clock still says live.
        assertEquals(EventStatus.Ended, event(now - hour, now + hour, phase = "ended").status())
        // A device clock running behind still shows a live event as live.
        assertEquals(EventStatus.Live, event(now + hour, now + 2 * hour, phase = "live").status())
        assertEquals(EventStatus.Cancelled, event(now - hour, now + hour, phase = "live", cancelled = true).status())
    }

    private fun json(extra: String) = JSONObject(
        """{"id":"e1","title":"Jam","category":"technology","startsAt":"2030-05-01T10:00:00Z","endsAt":"2030-05-01T12:00:00Z",
           "venueName":"Hall","organizer":{"id":"u1","fullName":"Org","handle":"org"},"status":"published","phase":"upcoming"$extra}""".trimIndent(),
    )

    @Test fun parsesStageSixAndSevenFields() {
        val e = parseCampusEvent(json(""","revision":3,"hidden":true,"remindersOn":false"""))
        assertEquals(3, e.revision)
        assertTrue(e.hidden)
        assertEquals(false, e.remindersOn)
        assertFalse(e.draft)
        assertEquals("upcoming", e.serverPhase)
    }

    @Test fun draftAndEndedPhaseAreRead() {
        val d = parseCampusEvent(JSONObject(json("").toString().replace("\"published\"", "\"draft\"")))
        assertTrue(d.draft)
        val ended = parseCampusEvent(JSONObject(json("").toString().replace("\"upcoming\"", "\"ended\"")))
        assertTrue(ended.isPast)
    }

    @Test fun remindersAreNullWhenNotGoing() {
        assertNull(parseCampusEvent(json("")).remindersOn)
    }
}
