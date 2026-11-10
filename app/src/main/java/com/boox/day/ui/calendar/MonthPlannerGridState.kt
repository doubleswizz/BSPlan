package com.boox.day.ui.calendar

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.boox.day.data.datastore.AppSettings
import com.boox.day.data.db.AppDatabase
import com.boox.day.data.db.CalendarEvent
import com.boox.day.data.db.Task
import com.boox.day.share.HandwrittenRows
import com.boox.day.week.WeekLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Single event summary line inside a Month View day cell.
 */
@Immutable
data class MonthCellEventItem(
    val id: String,
    val displayLine: String,
    val isAllDay: Boolean
)

/**
 * Data for one of the 42 day cells (6 rows × 7 columns) on the Full-Page Month View,
 * including spillover days from the previous and next month.
 */
@Immutable
data class MonthCellData(
    val date: LocalDate,
    val dateStr: String,
    val dayOfMonth: Int,
    val isCurrentMonth: Boolean,
    val isToday: Boolean,
    val isSelected: Boolean,
    val headerLabel: String,
    val events: List<MonthCellEventItem>,
    val openTaskCount: Int,
    val completedTaskCount: Int,
    val taskPreviews: List<String>,
    val hasDayNotes: Boolean
)

/**
 * Complete 6-row × 7-column (42-cell) month grid model for the Full-Page Month View.
 */
@Immutable
data class MonthGridModel(
    val year: Int,
    val monthZeroBased: Int,
    val weekdayHeaders: List<String>,
    val cells: List<MonthCellData>
)

/**
 * Holds the active 42-cell [MonthGridModel] so both the Compose touch overlay and
 * the native E-Ink background canvas renderer ([com.boox.day.editor.drawing.MonthPlannerCanvasRenderer])
 * read the exact same cell coordinates and data.
 */
object MonthPlannerGridState {
    @Volatile
    var currentGrid by mutableStateOf<MonthGridModel?>(null)

    private val shortMonthFormatter = DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())

    /**
     * Returns the DayPage database key for the Month View canvas of a given year and 0-based month.
     */
    fun monthPageKey(year: Int, monthZeroBased: Int): String =
        String.format(Locale.US, "MONTH-%04d-%02d", year, monthZeroBased + 1)

    /**
     * Computes the 42-day grid (6 rows × 7 columns) including spillover days from the previous
     * and next month, and loads all Google Calendar events and Google Tasks for every cell.
     */
    suspend fun loadMonthGrid(
        context: Context,
        year: Int,
        monthZeroBased: Int,
        selectedDate: String,
        firstDayOfWeek: AppSettings.FirstDayOfWeek,
        datesWithNotes: Set<String>,
        loadEventsForDay: suspend (String) -> List<CalendarEvent>
    ): MonthGridModel = withContext(Dispatchers.IO) {
        val yearMonth = YearMonth.of(year, monthZeroBased + 1)
        val firstOfMonth = yearMonth.atDay(1)
        val startDayOfWeek = when (firstDayOfWeek) {
            AppSettings.FirstDayOfWeek.Monday -> DayOfWeek.MONDAY
            else -> DayOfWeek.SUNDAY
        }

        val leadingDays = ((firstOfMonth.dayOfWeek.value - startDayOfWeek.value) + 7) % 7
        val gridStart = firstOfMonth.minusDays(leadingDays.toLong())
        val gridEnd = gridStart.plusDays(41)
        val today = LocalDate.now()
        val zone = ZoneId.systemDefault()

        val weekdayHeaders = if (startDayOfWeek == DayOfWeek.MONDAY) {
            listOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")
        } else {
            listOf("SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT")
        }

        val appContext = context.applicationContext
        val db = AppDatabase.getDatabase(appContext)
        val taskDao = db.taskDao()
        val handwriting = HandwrittenRows(appContext, db.openHelper.readableDatabase)

        fun taskHasContent(task: Task): Boolean =
            WeekLoader.text(task) != null || handwriting.inRow(task.pageId, task.dayDate, task.rowIndex)

        val startStr = gridStart.toString()
        val endStr = gridEnd.toString()

        val writtenTasksByDay = taskDao.getTasksForDateRange(startStr, endStr)
            .filter { it.parentTaskId == null && taskHasContent(it) }
            .groupBy { it.dayDate }

        val dueTasksByDay = taskDao.getTasksDueBetween(startStr, endStr)
            .filter { it.parentTaskId == null && it.dueDay != it.dayDate }
            .groupBy { it.dueDay }

        val cells = (0L..41L).map { offset ->
            val date = gridStart.plusDays(offset)
            val dateStr = date.toString()
            val isCurrentMonth = date.year == year && date.monthValue == (monthZeroBased + 1)
            val headerLabel = if (!isCurrentMonth || date.dayOfMonth == 1) {
                shortMonthFormatter.format(date)
            } else {
                date.dayOfMonth.toString()
            }

            val dayEvents = loadEventsForDay(dateStr).map { event ->
                val prefix = if (event.isAllDay) {
                    ""
                } else {
                    val zdt = Instant.ofEpochMilli(event.startTime).atZone(zone)
                    formatCompactTime(zdt.hour, zdt.minute) + " "
                }
                MonthCellEventItem(
                    id = event.id,
                    displayLine = "$prefix${event.title}".trim(),
                    isAllDay = event.isAllDay
                )
            }

            val dayTasks = (writtenTasksByDay[dateStr].orEmpty() + dueTasksByDay[dateStr].orEmpty())
                .distinctBy { it.id }
            val openTasks = dayTasks.filter { !it.isCompleted }
            val completedTasks = dayTasks.filter { it.isCompleted }
            val taskPreviews = openTasks.mapNotNull { task ->
                val label = WeekLoader.text(task) ?: "Handwritten task"
                "☐ $label"
            }

            MonthCellData(
                date = date,
                dateStr = dateStr,
                dayOfMonth = date.dayOfMonth,
                isCurrentMonth = isCurrentMonth,
                isToday = date == today,
                isSelected = dateStr == selectedDate,
                headerLabel = headerLabel,
                events = dayEvents,
                openTaskCount = openTasks.size,
                completedTaskCount = completedTasks.size,
                taskPreviews = taskPreviews,
                hasDayNotes = dateStr in datesWithNotes
            )
        }

        MonthGridModel(
            year = year,
            monthZeroBased = monthZeroBased,
            weekdayHeaders = weekdayHeaders,
            cells = cells
        )
    }

    private fun formatCompactTime(hour24: Int, minute: Int): String {
        val amPm = if (hour24 < 12) "a" else "p"
        val hour12 = when {
            hour24 == 0 -> 12
            hour24 > 12 -> hour24 - 12
            else -> hour24
        }
        return if (minute == 0) {
            "$hour12$amPm"
        } else {
            String.format(Locale.US, "%d:%02d%s", hour12, minute, amPm)
        }
    }
}
