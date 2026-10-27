package com.univibe.app

import com.univibe.app.data.media.CropGeometry
import com.univibe.app.data.media.CropHandle
import com.univibe.app.data.media.CropRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class CropGeometryTest {
    private val tallW = 1159
    private val tallH = 2576

    private fun inside(r: CropRect) =
        r.left >= -1e-4f && r.top >= -1e-4f && r.right <= 1f + 1e-4f && r.bottom <= 1f + 1e-4f && r.width > 0f && r.height > 0f

    @Test fun fitGivesTheRequestedShapeInsideThePhoto() {
        for (aspect in listOf(1f, 4f / 5f, 5f / 8f, 16f / 9f)) {
            val r = CropGeometry.fit(aspect, tallW, tallH)
            assertTrue(inside(r))
            assertEquals(aspect, CropGeometry.ratioOf(r, tallW, tallH), 0.001f)
        }
    }

    @Test fun aTallPhotoCroppedToFiveByEightFitsTheFeed() {
        val r = CropGeometry.fit(5f / 8f, tallW, tallH)
        assertTrue(CropGeometry.ratioOf(r, tallW, tallH) >= 0.62f)
    }

    @Test fun movingNeverLeavesThePhoto() {
        val r = CropGeometry.move(CropRect(0.2f, 0.2f, 0.6f, 0.6f), 5f, -5f)
        assertEquals(0.6f, r.left, 1e-5f); assertEquals(1f, r.right, 1e-5f)
        assertEquals(0f, r.top, 1e-5f); assertEquals(0.4f, r.bottom, 1e-5f)
    }

    @Test fun freeResizeKeepsAMinimumSize() {
        val r = CropGeometry.resizeFree(CropRect.Full, CropHandle.BottomRight, -5f, -5f)
        assertEquals(CropGeometry.MIN_SIDE, r.width, 1e-5f)
        assertEquals(CropGeometry.MIN_SIDE, r.height, 1e-5f)
    }

    @Test fun lockedResizeKeepsTheShapeAndStaysInside() {
        val random = Random(7)
        var r = CropGeometry.fit(1f, tallW, tallH)
        val corners = listOf(CropHandle.TopLeft, CropHandle.TopRight, CropHandle.BottomLeft, CropHandle.BottomRight)
        repeat(2000) {
            val next = CropGeometry.resizeLocked(r, corners[random.nextInt(4)], random.nextFloat() * 0.6f - 0.3f, random.nextFloat() * 0.6f - 0.3f, 1f, tallW, tallH)
            assertTrue(inside(next))
            assertEquals(1f, CropGeometry.ratioOf(next, tallW, tallH), 0.002f)
            r = next
        }
    }

    @Test fun lockedWindowIgnoresEdgeHandles() {
        val r = CropGeometry.fit(1f, tallW, tallH)
        assertEquals(r, CropGeometry.resizeLocked(r, CropHandle.Top, 0.1f, 0.1f, 1f, tallW, tallH))
    }

    @Test fun pixelsAreAlwaysInsideThePhoto() {
        val px = CropGeometry.toPixels(CropRect(0f, 0f, 1f, 1f), 100, 200)
        assertEquals(0, px.x); assertEquals(0, px.y); assertEquals(100, px.width); assertEquals(200, px.height)
        val tiny = CropGeometry.toPixels(CropRect(0.9999f, 0.9999f, 1f, 1f), 100, 200)
        assertTrue(tiny.width >= 1 && tiny.height >= 1 && tiny.x + tiny.width <= 100 && tiny.y + tiny.height <= 200)
    }

    @Test fun cornersWinOverEdgesAndTheInsideMovesTheWindow() {
        // Window from (100,100) to (300,400) on screen.
        assertEquals(CropHandle.TopLeft, CropGeometry.pickHandle(100f, 100f, 300f, 400f, 110f, 95f, 40f, true))
        assertEquals(CropHandle.Left, CropGeometry.pickHandle(100f, 100f, 300f, 400f, 105f, 250f, 40f, true))
        assertEquals(CropHandle.Move, CropGeometry.pickHandle(100f, 100f, 300f, 400f, 200f, 250f, 40f, true))
        // A locked window has no edge handles, so the same touch moves it.
        assertEquals(CropHandle.Move, CropGeometry.pickHandle(100f, 100f, 300f, 400f, 120f, 250f, 10f, false))
        assertNull(CropGeometry.pickHandle(100f, 100f, 300f, 400f, 10f, 10f, 40f, true))
        assertNotNull(CropGeometry.pickHandle(100f, 100f, 300f, 400f, 300f, 400f, 40f, false))
    }
}
