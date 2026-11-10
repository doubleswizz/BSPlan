package com.boox.day.editor.drawing

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.boox.day.ui.calendar.DayPlannerModel
import com.boox.day.ui.calendar.DayPlannerState
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Renders the Full-Page Day View template (Layout 2 + Automatic Tuesday Mode) directly onto
 * [com.boox.day.editor.PageView]'s background canvas:
 *
 * - Top 25%: Two-column dashboard (`TODAY'S SCHEDULE (Google Cal)` | `GOOGLE TASKS`), with
 *   interactive `[>]` indicators and checkboxes.
 * - Bottom 75%:
 *   - On Tuesdays: 5 Staff Meeting sections (`CALENDAR` | `OLD BUSINESS` / `NEW BUSINESS` | `ROUNDTABLE` / full-width `ACTION ITEMS`).
 *   - On all other days: Full-width lined notes area.
 *
 * Because it renders on the background canvas layer, the stylus can write anywhere over the
 * entire Day Page with zero lag, and the physical tail eraser erases only handwritten strokes.
 */
object DayPlannerCanvasRenderer {

    const val DASHBOARD_HEIGHT_FRACTION = 0.25f
    const val SECTION_HEADER_HEIGHT_PX = 42f
    const val DASHBOARD_MAX_ROWS = 5
    private const val WRITING_LINE_SPACING_PX = 54f

