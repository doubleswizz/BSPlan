package com.bsplan.app.ai.intelligence.scorers

import com.bsplan.app.data.ai.ScorerResult
import com.bsplan.app.data.ai.ScoringInput

/**
 * Base interface for all scoring components.
 *
 * Each scorer analyzes items from one perspective and produces:
 * - A raw score (0.0 to 1.0) for each item
 * - Human-readable signals explaining the score
 * - Optional metadata for debugging/analytics
 */
interface Scorer {
    /** Unique identifier for this scorer */
    val id: String

    /**
     * Score all items in the input.
     *
     * @param input Full scoring context including preferences, calendar, etc.
     * @return ScorerResult with scores and signals for each item
     */
    suspend fun score(input: ScoringInput): ScorerResult

    /**
     * Whether this scorer is applicable for the given feature.
     * Some scorers may be skipped for certain features.
     */
    fun isApplicable(input: ScoringInput): Boolean = true
}
