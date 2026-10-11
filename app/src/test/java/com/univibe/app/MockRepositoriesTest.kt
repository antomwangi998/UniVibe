package com.univibe.app

import com.univibe.app.data.model.FeedFilter
import com.univibe.app.data.model.RsvpState
import com.univibe.app.data.repository.MockChatRepository
import com.univibe.app.data.repository.MockDiscoverRepository
import com.univibe.app.data.repository.MockEventRepository
import com.univibe.app.data.repository.MockFeedRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MockRepositoriesTest {
    @Test
    fun followingFilterReturnsOnlyFollowedAuthors() = runTest {
        val posts = MockFeedRepository().getPosts(FeedFilter.Following)
        assertTrue(posts.isNotEmpty())
        assertTrue(posts.all { it.fromFollowing })
    }

    @Test
    fun likeAndSaveStateIsRemembered() = runTest {
        val repo = MockFeedRepository()
        val id = repo.getPosts(FeedFilter.Campus).first().id
        repo.setLiked(id, true)
        repo.setSaved(id, true)
        val post = repo.getPosts(FeedFilter.Campus).first { it.id == id }
        assertTrue(post.likedByMe)
        assertTrue(post.savedByMe)
        repo.setLiked(id, false)
        assertFalse(repo.getPosts(FeedFilter.Campus).first { it.id == id }.likedByMe)
    }

    @Test
    fun createdPostAppearsFirst() = runTest {
        val repo = MockFeedRepository()
        val created = repo.createPost("  hello campus  ", hasMedia = false)
        assertEquals("hello campus", created.text)
        assertEquals(created.id, repo.getPosts(FeedFilter.Campus).first().id)
    }

    @Test
    fun rsvpStatesPersist() = runTest {
        val repo = MockEventRepository()
        val id = repo.getEvents().first { !it.isPast }.id
        repo.setRsvp(id, RsvpState.Interested)
        assertEquals(RsvpState.Interested, repo.getEvents().first { it.id == id }.rsvp)
        repo.setRsvp(id, RsvpState.None)
        assertEquals(RsvpState.None, repo.getEvents().first { it.id == id }.rsvp)
    }

    @Test
    fun archivedChatsDisappear() = runTest {
        val repo = MockChatRepository()
        val id = repo.getChats().first().id
        repo.archive(id)
        assertTrue(repo.getChats().none { it.id == id })
    }

    @Test
    fun discoverCandidatesCarrySharedInterests() = runTest {
        val candidates = MockDiscoverRepository().getCandidates()
        assertTrue(candidates.any { it.shared.isNotEmpty() })
        assertTrue(candidates.any { it.sameCourse })
    }
}
