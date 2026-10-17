package com.univibe.app

import com.univibe.app.data.model.CampusEvent
import com.univibe.app.data.model.CommunityPrivacy
import com.univibe.app.data.model.EVENT_RETENTION_MILLIS
import com.univibe.app.data.model.EventDetails
import com.univibe.app.data.model.EventStatus
import com.univibe.app.data.model.NewEvent
import com.univibe.app.data.model.RsvpState
import com.univibe.app.data.repository.MockCommunityRepository
import com.univibe.app.data.repository.MockEventRepository
import com.univibe.app.data.repository.MockStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class EventLifecycleTest {
    private val store = MockStore()
    private val repo = MockEventRepository(store)
    private val hour = 3_600_000L
    private val now get() = System.currentTimeMillis()

    private suspend fun assertRefused(block: suspend () -> Unit) {
        try { block(); fail("should have been refused") } catch (e: IllegalStateException) { } catch (e: IllegalArgumentException) { }
    }

    private fun draft(
        startInHours: Long = 48, lengthHours: Long = 2, title: String = "Study Jam", venue: String = "Library Lawn",
        capacity: Int? = null, host: String? = null, lat: Double? = -1.28, lng: Double? = 36.81, category: String = "Social",
    ) = NewEvent(title, "Bring notes", category, now + startInHours * hour, now + (startInHours + lengthHours) * hour, venue, lat, lng, capacity, 1, host)

    private fun expiredEvent(endedDaysAgo: Double): EventDetails {
        val end = now - (endedDaysAgo * 24 * hour).toLong()
        val e = CampusEvent("old${endedDaysAgo}", "Old event", "", "", "", "Hall", 5, 2, "Party", 0, startMillis = end - 2 * hour, endMillis = end)
        return EventDetails(e, "", "Host", 0)
    }

    // ---- times and status ----

    @Test
    fun seededEventsHaveRealTimesAndStatuses() = runTest {
        val all = repo.getEvents().associateBy { it.id }
        assertEquals(EventStatus.Upcoming, all.getValue("e1").status())
        assertFalse(all.getValue("e1").isPast)
        assertTrue(all.getValue("e5").isPast)
        assertEquals(EventStatus.Ended, all.getValue("e5").status())
        assertTrue(all.getValue("e1").timeLabel.isNotBlank())
        assertEquals(all.values.sortedBy { it.startMillis }.map { it.id }, all.values.map { it.id }.sortedBy { all.getValue(it).startMillis })
    }

    @Test
    fun statusFollowsTheClock() {
        val e = CampusEvent("x", "t", "", "", "", "v", 0, 0, "Party", 0, startMillis = 1_000, endMillis = 5_000)
        assertEquals(EventStatus.Upcoming, e.status(now = 500))
        assertEquals(EventStatus.Live, e.status(now = 1_000))
        assertEquals(EventStatus.Ended, e.status(now = 5_000))
        assertEquals(EventStatus.Cancelled, e.copy(cancelled = true).status(now = 500))
        assertEquals(5_000 + EVENT_RETENTION_MILLIS, e.expiresAtMillis)
    }

    // ---- auto-expiry ----

    @Test
    fun eventsAreDeletedAWeekAfterTheyEnd() = runTest {
        val expired = expiredEvent(endedDaysAgo = 8.0)
        val recent = expiredEvent(endedDaysAgo = 6.0)
        store.events.add(expired); store.events.add(recent)
        store.rsvps[expired.event.id] = RsvpState.Going
        val ids = repo.getEvents().map { it.id }
        assertFalse(expired.event.id in ids)          // gone
        assertTrue(recent.event.id in ids)            // still in Past
        assertNull(repo.getEvent(expired.event.id))   // links to it now say "not found"
        assertFalse(expired.event.id in store.rsvps.keys) // its RSVPs went with it
    }

    @Test
    fun cannotRsvpToEndedOrCancelledOrFullEvents() = runTest {
        assertRefused { repo.setRsvp("e5", RsvpState.Going) } // ended
        val id = repo.createEvent(draft())
        repo.cancelEvent(id)
        assertRefused { repo.setRsvp(id, RsvpState.Going) }
        val full = repo.createEvent(draft(title = "Tiny Meetup", capacity = 1))
        store.rsvps.remove(full) // the host's own place is the only one
        repo.setRsvp(full, RsvpState.Going)
        assertEquals(RsvpState.Going, repo.getEvent(full)!!.event.rsvp)
    }

    // ---- creating ----

    @Test
    fun createdEventIsMineAndTheHostIsGoing() = runTest {
        val id = repo.createEvent(draft(capacity = 40))
        val d = repo.getEvent(id)!!
        assertTrue(d.event.mine)
        assertEquals(RsvpState.Going, d.event.rsvp)
        assertEquals(1, d.event.going)
        assertEquals(40, d.capacity)
        assertTrue(repo.getEvents().any { it.id == id })
    }

    @Test
    fun invalidEventsAreRefused() = runTest {
        assertRefused { repo.createEvent(draft(title = "ab")) }
        assertRefused { repo.createEvent(draft(venue = "x")) }
        assertRefused { repo.createEvent(draft(category = "Nonsense")) }
        assertRefused { repo.createEvent(draft(startInHours = -1)) }              // in the past
        assertRefused { repo.createEvent(draft(lengthHours = 0)) }                // ends when it starts
        assertRefused { repo.createEvent(draft(lengthHours = 49)) }               // too long
        assertRefused { repo.createEvent(draft(startInHours = 24 * 400)) }        // too far ahead
        assertRefused { repo.createEvent(draft(capacity = 0)) }
        assertRefused { repo.createEvent(draft(capacity = 5001)) }
        assertRefused { repo.createEvent(draft(lat = -1.0, lng = null)) }
        assertRefused { repo.createEvent(draft(lat = 120.0, lng = 36.0)) }
    }

    @Test
    fun youCanHaveFiveUpcomingEventsAtATime() = runTest {
        repeat(5) { repo.createEvent(draft(title = "Event number $it")) }
        assertRefused { repo.createEvent(draft(title = "One too many")) }
    }

    @Test
    fun communityHostingNeedsModeratorRights() = runTest {
        assertRefused { repo.createEvent(draft(host = "photo")) }   // I'm only a member of Photography Club
        val id = repo.createEvent(draft(host = "tech"))              // I moderate Tech Club
        assertEquals("Tech Club", repo.getEvent(id)!!.event.hostName)
        assertRefused { repo.createEvent(draft(host = "ghost")) }
    }

    @Test
    fun deletingACommunityDeletesItsEvents() = runTest {
        val communities = MockCommunityRepository(store)
        val communityId = communities.createCommunity("Board Games", "Games every Thursday night.", "Social", 1, emptyList(), CommunityPrivacy.Public)
        val id = repo.createEvent(draft(host = communityId))
        assertNotNull(repo.getEvent(id))
        communities.deleteCommunity(communityId)
        assertNull(repo.getEvent(id))
    }

    // ---- managing ----

    @Test
    fun onlyTheHostManagesAnEvent() = runTest {
        assertRefused { repo.updateEvent("e1", draft()) }
        assertRefused { repo.cancelEvent("e1") }
        assertRefused { repo.deleteEvent("e1") }
    }

    @Test
    fun hostCanEditWithoutMovingTheStartAndCannotShrinkBelowAttendance() = runTest {
        val id = repo.createEvent(draft(startInHours = 48, capacity = 10))
        val original = repo.getEvent(id)!!.event
        repo.updateEvent(id, draft(startInHours = 48).copy(title = "Study Jam v2", startMillis = original.startMillis, endMillis = original.endMillis + hour, capacity = 5))
        val edited = repo.getEvent(id)!!
        assertEquals("Study Jam v2", edited.event.title)
        assertEquals(5, edited.capacity)
        assertRefused { repo.updateEvent(id, draft().copy(startMillis = original.startMillis, endMillis = original.endMillis, capacity = 0)) }
        assertRefused { repo.updateEvent(id, draft().copy(startMillis = now - hour, endMillis = now + hour)) } // new start in the past
    }

    @Test
    fun cancelledEventStaysVisibleButCannotBeCancelledTwiceOrEdited() = runTest {
        val id = repo.createEvent(draft())
        repo.cancelEvent(id)
        assertTrue(repo.getEvent(id)!!.event.cancelled)
        assertEquals(EventStatus.Cancelled, repo.getEvent(id)!!.event.status())
        assertRefused { repo.cancelEvent(id) }
        assertRefused { repo.updateEvent(id, draft()) }
    }

    @Test
    fun deleteIsOnlyForEventsNobodyElseRsvpedTo() = runTest {
        val id = repo.createEvent(draft())
        repo.deleteEvent(id)
        assertNull(repo.getEvent(id))
        val busy = repo.createEvent(draft(title = "Popular"))
        val i = store.events.indexOfFirst { it.event.id == busy }
        store.events[i] = store.events[i].copy(event = store.events[i].event.copy(interested = 3))
        assertRefused { repo.deleteEvent(busy) }
    }
}
