package com.boox.day.ai.intelligence.scorers

import com.boox.day.ai.intelligence.IntelligenceTestUtils.createScoringInput
import com.boox.day.ai.intelligence.IntelligenceTestUtils.createTask
import com.boox.day.ai.intelligence.IntelligenceTestUtils.createTaskItem
import com.boox.day.data.ai.AiPreferences
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The preference score follows only the kept AI options (29 Oct 2026): Vacation lowers work items,
 * Health or Social suggestions off lower those items, and the removed AI Preferences answers (life
 * priorities, crunch and holiday modes, lifestyle) change nothing.
 */
class PreferenceScorerTest {

    private val scorer = PreferenceScorer()
    private val work = createTask(id = "work", text = "Finish project report for client")
    private val health = createTask(id = "health", text = "Go to the gym for exercise")
    private val social = createTask(id = "social", text = "Coffee with a friend")
    private val other = createTask(id = "other", text = "Buy stamps")
    private val items = listOf(work, health, social, other).map { createTaskItem(it) }

    private fun scores(prefs: AiPreferences?) = runBlocking {
        scorer.score(createScoringInput(items, preferences = prefs)).scores
    }

    private fun applies(prefs: AiPreferences?) = scorer.isApplicable(createScoringInput(items, preferences = prefs))

    @Test
    fun `no option set - the scorer stands aside`() {
        assertFalse(applies(null))
        assertFalse(applies(AiPreferences()))
        // The removed answers don't make it apply
        assertFalse(applies(AiPreferences(workPriority = 5, crunchMode = true, holidayMode = true, setupComplete = true)))
    }

    @Test
    fun `removed options leave scorer non-applicable and neutral`() {
        val oldOptions = AiPreferences(
            vacationMode = true,
            includeHealthReminders = false,
            includeSocialReminders = false,
            workPriority = 1, healthPriority = 5, familyPriority = 5,
            crunchMode = true, isSocialButterfly = true, setupComplete = true
        )
        assertFalse(applies(oldOptions))
        val result = scores(oldOptions)
        assertEquals(0.5f, result.getValue("work"), 0.001f)
        assertEquals(0.5f, result.getValue("health"), 0.001f)
        assertEquals(0.5f, result.getValue("social"), 0.001f)
        assertEquals(0.5f, result.getValue("other"), 0.001f)
    }
}
