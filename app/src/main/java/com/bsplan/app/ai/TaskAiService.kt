package com.bsplan.app.ai

import android.graphics.Bitmap
import android.util.Log
import com.bsplan.app.data.ai.ScoredContext
import com.bsplan.app.data.db.Task
import com.bsplan.app.ocr.TaskOcrService
import com.google.gson.annotations.SerializedName
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Local task service for priority sorting and voice/task helpers.
 */
object TaskAiService {
    private const val TAG = "TaskAiService"
    private val multiWhitespaceRegex = Regex("\\s+")

    // ═══════════════════════════════════════════════════════════════════════════
    // DATA CLASSES
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Parsed result from Voice to Task.
     * Supports multiple tasks in a single voice command.
     */
    data class VoiceTaskResult(
        val tasks: List<ParsedTask>,
        val confidence: Float,
        val originalIntent: String,
        val error: String? = null
    ) {
        val isSuccess: Boolean get() = error == null && tasks.isNotEmpty()
        val hasValidTasks: Boolean get() = tasks.isNotEmpty() && tasks.any { it.text.isNotBlank() }

        companion object {
            fun empty(error: String) = VoiceTaskResult(
                tasks = emptyList(),
                confidence = 0f,
                originalIntent = "",
                error = error
            )
        }
    }

    /**
     * A single parsed task from voice input.
     */
    data class ParsedTask(
        @SerializedName("text") val text: String,
        @SerializedName("date") val date: String?, // yyyy-MM-dd format
        @SerializedName("priority") val priority: String?, // "high", "medium", "low"
        @SerializedName("isFollowUp") val isFollowUp: Boolean = false
    )

    /**
     * JSON structure expected from LLM for voice input.
     */
    private data class VoiceTaskResponse(
        @SerializedName("tasks") val tasks: List<ParsedTask>,
        @SerializedName("confidence") val confidence: Float,
        @SerializedName("originalIntent") val originalIntent: String
    )

    /**
     * Result from handwriting recognition.
     */
    data class RecognitionResult(
        val recognizedTasks: List<RecognizedTask>,
        val error: String? = null
    ) {
        val isSuccess: Boolean get() = error == null && recognizedTasks.isNotEmpty()
    }

    /**
     * A single recognized task from handwriting.
     */
    data class RecognizedTask(
        val taskIndex: Int, // 0-based index in the task list
        val pageNumber: Int,
        val originalStrokes: Boolean, // true if had handwritten strokes
        val recognizedText: String
    )

    /**
     * Stats for handwriting recognition transparency.
     */
    data class OcrStats(
        val detected: Int,    // Rows with ink found
        val recognized: Int,  // Successfully read as text
        val skipped: Int      // Could not read / unclear
    )

    /**
     * Result from Sort by Priority.
     */
    data class SortResult(
        val sortedTasks: List<SortedTask>,
        val reasoning: String,
        val ocrStats: OcrStats? = null,
        val error: String? = null
    ) {
        val isSuccess: Boolean get() = error == null && sortedTasks.isNotEmpty()
    }

    /**
     * A task with its AI-assigned priority.
     * Includes original task metadata for proper apply logic.
     */
    data class SortedTask(
        val originalIndex: Int,       // Original rowIndex within the page
        val originalPage: Int,        // taskPageIndex
        val text: String,             // Task text content (typed or OCR'd)
        val suggestedPriority: Int,   // 1 = highest, higher number = lower priority
        val reason: String,           // AI's reasoning for this priority
        val userPriority: String = "",// User's original priority (A/B/C or empty)
        val isCompleted: Boolean = false, // Original completion status
        val taskId: String = "",      // Original task ID for direct updates
        val isHandwritten: Boolean = false, // True if text was OCR'd from handwriting
        val isChild: Boolean = false  // True if this is a subtask (child)
    )

