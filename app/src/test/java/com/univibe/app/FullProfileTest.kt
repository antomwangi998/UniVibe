package com.univibe.app

import com.univibe.app.data.repository.MockDiscoverRepository
import com.univibe.app.data.repository.MockProfileRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FullProfileTest {
    @Test
    fun knownStudentHasProfileAndComputedCommonInterests() = runTest {
        val p = MockProfileRepository().getStudentProfile("s2")
        assertNotNull(p)
        assertTrue(p!!.bio.isNotBlank())
        assertTrue(p.student.shared.all { it in p.student.interests })
        assertTrue(p.student.sameCourse)
    }

    @Test
    fun unknownOrMaliciousIdReturnsNull() = runTest {
        val repo = MockProfileRepository()
        assertNull(repo.getStudentProfile("nope"))
        assertNull(repo.getStudentProfile("../../x"))
    }

    @Test
    fun decidedStudentLeavesTheDeck() = runTest {
        val repo = MockDiscoverRepository()
        val first = repo.getCandidates().first()
        repo.recordDecision(first.id, liked = false)
        assertFalse(repo.getCandidates().any { it.id == first.id })
    }

    @Test
    fun communityIdsPointToRealCommunities() = runTest {
        val ids = com.univibe.app.data.repository.MockData.studentExtras.values.flatMap { it.communityIds }.toSet()
        val known = com.univibe.app.data.repository.MockCommunityRepository().getCommunities().map { it.id }.toSet()
        assertTrue(known.containsAll(ids))
        assertEquals(emptySet<String>(), ids - known)
    }
}
