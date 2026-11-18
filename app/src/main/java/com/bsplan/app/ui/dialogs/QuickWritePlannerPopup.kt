package com.bsplan.app.ui.dialogs

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bsplan.app.data.datastore.GlobalAppSettings
import com.bsplan.app.data.db.TaskRepository
import com.bsplan.app.data.model.ParsedEventData
import com.bsplan.app.editor.DrawCanvas
import com.bsplan.app.editor.utils.SlotAllocator
import com.bsplan.app.events.EventDraft
import com.bsplan.app.events.EventDraftSaver
import com.bsplan.app.events.EventTargets
import com.bsplan.app.ocr.DigitalInkModelManager
import com.bsplan.app.ocr.DigitalInkService
import com.bsplan.app.ocr.HandwritingNormalizerFactory
import com.bsplan.app.ui.MainScreenRequest
import com.bsplan.app.ui.MainScreenRequests
import com.bsplan.app.ui.ScreenCentre
import com.bsplan.app.ui.SnackConf
import com.bsplan.app.ui.SnackState
import com.bsplan.app.ui.noRippleClickable
import com.bsplan.app.ui.theme.EInkTheme
import com.bsplan.app.ui.theme.InkaTheme
import com.bsplan.app.viewmodel.EventsViewModel
import com.google.mlkit.vision.digitalink.recognition.Ink
import com.google.mlkit.vision.digitalink.recognition.RecognitionContext
import com.google.mlkit.vision.digitalink.recognition.WritingArea
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class QuickWriteType {
    Event,
    Task
}

data class QuickWriteRequest(
    val type: QuickWriteType,
    val initialDate: String,
    val id: Long = System.nanoTime()
)

