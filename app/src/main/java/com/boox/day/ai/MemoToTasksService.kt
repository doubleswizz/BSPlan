package com.boox.day.ai

import android.graphics.Bitmap
import android.util.Base64
import com.boox.day.api.ModelConfigProvider
import com.boox.day.api.ai.AiCapability
import com.boox.day.api.ai.AiModel
import com.boox.day.api.ai.AiProblem
import com.boox.day.api.ai.AiResponse
import com.boox.day.api.ai.LastAiProblem
import com.boox.day.api.ai.aiRequest
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.boox.day.utils.TaggedLog
import java.io.ByteArrayOutputStream

private val log = TaggedLog("MemoToTasksService")

/**
 * A task converted from memo text.
 */
data class ConvertedTask(
    @SerializedName("text") val text: String,
    @SerializedName("suggestedPriority") val suggestedPriority: String = "",  // "", "A", "B", "C"
    @SerializedName("reason") val reason: String = "",
    @SerializedName("confidence") val confidence: Float = 0.5f,  // NEW
    @SerializedName("suggestedTime") val suggestedTime: String? = null,  // NEW
    @SerializedName("relatedEventId") val relatedEventId: String? = null,  // NEW
    val isSelected: Boolean = true,  // User can toggle in preview
    // Per-task date overrides (null = use defaults from dialog)
    val targetDateOverride: String? = null,  // yyyy-MM-dd format
    val dueDateOverride: Long? = null        // epoch ms, null = no due date
)

/**
 * Result of adding tasks to a date.
 */
data class TasksAddedResult(
    val tasksAdded: Int,
    val pagesUsed: Int,
    val targetDates: Set<String>,  // All dates that tasks were added to (supports per-task date overrides)
    val latestPageIndexByDate: Map<String, Int> = emptyMap(),
    /** In the order they were placed. */
    val taskIds: List<String> = emptyList()
)

private data class OcrTextResponseJson(
    @SerializedName("hasText") val hasText: Boolean = true,
    @SerializedName("text") val text: String = ""
)

/**
 * Shared handwriting OCR and error-tracking helpers for [ScheduleItemsService],
 * [MeetingRecapService], and [com.boox.day.meetings.MeetingPages].
 */
object MemoToTasksService {
    private val gson = Gson()
    private val multiWhitespaceRegex = Regex("\\s+")

    /**
     * Why every model failed, for the dialog (Oct 2026). It said "All vision models failed to
     * extract text" or "All models failed to convert memo to tasks" whatever the cause, so no
     * Wi-Fi, no credit and a bad answer looked the same.
     */
    internal class ModelFailures {
        private var lastError: AiResponse.Error? = null
        private var creditRefusal: AiResponse.Error.OutOfCredit? = null
        private var keyProblem: AiResponse.Error? = null

        /** Notes a model's answer that could not be used; true when no other model can help. */
        fun note(response: AiResponse<String>): Boolean {
            when (response) {
                // The model answered, but nothing usable came back
                is AiResponse.Success -> lastError = null
                is AiResponse.Error.NoApiKey,
                is AiResponse.Error.KeyRejected -> {
                    keyProblem = response
                    return true
                }
                is AiResponse.Error.OutOfCredit -> creditRefusal = response
                is AiResponse.Error -> lastError = response
            }
            return false
        }

        /** A key problem first, then a credit refusal (says to add credit), else the last model's problem. */
        fun message(): String =
            (keyProblem ?: creditRefusal ?: lastError)?.let { AiProblem.messageFor(it) } ?: AiProblem.MODEL_FAILED.message
    }

    // Stage 1 vision models - same as AiHandwritingService
    private fun getVisionModels(): List<AiModel> =
        ModelConfigProvider.getVisionFallbackChain()

    // Delimiters for parsing AI responses
    private const val START_TEXT_DELIMITER = "<<<START_TEXT>>>"
    private const val END_TEXT_DELIMITER = "<<<END_TEXT>>>"
    private const val NO_TEXT_MARKER = "<<<NO_TEXT>>>"

