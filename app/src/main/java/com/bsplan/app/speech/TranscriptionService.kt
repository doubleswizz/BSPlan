package com.bsplan.app.speech

import android.content.Context
import android.util.Log
import com.bsplan.app.data.datastore.GlobalAppSettings
import com.bsplan.app.data.datastore.VoiceTextMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * TranscriptionService - THE unified voice-to-text service for all voice features.
 *
 * Uses the tablet's Android [VoiceInputService] (SpeechRecognizer) on-device / system speech
 * recognition service.
 */
class TranscriptionService(
    private val context: Context,
    private val audioRecorder: AudioRecorder
) {
    companion object {
        private const val TAG = "TranscriptionService"
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // DEPENDENCIES
    // ═══════════════════════════════════════════════════════════════════════════

    private val voiceInputService = VoiceInputService(context)

    // ═══════════════════════════════════════════════════════════════════════════
    // STATE
    // ═══════════════════════════════════════════════════════════════════════════

    private val _state = MutableStateFlow(TranscriptionState.IDLE)
    /** Current transcription state */
    val state: StateFlow<TranscriptionState> = _state.asStateFlow()

    private val _activeSource = MutableStateFlow<TranscriptionSource?>(null)
    /** The transcription source currently being used (null if not started) */
    val activeSource: StateFlow<TranscriptionSource?> = _activeSource.asStateFlow()

    // ═══════════════════════════════════════════════════════════════════════════
    // VOICE INPUT SERVICE STATE (for UI when using SpeechRecognizer)
    // ═══════════════════════════════════════════════════════════════════════════

    /** VoiceInputService state - use this for UI status when activeSource == SPEECH_RECOGNIZER */
    val speechRecognizerState: StateFlow<VoiceInputState> = voiceInputService.state

    /** VoiceInputService RMS level - use this for waveform when activeSource == SPEECH_RECOGNIZER */
    val speechRecognizerRmsLevel: StateFlow<Float> = voiceInputService.rmsLevel

    // ═══════════════════════════════════════════════════════════════════════════
    // UNIFIED AUTO-COMPLETE CALLBACK
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Set unified auto-complete listener that works for BOTH transcription modes.
     *
     * This is the ONLY way to receive auto-complete callbacks. The callback is triggered when:
     * - AudioRecorder detects silence (Gemini mode) - passes base64 audio
     * - VoiceInputService detects silence (SpeechRecognizer mode) - passes null (text already in service)
     *
     * @param listener Callback receiving optional base64 audio. In SpeechRecognizer mode, this
     *                 is null since text is already available via stopAndGetResult().
     */
    fun setOnAutoCompleteListener(listener: ((String?) -> Unit)?) {
        // Trigger auto-complete from SpeechRecognizer
        voiceInputService.setOnAutoCompleteListener { text ->
            Log.d(TAG, "════════════════════════════════════════════════════════════")
            Log.d(TAG, "AUTO-COMPLETE: VoiceInputService triggered (SpeechRecognizer mode)")
            Log.d(TAG, "  Transcribed text: '${text.take(100)}${if (text.length > 100) "..." else ""}'")
            Log.d(TAG, "  Text length: ${text.length} chars")
            listener?.invoke(null)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PUBLIC API
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Check if Google Search app is installed (enables SpeechRecognizer).
     */
    fun hasGoogleSearchApp(): Boolean {
        return VoiceInputService.hasGoogleSearchApp(context)
    }

    /**
     * Check if SpeechRecognizer is available on this device.
     *
     * Returns true if EITHER:
     * - Google Search app is installed (com.google.android.googlequicksearchbox)
     * - Android reports speech recognition is available (SpeechRecognizer.isRecognitionAvailable)
     *
     * We check BOTH because:
     * - hasGoogleSearchApp() can return false even when speech recognition works
     *   (some devices have speech recognition via Google Play Services or other providers)
     * - SpeechRecognizer.isRecognitionAvailable() can return false on some devices
     *   even when Google Search is installed and speech recognition works
     *
     * By using OR logic, we maximize compatibility across different Android devices.
     */
    fun isSpeechRecognitionAvailable(): Boolean {
        val hasGoogleSearch = VoiceInputService.hasGoogleSearchApp(context)
        val systemReportsAvailable = VoiceInputService.isAvailable(context)
        Log.d(TAG, "isSpeechRecognitionAvailable: hasGoogleSearch=$hasGoogleSearch, systemReportsAvailable=$systemReportsAvailable")
        return hasGoogleSearch || systemReportsAvailable
    }



    /**
     * Start transcription.
     *
     * This method validates configuration and starts the appropriate transcription service.
     *
     * IMPORTANT: AudioRecorder and SpeechRecognizer CANNOT run simultaneously!
     * They both need exclusive microphone access. Running both causes SpeechRecognizer
     * to receive no audio data (mic contention).
     *
     * Only on-device Android SpeechRecognizer runs (free, unlimited).
     *
     * @return StartResult indicating success or what action is needed
     */
    suspend fun startTranscribing(): StartResult {
        val speechRecognitionAvailable = isSpeechRecognitionAvailable()
        Log.i(TAG, "startTranscribing: speechRecognitionAvailable=$speechRecognitionAvailable")

        if (!speechRecognitionAvailable) {
            Log.w(TAG, "Speech recognition not available - needs configuration")
            return StartResult.NeedsConfiguration
        }

        Log.i(TAG, "Starting SpeechRecognizer")
        voiceInputService.startListening()
        _activeSource.value = TranscriptionSource.SPEECH_RECOGNIZER
        _state.value = TranscriptionState.LISTENING
        return StartResult.Started
    }

    /**
     * Pause transcription (preserves accumulated data).
     */
    fun pause() {
        if (_activeSource.value == TranscriptionSource.SPEECH_RECOGNIZER) {
            voiceInputService.pause()
            Log.d(TAG, "SpeechRecognizer paused")
        } else {
            Log.w(TAG, "pause() called but no active source")
        }
        _state.value = TranscriptionState.IDLE
    }

    /**
     * Resume transcription after pause.
     */
    fun resume() {
        if (_activeSource.value == TranscriptionSource.SPEECH_RECOGNIZER) {
            voiceInputService.resume()
            Log.d(TAG, "SpeechRecognizer resumed")
        } else {
            Log.w(TAG, "resume() called but no active source")
        }
        _state.value = TranscriptionState.LISTENING
    }

    suspend fun stopAndGetResult(@Suppress("UNUSED_PARAMETER") preCapturedAudio: String? = null): TranscriptionResult {
        _state.value = TranscriptionState.PROCESSING
        Log.i(TAG, "stopAndGetResult: activeSource=${_activeSource.value}")

        return when (_activeSource.value) {
            TranscriptionSource.SPEECH_RECOGNIZER -> {
                val text = voiceInputService.getAccumulatedText()
                val partialResult = voiceInputService.partialResult.value
                val state = voiceInputService.state.value

                voiceInputService.stopListening()

                Log.d(TAG, "SpeechRecognizer results: chars=${text.length}, partial=${partialResult.length}, state=$state")

                if (text.isNotBlank()) {
                    _state.value = TranscriptionState.IDLE
                    TranscriptionResult.Success(text.trim(), TranscriptionSource.SPEECH_RECOGNIZER)
                } else {
                    _state.value = TranscriptionState.ERROR
                    TranscriptionResult.Error("Speech not recognized. Please try again and speak clearly.")
                }
            }

            null -> {
                Log.e(TAG, "stopAndGetResult called but transcription was never started!")
                _state.value = TranscriptionState.ERROR
                TranscriptionResult.Error("Transcription not started")
            }
        }
    }

    /**
     * Cancel transcription and discard all data.
     */
    fun cancel() {
        if (_activeSource.value == TranscriptionSource.SPEECH_RECOGNIZER) {
            voiceInputService.cancel()
        }
        _state.value = TranscriptionState.IDLE
        _activeSource.value = null
        Log.d(TAG, "Transcription cancelled")
    }

    /**
     * Release all resources. Call when dialog is dismissed.
     */
    fun release() {
        audioRecorder.setOnAutoCompleteListener(null)
        voiceInputService.setOnAutoCompleteListener(null)
        voiceInputService.destroy()
        audioRecorder.release()
        _state.value = TranscriptionState.IDLE
        _activeSource.value = null
        Log.d(TAG, "TranscriptionService released")
    }
}

// ═════════════════════════════════════════════════════════════════════════════════
// RESULT TYPES
// ═════════════════════════════════════════════════════════════════════════════════

/**
 * Result of calling startTranscribing().
 */
sealed class StartResult {
    /** Transcription started successfully */
    object Started : StartResult()

    /** Configuration needed - show VoiceSettingsDialog */
    object NeedsConfiguration : StartResult()
}

/**
 * Result of calling stopAndGetResult().
 */
sealed class TranscriptionResult {
    /** Transcription succeeded */
    data class Success(
        val text: String,
        val source: TranscriptionSource
    ) : TranscriptionResult()

    /** Transcription failed with an error */
    data class Error(val message: String) : TranscriptionResult()
}

internal fun voiceSource(@Suppress("UNUSED_PARAMETER") mode: VoiceTextMode, speechRecognitionAvailable: Boolean): TranscriptionSource? =
    if (speechRecognitionAvailable) TranscriptionSource.SPEECH_RECOGNIZER else null

enum class TranscriptionSource {
    /** Android SpeechRecognizer (the tablet's speech service, from Google) - free */
    SPEECH_RECOGNIZER
}

/**
 * State of the transcription service.
 */
enum class TranscriptionState {
    /** Not transcribing */
    IDLE,

    /** Actively listening for speech */
    LISTENING,

    /** Processing audio / getting result */
    PROCESSING,

    /** Error occurred */
    ERROR
}

// ═════════════════════════════════════════════════════════════════════════════════
// PROMPTS
// ═════════════════════════════════════════════════════════════════════════════════

private const val TRANSCRIPTION_SYSTEM_PROMPT = """
You are a speech-to-text transcription system.
Transcribe the user's speech accurately and completely.

RULES:
- Output ONLY the transcription - no explanations, no formatting
- Preserve the user's exact words
- Include filler words (um, uh) only if they're significant
- Do NOT interpret or parse the content - just transcribe
- If you can't understand part of the audio, write [unclear]

OUTPUT: Plain text transcription only.
"""