/**
 * State controller for the Quick-Write Event & Task Popups (`[+ Event]` / `[+ Task]`).
 * Registered in [com.bsplan.app.ui.AppOverlays] so [DrawCanvas] automatically pauses raw surface
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
        key(req.id) {
            QuickWritePlannerPopup(
                request = req,
                onDismiss = QuickWriteUi::close
            )
        }
    }
}

private data class ParsedQuickWriteEvent(
    val cleanTitle: String,
    val isAllDay: Boolean,
    val startHour: Int,
    val startMinute: Int,
    val endHour: Int,
    val endMinute: Int
)

@Composable
private fun QuickWritePlannerPopup(
    request: QuickWriteRequest,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
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
    var timeManuallyAdjusted by remember(request) { mutableStateOf(false) }
    val calendarOptions = remember(request) { EventTargets.options() }
    var selectedTarget by remember(request) { mutableStateOf(EventTargets.current()) }
    var showCalendarDropdown by remember(request) { mutableStateOf(false) }

    // Task-specific state
    var taskPriority by remember(request) { mutableStateOf("") }

    // Hardware Boox handwriting pad state
    var completedStrokes by remember(request) { mutableStateOf<List<List<InkPoint>>>(emptyList()) }
    var strokeRevision by remember(request) { mutableIntStateOf(0) }
    var lastRecognizedRevision by remember(request) { mutableIntStateOf(0) }
    var writingActive by remember(request) { mutableStateOf(false) }
    var textFieldFocused by remember(request) { mutableStateOf(false) }
    var surfaceViewRef by remember(request) { mutableStateOf<QuickWriteInkSurfaceView?>(null) }

    LaunchedEffect(textFieldFocused, showCalendarDropdown, surfaceViewRef) {
        surfaceViewRef?.setPausedForTextEdit(textFieldFocused || showCalendarDropdown)
    }

    val closeSafely = {
        surfaceViewRef?.shutdownSynchronously()
        onDismiss()
    }

    DisposableEffect(request) {
        onDispose {
            surfaceViewRef?.shutdownSynchronously()
        }
    }

    // Ensure ML Kit model is pre-downloaded/synced
    LaunchedEffect(Unit) {
        DigitalInkModelManager.preDownloadAsync(scope)
    }

    suspend fun runHandwritingRecognition() {
        val snapshot = completedStrokes
        val revisionAtStart = strokeRevision
        if (snapshot.isEmpty()) return
        isRecognizing = true
        try {
            val recognizer = DigitalInkModelManager.ensureActiveRecognizer()
            if (recognizer == null) {
                Log.w("QuickWritePopup", "Handwriting recognizer unavailable for ${DigitalInkModelManager.activeLanguage.value}")
                return
            }
            val ink = buildQuickWriteInk(snapshot)
            val padW = surfaceViewRef?.width?.toFloat() ?: 0f
            val padH = surfaceViewRef?.height?.toFloat() ?: 0f
            val recognitionContext = if (padW > 0f && padH > 0f) {
                RecognitionContext.builder()
                    .setWritingArea(WritingArea(padW, padH))
                    .build()
            } else {
                null
            }
            val languageTag = DigitalInkModelManager.activeLanguage.value
            val (rawBest, englishNormalizer) = withContext(Dispatchers.Default) {
                val recognitionResult = if (recognitionContext != null) {
                    recognizer.recognize(ink, recognitionContext).await()
                } else {
                    recognizer.recognize(ink).await()
                }
                val best = recognitionResult.candidates.firstOrNull()?.text.orEmpty()
                val normalizer = if (
                    languageTag.startsWith("en") &&
                    HandwritingNormalizerFactory.hasLanguageSupport(context, languageTag)
                ) {
                    HandwritingNormalizerFactory.getInstance(context, languageTag)
                } else {
                    null
                }
                best to normalizer
            }
            val tidied = DigitalInkService.tidyRecognizedText(
                text = rawBest,
                languageTag = languageTag,
                english = englishNormalizer
            ).ifBlank { rawBest.trim() }
            if (tidied.isNotBlank()) {
                if (request.type == QuickWriteType.Event) {
                    val baseStartH = if (timeManuallyAdjusted) startHour else 9
                    val baseStartM = if (timeManuallyAdjusted) startMinute else 0
                    val baseEndH = if (timeManuallyAdjusted) endHour else 10
                    val baseEndM = if (timeManuallyAdjusted) endMinute else 0
                    val baseAllDay = if (timeManuallyAdjusted) isAllDay else false
                    val parsed = parseNaturalEventString(tidied, baseStartH, baseStartM, baseEndH, baseEndM, baseAllDay)
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
            lastRecognizedRevision = revisionAtStart
        } catch (t: Throwable) {
            Log.w("QuickWritePopup", "Handwriting recognition failed: ${t.message}", t)
        } finally {
            isRecognizing = false
        }
    }

    // Debounced auto-recognition 750ms after pen-up (cancelled if the user puts the pen back down)
    LaunchedEffect(strokeRevision, writingActive) {
        if (writingActive || strokeRevision <= 0 || completedStrokes.isEmpty()) return@LaunchedEffect
        delay(750L)
        if (!writingActive && surfaceViewRef?.isStylusDown != true) {
            runHandwritingRecognition()
        }
    }

    // Unfreeze Boox EPD briefly whenever Compose UI outside the pad finishes updating while pen is resting
    LaunchedEffect(recognizedTitle, isRecognizing, targetDate, isAllDay, startHour, startMinute, taskPriority, selectedTarget, showCalendarDropdown, completedStrokes.size) {
        if (!isRecognizing && !writingActive && surfaceViewRef?.isStylusDown != true) {
            surfaceViewRef?.flushHostUi()
        }
    }

    val formattedDateLabel = remember(targetDate) {
        val d = runCatching { LocalDate.parse(targetDate) }.getOrDefault(LocalDate.now())
        d.format(DateTimeFormatter.ofPattern("EEE, MMM d, yyyy", Locale.getDefault()))
    }

    Popup(
        popupPositionProvider = ScreenCentre,
        onDismissRequest = closeSafely,
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
                        "QUICK-WRITE EVENT"
                    } else {
                        "QUICK-WRITE TASK"
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
                                surfaceViewRef?.clearInk()
                                strokeRevision = 0
                                lastRecognizedRevision = 0
                                recognizedTitle = ""
                            }
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Clear Ink", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = EInkTheme.Colors.primary)
                    }
                }
            }

            // 2. Hardware Boox Stylus Handwriting Pad
            val padPlaceholder = if (request.type == QuickWriteType.Event) {
                "Write with stylus here (e.g. \"Team Sync 2:00p\" or \"Project Lunch 12pm\")"
            } else {
                "Write task with stylus here (e.g. \"Review Q1 Budget\")"
            }
            AndroidView(
                factory = { ctx ->
                    QuickWriteInkSurfaceView(ctx).apply {
                        placeholderText = padPlaceholder
                        onStrokesUpdated = { updated ->
                            completedStrokes = updated
                            strokeRevision++
                        }
                        onWritingStateChanged = { active ->
                            writingActive = active
                        }
                        onRequestClearFocusForWriting = {
                            focusManager.clearFocus()
                        }
                        surfaceViewRef = this
                    }
                },
                update = { view ->
                    view.placeholderText = padPlaceholder
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(210.dp)
            )

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
                        .onFocusChanged { textFieldFocused = it.isFocused }
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
                                .noRippleClickable {
                                    timeManuallyAdjusted = true
                                    isAllDay = !isAllDay
                                }
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
                                        timeManuallyAdjusted = true
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
                                        timeManuallyAdjusted = true
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

            // 4.5. Calendar Selection on Initial Screen (Events only)
            if (request.type == QuickWriteType.Event) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Calendar:",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = EInkTheme.Colors.primary
                    )

                    Box(modifier = Modifier.weight(1f)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(40.dp)
                                .border(1.5.dp, EInkTheme.Colors.borderStrong, RoundedCornerShape(4.dp))
                                .background(Color.White, RoundedCornerShape(4.dp))
                                .noRippleClickable { showCalendarDropdown = true }
                                .padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = selectedTarget.label,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = EInkTheme.Colors.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowDown,
                                contentDescription = "Choose calendar",
                                tint = EInkTheme.Colors.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        DropdownMenu(
                            expanded = showCalendarDropdown,
                            onDismissRequest = { showCalendarDropdown = false }
                        ) {
                            calendarOptions.forEach { option ->
                                val isSelected = option.key == selectedTarget.key
                                DropdownMenuItem(
                                    onClick = {
                                        selectedTarget = option
                                        EventTargets.select(option)
                                        showCalendarDropdown = false
                                    }
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = option.label,
                                            fontSize = 15.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            color = EInkTheme.Colors.primary
                                        )
                                        if (isSelected) {
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = "Selected",
                                                tint = EInkTheme.Colors.primary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
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
                                scope.launch {
                                    if (completedStrokes.isNotEmpty() && strokeRevision != lastRecognizedRevision) {
                                        runHandwritingRecognition()
                                    }
                                    val draft = ParsedEventData(
                                        title = recognizedTitle.trim(),
                                        date = targetDate,
                                        startHour = if (isAllDay) null else startHour,
                                        startMinute = if (isAllDay) null else startMinute,
                                        endHour = if (isAllDay) null else endHour,
                                        endMinute = if (isAllDay) null else endMinute,
                                        isAllDay = isAllDay,
                                        confidence = 1.0f,
                                        targetSyncEnabled = selectedTarget.syncEnabled,
                                        targetCalendarId = selectedTarget.calendar?.id,
                                        targetProviderId = selectedTarget.calendar?.providerId?.key,
                                        targetAccountId = selectedTarget.calendar?.accountId,
                                        targetLocalCalendarId = selectedTarget.localCalendarId
                                    )
                                    closeSafely()
                                    MainScreenRequests.request(MainScreenRequest.NewEventFrom(draft))
                                }
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
                            .noRippleClickable { closeSafely() }
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
                                    if (completedStrokes.isNotEmpty() && strokeRevision != lastRecognizedRevision) {
                                        runHandwritingRecognition()
                                    }
                                    val finalTitle = recognizedTitle.trim().ifBlank {
                                        if (request.type == QuickWriteType.Event) "New Event" else "Handwritten task"
                                    }

                                    if (request.type == QuickWriteType.Event) {
                                        EventTargets.select(selectedTarget)
                                        val draft = EventDraft(
                                            calendarId = selectedTarget.calendar?.id ?: "primary",
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
                                            syncEnabled = selectedTarget.syncEnabled,
                                            syncProviderId = selectedTarget.calendar?.providerId,
                                            syncAccountId = selectedTarget.calendar?.accountId,
                                            localCalendarId = selectedTarget.localCalendarId
                                        )
                                        EventDraftSaver.create(
                                            viewModel = eventsViewModel,
                                            draft = draft,
                                            successText = "Event saved: $finalTitle"
                                        ) {
                                            closeSafely()
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
                                            closeSafely()
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

internal fun buildQuickWriteInk(strokes: List<List<InkPoint>>): Ink {
    val inkBuilder = Ink.builder()
    var lastTimestamp = 0L
    for (stroke in strokes) {
        if (stroke.isEmpty()) continue
        val sb = Ink.Stroke.builder()
        for (pt in stroke) {
            val t = if (pt.t > 0L) pt.t.coerceAtLeast(lastTimestamp) else lastTimestamp + QuickWriteInkSurfaceView.DEFAULT_POINT_STEP_MS
            lastTimestamp = t
            sb.addPoint(Ink.Point.create(pt.x, pt.y, t))
        }
        inkBuilder.addStroke(sb.build())
    }
    return inkBuilder.build()
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
