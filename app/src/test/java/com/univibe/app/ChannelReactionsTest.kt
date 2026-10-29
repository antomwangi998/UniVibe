package com.univibe.app

import com.univibe.app.data.model.ChannelMessage
import com.univibe.app.data.model.withReactionToggled
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private fun m(reactions: Map<String, Int> = emptyMap(), mine: Set<String> = emptySet(), deleted: Boolean = false) = ChannelMessage(
    id = "m", channelId = "ch", seq = 1, author = null, authorRole = null, title = null, content = "hi", deleted = deleted,
    removedByModerators = false, createdAt = "2026-01-01T10:00:00Z", edited = false, pinned = false, replyCount = 0, replyTo = null,
    acceptedAnswerId = null, isAcceptedAnswer = false, attachments = emptyList(), reactions = reactions, myReactions = mine,
)

class ChannelReactionsTest {
    @Test fun `adding a reaction marks it mine and adds one to the count`() {
        val r = m(mapOf("fire" to 2)).withReactionToggled("fire", on = true)
        assertEquals(3, r.reactions["fire"]); assertTrue("fire" in r.myReactions)
    }

    @Test fun `the first reaction of a kind creates the chip`() {
        val r = m().withReactionToggled("heart", on = true)
        assertEquals(1, r.reactions["heart"])
    }

    @Test fun `removing my reaction lowers the count and drops the chip at zero`() {
        val r = m(mapOf("heart" to 1), setOf("heart")).withReactionToggled("heart", on = false)
        assertFalse("heart" in r.reactions); assertTrue(r.myReactions.isEmpty())
        val two = m(mapOf("heart" to 2), setOf("heart")).withReactionToggled("heart", on = false)
        assertEquals(1, two.reactions["heart"])
    }

    @Test fun `repeating the same toggle changes nothing, so a double tap cannot count twice`() {
        val once = m(mapOf("fire" to 1), setOf("fire"))
        assertEquals(once, once.withReactionToggled("fire", on = true))
        val none = m()
        assertEquals(none, none.withReactionToggled("fire", on = false))
    }

    @Test fun `a deleted message ignores reactions`() {
        val gone = m(deleted = true)
        assertEquals(gone, gone.withReactionToggled("fire", on = true))
    }

    @Test fun `other reactions on the message are left alone`() {
        val r = m(mapOf("heart" to 4, "fire" to 1), setOf("heart")).withReactionToggled("fire", on = true)
        assertEquals(4, r.reactions["heart"]); assertEquals(setOf("heart", "fire"), r.myReactions)
    }
}
