package com.univibe.app

import com.univibe.app.data.model.RsvpState
import com.univibe.app.data.repository.parsePost
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val AUTHOR_JSON =
    """{"id":"u1","fullName":"Brian Otieno","handle":"brianotieno","course":"Law","yearOfStudy":"","interests":[],"studentVerified":true,"hasPhoto":false,"universityName":"Uni","avatarUrl":null}"""

private fun eventJson(extra: String = "") =
    """{"id":"e1","title":"Hack night","description":"","category":"technology","eventType":"in_person","venueName":"Lab 3",
    "startsAt":"2030-01-01T10:00:00.000Z","endsAt":"2030-01-01T12:00:00.000Z","registeredCount":7,"interestedCount":3,
    "status":"published","myRsvp":"interested","myRegistration":null,"saved":true,"coverUrl":"https://files.example/c.jpg",
    "isOrganizer":false,"organizer":$AUTHOR_JSON$extra}"""

private fun postWith(event: String?) = parsePost(JSONObject(
    """{"id":"p1","author":$AUTHOR_JSON,"ageSeconds":60,"text":"","audience":"campus","likeCount":0,"commentCount":0,"shareCount":0,
    "likedByMe":false,"savedByMe":false,"edited":false,"media":[],"poll":null,"eventId":${if (event == null) "null" else "\"e1\""},"event":${event ?: "null"}}""",
))

class FeedEventPostTest {
    @Test fun anAnnouncementCarriesTheEventsCurrentState() {
        val e = postWith(eventJson()).event!!
        assertEquals("e1", e.id)
        assertEquals("Hack night", e.title)
        assertEquals(7, e.going)
        assertEquals(3, e.interested)
        assertEquals(RsvpState.Interested, e.rsvp)
        assertTrue(e.saved)
        assertEquals("https://files.example/c.jpg", e.coverUrl)
        assertFalse(e.cancelled)
    }

    @Test fun aCancelledEventShowsAsCancelledOnItsFeedCard() {
        val e = postWith(eventJson().replace("\"status\":\"published\"", "\"status\":\"cancelled\"")).event!!
        assertTrue(e.cancelled)
    }

    @Test fun ordinaryPostsHaveNoEvent() = assertNull(postWith(null).event)

    @Test fun aMalformedEventNeverBreaksTheFeed() {
        val post = postWith("""{"id":"e1"}""")
        assertEquals("p1", post.id)
        assertNull(post.event)
    }
}
