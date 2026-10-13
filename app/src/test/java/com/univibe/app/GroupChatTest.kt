package com.univibe.app

import com.univibe.app.data.model.GroupRole
import com.univibe.app.data.repository.MockData
import com.univibe.app.data.repository.MockGroupRepository
import com.univibe.app.data.repository.MockMessageRepository
import com.univibe.app.data.repository.MockStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GroupChatTest {
    // c2: me = Admin, Kevin = Owner. c4: me = Member. c6: me = Owner. c7: me = Member, admins-only messaging.
    private fun repo(store: MockStore = MockStore()) = MockGroupRepository(store)

    private suspend fun assertRefused(block: suspend () -> Unit) {
        try { block(); fail("should have been refused") } catch (e: IllegalStateException) { } catch (e: IllegalArgumentException) { }
    }

    @Test
    fun knownGroupLoadsAndUnknownIdIsNull() = runTest {
        val r = repo()
        assertEquals(5, r.getGroup("c2")!!.members.size)
        assertNull(r.getGroup("c1")) // a 1:1 chat is not a group
        assertNull(r.getGroup("nope"))
    }

    @Test
    fun rolesDecideWhatYouMayDo() = runTest {
        val r = repo()
        assertTrue(r.getGroup("c2")!!.iAmAdmin)
        assertFalse(r.getGroup("c7")!!.iAmAdmin)
        assertFalse(r.getGroup("c7")!!.canEditInfo)
        assertTrue(r.getGroup("c6")!!.myRole == GroupRole.Owner)
    }

    @Test
    fun memberCannotAddOrEditWhenAdminsOnly() = runTest {
        val r = repo()
        assertRefused { r.getAddable("c7") }
        assertRefused { r.addMembers("c7", listOf("s5")) }
        assertRefused { r.updateInfo("c7", "New name", "") }
    }

    @Test
    fun adminCanAddMembersAndDuplicatesAreIgnored() = runTest {
        val r = repo()
        val before = r.getGroup("c6")!!.members.size
        val addable = r.getAddable("c6")
        assertTrue(addable.none { a -> r.getGroup("c6")!!.members.any { it.student.id == a.id } })
        r.addMembers("c6", listOf("s5", "s5", "unknown", MockData.tech.id))
        assertEquals(before, r.getGroup("c6")!!.members.size) // all four were already members, unknown, or not a person
    }

    @Test
    fun cannotRemoveOwnerOrYourselfAndAdminsCannotRemoveAdmins() = runTest {
        val r = repo()
        assertRefused { r.removeMember("c2", "s4") }              // owner
        assertRefused { r.removeMember("c2", MockData.aisha.id) } // yourself: use leave
        val owner = repo()
        owner.setRole("c6", "s3", GroupRole.Admin)
        assertEquals(GroupRole.Admin, owner.getGroup("c6")!!.members.first { it.student.id == "s3" }.role)
        owner.removeMember("c6", "s3") // the owner may remove an admin
        assertTrue(owner.getGroup("c6")!!.members.none { it.student.id == "s3" })
    }

    @Test
    fun onlyTheOwnerChangesAdmins() = runTest {
        val r = repo()
        assertRefused { r.setRole("c2", "s3", GroupRole.Admin) } // me is Admin in c2, not owner
        assertRefused { repo().setRole("c6", "s3", GroupRole.Owner) }
    }

    @Test
    fun editInfoValidatesAndRenamesTheChat() = runTest {
        val store = MockStore()
        val r = repo(store)
        assertRefused { r.updateInfo("c6", "   ", "") }
        assertRefused { r.updateInfo("c6", "x".repeat(41), "") }
        assertRefused { r.updateInfo("c6", "ok", "d".repeat(201)) }
        r.updateInfo("c6", " Events Crew ", "Planning")
        assertEquals("Events Crew", r.getGroup("c6")!!.name)
        assertEquals("Events Crew", store.chats.first { it.id == "c6" }.title)
    }

    @Test
    fun leavingPassesOwnershipAndHidesTheChat() = runTest {
        val store = MockStore()
        val r = repo(store)
        r.leave("c6") // I am the owner
        assertTrue("c6" in store.archived)
        assertNull(r.getGroup("c6")) // no longer a member
        val heir = store.groups["c6"]!!.members.first { it.role == GroupRole.Owner }
        assertEquals("s4", heir.student.id) // Kevin was the admin
    }

    @Test
    fun adminsOnlyGroupBlocksMessagesFromMembers() = runTest {
        val store = MockStore()
        val messages = MockMessageRepository(store, autoReply = false)
        assertRefused { messages.send("c7", "hello") }
        messages.send("c2", "hello") // c2 allows everyone
    }
}
