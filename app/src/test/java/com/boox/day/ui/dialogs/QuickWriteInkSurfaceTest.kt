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
}
