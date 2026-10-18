package com.univibe.app

import com.univibe.app.data.model.FeedFilter
import com.univibe.app.data.model.Poll
import com.univibe.app.data.model.PollOption
import com.univibe.app.data.model.PostLimits
import com.univibe.app.data.repository.MockFeedRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PostFeaturesTest {
    private suspend fun refused(block: suspend () -> Unit) {
        try { block(); fail("Expected the repository to refuse this") } catch (e: IllegalArgumentException) { }
    }

    // ---- polls
    @Test
    fun pollIsCreatedWithZeroVotes() = runTest {
        val repo = MockFeedRepository()
        val post = repo.createPost("Best study spot?", hasMedia = false, pollOptions = listOf(" Library ", "Cafe"))
        val poll = post.poll!!
        assertEquals(listOf("Library", "Cafe"), poll.options.map { it.text })
        assertEquals(0, poll.totalVotes)
        assertNull(poll.myVote)
    }

    @Test
    fun badPollsAreRefused() = runTest {
        val repo = MockFeedRepository()
        refused { repo.createPost("q", false, pollOptions = listOf("Only one")) }
        refused { repo.createPost("q", false, pollOptions = listOf("a", "b", "c", "d", "e")) }
        refused { repo.createPost("q", false, pollOptions = listOf("a", "  ")) }
        refused { repo.createPost("q", false, pollOptions = listOf("Same", "same")) }
        refused { repo.createPost("q", false, pollOptions = listOf("a", "x".repeat(PostLimits.MAX_POLL_OPTION_LENGTH + 1))) }
        refused { repo.createPost("q", hasMedia = true, pollOptions = listOf("a", "b")) }
    }

    @Test
    fun oneVotePerPoll() = runTest {
        val repo = MockFeedRepository()
        val post = repo.createPost("Pick one", false, pollOptions = listOf("A", "B"))
        val voted = repo.vote(post.id, 1)
        assertEquals(1, voted.poll!!.myVote)
        assertEquals(listOf(0, 1), voted.poll!!.options.map { it.votes })
        refused { repo.vote(post.id, 0) } // already voted
    }

    @Test
    fun voteNeedsAPollAndAValidOption() = runTest {
        val repo = MockFeedRepository()
        val plain = repo.createPost("No poll here", false)
        refused { repo.vote(plain.id, 0) }
        val poll = repo.createPost("Pick", false, pollOptions = listOf("A", "B"))
        refused { repo.vote(poll.id, 2) }
        refused { repo.vote(poll.id, -1) }
    }

    @Test
    fun pollPercentagesUseTotalVotes() {
        val poll = Poll(listOf(PollOption("A", 3), PollOption("B", 1)))
        assertEquals(4, poll.totalVotes)
    }

    // ---- video
    @Test
    fun videoPathIsKeptOnThePost() = runTest {
        val repo = MockFeedRepository()
        val post = repo.createPost("Clip", hasMedia = true, videoPath = "/files/videos/post_1.mp4")
        assertEquals("/files/videos/post_1.mp4", post.videoPath)
        assertNull(post.mediaAccent) // a real video replaces the placeholder tile
        assertTrue(post.hasAttachment)
    }

    // ---- edit and delete
    @Test
    fun ownPostCanBeEdited() = runTest {
        val repo = MockFeedRepository()
        val post = repo.createPost("typo hre", false)
        val edited = repo.editPost(post.id, "  typo here ")
        assertEquals("typo here", edited.text)
        assertTrue(edited.edited)
        assertEquals("typo here", repo.getPosts(FeedFilter.Campus).first { it.id == post.id }.text)
    }

    @Test
    fun editRulesAreEnforced() = runTest {
        val repo = MockFeedRepository()
        val post = repo.createPost("hello", false)
        refused { repo.editPost(post.id, "   ") } // text-only post can't be emptied
        refused { repo.editPost(post.id, "a".repeat(PostLimits.MAX_TEXT + 1)) }
        val others = repo.getPosts(FeedFilter.Campus).first { it.author.id != "s1" }
        refused { repo.editPost(others.id, "hijacked") }
        refused { repo.editPost("missing", "x") }
    }

    @Test
    fun mediaPostMayHaveEmptyText() = runTest {
        val repo = MockFeedRepository()
        val post = repo.createPost("caption", hasMedia = true, videoPath = "/files/videos/a.mp4")
        assertEquals("", repo.editPost(post.id, "").text)
    }

    @Test
    fun ownPostCanBeDeletedButNotSomeoneElses() = runTest {
        val repo = MockFeedRepository()
        val post = repo.createPost("delete me", false)
        repo.setSaved(post.id, true)
        repo.deletePost(post.id)
        assertTrue(repo.getPosts(FeedFilter.Campus).none { it.id == post.id })
        assertTrue(repo.getSavedPosts().none { it.id == post.id })
        val others = repo.getPosts(FeedFilter.Campus).first { it.author.id != "s1" }
        refused { repo.deletePost(others.id) }
        assertNotNull(repo.getPosts(FeedFilter.Campus).firstOrNull { it.id == others.id })
    }

    // ---- sharing
    @Test
    fun sharingCountsOnce() = runTest {
        val repo = MockFeedRepository()
        val post = repo.createPost("share me", false)
        repo.recordShare(post.id)
        assertEquals(1, repo.getPosts(FeedFilter.Campus).first { it.id == post.id }.shareCount)
    }

    @Test
    fun seededPollPostExistsAndIsOpenToVoting() = runTest {
        val seeded = MockFeedRepository().getPosts(FeedFilter.Campus).first { it.poll != null }
        assertFalse(seeded.poll!!.myVote != null)
        assertTrue(seeded.poll!!.totalVotes > 0)
    }
}
