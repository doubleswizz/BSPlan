package com.boox.day.ui.dialogs

import android.view.MotionEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import com.boox.day.data.datastore.GlobalAppSettings
import com.boox.day.data.db.TaskRepository
import com.boox.day.data.model.ParsedEventData
import com.boox.day.editor.DrawCanvas
import com.boox.day.editor.utils.SlotAllocator
import com.boox.day.events.EventDraft
import com.boox.day.events.EventDraftSaver
import com.boox.day.events.EventTargets
import com.boox.day.ocr.DigitalInkModelManager
import com.boox.day.ocr.DigitalInkService
import com.boox.day.ui.MainScreenRequest
import com.boox.day.ui.MainScreenRequests
import com.boox.day.ui.ScreenCentre
import com.boox.day.ui.SnackConf
import com.boox.day.ui.SnackState
import com.boox.day.ui.noRippleClickable
import com.boox.day.ui.theme.EInkTheme
import com.boox.day.ui.theme.InkaTheme
import com.boox.day.viewmodel.EventsViewModel
import com.google.mlkit.vision.digitalink.recognition.Ink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.hypot

enum class QuickWriteType {
    Event,
    Task
}

data class QuickWriteRequest(
    val type: QuickWriteType,
    val initialDate: String
)

/**
 * State controller for the Quick-Write Event & Task Popups (`[+ Event]` / `[+ Task]`).
 * Registered in [com.boox.day.ui.AppOverlays] so [DrawCanvas] automatically pauses raw surface
 * drawing while the popup is open, allowing stylus strokes inside the popup's handwriting pad.
 */
object QuickWriteUi {
    var activeRequest by mutableStateOf<QuickWriteRequest?>(null)
        private set

    val isOpen: Boolean get() = activeRequest != null

    fun openForEvent(date: String) {
        DrawCanvas.forceUnfreeze()
        activeRequest = QuickWriteRequest(QuickWriteType.Event, date)
    }

    fun openForTask(date: String) {
        DrawCanvas.forceUnfreeze()
        activeRequest = QuickWriteRequest(QuickWriteType.Task, date)
    }

    fun close() {
        activeRequest = null
    }
}

@Composable
fun QuickWriteHost() {
    val req = QuickWriteUi.activeRequest ?: return
    InkaTheme {
        QuickWritePlannerPopup(
            request = req,
            onDismiss = QuickWriteUi::close
        )
    }
}

private data class InkPoint(val x: Float, val y: Float, val t: Long)