    /**
     * Processing state for UI feedback.
     */
    sealed class ProcessingState {
        data object Idle : ProcessingState()
        data object Recording : ProcessingState()
        data object Transcribing : ProcessingState()
        data object Processing : ProcessingState()
        data class Success(val message: String) : ProcessingState()
        data class Error(val message: String) : ProcessingState()
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // DAILY SUMMARY DATA CLASSES
    // ═══════════════════════════════════════════════════════════════════════════
    // TASK BREAKDOWN DATA CLASSES
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * A single subtask from task breakdown.
     */
    data class SubTask(
        @SerializedName("text") val text: String,
        @SerializedName("order") val order: Int // Suggested sequence
    )

    /**
     * Result from task breakdown.
     */
    data class BreakdownResult(
        val originalTask: String,
        val subtasks: List<SubTask>,
        val keepOriginal: Boolean, // AI recommendation
        val error: String? = null
    )

    /**
     * JSON structure expected from LLM for breakdown.
     */
    private data class BreakdownResponse(
        @SerializedName("subtasks") val subtasks: List<SubTask>,
        @SerializedName("keepOriginal") val keepOriginal: Boolean = false
    )

    /**
     * A task eligible for breakdown, with support for handwritten content.
     * Used in the selection dialog to show both typed and OCR'd tasks.
     */
    data class TaskForBreakdown(
        val task: Task,
        val displayText: String,       // typedText or OCR'd text
        val isHandwritten: Boolean,    // true if text was OCR'd from strokes
        val ocrConfidence: Float = 1.0f
    )

    /**
     * Result from grouped task breakdown with placement information.
     * Includes the target location for the parent + subtasks group.
     */
    data class GroupedBreakdownResult(
        val originalTask: String,
        val subtasks: List<SubTask>,
        val placementPageIndex: Int,   // Page where the group will be placed
        val placementStartRow: Int,    // Starting row index for the group
        val movedToNewPage: Boolean,   // True if moved from current page
        val error: String? = null
    )

    // ═══════════════════════════════════════════════════════════════════════════
    // CACHE DATA CLASSES
    // ═══════════════════════════════════════════════════════════════════════════

    // ═══════════════════════════════════════════════════════════════════════════
    // VOICE TO TASK
    // ═══════════════════════════════════════════════════════════════════════════

