package com.univibe.app

import com.univibe.app.data.model.CommunityRole
import com.univibe.app.data.model.FeedFilter
import com.univibe.app.data.model.ProfileUpdate
import com.univibe.app.data.profile.InMemoryProfileLocalStore
import com.univibe.app.data.repository.MockChatRepository
import com.univibe.app.data.repository.MockCommentRepository
import com.univibe.app.data.repository.MockCommunityRepository
import com.univibe.app.data.repository.MockData
import com.univibe.app.data.repository.MockFeedRepository
import com.univibe.app.data.repository.MockMessageRepository
import com.univibe.app.data.repository.MockProfileRepository
import com.univibe.app.data.repository.MockStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SocialRepositoriesTest {
    private val store = MockStore()

    @Test fun commentsUpdatePostCountAndCanBeDeleted() = runTest {
        val feed = MockFeedRepository(store); val comments = MockCommentRepository(store)
        val before = feed.getPosts(FeedFilter.Campus).first { it.id == "p4" }.commentCount
        val c = comments.addComment("p4", "  Nice one!  ")
        assertEquals("Nice one!", c.text)
        assertEquals(before + 1, feed.getPosts(FeedFilter.Campus).first { it.id == "p4" }.commentCount)
        comments.deleteComment(c.id)
        assertEquals(before, feed.getPosts(FeedFilter.Campus).first { it.id == "p4" }.commentCount)
    }

    @Test fun commentLikesPersist() = runTest {
        val comments = MockCommentRepository(store)
        comments.setLiked("cm1", true)
        assertTrue(comments.getComments("p1").first { it.id == "cm1" }.likedByMe)
    }

    @Test fun sendingAMessageAppendsAndUpdatesChatPreview() = runTest {
        val messages = MockMessageRepository(store, autoReply = false); val chats = MockChatRepository(store)
        messages.send("c3", "See you!")
        assertEquals("See you!", messages.messages("c3").first().last().text)
        assertTrue(chats.getChat("c3")!!.lastMessage.contains("See you!"))
        assertEquals(0, chats.getChat("c3")!!.unread)
    }

    @Test fun blankMessagesAreIgnored() = runTest {
        val messages = MockMessageRepository(store, autoReply = false)
        val before = messages.messages("c3").first().size
        messages.send("c3", "   ")
        assertEquals(before, messages.messages("c3").first().size)
    }

    @Test fun onlyOwnMessagesCanBeDeleted() = runTest {
        val messages = MockMessageRepository(store, autoReply = false)
        messages.send("c3", "oops")
        val mine = messages.messages("c3").first().last { it.isMine }
        messages.deleteMessage("c3", mine.id)
        assertFalse(messages.messages("c3").first().any { it.id == mine.id })
        messages.deleteMessage("c3", "c3-1") // someone else's seeded message
        assertTrue(messages.messages("c3").first().any { it.id == "c3-1" })
    }

    @Test fun openChatReusesExistingOrCreatesNew() = runTest {
        val messages = MockMessageRepository(store, autoReply = false); val chats = MockChatRepository(store)
        assertEquals("c3", messages.openChatWith(MockData.brian))
        val id = messages.openChatWith(MockData.amina)
        assertEquals("c_s5", id)
        assertNotNull(chats.getChat(id))
        assertEquals(id, messages.openChatWith(MockData.amina))
    }

    @Test fun joiningAndLeavingChangesRoleAndMemberCount() = runTest {
        val repo = MockCommunityRepository(store)
        val before = repo.getCommunity("music")!!
        assertEquals(CommunityRole.None, before.role)
        repo.setMembership("music", true)
        assertEquals(CommunityRole.Member, repo.getCommunity("music")!!.role)
        assertEquals(before.members + 1, repo.getCommunity("music")!!.members)
        repo.setMuted("music", true)
        repo.setMembership("music", false)
        val after = repo.getCommunity("music")!!
        assertEquals(CommunityRole.None, after.role)
        assertFalse(after.muted)
        assertEquals(before.members, after.members)
    }

    @Test fun moderatorCanResolveReports() = runTest {
        val repo = MockCommunityRepository(store)
        assertEquals(2, repo.getReports("tech").size)
        repo.resolveReport("tech", "r1", removePost = true)
        assertEquals(listOf("r2"), repo.getReports("tech").map { it.id })
    }

    @Test fun profileEditsAreMergedIntoProfile() = runTest {
        val repo = MockProfileRepository(store, InMemoryProfileLocalStore())
        repo.updateProfile(ProfileUpdate("  Aisha N.  ", "New bio", "Law", "3rd Year", listOf("Music", "Art"), null, null))
        val p = repo.getMyProfile()
        assertEquals("Aisha N.", p.student.name)
        assertEquals("New bio", p.bio)
        assertEquals("3rd Year", p.student.year)
        assertEquals(listOf("Music", "Art"), p.student.interests)
    }

    @Test fun photosCanBeAddedAndRemoved() = runTest {
        val repo = MockProfileRepository(store, InMemoryProfileLocalStore())
        repo.addPhoto("/tmp/not-in-photos-dir/a.jpg")
        assertEquals(listOf("/tmp/not-in-photos-dir/a.jpg"), repo.getMyProfile().photos)
        repo.removePhoto("/tmp/not-in-photos-dir/a.jpg")
        assertTrue(repo.getMyProfile().photos.isEmpty())
    }

    @Test fun followAndUnfollow() = runTest {
        val repo = MockProfileRepository(store, InMemoryProfileLocalStore())
        assertTrue(repo.getFollowing().any { it.student.id == "s2" })
        repo.setFollowing("s2", false)
        assertFalse(repo.getFollowing().any { it.student.id == "s2" })
        assertEquals(false, repo.getFollowers().first { it.student.id == "s2" }.isFollowing)
    }

    @Test fun savedPostsListOnlySavedOnes() = runTest {
        val feed = MockFeedRepository(store)
        feed.setSaved("p2", true)
        assertEquals(listOf("p2"), feed.getSavedPosts().map { it.id })
        feed.setSaved("p2", false)
        assertTrue(feed.getSavedPosts().isEmpty())
    }
}
