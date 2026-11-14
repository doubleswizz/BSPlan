package com.boox.day.editor.drawing

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.boox.day.data.db.CalendarEvent
import com.boox.day.data.db.Task
import com.boox.day.ui.calendar.DayPlannerModel
import com.boox.day.ui.calendar.DayPlannerState
import com.boox.day.ui.calendar.DayScheduleRow
import com.boox.day.ui.calendar.DayTaskRow
import com.boox.day.ui.calendar.PendingTaskRow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DayPlannerCanvasRendererTest {

    @After
    fun tearDown() {
        DayPlannerState.currentDayModel = null
    }

    @Test
    fun drawsHeadersScheduleRowsTasksAndNotesWithoutClipping() {
        val width = 1800
        val height = 2200
        val event = CalendarEvent(
            id = "ev-1",
            calendarId = "cal-1",
            calendarName = "Primary",
            title = "Team Standup & Roadmap Review",
            dayId = "2026-11-13",
            startTime = 1_700_000_000_000L,
            colorHex = "#2563EB"
        )
        val openTask = Task(
            id = "task-1",
            dayDate = "2026-11-13",
            taskPageIndex = 0,
            rowIndex = 0,
            pageId = "page-1",
            isCompleted = false,
            typedText = "Send weekly update"
        )
        val doneTask = Task(
            id = "task-2",
            dayDate = "2026-11-13",
            taskPageIndex = 0,
            rowIndex = 1,
            pageId = "page-2",
            isCompleted = true,
            typedText = "Review pull requests"
        )
        DayPlannerState.currentDayModel = DayPlannerModel(
            dateStr = "2026-11-13",
            date = LocalDate.of(2026, 11, 13),
            isTuesday = false,
            scheduleRows = listOf(DayScheduleRow(event = event, timeText = "10:30 AM", title = event.title)),
            taskRows = listOf(
                DayTaskRow(task = openTask, title = "Send weekly update", isCompleted = false),
                DayTaskRow(task = doneTask, title = "Review pull requests", isCompleted = true)
            )
        )

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        DayPlannerCanvasRenderer.drawDayPlannerBg(canvas)

        val headerBottom = DayPlannerCanvasRenderer.SECTION_HEADER_HEIGHT_PX.toInt()
        assertEquals(64, headerBottom)

        // Verify left section header background ("TODAY'S SCHEDULE") is #DBEAFE and divider is drawn at headerBottom
        assertEquals(Color.parseColor("#DBEAFE"), bitmap.getPixel(500, headerBottom / 2))
        val hasHeaderDivider = ((headerBottom - 1)..(headerBottom + 1))
            .any { y -> Color.red(bitmap.getPixel(500, y)) < 0x80 }
        assertTrue("Expected bottom border under TODAY'S SCHEDULE header", hasHeaderDivider)

        // Verify right section header background ("GOOGLE TASKS") is #D1FAE5
        assertEquals(Color.parseColor("#D1FAE5"), bitmap.getPixel(width - 200, headerBottom / 2))

        // Verify bottom "NOTES" header starts right under compact dashboard separator
        val dashboardBottomFloat = DayPlannerCanvasRenderer.dashboardBottomY(height.toFloat())
        val dashboardBottom = dashboardBottomFloat.toInt()
        assertEquals(Color.parseColor("#FEF3C7"), bitmap.getPixel(width / 2, dashboardBottom + 20))

        // Verify text pixels were drawn inside the first Schedule row
        val rowsAreaHeight = (dashboardBottomFloat - headerBottom - 4f)
        val rowHeight = rowsAreaHeight / DayPlannerCanvasRenderer.DASHBOARD_MAX_ROWS
        assertEquals(DayPlannerCanvasRenderer.DASHBOARD_ROW_HEIGHT_PX, rowHeight, 0.5f)
        val row0Top = headerBottom
        val row0Bottom = (headerBottom + rowHeight).toInt()
        val hasScheduleTextInk = (row0Top until row0Bottom).any { y ->
            (30 until 260).any { x -> Color.red(bitmap.getPixel(x, y)) < 0x40 }
        }
        assertTrue("Expected rendered schedule text in row 0", hasScheduleTextInk)

        bitmap.recycle()
    }

    @Test
    fun rendersPendingPastDueEntryInFirstGoogleTasksRowWhenPendingTasksExist() {
        val width = 1800
        val height = 2200
        val overdue1 = Task(
            id = "old-1",
            dayDate = "2026-11-10",
            taskPageIndex = 0,
            rowIndex = 0,
            pageId = "page-old",
            isCompleted = false,
            typedText = "Pay invoice",
            dueDay = "2026-11-10"
        )
        val overdue2 = Task(
            id = "old-2",
            dayDate = "2026-11-11",
            taskPageIndex = 0,
            rowIndex = 1,
            pageId = "page-old",
            isCompleted = false,
            typedText = "Call dentist"
        )
        val todayTask = Task(
            id = "today-1",
            dayDate = "2026-11-13",
            taskPageIndex = 0,
            rowIndex = 0,
            pageId = "page-today",
            isCompleted = false,
            typedText = "Prepare notes"
        )
        DayPlannerState.currentDayModel = DayPlannerModel(
            dateStr = "2026-11-13",
            date = LocalDate.of(2026, 11, 13),
            isTuesday = false,
            scheduleRows = emptyList(),
            taskRows = listOf(DayTaskRow(task = todayTask, title = "Prepare notes", isCompleted = false)),
            pendingTasks = listOf(
                PendingTaskRow(overdue1, "Pay invoice", "2026-11-10", ""),
                PendingTaskRow(overdue2, "Call dentist", "2026-11-11", "")
            )
        )

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        DayPlannerCanvasRenderer.drawDayPlannerBg(canvas)

        val headerBottom = DayPlannerCanvasRenderer.SECTION_HEADER_HEIGHT_PX.toInt()
        val dashboardBottomFloat = DayPlannerCanvasRenderer.dashboardBottomY(height.toFloat())
        val rowHeight = (dashboardBottomFloat - headerBottom - 4f) / DayPlannerCanvasRenderer.DASHBOARD_MAX_ROWS

        // Row 0 on the right column ("GOOGLE TASKS") should show the #FEF3C7 Pending highlight pill & #D97706 stripe
        val midX = width / 2
        val row0CenterY = (headerBottom + rowHeight * 0.5f).toInt()
        assertEquals(Color.parseColor("#D97706"), bitmap.getPixel(midX + 16, row0CenterY))
        assertEquals(Color.parseColor("#FEF3C7"), bitmap.getPixel(width - 200, row0CenterY))

        // Row 1 should still have today's task rendered cleanly (not tinted with the pending amber background)
        val row1CenterY = (headerBottom + rowHeight * 1.5f).toInt()
        assertEquals(Color.WHITE, bitmap.getPixel(width - 200, row1CenterY))

        // Verify no [>] chevron text is drawn on the right edge of schedule or task rows
        val hasRightEdgeChevron = ((headerBottom + 10)..(headerBottom + rowHeight.toInt() - 10)).any { y ->
            (midX - 45 until midX - 10).any { x -> Color.red(bitmap.getPixel(x, y)) < 0x40 } ||
                (width - 45 until width - 10).any { x -> Color.red(bitmap.getPixel(x, y)) < 0x40 }
        }
        assertTrue("Expected no [>] chevron indicator on schedule or task rows", !hasRightEdgeChevron)

        bitmap.recycle()
    }

    @Test
    fun drawsVisibleHandwritingLinesInNotesAndAllTuesdaySections() {
        val width = 1800
        val height = 2200
        val expectedLineColor = Color.parseColor("#64748B")

        fun hasLineNearY(bitmap: Bitmap, x: Int, targetY: Float): Boolean {
            val yInt = targetY.toInt()
            return ((yInt - 2)..(yInt + 2)).any { y ->
                bitmap.getPixel(x, y) == expectedLineColor
            }
        }

        val dashboardBottom = DayPlannerCanvasRenderer.dashboardBottomY(height.toFloat())
        val notesTop = dashboardBottom + 4f
        val spacing = DayPlannerCanvasRenderer.WRITING_LINE_SPACING_PX
        val headerH = DayPlannerCanvasRenderer.SECTION_HEADER_HEIGHT_PX

        // 1. Non-Tuesday NOTES section
        DayPlannerState.currentDayModel = DayPlannerModel(
            dateStr = "2026-11-13",
            date = LocalDate.of(2026, 11, 13),
            isTuesday = false,
            scheduleRows = emptyList(),
            taskRows = emptyList()
        )
        val notesBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        DayPlannerCanvasRenderer.drawDayPlannerBg(Canvas(notesBitmap))
        assertTrue(
            "Expected visible writing line in NOTES section",
            hasLineNearY(notesBitmap, width / 2, notesTop + headerH + spacing)
        )
        notesBitmap.recycle()

        // 2. Tuesday 5-section Staff Meeting layout
        DayPlannerState.currentDayModel = DayPlannerModel(
            dateStr = "2026-11-10",
            date = LocalDate.of(2026, 11, 10),
            isTuesday = true,
            scheduleRows = emptyList(),
            taskRows = emptyList()
        )
        val tuesdayBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        DayPlannerCanvasRenderer.drawDayPlannerBg(Canvas(tuesdayBitmap))

        val remainingHeight = height - notesTop
        val row1Bottom = notesTop + remainingHeight * 0.33f
        val row2Bottom = notesTop + remainingHeight * 0.66f
        val leftX = width / 4
        val rightX = (width * 3) / 4

        assertTrue("Expected lines in CALENDAR", hasLineNearY(tuesdayBitmap, leftX, notesTop + headerH + spacing))
        assertTrue("Expected lines in OLD BUSINESS", hasLineNearY(tuesdayBitmap, rightX, notesTop + headerH + spacing))
        assertTrue("Expected lines in NEW BUSINESS", hasLineNearY(tuesdayBitmap, leftX, row1Bottom + headerH + spacing))
        assertTrue("Expected lines in ROUNDTABLE", hasLineNearY(tuesdayBitmap, rightX, row1Bottom + headerH + spacing))
        assertTrue("Expected lines in ACTION ITEMS", hasLineNearY(tuesdayBitmap, width / 2, row2Bottom + headerH + spacing))
        tuesdayBitmap.recycle()
    }
}