    // ═══════════════════════════════════════════════════════════════════════════
    // NEW UNIFIED API - Used by TranscriptionService integration
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Parse pre-transcribed text into structured task data.
     *
     * This is the NEW primary API for TranscriptionService integration.
     * Skips Stage 1 (audio transcription) since TranscriptionService already handled it.
     *
     * Pipeline:
     * - Stage 1: SKIPPED (text already transcribed by TranscriptionService)
     * - Stage 2: TextInferenceService (semantic correction)
     * - Stage 3: Structured JSON parsing (GPT models with fallback)
     *
     * @param transcribedText Pre-transcribed text from SpeechRecognizer or Gemini
     * @param currentDate The currently selected date in the calendar
     * @param onStageChange Callback for UI progress feedback
     * @return VoiceTaskResult with parsed tasks
     */
    suspend fun parseTranscribedText(
        transcribedText: String,
        currentDate: LocalDate = LocalDate.now(),
        @Suppress("UNUSED_PARAMETER") onStageChange: (String) -> Unit = {}
    ): VoiceTaskResult {
        if (transcribedText.isBlank()) {
            return VoiceTaskResult.empty("No speech detected")
        }
        val dateStr = currentDate.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val items = transcribedText.split(Regex("""\s*(?:,|;|\band\b|\bthen\b)\s*""", RegexOption.IGNORE_CASE))
            .map { it.trim().replaceFirstChar { c -> c.uppercaseChar() } }
            .filter { it.isNotBlank() }
        val parsed = if (items.isEmpty()) listOf(ParsedTask(transcribedText.trim(), dateStr, "medium"))
        else items.map { ParsedTask(it, dateStr, "medium") }
        return VoiceTaskResult(
            tasks = parsed,
            confidence = 0.85f,
            originalIntent = transcribedText.trim()
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TASK BREAKDOWN
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Get tasks eligible for breakdown with OCR support for handwritten content.
     * Uses TaskOcrService (Digital Ink API) for on-device recognition.
     *
     * @param tasks List of tasks on the current page
     * @param density Screen density for dp-to-px conversion
     * @param onStageChange Callback for progress updates
     * @param parentTaskIds Set of task IDs that already have children (will be excluded)
     * @return List of tasks eligible for breakdown (typed + handwritten)
     */
    suspend fun getTasksForBreakdownWithOcr(
        tasks: List<Task>,
        density: Float,
        onStageChange: (String) -> Unit = {},
        parentTaskIds: Set<String> = emptySet()
    ): List<TaskForBreakdown> {
        val result = mutableListOf<TaskForBreakdown>()

        // Add tasks with typed text (not completed, not a child task, not already a parent)
        // Exclude child tasks to prevent creating deep hierarchy chains
        // Exclude parent tasks to prevent re-breaking down already broken tasks
        val typedTasks = tasks.filter {
            it.typedText?.isNotBlank() == true &&
            !it.isCompleted &&
            it.parentTaskId == null &&
            it.id !in parentTaskIds
        }
        for (task in typedTasks) {
            result.add(TaskForBreakdown(
                task = task,
                displayText = task.typedText!!,
                isHandwritten = false
            ))
        }

        // ═══════════════════════════════════════════════════════════════════════
        // Get text for handwritten tasks via TaskOcrService (replaces Vision AI)
        // ═══════════════════════════════════════════════════════════════════════
        val tasksWithoutText = tasks.filter {
            it.typedText.isNullOrBlank() &&
            !it.isCompleted &&
            it.parentTaskId == null &&
            it.id !in parentTaskIds
        }

        if (tasksWithoutText.isNotEmpty()) {
            onStageChange("Detecting handwritten tasks...")

            val ocrResult = TaskOcrService.getTextForTasks(tasksWithoutText, density)

            for ((taskId, ocrData) in ocrResult.results) {
                if (ocrData.text.isNotBlank()) {
                    val task = tasksWithoutText.find { it.id == taskId } ?: continue
                    result.add(TaskForBreakdown(
                        task = task,
                        displayText = ocrData.text,
                        isHandwritten = ocrData.isHandwritten
                    ))
                }
            }
        }

        Log.d(TAG, "Tasks for breakdown: ${result.size} (${typedTasks.size} typed + ${result.size - typedTasks.size} handwritten)")
        return result
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // V3 METHODS - INTELLIGENCE ENGINE INTEGRATION
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Sort tasks using pre-computed V3 scores WITH parent-child grouping preserved.
     *
     * Unlike sortTasksByPriorityV3() which sorts all tasks individually, this function:
     * 1. Identifies top-level tasks (parents and standalones)
     * 2. Sorts top-level by score
     * 3. Expands each parent with its children following immediately (in row order)
     *
     * This ensures parent-child groups stay together after sorting.
     *
     * @param tasks Tasks to sort (should be pre-filtered to content tasks only)
     * @param scoredContext Pre-scored context from IntelligenceEngine
     * @return List of tasks sorted by priority with groups preserved
     */
    fun sortTasksByPriorityV3WithGrouping(
        tasks: List<Task>,
        scoredContext: com.bsplan.app.data.ai.ScoredContext
    ): List<Task> {
        Log.d(TAG, "Sorting ${tasks.size} tasks using V3 with parent-child grouping")

        if (tasks.isEmpty()) return emptyList()

        // Build set of task IDs for efficient lookup
        val taskIds = tasks.map { it.id }.toSet()

        // Separate top-level (parents/standalone) from children
        // A task is "effective top-level" if it has no parent, OR its parent isn't in the task list
        val effectiveTopLevel = tasks.filter {
            it.parentTaskId == null || it.parentTaskId !in taskIds
        }

        // Group children by their parent (only if parent is in the list)
        val childrenByParent = tasks
            .filter { it.parentTaskId != null && it.parentTaskId in taskIds }
            .groupBy { it.parentTaskId!! }

        Log.d(TAG, "Top-level tasks: ${effectiveTopLevel.size}, " +
                "child groups: ${childrenByParent.size}")

        // Build task ID → score map from pre-computed scores
        val taskScores = scoredContext.scoredItems
            .filter { it.itemType == "task" }
            .associate { it.itemId to it.finalScore }

        // Sort top-level tasks by score (descending)
        val sortedTopLevel = effectiveTopLevel.sortedByDescending { task ->
            taskScores[task.id] ?: 0f
        }

        // Expand: each top-level task followed by its children (in row order)
        val result = mutableListOf<Task>()
        for (topLevelTask in sortedTopLevel) {
            result.add(topLevelTask)
            val children = childrenByParent[topLevelTask.id] ?: emptyList()
            // Sort children by row index to maintain visual order within group
            result.addAll(children.sortedBy { it.rowIndex })
        }

        Log.d(TAG, "V3 grouped sort complete. Result: ${result.size} tasks, " +
                "top task: ${result.firstOrNull()?.id}")

        return result
    }

    // ========== AUTO-PRIORITY ASSIGNMENT ==========

    /**
     * Map Intelligence Engine scores to A/B/C priority levels.
     *
     * Thresholds aligned with IntelligenceEngine score distribution:
     * - A: >= 0.70 (high urgency/relevance)
     * - B: 0.45 - 0.70 (moderate)
     * - C: < 0.45 (low urgency)
     */
    fun mapScoreToPriority(score: Float): String {
        return when {
            score >= 0.70f -> "A"
            score >= 0.45f -> "B"
            else -> "C"
        }
    }

    /**
     * Build priority assignments for sorted tasks based on their scores.
     * Used by Smart Prioritization to auto-assign A/B/C priorities after reordering.
     *
     * @param scoredContext The scored context from Intelligence Engine
     * @return Map of taskId to priority (A/B/C)
     */
    fun buildPriorityAssignments(
        scoredContext: com.bsplan.app.data.ai.ScoredContext
    ): Map<String, String> {
        return scoredContext.scoredItems
            .filter { it.itemType == "task" }
            .associate { it.itemId to mapScoreToPriority(it.finalScore) }
    }

    /**
     * Break down a task using pre-scored context from the Intelligence Engine.
     *
     * This V3 method uses ComplexityScorer signals to inform the LLM about
     * why the task needs breakdown and how to approach the decomposition.
     *
     * @param taskText Text of the task to break down
     * @param task The Task object for ID lookup
     * @param existingTasks Context of other tasks on the same day
     * @param currentDate The date for context
     * @param scoredContext Pre-scored context from IntelligenceEngine
     * @return BreakdownResult with 3-7 subtasks
     */
    suspend fun breakDownTaskV3(
        taskText: String,
        @Suppress("UNUSED_PARAMETER") task: Task,
        @Suppress("UNUSED_PARAMETER") existingTasks: List<Task>,
        @Suppress("UNUSED_PARAMETER") currentDate: LocalDate,
        @Suppress("UNUSED_PARAMETER") scoredContext: com.bsplan.app.data.ai.ScoredContext
    ): BreakdownResult {
        if (taskText.isBlank()) {
            return BreakdownResult(
                originalTask = taskText,
                subtasks = emptyList(),
                keepOriginal = false,
                error = "No task text provided"
            )
        }
        val parts = taskText.split(Regex("""\s*(?:,|;|\band\b|\bthen\b)\s*""", RegexOption.IGNORE_CASE))
            .map { collapseWhitespace(it) }
            .filter { it.isNotBlank() }
            .distinctBy { normalizeAiTextKey(it) }
            .take(7)
            .mapIndexed { idx, s -> SubTask(text = s.replaceFirstChar { c -> c.uppercaseChar() }, order = idx + 1) }

        return if (parts.size >= 2) {
            BreakdownResult(originalTask = taskText, subtasks = parts, keepOriginal = true)
        } else {
            BreakdownResult(
                originalTask = taskText,
                subtasks = emptyList(),
                keepOriginal = false,
                error = "Cloud AI breakdown is not available in this build."
            )
        }
    }

    private fun buildBreakdownSystemPromptV3(
        complexitySignals: List<String>,
        complexityScore: Float
    ): String {
        val basePrompt = """
You are a task breakdown assistant that helps users split complex tasks into actionable subtasks.

GUIDELINES:
1. Create 3-7 specific, actionable subtasks
2. Each subtask should be completable in a reasonable time
3. Order subtasks logically (prerequisites first)
4. Keep subtask text concise (3-10 words)
5. Be practical - real actions, not abstract steps

OUTPUT FORMAT:
Return JSON with subtasks:
{
  "subtasks": [
    {"text": "First actionable step", "order": 1},
    {"text": "Second actionable step", "order": 2},
    ...
  ],
  "keepOriginal": false
}

Set "keepOriginal" to true ONLY if the original task is a category/project name that should remain as a header.
Return raw JSON only, match the schema exactly, and do not add extra keys.
""".trimIndent()

        // Add complexity-aware guidance if signals exist
        val complexityGuidance = if (complexitySignals.isNotEmpty()) {
            """

## PRE-ANALYZED COMPLEXITY (Score: ${String.format("%.2f", complexityScore)})

The Intelligence Engine has identified the following complexity signals:
${complexitySignals.joinToString("\n") { "- $it" }}

Use these signals to guide your breakdown:
- If "Multiple actions detected" → ensure each action becomes a separate subtask
- If "Broad scope indicated" → break down the scope into manageable chunks
- If "Project-level task" → consider phases or major milestones
- If "Vague terms" → clarify with specific, concrete subtasks
""".trimIndent()
        } else ""

        return basePrompt + complexityGuidance
    }

    private fun buildBreakdownPromptV3(
        taskText: String,
        existingTasks: List<Task>,
        currentDate: LocalDate,
        complexitySignals: List<String>,
        scoredContext: com.bsplan.app.data.ai.ScoredContext
    ): String {
        val dayOfWeek = currentDate.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }
        val normalizedTaskText = normalizeAiTextKey(taskText)

        val basePrompt = """
## Task to Break Down

"$taskText"

**Day**: $dayOfWeek
**Date**: $currentDate
""".trimIndent()

        val existingTaskTexts = existingTasks
            .mapNotNull { task ->
                listOf(task.typedText, task.convertedText, task.recognizedText)
                    .firstOrNull { !it.isNullOrBlank() }
            }
            .map(::collapseWhitespace)
            .filter { it.isNotBlank() && normalizeAiTextKey(it) != normalizedTaskText }
            .distinctBy(::normalizeAiTextKey)
            .take(8)

        val existingSection = if (existingTaskTexts.isNotEmpty()) {
            """

## Already On Today's List (do not duplicate or lightly reword)
${existingTaskTexts.joinToString("\n") { "- $it" }}
""".trimIndent()
        } else ""

        // Add related tasks from scored context for better understanding
        val relatedTasks = scoredContext.scoredItems
            .filter { it.itemType == "task" && it.itemText != taskText }
            .take(5)
            .mapNotNull { scoredItem ->
                val text = collapseWhitespace(scoredItem.itemText)
                if (text.isBlank() || normalizeAiTextKey(text) == normalizedTaskText) {
                    null
                } else {
                    "${text.take(50)} [score: ${String.format("%.2f", scoredItem.finalScore)}]"
                }
            }

        val relatedSection = if (relatedTasks.isNotEmpty()) {
            """

## Related Tasks Today
${relatedTasks.joinToString("\n") { "- $it" }}
""".trimIndent()
        } else ""

        val instructions = """

Break this task into 3-7 specific, actionable subtasks that can each be completed independently.
Order them logically (do prerequisites first).
Do not repeat or lightly reword tasks that already exist today.
If the task is already mostly represented by existing tasks, return the smallest missing steps only.
""".trimIndent()

        return basePrompt + existingSection + relatedSection + instructions
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // UTILITIES
    // ═══════════════════════════════════════════════════════════════════════════

    private fun collapseWhitespace(text: String?): String {
        return text?.replace(multiWhitespaceRegex, " ")?.trim().orEmpty()
    }

    private fun normalizeAiTextKey(text: String): String {
        return collapseWhitespace(text).lowercase(Locale.ROOT)
    }

}

