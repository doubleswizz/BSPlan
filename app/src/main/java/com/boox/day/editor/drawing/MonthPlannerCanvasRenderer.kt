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
        color = Color.parseColor("#1E293B") // Crisp Slate/Navy
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        isAntiAlias = true
    }

    private val headerDividerPaint = Paint().apply {
        color = Color.parseColor("#0F172A") // Deep Navy
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        isAntiAlias = true
    }

    private val headerBgPaint = Paint().apply {
        color = Color.parseColor("#1E3A8A") // Deep Navy Header Banner
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val headerWeekendBgPaint = Paint().apply {
        color = Color.parseColor("#B91C1C") // Crimson Red Weekend Header Accent
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val spilloverCellBgPaint = Paint().apply {
        color = Color.parseColor("#F1F5F9") // Soft Slate Tint for spillover days
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val todayCellBgPaint = Paint().apply {
        color = Color.parseColor("#FFFBEB") // Warm Amber Tint for Today's cell
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val weekdayTextPaint = Paint().apply {
        color = Color.WHITE
        textSize = 23f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }

    private val dayNumberCurrentPaint = Paint().apply {
        color = Color.parseColor("#0F172A")
        textSize = 24f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textAlign = Paint.Align.LEFT
        isAntiAlias = true
    }

    private val dayNumberSpilloverPaint = Paint().apply {
        color = Color.parseColor("#64748B")
        textSize = 22f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textAlign = Paint.Align.LEFT
        isAntiAlias = true
    }

    private val todayPillFillPaint = Paint().apply {
        color = Color.parseColor("#D32F2F") // Crimson Red Today Badge
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
        color = Color.parseColor("#0F172A")
        textSize = 20f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        textAlign = Paint.Align.LEFT
        isAntiAlias = true
    }

    private val eventAllDayPaint = Paint().apply {
        color = Color.parseColor("#0F172A")
        textSize = 20f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textAlign = Paint.Align.LEFT
        isAntiAlias = true
    }

    private val taskPillFillPaint = Paint().apply {
        color = Color.parseColor("#D1FAE5") // Emerald Green Tint
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val taskStripeFillPaint = Paint().apply {
        color = Color.parseColor("#10B981") // Emerald Green Stripe
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val taskTextPaint = Paint().apply {
        color = Color.parseColor("#064E3B") // Deep Emerald Text
        textSize = 19f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textAlign = Paint.Align.LEFT
        isAntiAlias = true
    }

    private val noteBadgeFillPaint = Paint().apply {
        color = Color.parseColor("#FEF3C7") // Warm Amber Fill
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val noteBadgePaint = Paint().apply {
        color = Color.parseColor("#B45309") // Amber Gold Border
        style = Paint.Style.STROKE
        strokeWidth = 2f
        isAntiAlias = true
    }

    private val eventColorPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private fun parseEventColor(colorHex: String?): Int? {
        if (colorHex.isNullOrBlank()) return null
        return runCatching { Color.parseColor(colorHex) }.getOrNull()
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

        // 1. Draw SUN..SAT weekday header row with Deep Navy & Crimson Weekend accents
        val headerBaselineY = (WEEKDAY_HEADER_HEIGHT_PX / 2f) - ((weekdayTextPaint.descent() + weekdayTextPaint.ascent()) / 2f)
        for (col in 0..6) {
            val colLeft = col * colWidth
            val colRight = colLeft + colWidth
            val label = headers.getOrElse(col) { "" }
            val isWeekend = label == "SUN" || label == "SAT"
            canvas.drawRect(colLeft, 0f, colRight, gridTop, if (isWeekend) headerWeekendBgPaint else headerBgPaint)
            val centerX = colLeft + colWidth / 2f
            canvas.drawText(label, centerX, headerBaselineY, weekdayTextPaint)
        }

        // Pre-fill cell backgrounds (Spillover slate tint or Today warm amber tint) before grid lines
        val cells = grid?.cells
        if (cells != null) {
            for (row in 0 until 6) {
                for (col in 0 until 7) {
                    val cell = cells.getOrNull(row * 7 + col) ?: continue
                    val cellLeft = col * colWidth
                    val cellTop = gridTop + row * rowHeight
                    val cellRight = cellLeft + colWidth
                    val cellBottom = cellTop + rowHeight
                    if (cell.isToday) {
                        canvas.drawRect(cellLeft, cellTop, cellRight, cellBottom, todayCellBgPaint)
                    } else if (!cell.isCurrentMonth) {
                        canvas.drawRect(cellLeft, cellTop, cellRight, cellBottom, spilloverCellBgPaint)
                    }
                }
            }
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

        if (cells == null) return
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
                    canvas.drawRect(iconLeft, iconTop, iconRight, iconBottom, noteBadgeFillPaint)
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
                    val isOverflowLine = isLastEventSlot && cell.events.size - idx > 1
                    val paint = if (ev.isAllDay && !isOverflowLine) eventAllDayPaint else eventTextPaint
                    val eventColorInt = if (!isOverflowLine) parseEventColor(ev.colorHex) else null

                    if (isOverflowLine) {
                        val fitted = ellipsizeToWidth("+${cell.events.size - idx} more", eventTextPaint, maxTextWidth)
                        canvas.drawText(fitted, cellLeft + cellPaddingX, currentY, eventTextPaint)
                    } else if (ev.isAllDay && eventColorInt != null) {
                        // Google Calendar style: soft tinted pill + solid left color bar + crisp black text
                        val r = Color.red(eventColorInt)
                        val g = Color.green(eventColorInt)
                        val b = Color.blue(eventColorInt)
                        val pillRect = RectF(
                            cellLeft + 6f,
                            currentY - 18f,
                            cellRight - 6f,
                            currentY + 5f
                        )
                        eventColorPaint.color = Color.argb(65, r, g, b)
                        canvas.drawRoundRect(pillRect, 4f, 4f, eventColorPaint)
                        eventColorPaint.color = eventColorInt
                        canvas.drawRoundRect(
                            RectF(cellLeft + 6f, currentY - 18f, cellLeft + 12f, currentY + 5f),
                            3f,
                            3f,
                            eventColorPaint
                        )
                        val fitted = ellipsizeToWidth(ev.displayLine, paint, (maxTextWidth - 10f).coerceAtLeast(16f))
                        canvas.drawText(fitted, cellLeft + 16f, currentY, paint)
                    } else if (eventColorInt != null) {
                        // Timed event with Google Calendar color: vivid colored dot + crisp black text
                        eventColorPaint.color = eventColorInt
                        canvas.drawCircle(cellLeft + cellPaddingX + 5f, currentY - 7f, 5.5f, eventColorPaint)
                        val fitted = ellipsizeToWidth(ev.displayLine, paint, (maxTextWidth - 16f).coerceAtLeast(16f))
                        canvas.drawText(fitted, cellLeft + cellPaddingX + 15f, currentY, paint)
                    } else {
                        val fitted = ellipsizeToWidth("• ${ev.displayLine}", paint, maxTextWidth)
                        canvas.drawText(fitted, cellLeft + cellPaddingX, currentY, paint)
                    }
                    currentY += lineHeight
                }

                if (hasTasks && currentY <= maxBottomY) {
                    val taskLine = if (cell.openTaskCount == 1 && cell.taskPreviews.isNotEmpty()) {
                        cell.taskPreviews.first()
                    } else {
                        "☐ ${cell.openTaskCount} task${if (cell.openTaskCount == 1) "" else "s"}"
                    }
                    val pillRect = RectF(
                        cellLeft + 6f,
                        currentY - 18f,
                        cellRight - 6f,
                        currentY + 5f
                    )
                    canvas.drawRoundRect(pillRect, 4f, 4f, taskPillFillPaint)
                    canvas.drawRoundRect(
                        RectF(cellLeft + 6f, currentY - 18f, cellLeft + 12f, currentY + 5f),
                        3f,
                        3f,
                        taskStripeFillPaint
                    )
                    val fittedTask = ellipsizeToWidth(taskLine, taskTextPaint, (maxTextWidth - 10f).coerceAtLeast(16f))
                    canvas.drawText(fittedTask, cellLeft + 16f, currentY, taskTextPaint)
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
