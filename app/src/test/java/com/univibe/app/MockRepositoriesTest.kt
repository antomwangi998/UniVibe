package com.univibe.app

import com.univibe.app.data.model.FeedFilter
import com.univibe.app.data.repository.MockEventRepository
import com.univibe.app.data.repository.MockFeedRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
    fun likeStateIsRemembered() = runTest {
        val repo = MockFeedRepository()
        val id = repo.getPosts(FeedFilter.Campus).first().id
        repo.setLiked(id, true)
        assertTrue(repo.getPosts(FeedFilter.Campus).first { it.id == id }.likedByMe)
        repo.setLiked(id, false)
        assertEquals(false, repo.getPosts(FeedFilter.Campus).first { it.id == id }.likedByMe)
    }

    @Test
    fun rsvpCanBeSetAndCancelled() = runTest {
        val repo = MockEventRepository()
        val id = repo.getEvents().first().id
        repo.setRsvp(id, true)
        assertTrue(repo.getEvents().first { it.id == id }.rsvped)
        repo.setRsvp(id, false)
        assertEquals(false, repo.getEvents().first { it.id == id }.rsvped)
    }
}
