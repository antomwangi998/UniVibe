package com.univibe.app

import com.univibe.app.data.campus.CampusInfo
import com.univibe.app.data.campus.CampusMenu
import com.univibe.app.data.campus.CampusScope
import com.univibe.app.data.campus.CampusScopeStore
import com.univibe.app.data.campus.withCampusScope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CampusScopeTest {
    private val ku = CampusInfo("ku", "Kenyatta University", "KU", 3)
    private val lakeside = CampusInfo("lakeside", "Lakeside University", "Lakeside", 120)

    @Before fun fresh() = CampusScopeStore.reset()
    @After fun clean() = CampusScopeStore.reset()

    @Test
    fun homeAddsNothingExceptWhereEventsNeedItSaidOutLoud() {
        assertEquals("/posts?filter=campus", "/posts?filter=campus".withCampusScope(CampusScope.Home))
        assertEquals("/api/v1/events?scope=upcoming&campusScope=home", "/api/v1/events?scope=upcoming".withCampusScope(CampusScope.Home, explicitHome = true))
    }

    @Test
    fun otherScopesAreAppendedWithTheRightSeparator() {
        assertEquals("/posts?campusScope=all", "/posts".withCampusScope(CampusScope.All))
        assertEquals("/posts?filter=campus&campusScope=lakeside", "/posts?filter=campus".withCampusScope(CampusScope.One("lakeside", "Lakeside University")))
    }

    @Test
    fun aQuietHomeCampusOpensOnAllCampusesOnceAndSaysSo() {
        CampusScopeStore.applyMenu(CampusMenu(home = ku, others = listOf(lakeside), homeQuiet = true))
        assertEquals(CampusScope.All, CampusScopeStore.scope.value)
        assertTrue(CampusScopeStore.autoOpened.value)
        // The student switching back is respected, and a later menu refresh does not override it.
        CampusScopeStore.select(CampusScope.Home)
        assertFalse(CampusScopeStore.autoOpened.value)
        CampusScopeStore.applyMenu(CampusMenu(home = ku, others = listOf(lakeside), homeQuiet = true))
        assertEquals(CampusScope.Home, CampusScopeStore.scope.value)
    }

    @Test
    fun aBusyHomeCampusOrNoOtherCampusesStaysHome() {
        CampusScopeStore.applyMenu(CampusMenu(home = lakeside, others = listOf(ku), homeQuiet = false))
        assertEquals(CampusScope.Home, CampusScopeStore.scope.value)
        CampusScopeStore.reset()
        CampusScopeStore.applyMenu(CampusMenu(home = ku, others = emptyList(), homeQuiet = true))
        assertEquals(CampusScope.Home, CampusScopeStore.scope.value)
    }

    @Test
    fun aCampusThatDisappearsFallsBackToHome() {
        CampusScopeStore.applyMenu(CampusMenu(home = lakeside, others = listOf(ku), homeQuiet = false))
        CampusScopeStore.select(CampusScope.One("ku", "Kenyatta University"))
        CampusScopeStore.applyMenu(CampusMenu(home = lakeside, others = emptyList(), homeQuiet = false))
        assertEquals(CampusScope.Home, CampusScopeStore.scope.value)
    }
}
