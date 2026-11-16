package com.boox.day.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.boox.day.api.ModelConfigProvider
import com.boox.day.api.ai.AiCapability
import com.boox.day.api.ai.AiClient
import com.boox.day.api.ai.AiModel
import com.boox.day.api.ai.AiResponse
import com.boox.day.api.ai.aiRequest
import com.boox.day.data.ai.AiPreferencesRepository
import com.boox.day.data.ai.ScoredContext
import com.boox.day.data.db.Task
import com.boox.day.data.db.LocalEvent
import com.boox.day.data.db.Stroke
import com.boox.day.ocr.model.OcrSource
import com.boox.day.ocr.TaskOcrService
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.annotations.SerializedName
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * AI service for Task-specific operations.
 * Leverages existing AI infrastructure (AiClient, aiRequest, models).
 *
 * Features:
 * - Voice to Task: Audio → transcription → TextInferenceService → structured task data
 * - Recognize Handwriting: Vision → TextInferenceService → text conversion for task pages
 * - Sort by Priority: AI-powered task reordering across all pages
 *
 * Uses [TextInferenceService] for intelligent text correction and semantic inference
 * after audio transcription and handwriting recognition.
 */
object TaskAiService {
    private const val TAG = "TaskAiService"
    private val gson = Gson()
    private val multiWhitespaceRegex = Regex("\\s+")

