package com.boox.day.editor.ui.toolbar

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Without the Boox Notes button on the regular bar, the Go 10.3 fits all Tasks tools (including
 * FullScreen) and keeps Insert alongside AI & Lasso on Memos (only MeetingNotes overflows to ≡).
 */
class RegularToolbarOverflowTest {

    private val go103Width = 773.dp  // 385 dpi

    @Test
    fun go103TasksBarHasTaskAiAndFullScreen() {
        assertEquals(
            emptySet<BarTool>(),
            regularToolbarOverflow(barWidth = go103Width, isMemos = false, hasPageNav = true)
        )
    }

    @Test
    fun go103MemosBarKeepsFullScreenAndInsert() {
        assertEquals(
            setOf(BarTool.MeetingNotes),
            regularToolbarOverflow(barWidth = go103Width, isMemos = true, hasPageNav = true)
        )
    }
}
