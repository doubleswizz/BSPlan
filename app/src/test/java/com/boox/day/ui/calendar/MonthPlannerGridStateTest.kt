package com.boox.day.ui.calendar

import com.boox.day.data.datastore.AppSettings.FirstDayOfWeek
import com.boox.day.editor.drawing.MonthPlannerCanvasRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.YearMonth

class MonthPlannerGridStateTest {

    @Test
    fun februaryStartingOnWeekStartUsesFourRows() {
        // Feb 2026 starts on Sunday (28 days) -> 4 rows with Sunday start
        val feb2026 = MonthPlannerGridState.computeGridLayout(2026, 1, FirstDayOfWeek.Sunday)
        assertEquals(4, feb2026.rowCount)
        assertEquals("2026-02-01", feb2026.gridStart.toString())
        assertEquals("SUN", feb2026.weekdayHeaders.first())

        // Feb 2021 starts on Monday (28 days) -> 4 rows with Monday start
        val feb2021 = MonthPlannerGridState.computeGridLayout(2021, 1, FirstDayOfWeek.Monday)
        assertEquals(4, feb2021.rowCount)
        assertEquals("2021-02-01", feb2021.gridStart.toString())
        assertEquals("MON", feb2021.weekdayHeaders.first())

        // Feb 2025 starts on Saturday (28 days) -> 4 rows with Saturday start
        val feb2025 = MonthPlannerGridState.computeGridLayout(2025, 1, FirstDayOfWeek.Saturday)
        assertEquals(4, feb2025.rowCount)
        assertEquals("2025-02-01", feb2025.gridStart.toString())
        assertEquals("SAT", feb2025.weekdayHeaders.first())
    }

    @Test
    fun typicalMonthsUseFiveRows() {
        // Nov 2026 starts on Sunday (30 days) -> 5 rows (no blank Dec week)
        val nov2026 = MonthPlannerGridState.computeGridLayout(2026, 10, FirstDayOfWeek.Sunday)
        assertEquals(5, nov2026.rowCount)
        assertEquals("2026-11-01", nov2026.gridStart.toString())

        // Dec 2026 starts on Tuesday (31 days, 2 leading + 31 = 33 cells) -> 5 rows
        val dec2026 = MonthPlannerGridState.computeGridLayout(2026, 11, FirstDayOfWeek.Sunday)
        assertEquals(5, dec2026.rowCount)

        // Feb 2024 leap year starts on Thursday (29 days, 4 leading + 29 = 33 cells) -> 5 rows
        val feb2024 = MonthPlannerGridState.computeGridLayout(2024, 1, FirstDayOfWeek.Sunday)
        assertEquals(5, feb2024.rowCount)
    }

    @Test
    fun overflowingMonthsUseSixRows() {
        // May 2026 starts on Friday (31 days, 5 leading + 31 = 36 cells) -> 6 rows
        val may2026 = MonthPlannerGridState.computeGridLayout(2026, 4, FirstDayOfWeek.Sunday)
        assertEquals(6, may2026.rowCount)

        // Aug 2026 starts on Saturday (31 days, 6 leading + 31 = 37 cells) -> 6 rows
        val aug2026 = MonthPlannerGridState.computeGridLayout(2026, 7, FirstDayOfWeek.Sunday)
        assertEquals(6, aug2026.rowCount)

        // Nov 2026 starts on Sunday (30 days, 6 leading + 30 = 36 cells with Monday start) -> 6 rows
        val nov2026Mon = MonthPlannerGridState.computeGridLayout(2026, 10, FirstDayOfWeek.Monday)
        assertEquals(6, nov2026Mon.rowCount)
    }

    @Test
    fun allMonthsAcrossTenYearsUseStrictMinimalRowCount() {
        for (start in FirstDayOfWeek.entries) {
            for (year in 2020..2030) {
                for (monthZeroBased in 0..11) {
                    val layout = MonthPlannerGridState.computeGridLayout(year, monthZeroBased, start)
                    val ym = YearMonth.of(year, monthZeroBased + 1)
                    val firstOfMonth = ym.atDay(1)
                    val lastOfMonth = ym.atEndOfMonth()
                    val totalCells = layout.rowCount * 7
                    val gridEnd = layout.gridStart.plusDays((totalCells - 1).toLong())

                    assertTrue(
                        "$year-${monthZeroBased + 1} ($start) rowCount=${layout.rowCount}",
                        layout.rowCount in 4..6
                    )
                    assertEquals(start.dayOfWeek, layout.gridStart.dayOfWeek)
                    assertEquals(expectedHeaderFor(start.dayOfWeek), layout.weekdayHeaders.first())
                    // First day of month must appear in row 0 (cells 0..6)
                    assertTrue(
                        "First day in row 0 for $year-${monthZeroBased + 1}",
                        !firstOfMonth.isBefore(layout.gridStart) &&
                            firstOfMonth.isBefore(layout.gridStart.plusDays(7))
                    )
                    // Last day of month must appear in the final row (strictly minimal)
                    val finalRowStart = layout.gridStart.plusDays(((layout.rowCount - 1) * 7).toLong())
                    assertTrue(
                        "Last day in final row for $year-${monthZeroBased + 1}",
                        !lastOfMonth.isBefore(finalRowStart) && !lastOfMonth.isAfter(gridEnd)
                    )
                }
            }
        }
    }

    @Test
    fun hitTestMapsFullHeightAcrossFourFiveAndSixRows() {
        val header = MonthPlannerCanvasRenderer.WEEKDAY_HEADER_HEIGHT_PX
        val width = 1400f
        val height = 1848f // 48 header + 1800 grid

        // Tapping header returns null
        assertNull(MonthPlannerGridState.hitTestCellIndex(100f, header, width, height, header, 5))
        assertNull(MonthPlannerGridState.hitTestCellIndex(100f, 10f, width, height, header, 4))

        for (rowCount in listOf(4, 5, 6)) {
            val rowHeight = (height - header) / rowCount.toFloat()
            for (row in 0 until rowCount) {
                val centerY = header + row * rowHeight + rowHeight * 0.5f
                assertEquals(
                    "rowCount=$rowCount row=$row col=0",
                    row * 7,
                    MonthPlannerGridState.hitTestCellIndex(50f, centerY, width, height, header, rowCount)
                )
                assertEquals(
                    "rowCount=$rowCount row=$row col=6",
                    row * 7 + 6,
                    MonthPlannerGridState.hitTestCellIndex(width - 20f, centerY, width, height, header, rowCount)
                )
            }
            // Bottom-most pixel still maps to the final row
            assertEquals(
                rowCount * 7 - 1,
                MonthPlannerGridState.hitTestCellIndex(width - 1f, height - 1f, width, height, header, rowCount)
            )
        }
    }

    private fun expectedHeaderFor(dayOfWeek: DayOfWeek): String = when (dayOfWeek) {
        DayOfWeek.SUNDAY -> "SUN"
        DayOfWeek.MONDAY -> "MON"
        DayOfWeek.TUESDAY -> "TUE"
        DayOfWeek.WEDNESDAY -> "WED"
        DayOfWeek.THURSDAY -> "THU"
        DayOfWeek.FRIDAY -> "FRI"
        DayOfWeek.SATURDAY -> "SAT"
    }
}
