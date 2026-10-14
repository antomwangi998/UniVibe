package com.univibe.app

import com.univibe.app.data.model.CommunityPrivacy
import com.univibe.app.data.model.CommunityRole
import com.univibe.app.data.repository.MockCommunityRepository
import com.univibe.app.data.repository.MockData
import com.univibe.app.data.repository.MockStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CommunityManagementTest {
    private val store = MockStore()
    private val repo = MockCommunityRepository(store)

    private suspend fun assertRefused(block: suspend () -> Unit) {
        try { block(); fail("should have been refused") } catch (e: IllegalStateException) { } catch (e: IllegalArgumentException) { }
    }

    private suspend fun create(name: String = "Chess Club", privacy: CommunityPrivacy = CommunityPrivacy.Public) =
        repo.createCommunity(name, "Weekly games and friendly tournaments.", "Social", 2, listOf("Be kind", " "), privacy)

    @Test
    fun creatorBecomesOwnerAndMemberAndBlankRulesAreDropped() = runTest {
        val c = repo.getCommunity(create())!!
        assertEquals(CommunityRole.Owner, c.role)
        assertEquals(1, c.members)
        assertEquals(listOf("Be kind"), c.rules)
        assertEquals(listOf(MockData.aisha.name), c.moderators)
        assertEquals(c.id, repo.getCommunities().first().id)
    }

    @Test
    fun invalidCommunitiesAreRefused() = runTest {
        assertRefused { repo.createCommunity("ab", "A long enough description.", "Social", 0, emptyList(), CommunityPrivacy.Public) }
        assertRefused { repo.createCommunity("tech club", "A long enough description.", "Social", 0, emptyList(), CommunityPrivacy.Public) } // duplicate, any case
        assertRefused { repo.createCommunity("Valid Name", "short", "Social", 0, emptyList(), CommunityPrivacy.Public) }
        assertRefused { repo.createCommunity("Valid Name", "A long enough description.", "Nonsense", 0, emptyList(), CommunityPrivacy.Public) }
        assertRefused { repo.createCommunity("Valid Name", "A long enough description.", "Social", 9, emptyList(), CommunityPrivacy.Public) }
        assertRefused { repo.createCommunity("Valid Name", "A long enough description.", "Social", 0, List(9) { "rule $it" }, CommunityPrivacy.Public) }
        assertRefused { repo.createCommunity("Valid Name", "A long enough description.", "Social", 0, listOf("x".repeat(121)), CommunityPrivacy.Public) }
    }

    @Test
    fun youCanOwnAtMostFive() = runTest {
        repeat(5) { create("Club number $it") }
        assertRefused { create("One too many") }
    }

    @Test
    fun ownerCannotLeaveButCanDelete() = runTest {
        val id = create()
        assertRefused { repo.setMembership(id, false) }
        repo.deleteCommunity(id)
        assertNull(repo.getCommunity(id))
    }

    @Test
    fun deletingACommunityRemovesItsPosts() = runTest {
        val id = create()
        store.posts.add(MockData.posts.first().copy(id = "x1", audience = com.univibe.app.data.model.PostAudience.Community(id, "Chess Club")))
        assertEquals(1, repo.getPosts(id).size)
        repo.deleteCommunity(id)
        assertTrue(store.posts.none { it.id == "x1" })
    }

    @Test
    fun onlyTheOwnerCanDeleteOrEdit() = runTest {
        assertRefused { repo.deleteCommunity("tech") }  // I only moderate it
        assertRefused { repo.updateCommunity("tech", "A long enough description.", "Academic", 1, emptyList(), CommunityPrivacy.Public) }
    }

    @Test
    fun privateCommunityNeedsApproval() = runTest {
        repo.setMembership("tech", false) // I moderate it, so I may leave
        assertEquals(CommunityRole.None, repo.getCommunity("tech")!!.role)
        assertRefused { repo.getMembers("tech") }
        assertTrue(repo.getPosts("tech").isEmpty())
        repo.setMembership("tech", true)
        val requested = repo.getCommunity("tech")!!
        assertTrue(requested.joinRequested)
        assertEquals(CommunityRole.None, requested.role)
        repo.setMembership("tech", false) // withdraw
        assertFalse(repo.getCommunity("tech")!!.joinRequested)
    }

    @Test
    fun moderatorsApproveAndDeclineRequests() = runTest {
        val before = repo.getCommunity("tech")!!.members
        assertEquals(2, repo.getJoinRequests("tech").size)
        repo.resolveJoinRequest("tech", MockData.amina.id, approve = true)
        repo.resolveJoinRequest("tech", MockData.kevin.id, approve = false)
        assertTrue(repo.getJoinRequests("tech").isEmpty())
        assertEquals(before + 1, repo.getCommunity("tech")!!.members)
        assertTrue(repo.getMembers("tech").any { it.student.id == MockData.amina.id })
        assertTrue(repo.getMembers("tech").none { it.student.id == MockData.kevin.id })
    }

    @Test
    fun plainMembersCannotModerate() = runTest {
        assertRefused { repo.getJoinRequests("photo") } // I'm only a member there
        assertRefused { repo.removeMember("photo", MockData.grace.id) }
        assertRefused { repo.getReports("photo") }
    }

    @Test
    fun moderatorsRemoveMembersButNotModeratorsOrThemselves() = runTest {
        val members = repo.getMembers("tech")
        val plain = members.first { it.role == CommunityRole.Member }
        val moderator = members.first { it.role == CommunityRole.Moderator && !it.isMe }
        assertRefused { repo.removeMember("tech", moderator.student.id) }
        assertRefused { repo.removeMember("tech", MockData.aisha.id) }
        repo.removeMember("tech", plain.student.id)
        assertTrue(repo.getMembers("tech").none { it.student.id == plain.student.id })
    }

    @Test
    fun switchingPrivateToPublicApprovesEveryoneWaiting() = runTest {
        val id = create("Secret Society", CommunityPrivacy.Private)
        store.joinRequests[id] = mutableListOf(MockData.amina, MockData.kevin)
        repo.updateCommunity(id, "A long enough description.", "Social", 2, emptyList(), CommunityPrivacy.Public)
        assertEquals(3, repo.getMembers(id).size)
        assertEquals(3, repo.getCommunity(id)!!.members)
    }

    @Test
    fun onlyTheOwnerChangesModerators() = runTest {
        val id = create()
        store.membersOf(id) += com.univibe.app.data.model.CommunityMember(MockData.grace, CommunityRole.Member)
        repo.setMemberRole(id, MockData.grace.id, CommunityRole.Moderator)
        assertTrue(repo.getCommunity(id)!!.moderators.contains(MockData.grace.name))
        assertRefused { repo.setMemberRole(id, MockData.aisha.id, CommunityRole.Member) } // not yourself
        assertRefused { repo.setMemberRole("tech", MockData.grace.id, CommunityRole.Moderator) } // I'm not the owner there
    }
}
