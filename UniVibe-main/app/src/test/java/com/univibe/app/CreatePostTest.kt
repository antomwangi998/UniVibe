package com.univibe.app

import com.univibe.app.data.model.NewPost
import com.univibe.app.data.model.FeedFilter
import com.univibe.app.data.model.PostAudience
import com.univibe.app.data.repository.MockFeedRepository
import com.univibe.app.features.post.CreatePostUiState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CreatePostTest {
    @Test
    fun emptyPostCannotBeSent() {
        assertFalse(CreatePostUiState().canPost)
        assertFalse(CreatePostUiState(text = "   ").canPost)
    }

    @Test
    fun textOrPhotoMakesItSendable() {
        assertTrue(CreatePostUiState(text = "Hello").canPost)
        assertTrue(CreatePostUiState(photos = listOf("/p/1.jpg")).canPost)
    }

    @Test
    fun tooLongOrBusyBlocksSending() {
        assertFalse(CreatePostUiState(text = "a".repeat(281)).canPost)
        assertTrue(CreatePostUiState(text = "a".repeat(280)).canPost)
        assertFalse(CreatePostUiState(text = "Hi", publishing = true).canPost)
        assertFalse(CreatePostUiState(text = "Hi", importing = true).canPost)
    }

    @Test
    fun photoLimitBlocksAddingMore() {
        assertTrue(CreatePostUiState(photos = listOf("a", "b", "c")).canAddPhoto)
        assertFalse(CreatePostUiState(photos = listOf("a", "b", "c", "d")).canAddPhoto)
    }

    @Test
    fun publishedPostIsNotDirty() {
        assertTrue(CreatePostUiState(text = "draft").isDirty)
        assertFalse(CreatePostUiState(text = "draft", published = true).isDirty)
    }

    @Test
    fun mockServerRejectsInvalidPosts() = runTest {
        val repo = MockFeedRepository()
        for (bad in listOf(
            NewPost("  ", emptyList(), PostAudience.Campus),
            NewPost("a".repeat(281), emptyList(), PostAudience.Campus),
            NewPost("hi", List(5) { "/p/$it.jpg" }, PostAudience.Campus),
        )) {
            try { repo.publishPost(bad); fail("should have been rejected") } catch (e: IllegalArgumentException) { }
        }
    }

    @Test
    fun publishedPostAppearsFirstWithPhotosAndAudience() = runTest {
        val repo = MockFeedRepository()
        val audience = PostAudience.Community("tech", "Tech Club")
        val created = repo.publishPost(NewPost(" Hello campus ", listOf("/p/1.jpg", "/p/2.jpg"), audience))
        assertEquals("Hello campus", created.text)
        assertEquals(listOf("/p/1.jpg", "/p/2.jpg"), created.imagePaths)
        assertEquals(audience, created.audience)
        assertEquals(created.id, repo.getPosts(FeedFilter.Campus).first().id)
    }
}
