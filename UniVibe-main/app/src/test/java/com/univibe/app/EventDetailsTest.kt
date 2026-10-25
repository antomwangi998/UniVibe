package com.univibe.app

import com.univibe.app.data.model.RsvpState
import com.univibe.app.data.repository.MockEventRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EventDetailsTest {
    @Test
    fun knownIdReturnsDetails() = runTest {
        val d = MockEventRepository().getEvent("e1")
        assertNotNull(d)
        assertTrue(d!!.description.isNotBlank())
        assertTrue(d.agenda.isNotEmpty())
    }

    @Test
    fun unknownIdReturnsNull() = runTest {
        assertNull(MockEventRepository().getEvent("does-not-exist"))
        assertNull(MockEventRepository().getEvent("../../etc"))
    }

    @Test
    fun rsvpIsVisibleInDetailsAndListWithMatchingCounts() = runTest {
        val repo = MockEventRepository()
        val before = repo.getEvent("e1")!!.event
        repo.setRsvp("e1", RsvpState.Going)
        val after = repo.getEvent("e1")!!.event
        assertEquals(RsvpState.Going, after.rsvp)
        assertEquals(before.going + 1, after.going)
        assertEquals(after.going, repo.getEvents().first { it.id == "e1" }.going)
    }

    @Test
    fun fullEventBlocksNewGoingButNotExistingAttendee() = runTest {
        val repo = MockEventRepository()
        val d = repo.getEvent("e3")!!
        val full = d.copy(event = d.event.copy(going = d.capacity!!))
        assertTrue(full.isFull)
        assertEquals(0, full.spotsLeft)
        assertFalse(full.copy(event = full.event.copy(rsvp = RsvpState.Going)).isFull)
    }

    @Test
    fun uncappedEventIsNeverFull() = runTest {
        val d = MockEventRepository().getEvent("e2")!!
        assertNull(d.capacity)
        assertFalse(d.isFull)
    }
}