    private val strongBorderPaint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        isAntiAlias = true
    }

    private val thinBorderPaint = Paint().apply {
        color = Color.parseColor("#888888")
        style = Paint.Style.STROKE
        strokeWidth = 1f
        isAntiAlias = true
    }

    private val writingLinePaint = Paint().apply {
        color = Color.parseColor("#CCCCCC")
        style = Paint.Style.STROKE
        strokeWidth = 1f
        isAntiAlias = true
    }

    private val sectionHeaderPaint = Paint().apply {
        color = Color.BLACK
        textSize = 23f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textAlign = Paint.Align.LEFT
        isAntiAlias = true
    }

    private val rowTimePaint = Paint().apply {
        color = Color.BLACK
        textSize = 21f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textAlign = Paint.Align.LEFT
        isAntiAlias = true
    }

    private val rowTitlePaint = Paint().apply {
        color = Color.BLACK
        textSize = 21f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        textAlign = Paint.Align.LEFT
        isAntiAlias = true
    }

    private val rowCompletedTitlePaint = Paint().apply {
        color = Color.parseColor("#666666")
        textSize = 21f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        textAlign = Paint.Align.LEFT
        isStrikeThruText = true
        isAntiAlias = true
    }

    private val chevronPaint = Paint().apply {
        color = Color.BLACK
        textSize = 20f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.RIGHT
        isAntiAlias = true
    }

    private val checkboxStrokePaint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 2f
        isAntiAlias = true
    }

    private val checkboxFillPaint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val checkMarkPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
    }

    private val eventStripePaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private fun parseEventColor(colorHex: String?): Int? {
        if (colorHex.isNullOrBlank()) return null
        return runCatching { Color.parseColor(colorHex) }.getOrNull()
    }

    fun drawDayPlannerBg(canvas: Canvas) {
        val width = canvas.width.toFloat()
        val height = canvas.height.toFloat()
        canvas.drawColor(Color.WHITE)

        if (width <= 0f || height <= 200f) return

        val model: DayPlannerModel? = DayPlannerState.currentDayModel
        val isTuesday = model?.isTuesday ?: (LocalDate.now().dayOfWeek == DayOfWeek.TUESDAY)

        val midX = width * 0.5f
        val dashboardBottomY = height * DASHBOARD_HEIGHT_FRACTION

        // ═══════════════════════════════════════════════════════════════════════════
        // 1. TOP 25% DASHBOARD: TODAY'S SCHEDULE (Left) | GOOGLE TASKS (Right)
        // ═══════════════════════════════════════════════════════════════════════════
        drawSectionHeader(canvas, 0f, 0f, midX, "TODAY'S SCHEDULE")
        drawSectionHeader(canvas, midX, 0f, width, "GOOGLE TASKS")

        // Vertical divider between Schedule and Tasks
        canvas.drawLine(midX, 0f, midX, dashboardBottomY, strongBorderPaint)

        // Double-line separator under Top Dashboard
        canvas.drawLine(0f, dashboardBottomY - 3f, width, dashboardBottomY - 3f, strongBorderPaint)
        canvas.drawLine(0f, dashboardBottomY + 2f, width, dashboardBottomY + 2f, strongBorderPaint)

        val rowsTopY = SECTION_HEADER_HEIGHT_PX
        val rowsAreaHeight = (dashboardBottomY - rowsTopY - 4f).coerceAtLeast(50f)
        val rowHeight = rowsAreaHeight / DASHBOARD_MAX_ROWS

        // Draw Schedule rows (Left)
        val scheduleRows = model?.scheduleRows.orEmpty()
        for (i in 0 until DASHBOARD_MAX_ROWS) {
            val rowTop = rowsTopY + i * rowHeight
            val rowBottom = rowTop + rowHeight
            if (i < DASHBOARD_MAX_ROWS - 1) {
                canvas.drawLine(12f, rowBottom, midX - 12f, rowBottom, writingLinePaint)
            }
            val item = scheduleRows.getOrNull(i) ?: continue
            val baselineY = rowTop + (rowHeight * 0.66f)
            val colorInt = parseEventColor(item.event.colorHex)
            if (colorInt != null) {
                // Google Calendar color stripe + tinted row highlight on Kaleido 3 color screen
                val r = Color.red(colorInt)
                val g = Color.green(colorInt)
                val b = Color.blue(colorInt)
                eventStripePaint.color = Color.argb(40, r, g, b)
                canvas.drawRoundRect(
                    RectF(12f, rowTop + 3f, midX - 12f, rowBottom - 3f),
                    4f,
                    4f,
                    eventStripePaint
                )
                eventStripePaint.color = colorInt
                canvas.drawRoundRect(
                    RectF(12f, rowTop + 3f, 20f, rowBottom - 3f),
                    3f,
                    3f,
                    eventStripePaint
                )
                canvas.drawText(item.timeText, 28f, baselineY, rowTimePaint)
            } else {
                val timeStr = "• ${item.timeText}"
                canvas.drawText(timeStr, 16f, baselineY, rowTimePaint)
            }

            val titleStartX = 148f
            val maxTitleWidth = (midX - titleStartX - 58f).coerceAtLeast(20f)
            val fittedTitle = ellipsize(item.title, rowTitlePaint, maxTitleWidth)
            canvas.drawText(fittedTitle, titleStartX, baselineY, rowTitlePaint)

            canvas.drawText("[>]", midX - 14f, baselineY, chevronPaint)
        }

        // Draw Google Tasks rows (Right)
        val taskRows = model?.taskRows.orEmpty()
        for (i in 0 until DASHBOARD_MAX_ROWS) {
            val rowTop = rowsTopY + i * rowHeight
            val rowBottom = rowTop + rowHeight
            if (i < DASHBOARD_MAX_ROWS - 1) {
                canvas.drawLine(midX + 12f, rowBottom, width - 12f, rowBottom, writingLinePaint)
            }

            // Always draw checkbox on each task slot so user can also handwrite tasks in empty slots
            val boxSize = 22f
            val boxLeft = midX + 18f
            val boxTop = rowTop + (rowHeight - boxSize) / 2f
            val boxRect = RectF(boxLeft, boxTop, boxLeft + boxSize, boxTop + boxSize)

            val taskItem = taskRows.getOrNull(i)
            if (taskItem?.isCompleted == true) {
                canvas.drawRoundRect(boxRect, 4f, 4f, checkboxFillPaint)
                canvas.drawLine(boxLeft + 4f, boxTop + 11f, boxLeft + 9f, boxTop + 17f, checkMarkPaint)
                canvas.drawLine(boxLeft + 9f, boxTop + 17f, boxLeft + 18f, boxTop + 5f, checkMarkPaint)
            } else {
                canvas.drawRoundRect(boxRect, 4f, 4f, checkboxStrokePaint)
            }

            if (taskItem != null) {
                val baselineY = rowTop + (rowHeight * 0.66f)
                val titleStartX = boxLeft + boxSize + 14f
                val maxTitleWidth = (width - titleStartX - 58f).coerceAtLeast(20f)
                val paint = if (taskItem.isCompleted) rowCompletedTitlePaint else rowTitlePaint
                val fittedTitle = ellipsize(taskItem.title, paint, maxTitleWidth)
                canvas.drawText(fittedTitle, titleStartX, baselineY, paint)
                canvas.drawText("[>]", width - 14f, baselineY, chevronPaint)
            }
        }

        // ═══════════════════════════════════════════════════════════════════════════
        // 2. BOTTOM 75%: TUESDAY 5-SECTION STAFF MEETING vs STANDARD OPEN NOTES
        // ═══════════════════════════════════════════════════════════════════════════
        val notesTopY = dashboardBottomY + 4f
        if (isTuesday) {
            val remainingHeight = height - notesTopY
            val row1BottomY = notesTopY + remainingHeight * 0.33f
            val row2BottomY = notesTopY + remainingHeight * 0.66f

            // Row 1: CALENDAR (left) | OLD BUSINESS (right)
            drawSectionBoxWithLines(canvas, 0f, notesTopY, midX, row1BottomY, "CALENDAR")
            drawSectionBoxWithLines(canvas, midX, notesTopY, width, row1BottomY, "OLD BUSINESS")

            // Row 2: NEW BUSINESS (left) | ROUNDTABLE (right)
            drawSectionBoxWithLines(canvas, 0f, row1BottomY, midX, row2BottomY, "NEW BUSINESS")
            drawSectionBoxWithLines(canvas, midX, row1BottomY, width, row2BottomY, "ROUNDTABLE")

            // Row 3: ACTION ITEMS (full width)
            drawSectionBoxWithLines(canvas, 0f, row2BottomY, width, height, "ACTION ITEMS")

            // Bold section dividers
            canvas.drawLine(midX, notesTopY, midX, row2BottomY, strongBorderPaint)
            canvas.drawLine(0f, row1BottomY, width, row1BottomY, strongBorderPaint)
            canvas.drawLine(0f, row2BottomY, width, row2BottomY, strongBorderPaint)
        } else {
            drawSectionBoxWithLines(canvas, 0f, notesTopY, width, height, "NOTES")
        }
    }

    private fun drawSectionHeader(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        title: String
    ) {
        val headerBottom = top + SECTION_HEADER_HEIGHT_PX
        val baselineY = top + 29f
        canvas.drawText(title, left + 16f, baselineY, sectionHeaderPaint)
        canvas.drawLine(left, headerBottom, right, headerBottom, thinBorderPaint)
    }

    private fun drawSectionBoxWithLines(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        title: String
    ) {
        drawSectionHeader(canvas, left, top, right, title)
        var lineY = top + SECTION_HEADER_HEIGHT_PX + WRITING_LINE_SPACING_PX
        while (lineY < bottom - 14f) {
            canvas.drawLine(left + 16f, lineY, right - 16f, lineY, writingLinePaint)
            lineY += WRITING_LINE_SPACING_PX
        }
    }

    private fun ellipsize(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        val ellipsis = "…"
        val target = maxWidth - paint.measureText(ellipsis)
        if (target <= 0f) return ""
        var end = text.length
        while (end > 0 && paint.measureText(text, 0, end) > target) {
            end--
        }
        return text.substring(0, end).trimEnd() + ellipsis
    }
}
