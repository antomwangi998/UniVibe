package com.univibe.app.guide

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeStore : GuideProgressStore {
    val saved = mutableMapOf<String, TourProgress>()
    override fun welcomeSeen(userId: String): Flow<Boolean?> = MutableStateFlow(false)
    override suspend fun markWelcomeSeen(userId: String) {}
    override fun progress(userId: String, tourId: String): Flow<TourProgress> = MutableStateFlow(saved[tourId] ?: TourProgress.NotStarted)
    override suspend fun setProgress(userId: String, tourId: String, progress: TourProgress) { saved[tourId] = progress }
}

@OptIn(ExperimentalCoroutinesApi::class)
class GuideControllerTest {
    private val tour = Tutorial(
        "t", "Tour", "d", entryRoute = "home",
        steps = listOf(GuideStep("a", "x", "A", "a"), GuideStep("b", null, "B", "b", route = "chats"), GuideStep("c", null, "C", "c", kind = StepKind.Completion, route = "home")),
    )

    private fun controller(store: FakeStore, scope: TestScope) = GuideController(store, scope, "u1")

    @Test fun walksForwardAndBackAndOnlyCompletesAtTheEnd() = runTest(UnconfinedTestDispatcher()) {
        val store = FakeStore(); val c = controller(store, this)
        val opened = mutableListOf<String>(); c.navigate = { opened += it }
        c.start(tour)
        assertEquals(listOf("home"), opened)
        assertEquals(TourProgress.InProgress(0), store.saved["t"])
        c.next(); assertEquals("b", c.step?.id); assertEquals("chats", opened.last())
        c.back(); assertEquals("a", c.step?.id)
        c.next(); c.next()
        assertTrue(c.isLast); assertFalse(store.saved["t"] == TourProgress.Completed)
        c.next()
        assertNull(c.step); assertEquals(TourProgress.Completed, store.saved["t"]); assertEquals("home", opened.last())
    }

    @Test fun exitKeepsThePlaceAndSkipMarksSkipped() = runTest(UnconfinedTestDispatcher()) {
        val store = FakeStore(); val c = controller(store, this)
        c.start(tour); c.next(); c.exit()
        assertNull(c.step); assertEquals(TourProgress.InProgress(1), store.saved["t"])
        c.start(tour, from = 1); c.skip()
        assertEquals(TourProgress.Skipped, store.saved["t"])
    }

    @Test fun actionStepsOnlyAdvanceForTheirOwnStep() = runTest(UnconfinedTestDispatcher()) {
        val store = FakeStore(); val c = controller(store, this)
        val t = Tutorial("act", "Act", "d", "home", listOf(GuideStep("do", "x", "Do", "d", kind = StepKind.Action), GuideStep("end", null, "End", "e", kind = StepKind.Completion)))
        c.start(t)
        c.actionDone("other"); assertEquals("do", c.step?.id)
        c.actionDone("do"); assertEquals("end", c.step?.id)
    }

    @Test fun startClampsAnOutOfRangeStep() = runTest(UnconfinedTestDispatcher()) {
        val c = controller(FakeStore(), this)
        c.start(tour, from = 99); assertEquals(2, c.stepIndex)
    }

    @Test fun progressEncodingSurvivesGarbage() {
        listOf(TourProgress.NotStarted, TourProgress.InProgress(3), TourProgress.Completed, TourProgress.Skipped).forEach { assertEquals(it, TourProgress.decode(it.encode())) }
        assertEquals(TourProgress.NotStarted, TourProgress.decode("p:-4")); assertEquals(TourProgress.NotStarted, TourProgress.decode("???")); assertEquals(TourProgress.NotStarted, TourProgress.decode(null))
    }

    @Test fun registryRejectsEmptyAndDuplicateSteps() {
        assertTrue(runCatching { TutorialRegistry.register(Tutorial("e", "E", "d", "home", emptyList())) }.isFailure)
        assertTrue(runCatching { TutorialRegistry.register(Tutorial("d", "D", "d", "home", listOf(GuideStep("s", null, "t", "b"), GuideStep("s", null, "t", "b")))) }.isFailure)
    }
}

class TooltipPlacementTest {
    private val safe = Rect(0f, 100f, 1000f, 1900f) // below the status bar, above the navigation bar
    private val tip = IntSize(600, 300)

    @Test fun goesBelowATargetNearTheTop() {
        val at = placeTooltip(safe, Rect(100f, 150f, 300f, 250f), tip, gap = 20, margin = 16)
        assertEquals(270, at.y)
    }

    @Test fun goesAboveATargetAtTheBottomEdge() {
        val at = placeTooltip(safe, Rect(100f, 1750f, 300f, 1880f), tip, gap = 20, margin = 16)
        assertEquals(1750 - 20 - 300, at.y)
    }

    @Test fun neverLeavesTheSafeArea() {
        val at = placeTooltip(safe, Rect(950f, 1000f, 1000f, 1100f), tip, gap = 20, margin = 16)
        assertTrue(at.x >= 16 && at.x + tip.width <= 1000 - 16)
        assertTrue(at.y >= 116 && at.y + tip.height <= 1900 - 16)
    }

    @Test fun withoutATargetItIsCentred() {
        val at = placeTooltip(safe, null, tip, gap = 20, margin = 16)
        assertEquals(200, at.x); assertEquals(850, at.y)
    }

    @Test fun staysInsideEvenWhenTheCardIsTallerThanTheRoom() {
        val at = placeTooltip(Rect(0f, 0f, 1000f, 400f), Rect(100f, 100f, 200f, 200f), IntSize(600, 380), gap = 20, margin = 16)
        assertTrue(at.y >= 16)
    }
}

class ShippedToursTest {
    @org.junit.Test fun everyShippedTourIsWellFormed() {
        // Registering twice must be harmless, and every tour needs a closing step and unique step ids (the registry enforces both).
        registerGuideTours(); registerGuideTours()
        val ids = TutorialRegistry.all().map { it.id }
        org.junit.Assert.assertEquals(ids.distinct(), ids)
        listOf(NAVIGATION_TOUR_ID, HOME_TOUR_ID, STORIES_NOTES_TOUR_ID, DISCOVER_TOUR_ID).forEach { id ->
            val t = TutorialRegistry.get(id)
            org.junit.Assert.assertNotNull("missing tour $id", t)
            org.junit.Assert.assertEquals(StepKind.Completion, t!!.steps.last().kind)
        }
    }
}
