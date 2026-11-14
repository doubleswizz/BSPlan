package com.boox.day.ui.calendar

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.time.LocalDate

/**
 * Top-level PDF-planner view modes for BSPlan:
 * - [Month]: Full-page dynamic 4–6 row month calendar grid (with spillover days and stylus annotation)
 * - [Day]: Full-page day planner (Schedule + Tasks top dashboard, freeform notes / Tuesday 5-section Staff Meeting below)
 */
enum class PlannerViewMode {
    Month,
    Day
}

/**
 * Global session state for BSPlan's PDF-planner navigation between Month View and Day View.
 * Also persists the user's last viewed mode, year, month, and selected date so the app resumes
 * right where the user left off across app restarts.
 */
object PlannerNavigationState {
    private const val PREFS_NAME = "bsplan_navigation_state"
    private const val KEY_MODE = "view_mode"
    private const val KEY_SELECTED_DATE = "selected_date"
    private const val KEY_VIEWING_YEAR = "viewing_year"
    private const val KEY_VIEWING_MONTH = "viewing_month_zero_based"

    private var prefs: SharedPreferences? = null

    var mode by mutableStateOf(PlannerViewMode.Month)
    var savedSelectedDate: String? = null
        private set
    var savedViewingYear: Int? = null
        private set
    var savedViewingMonthZeroBased: Int? = null
        private set

    /** Callback set by [MonthPlannerEffects] so header popups can jump to any (year, monthZeroBased). */
    var onSelectMonthYear: ((Int, Int) -> Unit)? = null

    fun init(context: Context) {
        if (prefs != null) return
        val sp = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = sp
        val storedMode = sp.getString(KEY_MODE, null)
        mode = if (storedMode == PlannerViewMode.Day.name) PlannerViewMode.Day else PlannerViewMode.Month
        savedSelectedDate = sp.getString(KEY_SELECTED_DATE, null)?.takeIf {
            runCatching { LocalDate.parse(it) }.isSuccess
        }
        if (sp.contains(KEY_VIEWING_YEAR) && sp.contains(KEY_VIEWING_MONTH)) {
            savedViewingYear = sp.getInt(KEY_VIEWING_YEAR, LocalDate.now().year)
            savedViewingMonthZeroBased = sp.getInt(KEY_VIEWING_MONTH, LocalDate.now().monthValue - 1).coerceIn(0, 11)
        }
    }

    fun saveState(
        context: Context,
        currentMode: PlannerViewMode,
        selectedDate: String,
        viewingYear: Int,
        viewingMonthZeroBased: Int
    ) {
        init(context)
        savedSelectedDate = selectedDate
        savedViewingYear = viewingYear
        savedViewingMonthZeroBased = viewingMonthZeroBased
        prefs?.edit()
            ?.putString(KEY_MODE, currentMode.name)
            ?.putString(KEY_SELECTED_DATE, selectedDate)
            ?.putInt(KEY_VIEWING_YEAR, viewingYear)
            ?.putInt(KEY_VIEWING_MONTH, viewingMonthZeroBased.coerceIn(0, 11))
            ?.apply()
    }
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
