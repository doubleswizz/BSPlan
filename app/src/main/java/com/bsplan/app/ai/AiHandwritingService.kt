package com.bsplan.app.ai

import com.bsplan.app.data.db.Stroke
import com.bsplan.app.ocr.DigitalInkModelManager
import com.bsplan.app.ocr.DigitalInkService
import com.bsplan.app.utils.TaggedLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

private val log = TaggedLog("AiHandwritingService")

/**
 * Configuration for on-device handwriting recognition (ML Kit Digital Ink).
 */
data class RecognitionModelConfig(
    val useDigitalInk: Boolean = true
) {
    companion object {
        val DEFAULT: RecognitionModelConfig = RecognitionModelConfig(useDigitalInk = true)
        val FAST: RecognitionModelConfig = RecognitionModelConfig(useDigitalInk = true)
        val RELIABLE: RecognitionModelConfig = RecognitionModelConfig(useDigitalInk = true)
        val ACCURATE: RecognitionModelConfig = RecognitionModelConfig(useDigitalInk = true)
        val DIGITAL_INK: RecognitionModelConfig = RecognitionModelConfig(useDigitalInk = true)
    }
}

/**
 * On-device handwriting recognition service backed by ML Kit Digital Ink.
 */
class AiHandwritingService {

    sealed class RecognitionState {
        data object Idle : RecognitionState()
        data object CapturingImage : RecognitionState()
        data object ExtractingText : RecognitionState()
        data object InferringMeaning : RecognitionState()
        data object FormattingText : RecognitionState()
        data class Success(val result: AiRecognitionResult) : RecognitionState()
        data class NoTextFound(val message: String) : RecognitionState()
        data class Error(val message: String) : RecognitionState()
    }

    data class AiRecognitionResult(
        val rawText: String,
        val formattedText: String,
        val hasFormatting: Boolean
    )

    suspend fun recognizeStrokes(
        strokes: List<Stroke>,
        @Suppress("UNUSED_PARAMETER") config: RecognitionModelConfig = RecognitionModelConfig.DEFAULT,
        context: android.content.Context? = null,
        onStateChange: (RecognitionState) -> Unit
    ): Result<AiRecognitionResult> {
        if (strokes.isEmpty()) {
            return Result.failure(IllegalArgumentException("No strokes to recognize"))
        }
        return recognizeWithDigitalInk(strokes, context, onStateChange)
    }

    private suspend fun recognizeWithDigitalInk(
        strokes: List<Stroke>,
        context: android.content.Context?,
        onStateChange: (RecognitionState) -> Unit
    ): Result<AiRecognitionResult> {
        if (context == null) {
            val error = "Context required for Digital Ink recognition"
            log.e(error)
            onStateChange(RecognitionState.Error(error))
            return Result.failure(IllegalArgumentException(error))
        }

        try {
            if (!DigitalInkModelManager.isModelReady()) {
                val error = "Digital Ink model not downloaded. Please wait for model download to complete."
                log.w(error)
                onStateChange(RecognitionState.Error(error))
                return Result.failure(IllegalStateException(error))
            }

            onStateChange(RecognitionState.ExtractingText)
            log.d("Digital Ink: Recognizing ${strokes.size} strokes")

            val digitalInkService = DigitalInkService.create(context, CoroutineScope(Dispatchers.Default))
            val result = digitalInkService.recognizeStrokes(strokes)

            if (result.isEmpty) {
                log.i("Digital Ink: No text detected")
                onStateChange(RecognitionState.NoTextFound("No handwritten text detected in selection"))
                return Result.failure(NoTextFoundException("No handwritten text detected"))
            }

            log.i("Digital Ink: Recognized ${result.text.length} chars (confidence=${result.confidence})")

            val aiResult = AiRecognitionResult(
                rawText = result.text,
                formattedText = result.text,
                hasFormatting = false
            )

            onStateChange(RecognitionState.Success(aiResult))
            return Result.success(aiResult)
        } catch (e: Exception) {
            log.e("Digital Ink recognition failed: ${e.message}", e)
            onStateChange(RecognitionState.Error("Recognition failed: ${e.message}"))
            return Result.failure(e)
        }
    }
}

class NoTextFoundException(message: String) : Exception(message)
