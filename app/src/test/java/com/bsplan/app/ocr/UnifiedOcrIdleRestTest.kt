package com.bsplan.app.ocr

import com.bsplan.app.ocr.UnifiedOcrIdleProcessor.Companion.NO_WORK_BACKOFF_MS
import com.bsplan.app.ocr.UnifiedOcrIdleProcessor.Companion.shouldRest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When idle OCR passes rest after a pass that found no work (empty queue, or no key / AI budget). */
class UnifiedOcrIdleRestTest {

    private val activity = 1_000_000L
    private val pass = activity + 10_000L

    @Test
    fun noRestBeforeAnyNoWorkPass() {
        assertFalse(shouldRest(0L, activity, pass))
    }

    @Test
    fun restsAfterNoWorkPassUntilUserIsActive() {
        assertTrue(shouldRest(pass, activity, pass + 2_000L))
        assertTrue(shouldRest(pass, activity, pass + NO_WORK_BACKOFF_MS - 1))
    }

    @Test
    fun userActivityAfterThePassEndsTheRest() {
        assertFalse(shouldRest(pass, pass + 1, pass + 2_000L))
    }

    @Test
    fun restEndsAfterTheBackoff() {
        assertFalse(shouldRest(pass, activity, pass + NO_WORK_BACKOFF_MS))
    }
}
