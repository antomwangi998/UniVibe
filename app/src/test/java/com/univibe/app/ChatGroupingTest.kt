package com.univibe.app

import com.univibe.app.data.model.Message
import com.univibe.app.features.chats.GROUP_WINDOW_MILLIS
import com.univibe.app.features.chats.groupPosition
import com.univibe.app.features.chats.joinsGroup
import com.univibe.app.features.chats.newIncomingAfter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatGroupingTest {
    // Noon, so that a few minutes either way stays on the same day in any time zone.
    private val noon = java.time.LocalDate.of(2026, 10, 17).atTime(12, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun msg(id: String, sender: String, at: Long, mine: Boolean = false) =
        Message(id, "c", sender, sender, "t", at, mine)

    @Test fun sameSenderCloseTogetherJoins() {
        assertTrue(joinsGroup(msg("a", "u1", noon), msg("b", "u1", noon + 60_000)))
    }

    @Test fun differentSenderOrLongGapDoesNotJoin() {
        assertFalse(joinsGroup(msg("a", "u1", noon), msg("b", "u2", noon + 1_000)))
        assertFalse(joinsGroup(msg("a", "u1", noon), msg("b", "u1", noon + GROUP_WINDOW_MILLIS + 1)))
        assertFalse(joinsGroup(null, msg("b", "u1", noon)))
    }

    @Test fun positionsOfAThreeMessageRun() {
        val a = msg("a", "u1", noon); val b = msg("b", "u1", noon + 1_000); val c = msg("c", "u1", noon + 2_000)
        assertTrue(groupPosition(a, null, b).isFirst)
        val mid = groupPosition(b, a, c)
        assertFalse(mid.isFirst); assertFalse(mid.isLast)
        assertTrue(groupPosition(c, b, null).isLast)
    }

    @Test fun deletingTheMiddleMessageRejoinsTheNeighbours() {
        val a = msg("a", "u1", noon); val c = msg("c", "u1", noon + 2_000)
        assertTrue(groupPosition(a, null, c).joinsNewer)
    }

    @Test fun newIncomingCountsOnlyOthersAfterTheKnownNewestAndNeverTwice() {
        val list = listOf(msg("old", "u2", noon - 9_000), msg("n1", "u2", noon), msg("mine", "me", noon + 1, mine = true), msg("n2", "u2", noon + 2))
        assertEquals(listOf("n2"), newIncomingAfter(list, "mine", emptySet()))
        assertEquals(listOf("n1", "n2"), newIncomingAfter(list, "old", emptySet()))
        assertEquals(emptyList<String>(), newIncomingAfter(list, "old", setOf("n1", "n2")))
    }

    @Test fun olderHistoryPagedInIsNotNew() {
        val before = listOf(msg("n1", "u2", noon))
        val after = listOf(msg("older", "u2", noon - 50_000)) + before
        assertEquals(emptyList<String>(), newIncomingAfter(after, "n1", emptySet()))
    }

    @Test fun aDeletedAnchorGivesNoAnswer() {
        assertNull(newIncomingAfter(listOf(msg("x", "u2", noon)), "gone", emptySet()))
    }
}