    // ═══════════════════════════════════════════════════════════════════════════
    // MODEL CONFIGURATION HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    private fun getReasoningModels(): List<AiModel> =
        ModelConfigProvider.getReasoningFallbackChain()

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
        onStageChange: (String) -> Unit = {}
    ): VoiceTaskResult {
        if (transcribedText.isBlank()) {
            return VoiceTaskResult.empty("No speech detected")
        }

        Log.d(TAG, "parseTranscribedText: '${transcribedText.take(100)}...'")

        // ─────────────────────────────────────────────────────────────────────
        // STAGE 1: SKIPPED - TranscriptionService already transcribed
        // ─────────────────────────────────────────────────────────────────────

        // ─────────────────────────────────────────────────────────────────────
        // STAGE 2: Raw Text → Corrected Text (TextInferenceService)
        // ─────────────────────────────────────────────────────────────────────
        onStageChange("Understanding what you said...")
        Log.d(TAG, "Stage 2: Refining transcript with TextInferenceService...")

        val inferenceResult = TextInferenceService.refineText(
            rawText = transcribedText,
            context = InferenceContext.TASK,
            config = InferenceConfig.DEFAULT
        )

        val correctedText = inferenceResult.refinedText
        Log.d(TAG, "Stage 2 complete: Corrected = '$correctedText'")

        // ─────────────────────────────────────────────────────────────────────
        // STAGE 3: Corrected Text → Structured Task (GPT models with fallback)
        // ─────────────────────────────────────────────────────────────────────
        onStageChange("Creating your tasks...")
        Log.d(TAG, "Stage 3: Parsing corrected text to structured task...")

        // Uses 3×3 retry via AiRetryExecutor
        val modelsToTry = getReasoningModels()
        val taskSystemPrompt = buildVoiceTaskSystemPrompt(currentDate)
        val contextBlock = TaskAiContextBuilderV1.buildContextBlockOrEmpty(
            context = null,
            date = currentDate,
            rangeStart = currentDate.minusDays(3),
            rangeEnd = currentDate,
            includeBooxNotes = false,
            includeSourceTypes = setOf(OcrSource.TASK, OcrSource.MEMO)
        )
        // Oct 2026: the context is labelled as reference, so the AI doesn't turn a recent task or
        // memo line into a new task; tasks come only from what the user said
        val userPrompt = if (contextBlock.isNotBlank()) {
            buildString {
                appendLine(TaskAiContextBuilderV1.REFERENCE_ONLY_HEADER)
                appendLine(contextBlock)
                appendLine()
                appendLine("## WHAT THE USER SAID (make tasks only from this)")
                append(correctedText)
            }
        } else {
            correctedText
        }

        return when (val result = AiRetryExecutor.execute(
            models = modelsToTry,
            buildRequest = { model ->
                aiRequest {
                    model(model)
                    systemPrompt(taskSystemPrompt)
                    userMessage(userPrompt)
                    jsonOutput()
                    temperature(0.3f)
                    if (model.hasCapability(AiCapability.REASONING)) {
                        enableReasoning()
                    }
                }
            },
            processResponse = { response, model ->
                Log.d(TAG, "Stage 3 complete (${model.displayName}): ${response.data.take(500)}...")
                val parsed = parseVoiceTaskResponse(response.data)
                if (parsed.isSuccess) parsed else null
            }
        )) {
            is AiRetryExecutor.RetryResult.Success -> result.data
            is AiRetryExecutor.RetryResult.Failure -> VoiceTaskResult.empty(result.message)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // INTERNAL: VOICE TO TASK PROMPTS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * System prompt for Stage 3: Parse corrected text into structured task JSON.
     *
     * Note: Input text has already been cleaned by Stage 2 (TextInferenceService),
     * so filler words and basic corrections are already handled.
     */
    private fun buildVoiceTaskSystemPrompt(currentDate: LocalDate): String {
        val today = LocalDate.now()
        val tomorrow = today.plusDays(1)
        val currentYear = today.year
        val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

        return """
You are a task parser. Extract task(s) from text and return structured JSON.

TODAY: ${today.format(dateFormatter)}
TOMORROW: ${tomorrow.format(dateFormatter)}
CURRENT YEAR: $currentYear
CURRENTLY VIEWING: ${currentDate.format(dateFormatter)}

## EXTRACTION RULES

1. MULTIPLE TASKS: Split when each part is a distinct action
   - "Buy milk and call mom" → 2 tasks (different actions)
   - "Buy milk and eggs" → 1 task (single shopping trip)
   - "Finish report and email it to boss" → 2 tasks (distinct steps)
   - Split by "and", "also", "then" only when actions are independent

2. DATE PARSING:
   - "today" → ${today.format(dateFormatter)}
   - "tomorrow" → ${tomorrow.format(dateFormatter)}
   - "next Monday" → calculate from today
   - "this Friday" → closest upcoming Friday
   - "December 20th" or "Dec 20" → $currentYear-12-20 (or ${currentYear + 1} if date has passed)
   - "in 3 days" → add to today
   - No date mentioned → null

3. PRIORITY DETECTION:
   - "urgent", "important", "asap", "critical", "must do", "top priority" → "high"
   - "when you can", "eventually", "low priority", "maybe", "not urgent" → "low"
   - No priority indicator → "medium"

4. FOLLOW-UP DETECTION:
   - "follow up on", "follow up with", "check on", "check back" → isFollowUp: true
   - "remind me to [action]" → isFollowUp: false (this is a command prefix, not a follow-up)
   - "remind me about [topic]" → isFollowUp: true (checking on something existing)

5. TEXT CLEANUP:
   - Remove command prefixes: "add task", "create task", "new task", "add a"
   - Remove polite prefixes: "please", "can you", "I need to", "remind me to", "don't forget to"
   - Capitalize first letter: "buy milk" → "Buy milk"
   - Preserve the user's wording - do not rephrase

## NON-TASK INPUT

If input contains no actionable task (greetings, questions, random statements):
- Return empty tasks array
- Set confidence below 0.3
- Explain in originalIntent

Example: "Hello how are you" → {"tasks": [], "confidence": 0.1, "originalIntent": "Greeting, no task detected"}

## OUTPUT FORMAT

Return ONLY raw JSON (no markdown code blocks):

{
  "tasks": [
    {
      "text": "Task description",
      "date": "yyyy-MM-dd" or null,
      "priority": "high" | "medium" | "low",
      "isFollowUp": true | false
    }
  ],
  "confidence": 0.0-1.0,
  "originalIntent": "Brief summary of what was understood"
}

## CONFIDENCE SCORING
- 0.9-1.0: Clear task with explicit details
- 0.7-0.9: Task understood, minor inference needed
- 0.5-0.7: Understood intent but some ambiguity
- 0.3-0.5: Uncertain interpretation
- 0.0-0.3: No task detected or couldn't understand
""".trimIndent()
    }

    // Note: User prompt is not needed - correctedText is passed directly to the model.
    // The system prompt is comprehensive enough for task parsing.

    private fun parseVoiceTaskResponse(json: String): VoiceTaskResult {
        val cleanJson = JsonExtractor.extract(json)
        return try {
            val parsed = gson.fromJson(cleanJson, VoiceTaskResponse::class.java)
            VoiceTaskResult(
                tasks = parsed.tasks,
                confidence = parsed.confidence,
                originalIntent = parsed.originalIntent
            )
        } catch (e: JsonSyntaxException) {
            // Length only (Oct 2026): AI answers carry the user's tasks and events, and error
            // lines stay in release builds and in crash reports
            Log.e(TAG, "Failed to parse voice task response (${json.length} chars)", e)
            VoiceTaskResult.empty("Could not understand response")
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error parsing voice task", e)
            VoiceTaskResult.empty("Processing error")
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SORT BY PRIORITY
    // ═══════════════════════════════════════════════════════════════════════════

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

    private fun parseBreakdownResponse(json: String, originalTask: String): BreakdownResult {
        return try {
            val cleanJson = JsonExtractor.extract(json)
            val parsed = gson.fromJson(cleanJson, BreakdownResponse::class.java)
            val subtasks = parsed?.subtasks
                ?.sortedBy { it.order }
                ?.mapNotNull { subTask ->
                    val text = collapseWhitespace(subTask.text)
                    if (text.isBlank()) return@mapNotNull null
                    SubTask(
                        text = text,
                        order = subTask.order.coerceAtLeast(1)
                    )
                }
                ?.distinctBy { normalizeAiTextKey(it.text) }
                ?.take(7)
                ?.mapIndexed { index, subTask ->
                    SubTask(
                        text = subTask.text,
                        order = index + 1
                    )
                }
                .orEmpty()
            val keepOriginal = parsed?.keepOriginal ?: false

            if (subtasks.size < 3) {
                return BreakdownResult(
                    originalTask = originalTask,
                    subtasks = emptyList(),
                    keepOriginal = keepOriginal,
                    error = "AI generated too few subtasks. Please try again."
                )
            }
            BreakdownResult(
                originalTask = originalTask,
                subtasks = subtasks,
                keepOriginal = keepOriginal
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse breakdown response (${json.length} chars)", e)
            BreakdownResult(
                originalTask = originalTask,
                subtasks = emptyList(),
                keepOriginal = false,
                error = "Could not parse breakdown"
            )
        }
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
        scoredContext: com.boox.day.data.ai.ScoredContext
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
        scoredContext: com.boox.day.data.ai.ScoredContext
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
        task: Task,
        existingTasks: List<Task>,
        currentDate: LocalDate,
        scoredContext: com.boox.day.data.ai.ScoredContext
    ): BreakdownResult {
        if (taskText.isBlank()) {
            return BreakdownResult(
                originalTask = taskText,
                subtasks = emptyList(),
                keepOriginal = false,
                error = "No task text provided"
            )
        }

        Log.d(TAG, "Breaking down task (V3): $taskText")

        // Get complexity signals from pre-computed scores
        val scoredItem = scoredContext.scoredItems.find { it.itemId == task.id }
        val complexitySignals = scoredItem?.componentScores
            ?.get(com.boox.day.data.ai.ScorerIds.COMPLEXITY)?.signals ?: emptyList()
        val complexityScore = scoredItem?.componentScores
            ?.get(com.boox.day.data.ai.ScorerIds.COMPLEXITY)?.rawScore ?: 0f

        Log.d(TAG, "Complexity score: $complexityScore, signals: $complexitySignals")

        // Build enhanced prompt with complexity analysis
        val prompt = buildBreakdownPromptV3(
            taskText = taskText,
            existingTasks = existingTasks,
            currentDate = currentDate,
            complexitySignals = complexitySignals,
            scoredContext = scoredContext
        )
        val systemPrompt = buildBreakdownSystemPromptV3(complexitySignals, complexityScore)

        // Models to try using 3×3 retry pattern
        val modelsToTry = getReasoningModels()

        return when (val result = AiRetryExecutor.execute(
            models = modelsToTry,
            buildRequest = { model ->
                Log.d(TAG, "Breaking down task (V3) with ${model.displayName}...")
                aiRequest {
                    model(model)
                    systemPrompt(systemPrompt)
                    userMessage(prompt)
                    jsonSchema("task_breakdown_v3", StructuredOutputSchemas.breakdownSchema)
                    temperature(0.4f) // Lower for consistent structure
                    if (model.hasCapability(com.boox.day.api.ai.AiCapability.REASONING)) {
                        enableReasoning()
                    }
                }
            },
            processResponse = { response, model ->
                Log.d(TAG, "Breakdown V3 response (${model.displayName}): ${response.data.take(500)}...")
                // An unreadable answer or too few subtasks goes to the next model (29 Oct 2026:
                // it ended the feature, no other model tried)
                parseBreakdownResponse(response.data, taskText).takeIf { it.error == null }
            }
        )) {
            is AiRetryExecutor.RetryResult.Success -> result.data
            is AiRetryExecutor.RetryResult.Failure -> BreakdownResult(
                originalTask = taskText,
                subtasks = emptyList(),
                keepOriginal = false,
                // What went wrong (no Wi-Fi, no credit, ...); was always "temporarily unavailable" (Oct 2026)
                error = result.message
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
        scoredContext: com.boox.day.data.ai.ScoredContext
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

