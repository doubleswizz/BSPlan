package com.boox.day.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class SyncWindowPlannerTest {

    @Test
    fun `defaultWindow covers full calendar months around today`() {
        val today = LocalDate.of(2026, 11, 13)
        val planner = SyncWindowPlanner(clock = { 1_000L }, todayProvider = { today })

        val (start, end) = planner.defaultWindow()

        assertEquals(LocalDate.of(2026, 9, 1), start)
        assertEquals(LocalDate.of(2027, 12, 31), end)
    }

    @Test
    fun `months inside synced default window need no additional fetch`() {
        val today = LocalDate.of(2026, 11, 13)
        val planner = SyncWindowPlanner(clock = { 1_000L }, todayProvider = { today })
        val (start, end) = planner.defaultWindow()
        planner.markSynced(start, end)

        assertNull(planner.nextFetchRangeForMonth(YearMonth.of(2026, 10)))
        assertNull(planner.nextFetchRangeForMonth(YearMonth.of(2026, 11)))
        assertNull(planner.nextFetchRangeForMonth(YearMonth.of(2027, 11)))
    }

    @Test
    fun `navigating far forward or backward fetches centered 5-month window`() {
        val today = LocalDate.of(2026, 11, 13)
        val planner = SyncWindowPlanner(clock = { 1_000L }, todayProvider = { today })
        val (defaultStart, defaultEnd) = planner.defaultWindow()
        planner.markSynced(defaultStart, defaultEnd)

        val farFuture = planner.nextFetchRangeForMonth(YearMonth.of(2029, 6))
        assertEquals(LocalDate.of(2029, 4, 1) to LocalDate.of(2029, 8, 31), farFuture)

        planner.markSynced(farFuture!!.first, farFuture.second)
        assertNull(planner.nextFetchRangeForMonth(YearMonth.of(2029, 5)))
        assertNull(planner.nextFetchRangeForMonth(YearMonth.of(2029, 6)))
        assertNull(planner.nextFetchRangeForMonth(YearMonth.of(2029, 7)))

        val farPast = planner.nextFetchRangeForMonth(YearMonth.of(2021, 2))
        assertEquals(LocalDate.of(2020, 12, 1) to LocalDate.of(2021, 4, 30), farPast)
    }

    @Test
    fun `stepping to cached window edge extends outward without refetching already cached months`() {
        val today = LocalDate.of(2026, 11, 13)
        val planner = SyncWindowPlanner(clock = { 1_000L }, todayProvider = { today })
        val (defaultStart, defaultEnd) = planner.defaultWindow()
        planner.markSynced(defaultStart, defaultEnd)

        // At 2027-12, 2028-01 overflow days aren't cached yet -> extend forward 2028-01..2028-03
        assertEquals(
            LocalDate.of(2028, 1, 1) to LocalDate.of(2028, 3, 31),
            planner.nextFetchRangeForMonth(YearMonth.of(2027, 12))
        )

        // At 2026-09, 2026-08 overflow days aren't cached yet -> extend backward 2026-06..2026-08
        assertEquals(
            LocalDate.of(2026, 6, 1) to LocalDate.of(2026, 8, 31),
            planner.nextFetchRangeForMonth(YearMonth.of(2026, 9))
        )
    }

    @Test
    fun `future months refresh after TTL while past months stay cached until manual clear`() {
        var nowMs = 1_000L
        val today = LocalDate.of(2026, 11, 13)
        val planner = SyncWindowPlanner(clock = { nowMs }, todayProvider = { today })

        planner.markSynced(LocalDate.of(2025, 1, 1), LocalDate.of(2028, 1, 31))
        assertNull(planner.nextFetchRangeForMonth(YearMonth.of(2025, 6)))
        assertNull(planner.nextFetchRangeForMonth(YearMonth.of(2027, 6)))

        nowMs += SyncWindowPlanner.CURRENT_OR_FUTURE_TTL_MS

        // Past month remains cached
        assertNull(planner.nextFetchRangeForMonth(YearMonth.of(2025, 6)))
        // Future month becomes stale after TTL
        assertEquals(
            LocalDate.of(2027, 4, 1) to LocalDate.of(2027, 8, 31),
            planner.nextFetchRangeForMonth(YearMonth.of(2027, 6))
        )

        planner.clear()
        assertEquals(
            LocalDate.of(2025, 4, 1) to LocalDate.of(2025, 8, 31),
            planner.nextFetchRangeForMonth(YearMonth.of(2025, 6))
        )
    }

    @Test
    fun `shouldMarkSynced rejects network and auth failures`() {
        val planner = SyncWindowPlanner()

        assertTrue(planner.shouldMarkSynced(mapOf("a1" to SyncResult.Success(10, 2, 1))))
        assertFalse(planner.shouldMarkSynced(emptyMap()))
        assertFalse(
            planner.shouldMarkSynced(
                mapOf(
                    "a1" to SyncResult.Success(10, 2, 1),
                    "a2" to SyncResult.NoNetwork()
                )
            )
        )
        assertFalse(planner.shouldMarkSynced(mapOf("a1" to SyncResult.NotSignedIn())))
        assertFalse(planner.shouldMarkSynced(mapOf("a1" to SyncResult.Error("boom"))))
    }
}
