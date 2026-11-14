package com.boox.day.editor.drawing

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.boox.day.data.datastore.AppSettings.FirstDayOfWeek
import com.boox.day.ui.calendar.MonthCellData
import com.boox.day.ui.calendar.MonthGridModel
import com.boox.day.ui.calendar.MonthPlannerGridState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MonthPlannerCanvasRendererTest {

    @After
    fun tearDown() {
        MonthPlannerGridState.currentGrid = null
    }

    @Test
    fun drawsExactNumberOfRowsFullscreenTopToBottomFor4_5_And6Rows() {
        val width = 1400
        val gridTop = MonthPlannerCanvasRenderer.WEEKDAY_HEADER_HEIGHT_PX.toInt()
        val height = gridTop + 1800 // header + 1800px grid (divisible cleanly by 4, 5, 6)

        val testCases = listOf(
            Triple(2026, 1, 4),  // Feb 2026 (Sunday start) -> 4 rows (450px each)
            Triple(2026, 10, 5), // Nov 2026 (Sunday start) -> 5 rows (360px each)
            Triple(2026, 4, 6)   // May 2026 (Sunday start) -> 6 rows (300px each)
        )

        for ((year, monthZeroBased, expectedRows) in testCases) {
            val layout = MonthPlannerGridState.computeGridLayout(year, monthZeroBased, FirstDayOfWeek.Sunday)
            assertEquals(expectedRows, layout.rowCount)

            val cells = (0 until expectedRows * 7).map { offset ->
                val date = layout.gridStart.plusDays(offset.toLong())
                val isCurrentMonth = date.year == year && date.monthValue == monthZeroBased + 1
                MonthCellData(
                    date = date,
                    dateStr = date.toString(),
                    dayOfMonth = date.dayOfMonth,
                    isCurrentMonth = isCurrentMonth,
                    isToday = false,
                    isSelected = false,
                    headerLabel = date.dayOfMonth.toString(),
                    events = emptyList(),
                    openTaskCount = 0,
                    completedTaskCount = 0,
                    taskPreviews = emptyList(),
                    hasDayNotes = false
                )
            }
            MonthPlannerGridState.currentGrid = MonthGridModel(
                year = year,
                monthZeroBased = monthZeroBased,
                rowCount = expectedRows,
                weekdayHeaders = layout.weekdayHeaders,
                cells = cells
            )

            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            MonthPlannerCanvasRenderer.drawMonthPlannerBg(canvas)

            val sampleX = 100 // inside column 0, away from vertical borders and day text
            val rowHeight = (height - gridTop) / expectedRows.toFloat()

            // Verify each row boundary from row 1 to expectedRows (including bottom edge) has a dark grid line
            for (row in 1..expectedRows) {
                val expectedY = (gridTop + row * rowHeight).coerceAtMost(height - 1f).roundToInt()
                val hasDarkLinePixel = ((expectedY - 1).coerceAtLeast(0)..(expectedY + 1).coerceAtMost(height - 1))
                    .any { y -> Color.red(bitmap.getPixel(sampleX, y)) < 0xA0 }
                assertTrue(
                    "Expected grid line at row=$row/$expectedRows y=$expectedY for $year-${monthZeroBased + 1}",
                    hasDarkLinePixel
                )
            }

            // Count distinct horizontal grid lines across the grid area below the header
            val lineRows = ((gridTop + 5) until height).filter { y ->
                Color.red(bitmap.getPixel(sampleX, y)) < 0xA0
            }
            val distinctLines = lineRows.fold(mutableListOf<Int>()) { acc, y ->
                if (acc.isEmpty() || y - acc.last() > 3) acc.add(y)
                acc
            }
            assertEquals(
                "Expected exactly $expectedRows horizontal row divider lines for $year-${monthZeroBased + 1}",
                expectedRows,
                distinctLines.size
            )

            bitmap.recycle()
        }
    }
}
