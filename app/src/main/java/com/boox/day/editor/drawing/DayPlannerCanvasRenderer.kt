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
    const val SECTION_HEADER_HEIGHT_PX = 56f
    const val DASHBOARD_MAX_ROWS = 5
    private const val WRITING_LINE_SPACING_PX = 54f

    private val strongBorderPaint = Paint().apply {
        color = Color.parseColor("#0F172A") // Deep Navy
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        isAntiAlias = true
    }

    private val thinBorderPaint = Paint().apply {
        color = Color.parseColor("#475569")
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        isAntiAlias = true
    }

    private val writingLinePaint = Paint().apply {
        color = Color.parseColor("#BFDBFE") // Classic sky-blue notebook ruling lines
        style = Paint.Style.STROKE
        strokeWidth = 1.3f
        isAntiAlias = true
    }

    private val sectionHeaderPaint = Paint().apply {
        color = Color.parseColor("#0F172A")
        textSize = 31f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textAlign = Paint.Align.LEFT
        isAntiAlias = true
    }

    private val sectionHeaderBgPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val sectionHeaderStripePaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val rowTimePaint = Paint().apply {
        color = Color.parseColor("#0F172A")
        textSize = 29f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textAlign = Paint.Align.LEFT
        isAntiAlias = true
    }

    private val rowTitlePaint = Paint().apply {
        color = Color.parseColor("#0F172A")
        textSize = 29f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        textAlign = Paint.Align.LEFT
        isAntiAlias = true
    }

    private val rowCompletedTitlePaint = Paint().apply {
        color = Color.parseColor("#64748B")
        textSize = 29f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        textAlign = Paint.Align.LEFT
        isStrikeThruText = true
        isAntiAlias = true
    }

    private val chevronPaint = Paint().apply {
        color = Color.parseColor("#1E3A8A")
        textSize = 26f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.RIGHT
        isAntiAlias = true
    }

    private val checkboxStrokePaint = Paint().apply {
        color = Color.parseColor("#059669") // Emerald Green checkbox border
        style = Paint.Style.STROKE
        strokeWidth = 2.2f
        isAntiAlias = true
    }

    private val checkboxFillPaint = Paint().apply {
        color = Color.parseColor("#10B981") // Emerald Green filled checkbox
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
        drawSectionHeader(
            canvas = canvas,
            left = 0f,
            top = 0f,
            right = midX,
            title = "TODAY'S SCHEDULE",
            bgColor = Color.parseColor("#DBEAFE"),
            stripeColor = Color.parseColor("#2563EB")
        )
        drawSectionHeader(
            canvas = canvas,
            left = midX,
            top = 0f,
            right = width,
            title = "GOOGLE TASKS",
            bgColor = Color.parseColor("#D1FAE5"),
            stripeColor = Color.parseColor("#10B981")
        )

        // Vertical divider between Schedule and Tasks
        canvas.drawLine(midX, 0f, midX, dashboardBottomY, strongBorderPaint)

        // Double-line separator under Top Dashboard
        canvas.drawLine(0f, dashboardBottomY - 3f, width, dashboardBottomY - 3f, strongBorderPaint)
        canvas.drawLine(0f, dashboardBottomY + 2f, width, dashboardBottomY + 2f, strongBorderPaint)

        val rowsTopY = SECTION_HEADER_HEIGHT_PX
        val rowsAreaHeight = (dashboardBottomY - rowsTopY - 4f).coerceAtLeast(50f)
        val rowHeight = rowsAreaHeight / DASHBOARD_MAX_ROWS

        val chevronReservePx = 68f

        // Draw Schedule rows (Left)
        val scheduleRows = model?.scheduleRows.orEmpty()
        for (i in 0 until DASHBOARD_MAX_ROWS) {
            val rowTop = rowsTopY + i * rowHeight
            val rowBottom = rowTop + rowHeight
            if (i < DASHBOARD_MAX_ROWS - 1) {
                canvas.drawLine(12f, rowBottom, midX - 12f, rowBottom, writingLinePaint)
            }
            val item = scheduleRows.getOrNull(i) ?: continue
            val baselineY = rowTop + (rowHeight / 2f) - ((rowTitlePaint.descent() + rowTitlePaint.ascent()) / 2f)
            val colorInt = parseEventColor(item.event.colorHex)
            val timeStartX: Float
            val timeStr: String
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
                timeStartX = 28f
                timeStr = item.timeText
            } else {
                timeStartX = 16f
                timeStr = "• ${item.timeText}"
            }
            canvas.drawText(timeStr, timeStartX, baselineY, rowTimePaint)

            val titleStartX = maxOf(196f, timeStartX + rowTimePaint.measureText(timeStr) + 16f)
            val maxTitleWidth = (midX - titleStartX - chevronReservePx).coerceAtLeast(20f)
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
            val boxSize = 28f
            val boxLeft = midX + 18f
            val boxTop = rowTop + (rowHeight - boxSize) / 2f
            val boxRect = RectF(boxLeft, boxTop, boxLeft + boxSize, boxTop + boxSize)

            val taskItem = taskRows.getOrNull(i)
            if (taskItem?.isCompleted == true) {
                canvas.drawRoundRect(boxRect, 5f, 5f, checkboxFillPaint)
                canvas.drawLine(boxLeft + 5f, boxTop + 14f, boxLeft + 11f, boxTop + 21f, checkMarkPaint)
                canvas.drawLine(boxLeft + 11f, boxTop + 21f, boxLeft + 23f, boxTop + 7f, checkMarkPaint)
            } else {
                canvas.drawRoundRect(boxRect, 5f, 5f, checkboxStrokePaint)
            }

            if (taskItem != null) {
                val baselineY = rowTop + (rowHeight / 2f) - ((rowTitlePaint.descent() + rowTitlePaint.ascent()) / 2f)
                val titleStartX = boxLeft + boxSize + 16f
                val maxTitleWidth = (width - titleStartX - chevronReservePx).coerceAtLeast(20f)
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
            drawSectionBoxWithLines(
                canvas = canvas,
                left = 0f,
                top = notesTopY,
                right = midX,
                bottom = row1BottomY,
                title = "CALENDAR",
                bgColor = Color.parseColor("#DBEAFE"),
                stripeColor = Color.parseColor("#1E3A8A")
            )
            drawSectionBoxWithLines(
                canvas = canvas,
                left = midX,
                top = notesTopY,
                right = width,
                bottom = row1BottomY,
                title = "OLD BUSINESS",
                bgColor = Color.parseColor("#FEF3C7"),
                stripeColor = Color.parseColor("#D97706")
            )

            // Row 2: NEW BUSINESS (left) | ROUNDTABLE (right)
            drawSectionBoxWithLines(
                canvas = canvas,
                left = 0f,
                top = row1BottomY,
                right = midX,
                bottom = row2BottomY,
                title = "NEW BUSINESS",
                bgColor = Color.parseColor("#D1FAE5"),
                stripeColor = Color.parseColor("#059669")
            )
            drawSectionBoxWithLines(
                canvas = canvas,
                left = midX,
                top = row1BottomY,
                right = width,
                bottom = row2BottomY,
                title = "ROUNDTABLE",
                bgColor = Color.parseColor("#EDE9FE"),
                stripeColor = Color.parseColor("#7C3AED")
            )

            // Row 3: ACTION ITEMS (full width)
            drawSectionBoxWithLines(
                canvas = canvas,
                left = 0f,
                top = row2BottomY,
                right = width,
                bottom = height,
                title = "ACTION ITEMS",
                bgColor = Color.parseColor("#FEE2E2"),
                stripeColor = Color.parseColor("#D32F2F")
            )

            // Bold section dividers
            canvas.drawLine(midX, notesTopY, midX, row2BottomY, strongBorderPaint)
            canvas.drawLine(0f, row1BottomY, width, row1BottomY, strongBorderPaint)
            canvas.drawLine(0f, row2BottomY, width, row2BottomY, strongBorderPaint)
        } else {
            drawSectionBoxWithLines(
                canvas = canvas,
                left = 0f,
                top = notesTopY,
                right = width,
                bottom = height,
                title = "NOTES",
                bgColor = Color.parseColor("#FEF3C7"),
                stripeColor = Color.parseColor("#F59E0B")
            )
        }
    }

    private fun drawSectionHeader(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        title: String,
        bgColor: Int = Color.parseColor("#F1F5F9"),
        stripeColor: Int = Color.parseColor("#1E3A8A")
    ) {
        val headerBottom = top + SECTION_HEADER_HEIGHT_PX
        sectionHeaderBgPaint.color = bgColor
        canvas.drawRect(left, top, right, headerBottom, sectionHeaderBgPaint)
        sectionHeaderStripePaint.color = stripeColor
        canvas.drawRect(left, top, left + 10f, headerBottom, sectionHeaderStripePaint)
        val baselineY = top + (SECTION_HEADER_HEIGHT_PX / 2f) - ((sectionHeaderPaint.descent() + sectionHeaderPaint.ascent()) / 2f)
        canvas.drawText(title, left + 22f, baselineY, sectionHeaderPaint)
        canvas.drawLine(left, headerBottom, right, headerBottom, thinBorderPaint)
    }

    private fun drawSectionBoxWithLines(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        title: String,
        bgColor: Int = Color.parseColor("#F1F5F9"),
        stripeColor: Int = Color.parseColor("#1E3A8A")
    ) {
        drawSectionHeader(canvas, left, top, right, title, bgColor, stripeColor)
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
