package com.boox.day.ui.calendar

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.boox.day.data.db.CalendarEvent
import com.boox.day.data.db.Task
import com.boox.day.week.WeekLoader
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

@Immutable
data class DayScheduleRow(
    val event: CalendarEvent,
    val timeText: String,
    val title: String
)

@Immutable
data class DayTaskRow(
    val task: Task,
    val title: String,
    val isCompleted: Boolean
)

@Immutable
data class DayPlannerModel(
    val dateStr: String,
    val date: LocalDate,
    val isTuesday: Boolean,
    val scheduleRows: List<DayScheduleRow>,
    val taskRows: List<DayTaskRow>
)

/**
 * Shared state for the Full-Page Day View so both the background E-Ink canvas renderer
 * ([com.boox.day.editor.drawing.DayPlannerCanvasRenderer]) and the finger-tap management overlay
 * ([DayPlannerTouchOverlay]) use identical row coordinates and items.
 */
object DayPlannerState {
    @Volatile
    var currentDayModel by mutableStateOf<DayPlannerModel?>(null)

    fun buildModel(
        dateStr: String,
        events: List<CalendarEvent>,
        tasks: List<Task>
    ): DayPlannerModel {
        val parsedDate = runCatching { LocalDate.parse(dateStr) }.getOrDefault(LocalDate.now())
        val isTuesday = parsedDate.dayOfWeek == DayOfWeek.TUESDAY
        val zone = ZoneId.systemDefault()

        val scheduleRows = events.map { event ->
            val timeLabel = if (event.isAllDay) {
                "All Day"
            } else {
                val zdt = Instant.ofEpochMilli(event.startTime).atZone(zone)
                formatTime(zdt.hour, zdt.minute)
            }
            DayScheduleRow(
                event = event,
                timeText = timeLabel,
                title = event.title
            )
        }

        val meaningfulTasks = tasks.filter { task ->
            !WeekLoader.text(task).isNullOrBlank() || task.isCompleted || task.priority.isNotBlank() || task.dueDay != null
        }

        val taskRows = meaningfulTasks.map { task ->
            DayTaskRow(
                task = task,
                title = WeekLoader.text(task) ?: "Handwritten task",
                isCompleted = task.isCompleted
            )
        }

        return DayPlannerModel(
            dateStr = dateStr,
            date = parsedDate,
            isTuesday = isTuesday,
            scheduleRows = scheduleRows,
            taskRows = taskRows
        )
    }

    private fun formatTime(hour24: Int, minute: Int): String {
        val amPm = if (hour24 < 12) "a" else "p"
        val hour12 = when {
            hour24 == 0 -> 12
            hour24 > 12 -> hour24 - 12
            else -> hour24
        }
        return String.format(Locale.US, "%d:%02d%s", hour12, minute, amPm)
    }
}
