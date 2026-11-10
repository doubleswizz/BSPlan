package com.boox.day.editor.drawing

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.boox.day.ui.calendar.MonthGridModel
import com.boox.day.ui.calendar.MonthPlannerGridState

/**
 * Renders the Full-Page 7×6 Month View planner grid directly onto [PageView]'s background canvas.
 *
 * Because it renders in the background layer of [com.boox.day.editor.DrawCanvas]:
 * - The Onyx hardware stylus driver can write over the top of the entire Month View with zero lag.
 * - The physical stylus tail eraser erases only handwritten strokes, leaving the calendar grid,
 *   dates, Google Calendar events, and Google Tasks untouched.
 */
object MonthPlannerCanvasRenderer {

    const val WEEKDAY_HEADER_HEIGHT_PX = 48f

    private val gridBorderPaint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        isAntiAlias = true
    }

    private val headerDividerPaint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        isAntiAlias = true
    }

    private val weekdayTextPaint = Paint().apply {
        color = Color.BLACK
        textSize = 24f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }

    private val dayNumberCurrentPaint = Paint().apply {
        color = Color.BLACK
        textSize = 24f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textAlign = Paint.Align.LEFT
        isAntiAlias = true
    }

    private val dayNumberSpilloverPaint = Paint().apply {
        color = Color.parseColor("#555555")
        textSize = 22f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textAlign = Paint.Align.LEFT
        isAntiAlias = true
    }

    private val todayPillFillPaint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val dayNumberTodayPaint = Paint().apply {
        color = Color.WHITE
        textSize = 24f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textAlign = Paint.Align.LEFT
        isAntiAlias = true
    }

    private val eventTextPaint = Paint().apply {
        color = Color.BLACK
        textSize = 20f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        textAlign = Paint.Align.LEFT
        isAntiAlias = true
    }

    private val eventAllDayPaint = Paint().apply {
        color = Color.BLACK
        textSize = 20f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textAlign = Paint.Align.LEFT
        isAntiAlias = true
    }

    private val taskTextPaint = Paint().apply {
        color = Color.parseColor("#222222")
        textSize = 19f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        textAlign = Paint.Align.LEFT
        isAntiAlias = true
    }

    private val noteBadgePaint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 2f
        isAntiAlias = true
    }

    fun drawMonthPlannerBg(canvas: Canvas) {
        val width = canvas.width.toFloat()
        val height = canvas.height.toFloat()
        canvas.drawColor(Color.WHITE)

        if (width <= 0f || height <= WEEKDAY_HEADER_HEIGHT_PX) return

        val grid: MonthGridModel? = MonthPlannerGridState.currentGrid
        val headers = grid?.weekdayHeaders ?: listOf("SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT")

        val colWidth = width / 7f
        val gridTop = WEEKDAY_HEADER_HEIGHT_PX
        val rowHeight = (height - gridTop) / 6f

        // 1. Draw SUN..SAT weekday header row
        val headerBaselineY = (WEEKDAY_HEADER_HEIGHT_PX / 2f) - ((weekdayTextPaint.descent() + weekdayTextPaint.ascent()) / 2f)
        for (col in 0..6) {
            val centerX = col * colWidth + colWidth / 2f
            canvas.drawText(headers.getOrElse(col) { "" }, centerX, headerBaselineY, weekdayTextPaint)
        }
        canvas.drawLine(0f, gridTop, width, gridTop, headerDividerPaint)

        // 2. Draw vertical column lines (including through header)
        for (col in 0..7) {
            val x = (col * colWidth).coerceAtMost(width - 1f)
            canvas.drawLine(x, 0f, x, height, gridBorderPaint)
        }

        // 3. Draw horizontal row lines for the 6 weeks
        for (row in 1..6) {
            val y = (gridTop + row * rowHeight).coerceAtMost(height - 1f)
            canvas.drawLine(0f, y, width, y, gridBorderPaint)
        }

        val cells = grid?.cells ?: return
        val cellPaddingX = 10f
        val lineHeight = 25f

        // 4. Draw each of the 42 day cells (including spillover days)
        for (row in 0 until 6) {
            for (col in 0 until 7) {
                val index = row * 7 + col
                val cell = cells.getOrNull(index) ?: continue

                val cellLeft = col * colWidth
                val cellTop = gridTop + row * rowHeight
                val cellRight = cellLeft + colWidth
                val cellBottom = cellTop + rowHeight
                val maxTextWidth = (colWidth - cellPaddingX * 2f).coerceAtLeast(20f)

                // Draw date label in top-left
                val labelText = cell.headerLabel
                val labelX = cellLeft + cellPaddingX
                val labelBaselineY = cellTop + 30f

                if (cell.isToday) {
                    val labelWidth = dayNumberTodayPaint.measureText(labelText)
                    val pillRect = RectF(
                        labelX - 6f,
                        cellTop + 6f,
                        labelX + labelWidth + 8f,
                        cellTop + 36f
                    )
                    canvas.drawRoundRect(pillRect, 6f, 6f, todayPillFillPaint)
                    canvas.drawText(labelText, labelX + 1f, labelBaselineY, dayNumberTodayPaint)
                } else {
                    val paint = if (cell.isCurrentMonth) dayNumberCurrentPaint else dayNumberSpilloverPaint
                    canvas.drawText(labelText, labelX, labelBaselineY, paint)
                }

                // Draw small note page indicator in top-right if day has handwritten notes
                if (cell.hasDayNotes) {
                    val iconRight = cellRight - 10f
                    val iconLeft = iconRight - 16f
                    val iconTop = cellTop + 10f
                    val iconBottom = iconTop + 20f
                    canvas.drawRect(iconLeft, iconTop, iconRight, iconBottom, noteBadgePaint)
                    canvas.drawLine(iconLeft + 3f, iconTop + 6f, iconRight - 3f, iconTop + 6f, noteBadgePaint)
                    canvas.drawLine(iconLeft + 3f, iconTop + 11f, iconRight - 3f, iconTop + 11f, noteBadgePaint)
                }

                // Draw events and tasks inside the cell
                var currentY = cellTop + 58f
                val maxBottomY = cellBottom - 8f

                // Render lines: events first, then tasks summary/previews
                val totalLinesAvailable = ((maxBottomY - currentY) / lineHeight).toInt().coerceAtLeast(0)
                if (totalLinesAvailable <= 0) continue

                val hasTasks = cell.openTaskCount > 0
                val maxEventLines = if (hasTasks && totalLinesAvailable >= 2) {
                    totalLinesAvailable - 1
                } else {
                    totalLinesAvailable
                }

                val eventsToShow = cell.events.take(maxEventLines)
                eventsToShow.forEachIndexed { idx, ev ->
                    val isLastEventSlot = idx == eventsToShow.lastIndex && cell.events.size > eventsToShow.size
                    val rawText = if (isLastEventSlot && cell.events.size - idx > 1) {
                        "+${cell.events.size - idx} more"
                    } else {
                        "• ${ev.displayLine}"
                    }
                    val paint = if (ev.isAllDay) eventAllDayPaint else eventTextPaint
                    val fitted = ellipsizeToWidth(rawText, paint, maxTextWidth)
                    canvas.drawText(fitted, cellLeft + cellPaddingX, currentY, paint)
                    currentY += lineHeight
                }

                if (hasTasks && currentY <= maxBottomY) {
                    val taskLine = if (cell.openTaskCount == 1 && cell.taskPreviews.isNotEmpty()) {
                        cell.taskPreviews.first()
                    } else {
                        "☐ ${cell.openTaskCount} task${if (cell.openTaskCount == 1) "" else "s"}"
                    }
                    val fittedTask = ellipsizeToWidth(taskLine, taskTextPaint, maxTextWidth)
                    canvas.drawText(fittedTask, cellLeft + cellPaddingX, currentY, taskTextPaint)
                }
            }
        }
    }

    private fun ellipsizeToWidth(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        val ellipsis = "…"
        val targetWidth = maxWidth - paint.measureText(ellipsis)
        if (targetWidth <= 0f) return ""
        var end = text.length
        while (end > 0 && paint.measureText(text, 0, end) > targetWidth) {
            end--
        }
        return text.substring(0, end).trimEnd() + ellipsis
    }
}