private data class ParsedQuickWriteEvent(
    val cleanTitle: String,
    val isAllDay: Boolean,
    val startHour: Int,
    val startMinute: Int,
    val endHour: Int,
    val endMinute: Int
)

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun QuickWritePlannerPopup(
    request: QuickWriteRequest,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val eventsViewModel: EventsViewModel = viewModel()
    val taskRepository = remember { TaskRepository(context.applicationContext) }

    var targetDate by remember(request) { mutableStateOf(request.initialDate) }
    var recognizedTitle by remember(request) { mutableStateOf("") }
    var isRecognizing by remember { mutableStateOf(false) }

    // Event-specific state
    var isAllDay by remember(request) { mutableStateOf(false) }
    var startHour by remember(request) { mutableIntStateOf(9) }
    var startMinute by remember(request) { mutableIntStateOf(0) }
    var endHour by remember(request) { mutableIntStateOf(10) }
    var endMinute by remember(request) { mutableIntStateOf(0) }

    // Task-specific state
    var taskPriority by remember(request) { mutableStateOf("") }

    // Handwriting pad stroke state
    val completedStrokes = remember(request) { mutableStateListOf<List<InkPoint>>() }
    val currentStroke = remember(request) { mutableStateListOf<InkPoint>() }
    var strokeRevision by remember(request) { mutableIntStateOf(0) }

    // Ensure ML Kit model is pre-downloaded/synced
    LaunchedEffect(Unit) {
        DigitalInkModelManager.preDownloadAsync(scope)
    }

    suspend fun runHandwritingRecognition() {
        if (completedStrokes.isEmpty()) return
        isRecognizing = true
        try {
            val recognizer = DigitalInkModelManager.getActiveRecognizer()
            if (recognizer == null) {
                isRecognizing = false
                return
            }
            val inkBuilder = Ink.builder()
            for (stroke in completedStrokes) {
                if (stroke.isEmpty()) continue
                val sb = Ink.Stroke.builder()
                for (pt in stroke) {
                    sb.addPoint(Ink.Point.create(pt.x, pt.y, pt.t))
                }
                inkBuilder.addStroke(sb.build())
            }
            val result = withContext(Dispatchers.Default) {
                recognizer.recognize(inkBuilder.build()).await()
            }
            val rawBest = result.candidates.firstOrNull()?.text.orEmpty()
            val tidied = DigitalInkService.tidyRecognizedText(
                text = rawBest,
                languageTag = DigitalInkModelManager.activeLanguage.value,
                english = null
            )
            if (tidied.isNotBlank()) {
                if (request.type == QuickWriteType.Event) {
                    val parsed = parseNaturalEventString(tidied, startHour, startMinute, endHour, endMinute, isAllDay)
                    recognizedTitle = parsed.cleanTitle
                    isAllDay = parsed.isAllDay
                    startHour = parsed.startHour
                    startMinute = parsed.startMinute
                    endHour = parsed.endHour
                    endMinute = parsed.endMinute
                } else {
                    recognizedTitle = tidied
                }
            }
        } catch (_: Exception) {
        } finally {
            isRecognizing = false
        }
    }

    // Debounced auto-recognition 450ms after pen-up
    LaunchedEffect(strokeRevision) {
        if (strokeRevision <= 0 || completedStrokes.isEmpty()) return@LaunchedEffect
        delay(450L)
        runHandwritingRecognition()
    }

    val formattedDateLabel = remember(targetDate) {
        val d = runCatching { LocalDate.parse(targetDate) }.getOrDefault(LocalDate.now())
        d.format(DateTimeFormatter.ofPattern("EEE, MMM d, yyyy", Locale.getDefault()))
    }

    Popup(
        popupPositionProvider = ScreenCentre,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true, dismissOnBackPress = true, dismissOnClickOutside = false)
    ) {
        Column(
            modifier = Modifier
                .width(620.dp)
                .background(EInkTheme.Colors.background, RoundedCornerShape(EInkTheme.Global.cornerRadius))
                .border(2.5.dp, EInkTheme.Colors.borderStrong, RoundedCornerShape(EInkTheme.Global.cornerRadius))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 1. Header Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (request.type == QuickWriteType.Event) {
                        "QUICK-WRITE EVENT (GOOGLE CALENDAR)"
                    } else {
                        "QUICK-WRITE TASK (GOOGLE TASKS)"
                    },
                    fontSize = 18.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = EInkTheme.Colors.primary
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        modifier = Modifier
                            .height(36.dp)
                            .border(1.5.dp, EInkTheme.Colors.borderStrong, RoundedCornerShape(4.dp))
                            .noRippleClickable {
                                completedStrokes.clear()
                                currentStroke.clear()
                                strokeRevision = 0
                                recognizedTitle = ""
                            }
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Clear Ink", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = EInkTheme.Colors.primary)
                    }
                }
            }

            // 2. Stylus Handwriting Pad
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(210.dp)
                    .border(2.dp, EInkTheme.Colors.borderStrong, RoundedCornerShape(4.dp))
                    .background(Color.White, RoundedCornerShape(4.dp))
                    .pointerInteropFilter { motionEvent ->
                        val toolType = motionEvent.getToolType(0)
                        val isEraser = toolType == MotionEvent.TOOL_TYPE_ERASER ||
                            (motionEvent.buttonState and MotionEvent.BUTTON_STYLUS_SECONDARY) != 0

                        when (motionEvent.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                if (isEraser) {
                                    eraseStrokesNear(completedStrokes, motionEvent.x, motionEvent.y)
                                    strokeRevision++
                                } else {
                                    currentStroke.clear()
                                    currentStroke.add(InkPoint(motionEvent.x, motionEvent.y, motionEvent.eventTime))
                                }
                                true
                            }
                            MotionEvent.ACTION_MOVE -> {
                                if (isEraser) {
                                    for (h in 0 until motionEvent.historySize) {
                                        eraseStrokesNear(
                                            completedStrokes,
                                            motionEvent.getHistoricalX(0, h),
                                            motionEvent.getHistoricalY(0, h)
                                        )
                                    }
                                    eraseStrokesNear(completedStrokes, motionEvent.x, motionEvent.y)
                                } else {
                                    for (h in 0 until motionEvent.historySize) {
                                        currentStroke.add(
                                            InkPoint(
                                                motionEvent.getHistoricalX(0, h),
                                                motionEvent.getHistoricalY(0, h),
                                                motionEvent.getHistoricalEventTime(h)
                                            )
                                        )
                                    }
                                    currentStroke.add(InkPoint(motionEvent.x, motionEvent.y, motionEvent.eventTime))
                                }
                                true
                            }
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                if (!isEraser && currentStroke.isNotEmpty()) {
                                    currentStroke.add(InkPoint(motionEvent.x, motionEvent.y, motionEvent.eventTime))
                                    completedStrokes.add(currentStroke.toList())
                                    currentStroke.clear()
                                    strokeRevision++
                                } else if (isEraser) {
                                    strokeRevision++
                                }
                                true
                            }
                            else -> false
                        }
                    }
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    // Draw subtle ruled guide lines inside the writing pad
                    val guideColor = Color(0xFFDDDDDD)
                    val third = size.height / 3f
                    drawLine(guideColor, Offset(16f, third), Offset(size.width - 16f, third), strokeWidth = 1f)
                    drawLine(guideColor, Offset(16f, third * 2f), Offset(size.width - 16f, third * 2f), strokeWidth = 1f)

                    val strokeStyle = Stroke(width = 4.2f, cap = StrokeCap.Round, join = StrokeJoin.Round)

                    for (stroke in completedStrokes) {
                        if (stroke.size == 1) {
                            drawCircle(Color.Black, radius = 2.5f, center = Offset(stroke[0].x, stroke[0].y))
                        } else if (stroke.size > 1) {
                            val path = Path().apply {
                                moveTo(stroke[0].x, stroke[0].y)
                                for (i in 1 until stroke.size) {
                                    lineTo(stroke[i].x, stroke[i].y)
                                }
                            }
                            drawPath(path, Color.Black, style = strokeStyle)
                        }
                    }

                    if (currentStroke.size == 1) {
                        drawCircle(Color.Black, radius = 2.5f, center = Offset(currentStroke[0].x, currentStroke[0].y))
                    } else if (currentStroke.size > 1) {
                        val path = Path().apply {
                            moveTo(currentStroke[0].x, currentStroke[0].y)
                            for (i in 1 until currentStroke.size) {
                                lineTo(currentStroke[i].x, currentStroke[i].y)
                            }
                        }
                        drawPath(path, Color.Black, style = strokeStyle)
                    }
                }

                if (completedStrokes.isEmpty() && currentStroke.isEmpty()) {
                    Text(
                        text = if (request.type == QuickWriteType.Event) {
                            "Write with stylus here (e.g. \"Team Sync 2:00p\" or \"Project Lunch 12pm\")"
                        } else {
                            "Write task with stylus here (e.g. \"Review Q1 Budget\")"
                        },
                        fontSize = 15.sp,
                        color = Color(0xFF777777),
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(14.dp)
                    )
                }
            }

            // 3. Recognized / Editable Text Field
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = if (isRecognizing) "RECOGNIZING HANDWRITING..." else "RECOGNIZED TITLE (TAP TO EDIT):",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = EInkTheme.Colors.primary
                )

                BasicTextField(
                    value = recognizedTitle,
                    onValueChange = { recognizedTitle = it.replace("\n", "") },
                    singleLine = true,
                    textStyle = TextStyle(
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold,
                        color = EInkTheme.Colors.primary
                    ),
                    cursorBrush = SolidColor(EInkTheme.Colors.primary),
                    decorationBox = { inner ->
                        Box {
                            if (recognizedTitle.isEmpty()) {
                                Text(
                                    text = if (request.type == QuickWriteType.Event) "Event title..." else "Task title...",
                                    fontSize = 19.sp,
                                    color = Color(0xFF888888)
                                )
                            }
                            inner()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.5.dp, EInkTheme.Colors.borderStrong, RoundedCornerShape(4.dp))
                        .background(Color.White, RoundedCornerShape(4.dp))
                        .padding(12.dp)
                )
            }

            // 4. Date & Time / Priority Controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Date stepper: < EEE, MMM d, yyyy >
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .border(1.dp, EInkTheme.Colors.borderStrong, RoundedCornerShape(4.dp))
                            .noRippleClickable {
                                val d = runCatching { LocalDate.parse(targetDate) }.getOrDefault(LocalDate.now())
                                targetDate = d.minusDays(1).toString()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous day", tint = EInkTheme.Colors.primary)
                    }

                    Text(
                        text = formattedDateLabel,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = EInkTheme.Colors.primary,
                        modifier = Modifier.padding(horizontal = 6.dp)
                    )

                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .border(1.dp, EInkTheme.Colors.borderStrong, RoundedCornerShape(4.dp))
                            .noRippleClickable {
                                val d = runCatching { LocalDate.parse(targetDate) }.getOrDefault(LocalDate.now())
                                targetDate = d.plusDays(1).toString()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next day", tint = EInkTheme.Colors.primary)
                    }
                }

                if (request.type == QuickWriteType.Event) {
                    // Event Time Controls: [All Day] | [< 9:00 AM >]
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .height(40.dp)
                                .border(1.5.dp, EInkTheme.Colors.borderStrong, RoundedCornerShape(4.dp))
                                .background(if (isAllDay) EInkTheme.Colors.primary else Color.White, RoundedCornerShape(4.dp))
                                .noRippleClickable { isAllDay = !isAllDay }
                                .padding(horizontal = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "All Day",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isAllDay) Color.White else EInkTheme.Colors.primary
                            )
                        }

                        if (!isAllDay) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .border(1.dp, EInkTheme.Colors.borderStrong, RoundedCornerShape(4.dp))
                                    .noRippleClickable {
                                        val total = ((startHour * 60 + startMinute) - 30 + 1440) % 1440
                                        startHour = total / 60
                                        startMinute = total % 60
                                        val endTotal = (total + 60) % 1440
                                        endHour = endTotal / 60
                                        endMinute = endTotal % 60
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text("-30m", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = EInkTheme.Colors.primary)
                            }

                            Text(
                                text = "${format12Hour(startHour, startMinute)} – ${format12Hour(endHour, endMinute)}",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = EInkTheme.Colors.primary,
                                modifier = Modifier.padding(horizontal = 2.dp)
                            )

                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .border(1.dp, EInkTheme.Colors.borderStrong, RoundedCornerShape(4.dp))
                                    .noRippleClickable {
                                        val total = ((startHour * 60 + startMinute) + 30) % 1440
                                        startHour = total / 60
                                        startMinute = total % 60
                                        val endTotal = (total + 60) % 1440
                                        endHour = endTotal / 60
                                        endMinute = endTotal % 60
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text("+30m", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = EInkTheme.Colors.primary)
                            }
                        }
                    }
                } else {
                    // Task Priority Controls: None, A, B, C
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("Priority:", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = EInkTheme.Colors.primary)
                        listOf("" to "None", "A" to "A", "B" to "B", "C" to "C").forEach { (prio, label) ->
                            val selected = taskPriority == prio
                            Box(
                                modifier = Modifier
                                    .height(38.dp)
                                    .border(1.5.dp, EInkTheme.Colors.borderStrong, RoundedCornerShape(4.dp))
                                    .background(if (selected) EInkTheme.Colors.primary else Color.White, RoundedCornerShape(4.dp))
                                    .noRippleClickable { taskPriority = prio }
                                    .padding(horizontal = 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = label,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (selected) Color.White else EInkTheme.Colors.primary
                                )
                            }
                        }
                    }
                }
            }

            // 5. Action Buttons Footer
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (request.type == QuickWriteType.Event) {
                    Box(
                        modifier = Modifier
                            .height(44.dp)
                            .border(1.5.dp, EInkTheme.Colors.borderStrong, RoundedCornerShape(4.dp))
                            .noRippleClickable {
                                val draft = ParsedEventData(
                                    title = recognizedTitle.trim(),
                                    date = targetDate,
                                    startHour = if (isAllDay) null else startHour,
                                    startMinute = if (isAllDay) null else startMinute,
                                    endHour = if (isAllDay) null else endHour,
                                    endMinute = if (isAllDay) null else endMinute,
                                    isAllDay = isAllDay,
                                    confidence = 1.0f
                                )
                                onDismiss()
                                MainScreenRequests.request(MainScreenRequest.NewEventFrom(draft))
                            }
                            .padding(horizontal = 14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Full Options...", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = EInkTheme.Colors.primary)
                    }
                } else {
                    Spacer(modifier = Modifier.width(1.dp))
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        modifier = Modifier
                            .height(44.dp)
                            .border(1.5.dp, EInkTheme.Colors.borderStrong, RoundedCornerShape(4.dp))
                            .noRippleClickable { onDismiss() }
                            .padding(horizontal = 18.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Cancel", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = EInkTheme.Colors.primary)
                    }

                    val canSave = recognizedTitle.isNotBlank() || completedStrokes.isNotEmpty()
                    Box(
                        modifier = Modifier
                            .height(44.dp)
                            .background(
                                if (canSave) EInkTheme.Colors.primary else Color(0xFFCCCCCC),
                                RoundedCornerShape(4.dp)
                            )
                            .noRippleClickable {
                                if (!canSave) return@noRippleClickable
                                scope.launch {
                                    if (recognizedTitle.isBlank() && completedStrokes.isNotEmpty()) {
                                        runHandwritingRecognition()
                                    }
                                    val finalTitle = recognizedTitle.trim().ifBlank {
                                        if (request.type == QuickWriteType.Event) "New Event" else "Handwritten task"
                                    }

                                    if (request.type == QuickWriteType.Event) {
                                        val target = EventTargets.current()
                                        val draft = EventDraft(
                                            calendarId = target.calendar?.id ?: "primary",
                                            title = finalTitle,
                                            date = targetDate,
                                            startHour = startHour,
                                            startMinute = startMinute,
                                            endHour = if (isAllDay) null else endHour,
                                            endMinute = if (isAllDay) null else endMinute,
                                            isAllDay = isAllDay,
                                            location = null,
                                            description = null,
                                            attendees = null,
                                            syncEnabled = target.syncEnabled,
                                            syncProviderId = target.calendar?.providerId,
                                            syncAccountId = target.calendar?.accountId,
                                            localCalendarId = target.localCalendarId
                                        )
                                        EventDraftSaver.create(
                                            viewModel = eventsViewModel,
                                            draft = draft,
                                            successText = "Event saved: $finalTitle"
                                        ) {
                                            onDismiss()
                                        }
                                    } else {
                                        val canvasH = GlobalAppSettings.tasksCanvasHeight.takeIf { it > 0f } ?: 1200f
                                        val allocated = withContext(Dispatchers.IO) {
                                            SlotAllocator.allocateSingle(
                                                date = targetDate,
                                                canvasHeight = canvasH,
                                                taskRepository = taskRepository,
                                                startPageIndex = 0
                                            )
                                        }
                                        if (allocated != null) {
                                            withContext(Dispatchers.IO) {
                                                taskRepository.setTypedText(allocated.task.id, finalTitle)
                                                if (taskPriority.isNotBlank()) {
                                                    taskRepository.setPriority(allocated.task.id, taskPriority, canvasH)
                                                }
                                            }
                                            MainScreenRequests.request(MainScreenRequest.ReloadTasks)
                                            SnackState.globalSnackFlow.tryEmit(
                                                SnackConf(text = "Task saved: $finalTitle", duration = SnackConf.DURATION_QUICK)
                                            )
                                            onDismiss()
                                        } else {
                                            SnackState.globalSnackFlow.tryEmit(
                                                SnackConf(text = "No available task slots on $targetDate", duration = SnackConf.DURATION_ERROR)
                                            )
                                        }
                                    }
                                }
                            }
                            .padding(horizontal = 22.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (request.type == QuickWriteType.Event) "Save Event" else "Save Task",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }
        }
    }
}

