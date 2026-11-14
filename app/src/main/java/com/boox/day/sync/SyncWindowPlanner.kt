package com.boox.day.sync

import java.time.LocalDate
import java.time.YearMonth

/**
 * Tracks synced calendar months in memory and computes on-demand sliding-window fetch ranges so
 * users can browse indefinitely forward or backward in BSPlan (like the Google Calendar app) while
 * keeping initial sync bounded and skipping redundant network calls for cached months.
 */
internal class SyncWindowPlanner(
    private val clock: () -> Long = System::currentTimeMillis,
    private val todayProvider: () -> LocalDate = LocalDate::now
) {
    companion object {
        internal const val ON_DEMAND_PADDING_MONTHS = 2L
        internal const val EDGE_EXTENSION_MONTHS = 2L
        internal const val CURRENT_OR_FUTURE_TTL_MS = 15L * 60L * 1000L
    }

    private val syncedMonths = mutableMapOf<YearMonth, Long>()

    fun defaultWindow(today: LocalDate = todayProvider()): Pair<LocalDate, LocalDate> {
        val currentMonth = YearMonth.from(today)
        val startMonth = currentMonth.minusMonths(SyncScheduler.DEFAULT_MONTHS_BEFORE.toLong())
        val endMonth = currentMonth.plusMonths(SyncScheduler.DEFAULT_MONTHS_AFTER.toLong())
        return startMonth.atDay(1) to endMonth.atEndOfMonth()
    }

    fun clear() {
        syncedMonths.clear()
    }

    fun markSynced(startDate: LocalDate, endDate: LocalDate) {
        if (endDate.isBefore(startDate)) return
        val syncedAt = clock()
        var month = if (startDate.dayOfMonth == 1) {
            YearMonth.from(startDate)
        } else {
            YearMonth.from(startDate).plusMonths(1)
        }
        while (!month.atEndOfMonth().isAfter(endDate)) {
            syncedMonths[month] = syncedAt
            month = month.plusMonths(1)
        }
    }

    fun shouldMarkSynced(results: Map<String, SyncResult>): Boolean {
        if (results.isEmpty()) return false
        val hasSuccess = results.values.any { it is SyncResult.Success }
        val hasFailure = results.values.any {
            it is SyncResult.NoNetwork || it is SyncResult.Error || it is SyncResult.NotSignedIn
        }
        return hasSuccess && !hasFailure
    }

    fun nextFetchRangeForMonth(target: YearMonth): Pair<LocalDate, LocalDate>? {
        val prev = target.minusMonths(1)
        val next = target.plusMonths(1)
        val targetNeeds = needsSync(target)
        val prevNeeds = needsSync(prev)
        val nextNeeds = needsSync(next)

        if (!targetNeeds && !prevNeeds && !nextNeeds) return null

        val (startMonth, endMonth) = when {
            targetNeeds || (prevNeeds && nextNeeds) -> {
                target.minusMonths(ON_DEMAND_PADDING_MONTHS) to target.plusMonths(ON_DEMAND_PADDING_MONTHS)
            }
            prevNeeds -> {
                prev.minusMonths(EDGE_EXTENSION_MONTHS) to prev
            }
            else -> {
                next to next.plusMonths(EDGE_EXTENSION_MONTHS)
            }
        }
        return startMonth.atDay(1) to endMonth.atEndOfMonth()
    }

    private fun needsSync(month: YearMonth): Boolean {
        val syncedAt = syncedMonths[month] ?: return true
        val currentMonth = YearMonth.from(todayProvider())
        if (month.isBefore(currentMonth)) return false
        return (clock() - syncedAt) >= CURRENT_OR_FUTURE_TTL_MS
    }
}
