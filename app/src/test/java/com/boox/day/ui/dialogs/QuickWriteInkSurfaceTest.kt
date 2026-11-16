package com.boox.day.ui.dialogs

import com.boox.day.editor.DrawCanvas
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickWriteInkSurfaceTest {

    @After
    fun tearDown() {
        DrawCanvas.releaseExternalRawSurface()
    }

    @Test
    fun externalRawSurfaceTogglesBlockingFlagCleanly() {
        assertFalse(DrawCanvas.externalRawSurfaceActive)
        DrawCanvas.acquireExternalRawSurface()
        assertTrue(DrawCanvas.externalRawSurfaceActive)
        assertTrue(DrawCanvas.isCanvasInputBlockedByOverlay())
        DrawCanvas.releaseExternalRawSurface()
        assertFalse(DrawCanvas.externalRawSurfaceActive)
    }

    @Test
    fun eraseStrokesNearPointsRemovesOnlyHitStrokes() {
        val firstStroke = listOf(InkPoint(20f, 20f, 1L), InkPoint(40f, 25f, 2L))
        val secondStroke = listOf(InkPoint(200f, 120f, 3L), InkPoint(230f, 125f, 4L))
        val strokes = mutableListOf(firstStroke, secondStroke)

        val changed = eraseStrokesNearPoints(
            strokes = strokes,
            eraserPoints = listOf(InkPoint(30f, 22f, 5L)),
            radiusPx = 28f
        )

        assertTrue(changed)
        assertEquals(listOf(secondStroke), strokes)
    }

    @Test
    fun normalizeCapturedStrokeDistributesInvalidOrResetTimestampsAndPreservesInterStrokeGap() {
        val rawFirst = listOf(
            InkPoint(10f, 10f, 0L),
            InkPoint(10f, 10f, 0L), // duplicate point should be dropped
            InkPoint(30f, 20f, 0L),
            InkPoint(50f, 25f, 0L)
        )
        val first = normalizeCapturedStroke(
            rawPoints = rawFirst,
            strokeStartMs = 1_000L,
            strokeEndMs = 1_120L,
            previousStrokeEndMs = 0L
        )
        assertEquals(3, first.size)
        assertEquals(1_000L, first.first().t)
        assertEquals(1_120L, first.last().t)
        assertTrue(first.zipWithNext().all { (a, b) -> b.t > a.t })

        // Second stroke with resetting raw timestamps still advances strictly beyond first stroke's end time
        val rawSecond = listOf(
            InkPoint(70f, 10f, 5L),
            InkPoint(90f, 20f, 3L) // non-monotonic raw SDK timestamps
        )
        val second = normalizeCapturedStroke(
            rawPoints = rawSecond,
            strokeStartMs = 1_050L, // overlapping clock -> should apply MIN_INTER_STROKE_GAP_MS
            strokeEndMs = 1_180L,
            previousStrokeEndMs = first.last().t
        )
        assertEquals(2, second.size)
        assertEquals(1_120L + QuickWriteInkSurfaceView.MIN_INTER_STROKE_GAP_MS, second.first().t)
        assertTrue(second.last().t > second.first().t)
    }

    @Test
    fun normalizeCapturedStrokePreservesValidMillisecondDeltasAndSingleDots() {
        val rawDot = listOf(InkPoint(42f, 84f, 0L))
        val dot = normalizeCapturedStroke(rawDot, strokeStartMs = 500L, strokeEndMs = 505L, previousStrokeEndMs = 0L)
        assertEquals(1, dot.size)
        assertEquals(500L, dot[0].t)

        val rawStroke = listOf(
            InkPoint(100f, 50f, 10L),
            InkPoint(120f, 55f, 28L),
            InkPoint(145f, 60f, 62L)
        )
        val shifted = normalizeCapturedStroke(
            rawPoints = rawStroke,
            strokeStartMs = 800L,
            strokeEndMs = 855L,
            previousStrokeEndMs = dot.last().t
        )
        assertEquals(listOf(800L, 818L, 852L), shifted.map { it.t })

        val ink = buildQuickWriteInk(listOf(dot, shifted))
        assertEquals(2, ink.strokes.size)
        assertEquals(listOf(500L), ink.strokes[0].pointsInGlobalCoordinates.map { it.timestamp })
        assertEquals(listOf(800L, 818L, 852L), ink.strokes[1].pointsInGlobalCoordinates.map { it.timestamp })
    }
}