private fun eraseStrokesNear(
    strokes: MutableList<List<InkPoint>>,
    x: Float,
    y: Float,
    radiusPx: Float = 28f
) {
    strokes.removeAll { stroke ->
        stroke.any { pt -> hypot((pt.x - x).toDouble(), (pt.y - y).toDouble()) <= radiusPx }
    }
}

private fun format12Hour(hour24: Int, minute: Int): String {
    val amPm = if (hour24 < 12) "AM" else "PM"
    val h12 = when {
        hour24 == 0 -> 12
        hour24 > 12 -> hour24 - 12
        else -> hour24
    }
    return String.format(Locale.US, "%d:%02d %s", h12, minute, amPm)
}

/**
 * Extracts natural time expressions from handwritten event strings such as:
 * - `"Staff Meeting 2pm"` -> title `"Staff Meeting"`, 14:00–15:00
 * - `"10:30a Dentist"` -> title `"Dentist"`, 10:30–11:30
 * - `"9:00am - 10:30am Workshop"` -> title `"Workshop"`, 09:00–10:30
 */
private fun parseNaturalEventString(
    rawText: String,
    currentStartH: Int,
    currentStartM: Int,
    currentEndH: Int,
    currentEndM: Int,
    currentAllDay: Boolean
): ParsedQuickWriteEvent {
    val rangeRegex = Regex(
        """\b(\d{1,2})(?::(\d{2}))?\s*([aApP][mM]?)?\s*[-–to]+\s*(\d{1,2})(?::(\d{2}))?\s*([aApP][mM]?)\b"""
    )
    val rangeMatch = rangeRegex.find(rawText)
    if (rangeMatch != null) {
        val h1Raw = rangeMatch.groupValues[1].toIntOrNull() ?: currentStartH
        val m1 = rangeMatch.groupValues[2].toIntOrNull() ?: 0
        val mer1 = rangeMatch.groupValues[3].lowercase(Locale.US)
        val h2Raw = rangeMatch.groupValues[4].toIntOrNull() ?: currentEndH
        val m2 = rangeMatch.groupValues[5].toIntOrNull() ?: 0
        val mer2 = rangeMatch.groupValues[6].lowercase(Locale.US)

        val effectiveMer1 = mer1.ifEmpty { mer2 }
        val startH = to24Hour(h1Raw, effectiveMer1)
        val endH = to24Hour(h2Raw, mer2)
        val stripped = rawText.removeRange(rangeMatch.range).replace(Regex("\\s+"), " ").trim(' ', '-', ',', '@')
        return ParsedQuickWriteEvent(
            cleanTitle = stripped.ifBlank { rawText.trim() },
            isAllDay = false,
            startHour = startH,
            startMinute = m1.coerceIn(0, 59),
            endHour = endH,
            endMinute = m2.coerceIn(0, 59)
        )
    }

    val singleTimeRegex = Regex("""(?:\bat\s+)?\b(\d{1,2})(?::(\d{2}))?\s*([aApP][mM]?)\b""")
    val singleMatch = singleTimeRegex.find(rawText)
    if (singleMatch != null) {
        val hRaw = singleMatch.groupValues[1].toIntOrNull() ?: currentStartH
        val m = singleMatch.groupValues[2].toIntOrNull() ?: 0
        val mer = singleMatch.groupValues[3].lowercase(Locale.US)
        val startH = to24Hour(hRaw, mer)
        val endTotal = (startH * 60 + m + 60) % 1440
        val stripped = rawText.removeRange(singleMatch.range).replace(Regex("\\s+"), " ").trim(' ', '-', ',', '@')
        return ParsedQuickWriteEvent(
            cleanTitle = stripped.ifBlank { rawText.trim() },
            isAllDay = false,
            startHour = startH,
            startMinute = m.coerceIn(0, 59),
            endHour = endTotal / 60,
            endMinute = endTotal % 60
        )
    }

    return ParsedQuickWriteEvent(
        cleanTitle = rawText.trim(),
        isAllDay = currentAllDay,
        startHour = currentStartH,
        startMinute = currentStartM,
        endHour = currentEndH,
        endMinute = currentEndM
    )
}

private fun to24Hour(hour: Int, meridiem: String): Int {
    val h = hour.coerceIn(0, 23)
    return when {
        meridiem.startsWith("p") && h < 12 -> h + 12
        meridiem.startsWith("a") && h == 12 -> 0
        else -> h
    }
}
