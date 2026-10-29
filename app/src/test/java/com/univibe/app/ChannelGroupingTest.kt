package com.univibe.app

import com.univibe.app.data.model.ChannelAuthor
import com.univibe.app.data.model.ChannelMessage
import com.univibe.app.data.model.ReplyPreview
import com.univibe.app.data.repository.parseChannelMessage
import com.univibe.app.features.communities.channelContinuations
import com.univibe.app.features.communities.joinsChannelGroup
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

private val UTC = ZoneOffset.UTC

private fun m(
    id: String, author: String? = "a", at: String = "2026-01-01T10:00:00Z", deleted: Boolean = false, replyTo: ReplyPreview? = null,
    title: String? = null, pinned: Boolean = false,
) = ChannelMessage(
    id = id, channelId = "ch", seq = 0, author = author?.let { ChannelAuthor(it, it, it, null, 0, false) }, authorRole = null, title = title,
    content = if (deleted) null else "hi", deleted = deleted, removedByModerators = false, createdAt = at, edited = false, pinned = pinned,
    replyCount = 0, replyTo = replyTo, acceptedAnswerId = null, isAcceptedAnswer = false, attachments = emptyList(),
    reactions = emptyMap(), myReactions = emptySet(),
)

class ChannelGroupingTest {
    @Test fun `same author within the window continues the run`() {
        assertTrue(joinsChannelGroup(m("1"), m("2", at = "2026-01-01T10:04:59Z"), UTC))
    }

    @Test fun `a gap longer than five minutes starts a new run`() {
        assertFalse(joinsChannelGroup(m("1"), m("2", at = "2026-01-01T10:05:01Z"), UTC))
    }

    @Test fun `a different author never continues the run`() {
        assertFalse(joinsChannelGroup(m("1", author = "a"), m("2", author = "b"), UTC))
    }

    @Test fun `a message with no known author never joins`() {
        assertFalse(joinsChannelGroup(m("1", author = null), m("2", author = null), UTC))
    }

    @Test fun `a reply starts its own run because its preview sits on top`() {
        assertFalse(joinsChannelGroup(m("1"), m("2", replyTo = ReplyPreview("x", "hello", false, "Sam")), UTC))
    }

    @Test fun `titled posts, pinned messages and tombstones never continue a run`() {
        assertFalse(joinsChannelGroup(m("1"), m("2", title = "Exam dates"), UTC))
        assertFalse(joinsChannelGroup(m("1"), m("2", pinned = true), UTC))
        assertFalse(joinsChannelGroup(m("1"), m("2", deleted = true), UTC))
        assertFalse(joinsChannelGroup(m("1", deleted = true), m("2"), UTC))
    }

    @Test fun `a run does not cross midnight in the viewer's time zone`() {
        assertFalse(joinsChannelGroup(m("1", at = "2026-01-01T23:58:00Z"), m("2", at = "2026-01-02T00:01:00Z"), UTC))
        // 20:58Z and 21:01Z are one day in UTC, but 23:58 and 00:01 across midnight three hours ahead.
        val a = m("1", at = "2026-01-01T20:58:00Z")
        val b = m("2", at = "2026-01-01T21:01:00Z")
        assertTrue(joinsChannelGroup(a, b, UTC))
        assertFalse(joinsChannelGroup(a, b, ZoneOffset.ofHours(3)))
    }

    @Test fun `an unreadable timestamp never joins`() {
        assertFalse(joinsChannelGroup(m("1", at = "yesterday"), m("2"), UTC))
    }

    @Test fun `continuations are computed against the previous message only`() {
        val list = listOf(
            m("1"), m("2", at = "2026-01-01T10:01:00Z"), m("3", at = "2026-01-01T10:02:00Z"),
            m("4", author = "b", at = "2026-01-01T10:03:00Z"), m("5", at = "2026-01-01T10:04:00Z"),
        )
        // 2 and 3 continue 1's run; 4 is a new author; 5 is by "a" again but the message before it is by "b".
        assertEquals(setOf("2", "3"), channelContinuations(list, UTC))
    }

    @Test fun `an empty or single-message list has no continuations`() {
        assertTrue(channelContinuations(emptyList(), UTC).isEmpty())
        assertTrue(channelContinuations(listOf(m("1")), UTC).isEmpty())
    }

    @Test fun `the reply preview carries the original author's name from the server`() {
        val o = JSONObject(
            """{"id":"m1","channelId":"c","seq":1,"createdAt":"2026-01-01T10:00:00Z","deleted":false,"content":"yes",
                "replyTo":{"id":"m0","authorId":"u1","authorName":"Sam Otieno","preview":"who has notes?","deleted":false}}""",
        )
        val r = parseChannelMessage(o).replyTo!!
        assertEquals("Sam Otieno", r.authorName)
        assertEquals("who has notes?", r.preview)
    }

    @Test fun `a hidden original has no name and no preview`() {
        val o = JSONObject(
            """{"id":"m1","channelId":"c","seq":1,"createdAt":"2026-01-01T10:00:00Z","deleted":false,"content":"yes",
                "replyTo":{"id":"m0","authorId":null,"authorName":null,"preview":null,"deleted":false}}""",
        )
        val r = parseChannelMessage(o).replyTo!!
        assertNull(r.authorName)
        assertNull(r.preview)
    }
}