    // Stage 1 prompt: Extract ALL text from image exactly as written
    private val STAGE1_PROMPT = """
You are a text extraction system for handwritten notes.

TASK: Extract ALL readable text from the image exactly as written.

RULES:
- Extract all text: handwritten, typed, printed
- Preserve line breaks between distinct items or thoughts
- Preserve list markers: bullets (•, -, *), checkboxes (☐, ✓), numbers (1., 2.)
- Use [unclear] for illegible words
- Do NOT correct spelling or grammar - preserve exactly as written
- IGNORE non-text elements: shapes, arrows, decorative lines, doodles
- If the handwriting is in a non-English language, transcribe in that language

OUTPUT:
- Return raw JSON only.
- If text exists, return {"hasText": true, "text": "..."}.
- If no readable text exists, return {"hasText": false, "text": ""}.
    """.trimIndent()

    /**
     * Encodes a bitmap to Base64 JPEG string.
     */
    private fun encodeBitmapToBase64(bitmap: Bitmap): String {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)
        return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
    }

    /** Stage 1 for other features: the words in [bitmap], "" when there are none. Throws when every model failed. */
    internal suspend fun readHandwriting(bitmap: Bitmap): String = extractTextFromImage(encodeBitmapToBase64(bitmap))

    /**
     * Stage 1: Extract text from image using vision models with fallback.
     */
    private suspend fun extractTextFromImage(base64Image: String): String {
        val failures = ModelFailures()
        for (model in getVisionModels()) {
            try {
                log.i("Trying vision model: ${model.id}")

                val request = aiRequest {
                    model(model)
                    systemPrompt(STAGE1_PROMPT)
                    userMessageWithImage("Extract all text from this handwritten memo:", base64Image)
                    jsonSchema("ocr_text", StructuredOutputSchemas.ocrTextSchema)
                    if (model.hasCapability(AiCapability.REASONING)) {
                        enableReasoning()
                    }
                }

                // AiClient.execute; unit tests replace it
                val response = AiRetryExecutor.send(request, true)

                if (response is AiResponse.Success) {
                    val content = response.data
                    log.i("Vision model ${model.id} returned ${content.length} chars")

                    // Check for no text marker
                    if (content.contains(NO_TEXT_MARKER)) {
                        log.i("Model detected no text in image")
                        return ""
                    }

                    val structuredText = parseStructuredOcrResponse(content)
                    if (structuredText != null) {
                        return structuredText
                    }

                    // Extract text between delimiters
                    val extractedText = extractBetweenDelimiters(content)
                    if (extractedText.isNotBlank()) {
                        return extractedText
                    }
                    // Any other answer goes to the next model (2 Nov 2026): the whole answer was
                    // taken as the memo's text, so a model's own words could become the user's tasks
                }
                if (response is AiResponse.Success) LastAiProblem.noteTry(request, AiRetryExecutor.UNUSABLE_ANSWER)
                if (failures.note(response)) break

                log.w("Model ${model.id} failed or returned empty, trying next...")

            } catch (e: Exception) {
                log.w("Model ${model.id} threw exception: ${e.message}")
                continue
            }
        }

        throw Exception(failures.message())
    }

    private fun parseStructuredOcrResponse(response: String): String? {
        return try {
            val cleanJson = JsonExtractor.extract(response)
            val parsed = gson.fromJson(cleanJson, OcrTextResponseJson::class.java)
            if (parsed?.hasText == false) {
                ""
            } else {
                collapseWhitespacePreservingLines(parsed?.text)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun collapseWhitespace(text: String?): String {
        return text?.replace(multiWhitespaceRegex, " ")?.trim().orEmpty()
    }

    private fun collapseWhitespacePreservingLines(text: String?): String {
        return text
            ?.lines()
            ?.joinToString("\n") { line -> collapseWhitespace(line) }
            ?.trim()
            .orEmpty()
    }

    /**
     * Extract text between START_TEXT and END_TEXT delimiters.
     */
    private fun extractBetweenDelimiters(content: String): String {
        val startIndex = content.indexOf(START_TEXT_DELIMITER)
        val endIndex = content.indexOf(END_TEXT_DELIMITER)

        if (startIndex != -1 && endIndex != -1 && endIndex > startIndex) {
            return content.substring(
                startIndex + START_TEXT_DELIMITER.length,
                endIndex
            ).trim()
        }

        return ""
    }
}
