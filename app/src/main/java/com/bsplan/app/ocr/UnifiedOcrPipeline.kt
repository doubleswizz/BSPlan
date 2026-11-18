package com.bsplan.app.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.util.Log
import com.bsplan.app.data.db.UnifiedOcrRepository
import com.bsplan.app.ocr.model.OcrSource
import com.bsplan.app.ocr.model.UnifiedOcrStatus
import com.bsplan.app.ocr.semantic.SemanticGeniusService
import com.bsplan.app.ocr.source.OcrBitmapSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Background OCR pipeline helper. Cloud page reading has been removed; existing indexed
 * pages remain searchable through [UnifiedOcrRepository] and [SemanticGeniusService].
 */
class UnifiedOcrPipeline(private val context: Context) {

    companion object {
        private const val TAG = "UnifiedOcrPipeline"

        /** Target width for bitmap preprocessing (balance quality vs speed) */
        private const val TARGET_WIDTH = 1400

        /** Fail count threshold for quarantine (A-SHORT-3: Zombie cleanup) */
        private const val QUARANTINE_THRESHOLD = 3
    }

    private val booxNoteRepository by lazy { com.bsplan.app.data.db.BooxNoteRepository(context) }
    private val ocrRepository by lazy { UnifiedOcrRepository(context) }
    private val quotaManager by lazy { OcrQuotaManager(context) }

    /** Grace period for bitmap persistence (local default: 30 seconds) */
    private val bitmapGracePeriodMs: Long
        get() = quotaManager.bitmapGracePeriodMs

    /**
     * SemanticGeniusService for unified semantic extraction and cache invalidation.
     * Lazily initialized on first use.
     */
    private var _semanticGeniusService: SemanticGeniusService? = null

    private fun getSemanticService(): SemanticGeniusService {
        return _semanticGeniusService ?: SemanticGeniusService(context).also {
            _semanticGeniusService = it
        }
    }

    private suspend fun invalidateBooxNoteAggregateIfNeeded(sourceType: OcrSource, sourceId: String) {
        if (sourceType == OcrSource.BOOX_NOTE) {
            booxNoteRepository.deleteSemanticAggregate(sourceId)
        }
    }

    /**
     * Public accessor for cache invalidation (backward compatibility).
     */
    var semanticGeniusService: SemanticGeniusService?
        get() = _semanticGeniusService
        set(value) { _semanticGeniusService = value }

    /**
     * OCR result from the pipeline.
     */
    data class OcrResult(
        val text: String,
        val confidence: Float,
        val language: String?,
        val blocks: Int
    )

    /**
     * Result of processPage indicating what happened.
     */
    enum class ProcessResult {
        /** Page processed successfully */
        SUCCESS,
        /** Page was empty (no bitmap) - not a failure */
        EMPTY,
        /** Processing failed but should be retried later */
        FAILED,
        /** Processing failed too many times and was quarantined */
        QUARANTINED,
        /** Indexing waits (unavailable or monthly limit) - page queued for later */
        BUDGET_EXHAUSTED,
        /** Bitmap not ready yet (recently modified page) - will retry later */
        BITMAP_NOT_READY
    }

    suspend fun processPage(
        source: OcrBitmapSource,
        sourceId: String,
        pageId: String,
        sourceModified: Long,
        sourceDate: String? = null,
        sourceTitle: String? = null
    ): Boolean {
        return processPageWithResult(source, sourceId, pageId, sourceModified, sourceDate, sourceTitle)
            .let { it == ProcessResult.SUCCESS || it == ProcessResult.EMPTY || it == ProcessResult.BITMAP_NOT_READY }
    }

    private suspend fun handleRetryableFailure(
        sourceType: OcrSource,
        sourceId: String,
        pageId: String,
        error: String?
    ): ProcessResult {
        val failCount = ocrRepository.markFailedWithRetry(sourceType, sourceId, pageId, error)
        return if (failCount >= QUARANTINE_THRESHOLD) {
            ocrRepository.quarantinePage(sourceType, sourceId, pageId, failCount, error)
            ProcessResult.QUARANTINED
        } else {
            ProcessResult.FAILED
        }
    }

    /**
     * Process a single page from any source with detailed result.
     *
     * @param source The bitmap source to use
     * @param sourceId Parent identifier (noteId, date)
     * @param pageId Page identifier
     * @param sourceModified Source modification timestamp
     * @param sourceDate Optional date for the content (for search filtering)
     * @param sourceTitle Optional title for the source
     * @return ProcessResult indicating what happened
     */
    suspend fun processPageWithResult(
        source: OcrBitmapSource,
        sourceId: String,
        pageId: String,
        sourceModified: Long,
        sourceDate: String? = null,
        sourceTitle: String? = null
    ): ProcessResult {
        val start = System.currentTimeMillis()
        var bitmap: Bitmap? = null

        // Step 1: Check if this is a new page (costs budget) or re-index (free)
        val isNewPage = ocrRepository.isNewPage(source.sourceType, sourceId, pageId)

        // Step 2: For new pages, check budget availability (source-type aware)
        if (isNewPage && !quotaManager.canIndexNewPage(source.sourceType)) {
            Log.w(TAG, "Budget exhausted for ${source.sourceType.name}, skipping new page $sourceId/$pageId " +
                "(remaining: ${quotaManager.getRemainingBudget()})")
            return ProcessResult.BUDGET_EXHAUSTED
        }

        // Cloud AI OCR has been removed; keep existing index searchable without cloud requests.
        return ProcessResult.BUDGET_EXHAUSTED
    }

    private fun preprocessBitmap(bitmap: Bitmap): Bitmap {
        var result = bitmap
        var needsRecycle = false

        try {
            // Step 1: Downscale if larger than target
            if (bitmap.width > TARGET_WIDTH) {
                val scale = TARGET_WIDTH.toFloat() / bitmap.width
                val newWidth = TARGET_WIDTH
                val newHeight = (bitmap.height * scale).toInt()
                result = Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
                needsRecycle = true
            }

            // Step 2: Convert to grayscale for better OCR
            val grayscale = Bitmap.createBitmap(result.width, result.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(grayscale)
            val paint = Paint().apply {
                colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
            }
            canvas.drawBitmap(result, 0f, 0f, paint)

            // Recycle intermediate bitmap if we created one from scaling
            if (needsRecycle && result !== bitmap) {
                result.recycle()
            }

            return grayscale
        } catch (e: Exception) {
            Log.w(TAG, "Failed to preprocess bitmap: ${e.message}")
            return bitmap
        }
    }

    suspend fun needsOcr(
        sourceType: OcrSource,
        sourceId: String,
        pageId: String,
        currentModified: Long
    ): Boolean {
        return ocrRepository.needsReOcr(sourceType, sourceId, pageId, currentModified)
    }

    /**
     * Close resources.
     */
    fun close() = Unit
}
