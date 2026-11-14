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
}
