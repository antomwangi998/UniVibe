package com.univibe.app

import com.univibe.app.data.model.EDIT_WINDOW_MILLIS
import com.univibe.app.data.model.GroupRole
import com.univibe.app.data.model.Message
import com.univibe.app.data.repository.MockChatRepository
import com.univibe.app.data.repository.MockData
import com.univibe.app.data.repository.MockGroupRepository
import com.univibe.app.data.repository.MockMessageRepository
import com.univibe.app.data.repository.MockStore
import com.univibe.app.features.chats.ConversationUiState
import com.univibe.app.features.chats.canEditMessage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ChatFeaturesTest {
    private suspend fun assertRefused(block: suspend () -> Unit) {
        try { block(); fail("should have been refused") } catch (e: IllegalStateException) { } catch (e: IllegalArgumentException) { }
    }

    private fun msg(id: String, text: String, mine: Boolean = true, ageMillis: Long = 0, sender: String = MockData.ME_ID) =
        Message(id, "c", sender, if (mine) "You" else "Other", text, System.currentTimeMillis() - ageMillis, mine)

    // ---- editing ----

    @Test
    fun recentOwnMessageCanBeEditedAndIsMarked() = runTest {
        val repo = MockMessageRepository(MockStore(), autoReply = false)
        repo.send("c1", "Hello")
        val sent = repo.messages("c1").first().last()
        repo.editMessage("c1", sent.id, " Hello there ")
        val edited = repo.messages("c1").first().last()
        assertEquals("Hello there", edited.text)
        assertTrue(edited.edited)
    }

    @Test
    fun oldOrForeignOrEmptyEditsAreRefused() = runTest {
        val repo = MockMessageRepository(MockStore(), autoReply = false)
        val all = repo.messages("c1").first()
        assertRefused { repo.editMessage("c1", all.first { it.isMine }.id, "late") }   // seeded message is about an hour old
        assertRefused { repo.editMessage("c1", all.first { !it.isMine }.id, "mine now") }
        repo.send("c1", "Fresh")
        val fresh = repo.messages("c1").first().last()
        assertRefused { repo.editMessage("c1", fresh.id, "   ") }
        assertRefused { repo.editMessage("c1", fresh.id, "x".repeat(1001)) }
    }

    @Test
    fun editWindowHelper() {
        assertTrue(canEditMessage(msg("a", "hi")))
        assertFalse(canEditMessage(msg("a", "hi", ageMillis = EDIT_WINDOW_MILLIS + 1_000)))
        assertFalse(canEditMessage(msg("a", "hi", mine = false)))
        assertFalse(canEditMessage(msg("a", "")))
    }

    // ---- forwarding ----

    @Test
    fun forwardCopiesAreMarkedAndSentToEveryTarget() = runTest {
        val repo = MockMessageRepository(MockStore(), autoReply = false)
        val original = repo.messages("c1").first().first()
        repo.forward("c1", original.id, listOf("c2", "c3"))
        assertTrue(repo.messages("c2").first().last().forwarded)
        assertEquals(original.text, repo.messages("c3").first().last().text)
    }

    @Test
    fun forwardIsAllOrNothing() = runTest {
        val repo = MockMessageRepository(MockStore(), autoReply = false)
        val original = repo.messages("c1").first().first()
        val before = repo.messages("c2").first().size
        assertRefused { repo.forward("c1", original.id, listOf("c2", "c7")) } // c7 is admins-only and I'm a member
        assertEquals(before, repo.messages("c2").first().size)
    }

    @Test
    fun forwardTargetLimits() = runTest {
        val repo = MockMessageRepository(MockStore(), autoReply = false)
        val id = repo.messages("c1").first().first().id
        assertRefused { repo.forward("c1", id, emptyList()) }
        assertRefused { repo.forward("c1", id, listOf("c2", "c3", "c4", "c5", "c6", "c7")) }
        assertRefused { repo.forward("c1", "ghost", listOf("c2")) }
    }

    // ---- pinning ----

    @Test
    fun anyoneCanPinInAOneToOneChat() = runTest {
        val repo = MockMessageRepository(MockStore(), autoReply = false)
        val id = repo.messages("c1").first().first().id
        repo.pin("c1", id)
        assertEquals(id, repo.pinnedMessageId("c1").first())
        repo.pin("c1", null)
        assertNull(repo.pinnedMessageId("c1").first())
    }

    @Test
    fun onlyAdminsPinInGroups() = runTest {
        val repo = MockMessageRepository(MockStore(), autoReply = false)
        val inC7 = repo.messages("c7").first().first().id
        assertRefused { repo.pin("c7", inC7) }            // I'm a member there
        val inC2 = repo.messages("c2").first().first().id
        repo.pin("c2", inC2)                                // I'm an admin there
        assertEquals(inC2, repo.pinnedMessageId("c2").first())
        assertRefused { repo.pin("c2", "ghost") }
    }

    // ---- creating groups ----

    @Test
    fun contactsExcludeYouAndCommunityAccounts() = runTest {
        val ids = MockGroupRepository(MockStore()).getContacts().map { it.id }
        assertFalse(MockData.aisha.id in ids)
        assertFalse(MockData.tech.id in ids)
        assertTrue(ids.isNotEmpty())
    }

    @Test
    fun createdGroupHasYouAsOwnerAndAppearsFirstInTheInbox() = runTest {
        val store = MockStore()
        val groups = MockGroupRepository(store)
        val id = groups.createGroup(" Study Crew ", "Finals prep", null, listOf("s2", "s3", "s2"))
        val info = groups.getGroup(id)!!
        assertEquals("Study Crew", info.name)
        assertEquals(3, info.members.size)
        assertEquals(GroupRole.Owner, info.myRole)
        assertEquals(id, MockChatRepository(store).getChats().first().id)
    }

    @Test
    fun invalidGroupsAreRefused() = runTest {
        val groups = MockGroupRepository(MockStore())
        assertRefused { groups.createGroup("  ", "", null, listOf("s2")) }
        assertRefused { groups.createGroup("x".repeat(41), "", null, listOf("s2")) }
        assertRefused { groups.createGroup("Ok", "d".repeat(201), null, listOf("s2")) }
        assertRefused { groups.createGroup("Ok", "", null, emptyList()) }
        assertRefused { groups.createGroup("Ok", "", null, listOf(MockData.aisha.id, MockData.tech.id, "ghost")) }
    }

    @Test
    fun oneToOneChatsKnowTheirStudent() = runTest {
        assertEquals("s3", MockChatRepository(MockStore()).getChat("c1")!!.studentId)
        assertNull(MockChatRepository(MockStore()).getChat("c2")!!.studentId)
    }

    // ---- mentions and search (pure state) ----

    @Test
    fun mentionSuggestionsOnlyInGroupsAndOnlyForRealMentions() {
        val group = MockStore().group("c2")!!
        val brian = MockData.brian
        val typing = ConversationUiState(group = group, input = "Hi @${brian.handle.removePrefix("@").take(2)}")
        assertTrue(typing.mentionSuggestions.any { it.id == brian.id })
        assertTrue(ConversationUiState(group = group, input = "mail me at a@b").mentionSuggestions.isEmpty())
        assertTrue(ConversationUiState(group = null, input = "Hi @br").mentionSuggestions.isEmpty())
        assertTrue(ConversationUiState(group = group, input = "Hi @br and").mentionSuggestions.isEmpty())
        assertTrue(ConversationUiState(group = group, input = "@").mentionSuggestions.none { it.id == MockData.aisha.id })
    }

    @Test
    fun searchMatchesAreNewestFirstAndIgnoreCase() {
        val list = listOf(msg("1", "Library at 3"), msg("2", "ok"), msg("3", "the LIBRARY closed"))
        val state = ConversationUiState(messages = list, searching = true, query = " library ")
        assertEquals(listOf("3", "1"), state.matches.map { it.id })
        assertTrue(ConversationUiState(messages = list, searching = false, query = "library").matches.isEmpty())
        assertTrue(ConversationUiState(messages = list, searching = true, query = "  ").matches.isEmpty())
    }
}
