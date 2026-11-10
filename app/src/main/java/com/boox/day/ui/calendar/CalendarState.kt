package com.boox.day.ui.calendar

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Top-level PDF-planner view modes for BSPlan:
 * - [Month]: Full-page 6-row month calendar grid (with spillover days and stylus annotation)
 * - [Day]: Full-page day planner (Schedule + Tasks top dashboard, freeform notes / Tuesday 5-section Staff Meeting below)
 */
enum class PlannerViewMode {
    Month,
    Day
}

/**
 * Global session state for BSPlan's PDF-planner navigation between Month View and Day View.
 */
object PlannerNavigationState {
    var mode by mutableStateOf(PlannerViewMode.Month)
}

/**
 * Immutable wrapper for calendar badge data to prevent unnecessary recompositions of CalendarGrid.
 * Compose considers Set<T> unstable, so wrapping it in an @Immutable class ensures stability.
 */
@Immutable
data class CalendarBadges(
    val datesWithNotes: Set<String> = emptySet(),
    val datesWithEvents: Set<String> = emptySet(),
    val datesWithBooxNotes: Set<String> = emptySet(),
    val datesWithTasks: Set<String> = emptySet()
)
