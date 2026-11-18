package com.bsplan.app.speech

import com.bsplan.app.data.datastore.VoiceTextMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Voice uses Google's free speech recognition whenever the tablet has it (Oct 2026). */
class VoiceSourceTest {

    @Test
    fun googleSpeechWheneverTheTabletHasIt() {
        for (mode in VoiceTextMode.entries) {
            assertEquals("$mode", TranscriptionSource.SPEECH_RECOGNIZER, voiceSource(mode, speechRecognitionAvailable = true))
        }
    }

    @Test
    fun withoutSpeechRecognitionVoiceSettingsOpenFirst() {
        for (mode in VoiceTextMode.entries) {
            assertNull("$mode", voiceSource(mode, speechRecognitionAvailable = false))
        }
    }
}
