package com.boox.day.ui.dialogs

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewTreeObserver
import com.boox.day.compat.AndroidStylusInput
import com.boox.day.compat.OnyxCompat
import com.boox.day.editor.DrawCanvas
import com.boox.day.editor.utils.Eraser
import com.boox.day.editor.utils.setEraserTrail
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.api.device.epd.UpdateMode
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.RawInputCallback
import com.onyx.android.sdk.pen.TouchHelper
import com.onyx.android.sdk.pen.data.TouchPointList
import kotlin.math.hypot

internal data class InkPoint(val x: Float, val y: Float, val t: Long)

/**
 * Hardware-backed Boox E-Ink handwriting pad for Quick-Write Events & Tasks.
 *
 * Uses [OnyxCompat.createTouchHelper] on a dedicated [SurfaceView] so live stylus ink renders
 * directly through Onyx's SurfaceFlinger/EPD hardware scribble path (`FEATURE_SF_TOUCH_RENDER` on
 * EMR Boox tablets or `FEATURE_APP_PEN_TOUCH_RENDER` + `EpdController` on Android-pen devices)
 * with zero Compose frame-recomposition lag.
 */
internal class QuickWriteInkSurfaceView(context: Context) : SurfaceView(context) {

    companion object {
        private const val TAG = "QuickWriteInkSurface"
        private const val STROKE_WIDTH_PX = 4.2f
        private const val PEN_REST_BLIT_DELAY_MS = 220L
        private const val UI_FLUSH_MIN_IDLE_MS = 650L
        private const val UI_UNFREEZE_MS = 55L
        private const val ERASE_RADIUS_PX = 28f
    }

    var placeholderText: String = ""
        set(value) {
            if (field != value) {
                field = value
                if (surfaceReady && completedStrokes.isEmpty()) renderSurface()
            }
        }

    var onStrokesUpdated: ((List<List<InkPoint>>) -> Unit)? = null
    var onWritingStateChanged: ((Boolean) -> Unit)? = null
    var onRequestClearFocusForWriting: (() -> Unit)? = null

    @Volatile
    var isStylusDown: Boolean = false
        private set

