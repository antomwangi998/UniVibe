package com.univibe.app

import com.univibe.app.data.model.CommunityRole
import com.univibe.app.data.model.ProfileUpdate
import com.univibe.app.data.model.Student
import com.univibe.app.data.repository.MockData
import com.univibe.app.data.repository.MockProfileRepository
import com.univibe.app.data.repository.MockStore
import com.univibe.app.data.profile.InMemoryProfileLocalStore
import com.univibe.app.domain.auth.AuthUser
import com.univibe.app.domain.auth.ProfileEdit
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AccountProfileTest {
    private val account = AuthUser(
        id = "server-uuid", fullName = "Wanjiru Kamau", email = "411127-2026@student.cuk.ac.ke",
        universityId = "cuk", universityName = "The Co-operative University of Kenya",
        emailVerified = true, studentVerified = true,
        course = "Law", interests = listOf("Reading", "Debate"), bio = "Future advocate.",
        handle = "wanjirukamau", yearOfStudy = "",
    )

    /** MockData is shared by every test, so always put the sample student back. */
    @After fun resetIdentity() = MockData.applyAccount(null)

    @Test fun theRealAccountReplacesTheSampleStudent() = runTest {
        val repo = MockProfileRepository(MockStore(demo = false), InMemoryProfileLocalStore(), account = { account })
        val profile = repo.getMyProfile()
        assertEquals("Wanjiru Kamau", profile.student.name)
        assertEquals("@wanjirukamau", profile.student.handle)
        assertEquals("Law", profile.student.course)
        assertEquals("The Co-operative University of Kenya", profile.student.university)
        assertEquals(listOf("Reading", "Debate"), profile.student.interests)
        assertEquals("Future advocate.", profile.bio)
        assertTrue(profile.student.verified)
    }

    @Test fun aNewAccountStartsWithNothing() = runTest {
        val repo = MockProfileRepository(MockStore(demo = false), InMemoryProfileLocalStore(), account = { account })
        val profile = repo.getMyProfile()
        assertEquals(0, profile.posts)
        assertEquals(0, profile.followers)
        assertEquals(0, profile.following)
        assertEquals(0, profile.savedPosts)
        assertTrue(profile.groups.isEmpty())
    }

    @Test fun noSampleClubsOrFollowsForARealAccount() {
        val store = MockStore(demo = false)
        assertTrue(store.following.isEmpty())
        assertTrue(store.communities.all { it.role == CommunityRole.None })
        // the demo store still has them
        assertFalse(MockStore().following.isEmpty())
    }

    @Test fun postsAndCommentsAreAuthoredByTheRealStudent() {
        MockData.applyAccount(account)
        assertEquals("Wanjiru Kamau", MockData.aisha.name)
        assertEquals("s1", MockData.aisha.id) // the mock world's id for "me" does not change
        assertTrue(MockData.allStudents.any { it.name == "Wanjiru Kamau" })
        assertTrue(MockData.allStudents.none { it.name == "Aisha Njoki" })
        MockData.applyAccount(null)
        assertEquals("Aisha Njoki", MockData.aisha.name)
    }

    @Test fun handleFallsBackToTheNameWhenTheServerSentNone() {
        MockData.applyAccount(account.copy(handle = ""))
        assertEquals("@wanjirukamau", MockData.aisha.handle)
    }

    @Test fun editsGoToTheServerNotTheDevice() = runTest {
        var saved: ProfileEdit? = null
        val repo = MockProfileRepository(
            MockStore(demo = false), InMemoryProfileLocalStore(), account = { account },
            saveAccount = { edit -> saved = edit; true },
        )
        repo.updateProfile(ProfileUpdate("Wanjiru K", "New bio", "Law", "2nd Year", listOf("Music"), null, null))
        assertEquals(ProfileEdit("Wanjiru K", "Law", "2nd Year", listOf("Music"), "New bio"), saved)
    }

    @Test fun aRefusedSaveIsReportedSoTheScreenCanShowAnError() = runTest {
        val repo = MockProfileRepository(
            MockStore(demo = false), InMemoryProfileLocalStore(), account = { account }, saveAccount = { false },
        )
        try {
            repo.updateProfile(ProfileUpdate("Wanjiru K", "", "Law", "", emptyList(), null, null))
            fail("expected the save to be reported as failed")
        } catch (e: IllegalStateException) { /* expected */ }
    }

    @Test fun courseLineSkipsABlankYear() {
        fun student(course: String, year: String) =
            Student("x", "N", "@n", 0, course, year, "U", emptyList(), verified = false)
        assertEquals("Law", student("Law", "").courseLine)
        assertEquals("Law · 2nd Year", student("Law", "2nd Year").courseLine)
        assertEquals("2nd Year", student("", "2nd Year").courseLine)
        assertEquals("", student("", "").courseLine)
    }
}
