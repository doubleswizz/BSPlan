package com.bsplan.app.ai

/**
 * Local formatter for voice-to-text transcripts.
 */
object VoiceTextParser {

    data class ParsedText(
        val plainText: String,
        val markdownText: String,
        val originalTranscript: String,
        val success: Boolean,
        val errorMessage: String? = null
    ) {
        companion object {
            fun empty(error: String) = ParsedText(
                plainText = "",
                markdownText = "",
                originalTranscript = "",
                success = false,
                errorMessage = error
            )
        }
    }

    suspend fun formatTranscribedText(rawText: String): ParsedText {
        if (rawText.isBlank()) {
            return ParsedText.empty("No text to format")
        }
        val capitalized = rawText.trim().replaceFirstChar { it.uppercaseChar() }
        return ParsedText(
            plainText = capitalized,
            markdownText = capitalized,
            originalTranscript = rawText,
            success = true
        )
    }
}