    private val completedStrokes = mutableListOf<List<InkPoint>>()
    private val androidPenBackend = OnyxCompat.usesAndroidPenInput(context)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val density = resources.displayMetrics.density
    private val placeholderTextSizePx = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP, 15f, resources.displayMetrics
    )

    private var touchHelper: TouchHelper? = null
    private var surfaceReady = false
    private var rawDrawingOpened = false
    private var rawDrawingEnabled = false
    private var pausedForTextEdit = false
    private var ownsExternalRawSurface = false
    @Volatile private var lastPenUpMs: Long = 0L

    private val lastScreenLoc = IntArray(2) { -1 }
    private var lastWidth = 0
    private var lastHeight = 0

    private var penRestBlitRunnable: Runnable? = null
    private var uiFlushRunnable: Runnable? = null
    private var clearResetRunnable: Runnable? = null

    private val borderPaint = Paint().apply {
        color = Color.parseColor("#0F172A")
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        isAntiAlias = true
    }

    private val guidePaint = Paint().apply {
        color = Color.parseColor("#B8C4D0")
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        isAntiAlias = true
    }

    private val placeholderPaint = Paint().apply {
        color = Color.parseColor("#777777")
        textSize = placeholderTextSizePx
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        isAntiAlias = true
    }

    private val strokePaint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = STROKE_WIDTH_PX
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = true
    }

    private val dotPaint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val rawInputCallback = object : RawInputCallback() {
        override fun onBeginRawDrawing(cancelled: Boolean, point: TouchPoint?) {
            isStylusDown = true
            cancelScheduledCallbacks()
            if (!androidPenBackend && rawDrawingOpened && rawDrawingEnabled) {
                touchHelper?.let {
                    if (!it.isRawDrawingRenderEnabled) {
                        runCatching {
                            applyPenStyle(it)
                            it.isRawDrawingRenderEnabled = true
                        }
                    }
                }
            }
            mainHandler.post { onWritingStateChanged?.invoke(true) }
        }

        override fun onEndRawDrawing(cancelled: Boolean, point: TouchPoint?) {
            isStylusDown = false
            lastPenUpMs = System.currentTimeMillis()
            mainHandler.post { onWritingStateChanged?.invoke(false) }
        }

        override fun onRawDrawingTouchPointMoveReceived(point: TouchPoint?) = Unit

        override fun onRawDrawingTouchPointListReceived(plist: TouchPointList?) {
            val captured = plist?.points?.map { InkPoint(it.x, it.y, it.timestamp) }.orEmpty()
            isStylusDown = false
            lastPenUpMs = System.currentTimeMillis()
            mainHandler.post {
                if (captured.isNotEmpty()) {
                    completedStrokes.add(captured)
                    onStrokesUpdated?.invoke(completedStrokes.map { it.toList() })
                }
                if (androidPenBackend) {
                    renderSurface()
                } else {
                    schedulePenRestBlit()
                }
                onWritingStateChanged?.invoke(false)
            }
        }

        override fun onBeginRawErasing(cancelled: Boolean, point: TouchPoint?) {
            isStylusDown = true
            cancelScheduledCallbacks()
            if (!androidPenBackend) {
                touchHelper?.let { setEraserTrail(it, Eraser.PEN) }
            }
            mainHandler.post { onWritingStateChanged?.invoke(true) }
        }

        override fun onEndRawErasing(cancelled: Boolean, point: TouchPoint?) {
            isStylusDown = false
            mainHandler.post { onWritingStateChanged?.invoke(false) }
        }

        override fun onRawErasingTouchPointMoveReceived(point: TouchPoint?) = Unit

        override fun onRawErasingTouchPointListReceived(plist: TouchPointList?) {
            val eraserPts = plist?.points?.map { InkPoint(it.x, it.y, it.timestamp) }.orEmpty()
            isStylusDown = false
            mainHandler.post {
                if (eraserPts.isNotEmpty() && eraseStrokesNearPoints(completedStrokes, eraserPts, ERASE_RADIUS_PX)) {
                    onStrokesUpdated?.invoke(completedStrokes.map { it.toList() })
                    renderSurface()
                    if (!androidPenBackend) {
                        reopenSessionAfterClear()
                    }
                }
                onWritingStateChanged?.invoke(false)
            }
        }
    }

    private val androidStylusInput by lazy {
        AndroidStylusInput(rawInputCallback, { OnyxCompat.getMaxTouchPressureOrDefault() }) { action, point, normalizedPressure, erasing ->
            when (action) {
                MotionEvent.ACTION_DOWN -> {
                    val width = if (erasing) STROKE_WIDTH_PX * 3f else STROKE_WIDTH_PX
                    EpdController.moveTo(this, point!!.x, point.y, width, normalizedPressure)
                }
                MotionEvent.ACTION_MOVE -> {
                    EpdController.quadTo(this, point!!.x, point.y, UpdateMode.DU, normalizedPressure)
                }
                MotionEvent.ACTION_UP -> {
                    EpdController.penUp()
                }
                MotionEvent.ACTION_CANCEL -> {
                    EpdController.penUp()
                    if (surfaceReady) renderSurface()
                }
            }
        }
    }

    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener {
        if (!surfaceReady || pausedForTextEdit || !rawDrawingOpened || width <= 0 || height <= 0) return@OnGlobalLayoutListener
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        if (loc[0] != lastScreenLoc[0] || loc[1] != lastScreenLoc[1] || width != lastWidth || height != lastHeight) {
            reopenRawSessionForUpdatedBounds()
        }
    }

    private val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            surfaceReady = true
            acquireExternalOwnership()
            renderSurface()
            if (!pausedForTextEdit) {
                openRawSession()
            }
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            surfaceReady = true
            renderSurface()
            if (!pausedForTextEdit && (width != lastWidth || height != lastHeight)) {
                reopenRawSessionForUpdatedBounds()
            }
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            surfaceReady = false
            shutdownSynchronously()
        }
    }

    init {
        setZOrderOnTop(true)
        holder.addCallback(surfaceCallback)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
        shutdownSynchronously()
        super.onDetachedFromWindow()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val toolType = event.getToolType(event.actionIndex)
        val isStylus = toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER
        if (pausedForTextEdit && isStylus && event.actionMasked == MotionEvent.ACTION_DOWN) {
            onRequestClearFocusForWriting?.invoke()
            setPausedForTextEdit(false)
        }
        if (androidPenBackend && surfaceReady && rawDrawingOpened && rawDrawingEnabled && !pausedForTextEdit) {
            val handled = androidStylusInput.onTouchEvent(event, Rect(0, 0, width, height))
            if (handled) return true
        }
        return isStylus || super.onTouchEvent(event)
    }

    fun setPausedForTextEdit(paused: Boolean) {
        if (pausedForTextEdit == paused) return
        pausedForTextEdit = paused
        if (paused) {
            cancelScheduledCallbacks()
            androidStylusInput.cancel()
            val helper = touchHelper
            if (helper != null && rawDrawingEnabled) {
                runCatching { helper.setRawDrawingEnabled(false) }
                rawDrawingEnabled = false
            }
        } else if (surfaceReady && !isStylusDown) {
            if (rawDrawingOpened) {
                touchHelper?.let { helper ->
                    runCatching {
                        applyPenStyle(helper)
                        helper.setRawDrawingEnabled(true)
                        rawDrawingEnabled = true
                    }
                }
            } else {
                openRawSession()
            }
        }
    }

    fun clearInk() {
        cancelScheduledCallbacks()
        androidStylusInput.cancel()
        completedStrokes.clear()
        onStrokesUpdated?.invoke(emptyList())
        renderSurface()
        if (!androidPenBackend) {
            reopenSessionAfterClear()
        }
    }

    /**
     * Momentarily pauses raw-drawing render (keeping raw touch input active so no pen-down stroke
     * is lost) once the pen has rested briefly, allowing Compose updates outside the pad to render
     * on the Boox E-Ink panel.
     */
    fun flushHostUi(immediate: Boolean = false) {
        if (!surfaceReady || pausedForTextEdit || isStylusDown || androidPenBackend) return
        val helper = touchHelper ?: return
        if (!rawDrawingOpened || !rawDrawingEnabled) return
        uiFlushRunnable?.let { mainHandler.removeCallbacks(it) }

        val elapsed = System.currentTimeMillis() - lastPenUpMs
        val waitMs = if (immediate) 0L else (UI_FLUSH_MIN_IDLE_MS - elapsed).coerceAtLeast(0L)
        val flushAction = Runnable {
            uiFlushRunnable = null
            if (!surfaceReady || pausedForTextEdit || isStylusDown || !rawDrawingOpened || !rawDrawingEnabled) return@Runnable
            runCatching { helper.isRawDrawingRenderEnabled = false }
            rootView?.let { host ->
                host.postInvalidate()
                if (host.width > 0 && host.height > 0) {
                    runCatching {
                        EpdController.refreshScreenRegion(host, 0, 0, host.width, host.height, UpdateMode.DU)
                    }
                }
            }
            val resumeRunnable = Runnable {
                uiFlushRunnable = null
                if (!surfaceReady || pausedForTextEdit || !rawDrawingOpened) return@Runnable
                runCatching {
                    if (!isStylusDown) applyPenStyle(helper)
                    helper.isRawDrawingRenderEnabled = true
                }
            }
            uiFlushRunnable = resumeRunnable
            mainHandler.postDelayed(resumeRunnable, UI_UNFREEZE_MS)
        }
        uiFlushRunnable = flushAction
        if (waitMs == 0L) {
            flushAction.run()
        } else {
            mainHandler.postDelayed(flushAction, waitMs)
        }
    }

    private fun acquireExternalOwnership() {
        if (!ownsExternalRawSurface) {
            ownsExternalRawSurface = true
            DrawCanvas.acquireExternalRawSurface()
        }
    }

    fun shutdownSynchronously() {
        cancelScheduledCallbacks()
        androidStylusInput.cancel()
        isStylusDown = false
        closeRawSessionOnly()
        touchHelper = null
        if (ownsExternalRawSurface) {
            ownsExternalRawSurface = false
            DrawCanvas.releaseExternalRawSurface()
        }
    }

    private fun openRawSession() {
        if (!surfaceReady || width <= 0 || height <= 0 || pausedForTextEdit) return
        acquireExternalOwnership()
        val helper = touchHelper ?: OnyxCompat.createTouchHelper(
            this,
            if (androidPenBackend) AndroidStylusInput.previewCallback else rawInputCallback,
            androidPenBackend
        ).also { touchHelper = it }

        closeRawSessionOnly()
        renderSurface()

        getLocationOnScreen(lastScreenLoc)
        lastWidth = width
        lastHeight = height

        runCatching {
            helper.debugLog(false)
            helper.setLimitRect(mutableListOf(Rect(0, 0, width, height)))
            helper.openRawDrawing()
            rawDrawingOpened = true
            applyPenStyle(helper)
            helper.setRawDrawingEnabled(true)
            rawDrawingEnabled = true
        }.onFailure { t ->
            Log.w(TAG, "Failed to open QuickWrite TouchHelper: ${t.message}", t)
            rawDrawingOpened = false
            rawDrawingEnabled = false
        }
    }

    private fun reopenRawSessionForUpdatedBounds() {
        if (!surfaceReady || width <= 0 || height <= 0 || pausedForTextEdit || isStylusDown) return
        closeRawSessionOnly()
        openRawSession()
    }

    private fun reopenSessionAfterClear() {
        closeRawSessionOnly()
        renderSurface()
        clearResetRunnable?.let { mainHandler.removeCallbacks(it) }
        val runnable = Runnable {
            clearResetRunnable = null
            if (surfaceReady && !pausedForTextEdit && !isStylusDown) {
                openRawSession()
            }
        }
        clearResetRunnable = runnable
        mainHandler.postDelayed(runnable, UI_UNFREEZE_MS)
    }

    private fun closeRawSessionOnly() {
        val helper = touchHelper ?: return
        runCatching {
            if (rawDrawingEnabled) {
                helper.setRawDrawingEnabled(false)
            }
            if (rawDrawingOpened) {
                helper.closeRawDrawing()
            }
        }.onFailure { t ->
            Log.w(TAG, "Failed closing QuickWrite TouchHelper: ${t.message}")
        }
        rawDrawingEnabled = false
        rawDrawingOpened = false
    }

    private fun applyPenStyle(helper: TouchHelper) {
        helper.setStrokeStyle(TouchHelper.STROKE_STYLE_PENCIL)
            ?.setStrokeColor(Color.BLACK)
            ?.setStrokeWidth(STROKE_WIDTH_PX)
        if (!androidPenBackend) {
            setEraserTrail(helper, Eraser.PEN)
        }
    }

    private fun schedulePenRestBlit() {
        penRestBlitRunnable?.let { mainHandler.removeCallbacks(it) }
        val runnable = Runnable {
            penRestBlitRunnable = null
            if (surfaceReady && !isStylusDown) {
                renderSurface()
            }
        }
        penRestBlitRunnable = runnable
        mainHandler.postDelayed(runnable, PEN_REST_BLIT_DELAY_MS)
    }

    private fun cancelScheduledCallbacks() {
        penRestBlitRunnable?.let { mainHandler.removeCallbacks(it) }
        penRestBlitRunnable = null
        uiFlushRunnable?.let { mainHandler.removeCallbacks(it) }
        uiFlushRunnable = null
        clearResetRunnable?.let { mainHandler.removeCallbacks(it) }
        clearResetRunnable = null
    }

    private fun renderSurface() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (!surfaceReady || !holder.surface.isValid || w <= 0f || h <= 0f) return
        var canvas: Canvas? = null
        try {
            canvas = holder.lockCanvas() ?: return
            canvas.drawColor(Color.WHITE)

            // Draw subtle ruled guide lines inside the writing pad
            val padMargin = 16f
            val third = h / 3f
            canvas.drawLine(padMargin, third, w - padMargin, third, guidePaint)
            canvas.drawLine(padMargin, third * 2f, w - padMargin, third * 2f, guidePaint)

            if (completedStrokes.isEmpty() && placeholderText.isNotBlank()) {
                val padPx = 14f * density
                val baseline = padPx - placeholderPaint.ascent()
                canvas.drawText(placeholderText, padPx, baseline, placeholderPaint)
            }

            for (stroke in completedStrokes) {
                if (stroke.size == 1) {
                    canvas.drawCircle(stroke[0].x, stroke[0].y, 2.5f, dotPaint)
                } else if (stroke.size > 1) {
                    val path = Path().apply {
                        moveTo(stroke[0].x, stroke[0].y)
                        for (i in 1 until stroke.size) {
                            lineTo(stroke[i].x, stroke[i].y)
                        }
                    }
                    canvas.drawPath(path, strokePaint)
                }
            }

            // Crisp border matching the popup card framing
            val halfBorder = borderPaint.strokeWidth / 2f
            canvas.drawRect(halfBorder, halfBorder, w - halfBorder, h - halfBorder, borderPaint)
        } catch (t: Throwable) {
            Log.w(TAG, "renderSurface failed: ${t.message}")
        } finally {
            if (canvas != null) {
                runCatching { holder.unlockCanvasAndPost(canvas) }
            }
        }
    }
}

internal fun eraseStrokesNearPoints(
    strokes: MutableList<List<InkPoint>>,
    eraserPoints: List<InkPoint>,
    radiusPx: Float = 28f
): Boolean {
    if (strokes.isEmpty() || eraserPoints.isEmpty()) return false
    return strokes.removeAll { stroke ->
        stroke.any { pt ->
            eraserPoints.any { ep ->
                hypot((pt.x - ep.x).toDouble(), (pt.y - ep.y).toDouble()) <= radiusPx
            }
        }
    }
}
