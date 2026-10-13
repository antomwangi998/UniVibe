package com.univibe.app

import com.univibe.app.data.model.Comment
import com.univibe.app.data.model.Reaction
import com.univibe.app.data.model.ReplyRef
import com.univibe.app.data.repository.MockCommentRepository
import com.univibe.app.data.repository.MockData
import com.univibe.app.data.repository.MockMessageRepository
import com.univibe.app.data.repository.MockStore
import com.univibe.app.features.comments.CommentSort
import com.univibe.app.features.comments.CommentsUiState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ReplyReactionTest {
    private fun messages(store: MockStore = MockStore()) = MockMessageRepository(store, autoReply = false)

    @Test
    fun replyKeepsItsQuote() = runTest {
        val repo = messages()
        val original = repo.messages("c1").first().first()
        repo.send("c1", "Yes!", null, ReplyRef(original.id, original.senderName, original.text))
        val sent = repo.messages("c1").first().last()
        assertEquals(original.id, sent.replyTo?.messageId)
    }

    @Test
    fun replyToUnknownMessageIsRejected() = runTest {
        try { messages().send("c1", "Hi", null, ReplyRef("ghost", "Nobody", "x")); fail() } catch (e: IllegalArgumentException) { }
    }

    @Test
    fun oneReactionPerPersonAndSwitchingMovesTheCount() = runTest {
        val repo = messages()
        val id = repo.messages("c1").first().first().id
        repo.react("c1", id, Reaction.Heart)
        var m = repo.messages("c1").first().first { it.id == id }
        assertEquals(Reaction.Heart, m.myReaction)
        assertEquals(1, m.reactions[Reaction.Heart])
        repo.react("c1", id, Reaction.Fire)
        m = repo.messages("c1").first().first { it.id == id }
        assertNull(m.reactions[Reaction.Heart])
        assertEquals(1, m.reactions[Reaction.Fire])
        repo.react("c1", id, null)
        m = repo.messages("c1").first().first { it.id == id }
        assertTrue(m.reactions.isEmpty())
        assertNull(m.myReaction)
    }

    @Test
    fun clearChatEmptiesTheConversation() = runTest {
        val repo = messages()
        repo.clearChat("c1")
        assertTrue(repo.messages("c1").first().isEmpty())
    }

    @Test
    fun replyToAReplyLandsInTheSameThreadWithAMention() = runTest {
        val repo = MockCommentRepository(MockStore())
        val reply = repo.addComment("p1", "Thanks!", "cm5") // cm5 is itself a reply under cm1
        assertEquals("cm1", reply.parentId)
        assertEquals(MockData.tech.handle, reply.replyToHandle)
    }

    @Test
    fun invalidCommentsAreRejected() = runTest {
        val repo = MockCommentRepository(MockStore())
        for (bad in listOf<suspend () -> Unit>({ repo.addComment("p1", "  ") }, { repo.addComment("p1", "x".repeat(281)) }, { repo.addComment("p1", "ok", "ghost") })) {
            try { bad(); fail() } catch (e: IllegalArgumentException) { }
        }
    }

    @Test
    fun deletingAThreadRemovesItsRepliesAndAdjustsNothingElse() = runTest {
        val store = MockStore()
        val repo = MockCommentRepository(store)
        val mine = repo.addComment("p1", "Top level")
        repo.addComment("p1", "Reply", mine.id)
        val before = repo.getComments("p1").size
        repo.deleteComment(mine.id)
        assertEquals(before - 2, repo.getComments("p1").size)
    }

    @Test
    fun postAuthorIsFlagged() = runTest {
        val comments = MockCommentRepository(MockStore()).getComments("p1")
        assertTrue(comments.first { it.id == "cm5" }.isPostAuthor) // Tech Club wrote p1
        assertFalse(comments.first { it.id == "cm1" }.isPostAuthor)
    }

    @Test
    fun threadsGroupRepliesAndSortByLikesOrNewest() {
        val a = Comment("a", "p", MockData.brian, "first", "2h", 1)
        val b = Comment("b", "p", MockData.grace, "second", "1h", 9)
        val r = Comment("r", "p", MockData.kevin, "reply", "30m", 0, parentId = "a")
        val orphan = Comment("o", "p", MockData.kevin, "orphan", "5m", 0, parentId = "gone")
        val top = CommentsUiState(comments = listOf(a, b, r, orphan), sort = CommentSort.Top).threads
        assertEquals(listOf("b", "a"), top.map { it.parent.id })
        assertEquals(listOf("r"), top.first { it.parent.id == "a" }.replies.map { it.id })
        val newest = CommentsUiState(comments = listOf(a, b, r, orphan), sort = CommentSort.Newest).threads
        assertEquals(listOf("b", "a"), newest.map { it.parent.id })
    }
}
