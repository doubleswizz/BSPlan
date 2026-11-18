package com.boox.day.ui.calendar

import android.view.MotionEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.boox.day.data.datastore.AppSettings
import com.boox.day.data.db.CalendarEvent
import com.boox.day.editor.DrawCanvas
import com.boox.day.editor.drawing.MonthPlannerCanvasRenderer
import com.boox.day.ui.noRippleClickable
import com.boox.day.ui.theme.EInkTheme
import java.text.DateFormatSymbols
import java.util.Calendar
import java.util.Locale
import kotlin.math.hypot

/**
 * Top header bar for the Full-Page Month View (`< JANUARY 2027 ▼ >` + `[+ Event]` `[+ Task]` `[⚙]`).
 * Tapping `MONTH YEAR` opens an E-Ink Month & Year picker popup.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun MonthPlannerHeaderBar(
    year: Int,
    monthZeroBased: Int,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onTodayClick: () -> Unit,
    onAddEventClick: () -> Unit,
    onAddTaskClick: () -> Unit,
    onPreferences: () -> Unit = {},
    onCalendarSettings: () -> Unit = {},
    onCalendarAccounts: () -> Unit = {},
    onAiSettings: () -> Unit = {},
    onVoiceSettings: () -> Unit = {},
    onDataBackup: () -> Unit = {},
    onBooxNotesSettings: () -> Unit = {},
    onAbout: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    if (PlannerNavigationState.mode != PlannerViewMode.Month) return

    var showSettingsMenu by remember { mutableStateOf(false) }
    var showMonthYearPicker by remember { mutableStateOf(false) }
    val monthName = remember(monthZeroBased) {
        DateFormatSymbols(Locale.getDefault()).months
            .getOrElse(monthZeroBased) { "" }
            .uppercase(Locale.getDefault())
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(EInkTheme.Colors.background)
            .pointerInteropFilter { event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    DrawCanvas.forceUnfreeze()
                }
                false
            }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .padding(horizontal = EInkTheme.Global.padding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Left: < MONTH YEAR ▼ >
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(EInkTheme.Global.touchTarget)
                        .noRippleClickable {
                            DrawCanvas.forceUnfreeze()
                            onPreviousMonth()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = "Previous Month",
                        tint = EInkTheme.Colors.primary,
                        modifier = Modifier.size(28.dp)
                    )
                }

                Box {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .height(EInkTheme.Global.touchTarget)
                            .noRippleClickable {
                                DrawCanvas.forceUnfreeze()
                                showMonthYearPicker = !showMonthYearPicker
                            }
                            .padding(horizontal = 6.dp)
                    ) {
                        Text(
                            text = "$monthName $year",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = EInkTheme.Colors.primary
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        Icon(
                            imageVector = Icons.Default.ArrowDropDown,
                            contentDescription = "Select Month and Year",
                            tint = EInkTheme.Colors.primary,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    if (showMonthYearPicker) {
                        MonthYearPickerPopup(
                            currentYear = year,
                            currentMonthZeroBased = monthZeroBased,
                            onSelectMonthYear = { selectedYear, selectedMonth ->
                                showMonthYearPicker = false
                                PlannerNavigationState.onSelectMonthYear?.invoke(selectedYear, selectedMonth)
                            },
                            onJumpToToday = {
                                showMonthYearPicker = false
                                onTodayClick()
                            },
                            onDismiss = { showMonthYearPicker = false }
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .size(EInkTheme.Global.touchTarget)
                        .noRippleClickable {
                            DrawCanvas.forceUnfreeze()
                            onNextMonth()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "Next Month",
                        tint = EInkTheme.Colors.primary,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            // Right: [+ Event]  [+ Task]  [Settings]
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                PlannerHeaderActionButton(
                    label = "Event",
                    accentColor = Color(0xFF2563EB), // Royal Blue
                    tintBgColor = Color(0xFFDBEAFE),
                    onClick = {
                        DrawCanvas.forceUnfreeze()
                        onAddEventClick()
                    }
                )

                PlannerHeaderActionButton(
                    label = "Task",
                    accentColor = Color(0xFF059669), // Emerald Green
                    tintBgColor = Color(0xFFD1FAE5),
                    onClick = {
                        DrawCanvas.forceUnfreeze()
                        onAddTaskClick()
                    }
                )

                Box(
                    modifier = Modifier
                        .size(EInkTheme.Global.touchTarget)
                        .noRippleClickable {
                            DrawCanvas.forceUnfreeze()
                            showSettingsMenu = !showSettingsMenu
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Settings",
                        tint = EInkTheme.Colors.primary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }

        if (showSettingsMenu) {
            SettingsMenu(
                onPreferences = {
                    showSettingsMenu = false
                    onPreferences()
                },
                onCalendarSettings = {
                    showSettingsMenu = false
                    onCalendarSettings()
                },
                onCalendarAccounts = {
                    showSettingsMenu = false
                    onCalendarAccounts()
                },
                onAiSettings = {
                    showSettingsMenu = false
                    onAiSettings()
                },
                onVoiceSettings = {
                    showSettingsMenu = false
                    onVoiceSettings()
                },
                onDataBackup = {
                    showSettingsMenu = false
                    onDataBackup()
                },
                onBooxNotesSettings = {
                    showSettingsMenu = false
                    onBooxNotesSettings()
                },
                onAbout = {
                    showSettingsMenu = false
                    onAbout()
                },
                onClose = { showSettingsMenu = false }
            )
        }

        // Solid bottom border
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(EInkTheme.Global.borderWidth)
                .background(EInkTheme.Colors.borderStrong)
        )
    }
}

/**
 * E-Ink Month & Year picker popup opened when tapping the Month/Year title in the top left header.
 */
@Composable
fun MonthYearPickerPopup(
    currentYear: Int,
    currentMonthZeroBased: Int,
    onSelectMonthYear: (year: Int, monthZeroBased: Int) -> Unit,
    onJumpToToday: () -> Unit,
    onDismiss: () -> Unit
) {
    var pickerYear by remember(currentYear) { mutableIntStateOf(currentYear) }
    val monthLabels = remember {
        listOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")
    }

    DisposableEffect(Unit) {
        DrawCanvas.mainScreenModalVisible.value = true
        DrawCanvas.forceUnfreeze()
        onDispose {
            DrawCanvas.mainScreenModalVisible.value = false
        }
    }

    Popup(
        alignment = Alignment.TopStart,
        offset = IntOffset(0, 80),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true)
    ) {
        Column(
            modifier = Modifier
                .width(340.dp)
                .border(
                    width = 2.dp,
                    color = EInkTheme.Colors.borderStrong,
                    shape = RoundedCornerShape(EInkTheme.Global.cornerRadius)
                )
                .background(
                    color = EInkTheme.Colors.background,
                    shape = RoundedCornerShape(EInkTheme.Global.cornerRadius)
                )
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Year selector row: [ < ]  2026  [ > ]
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Box(
                    modifier = Modifier
                        .size(EInkTheme.Global.touchTarget)
                        .border(
                            width = EInkTheme.Global.borderWidth,
                            color = EInkTheme.Colors.borderStrong,
                            shape = RoundedCornerShape(EInkTheme.Global.cornerRadius)
                        )
                        .noRippleClickable { pickerYear -= 1 },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = "Previous Year",
                        tint = EInkTheme.Colors.primary,
                        modifier = Modifier.size(26.dp)
                    )
                }

                Text(
                    text = pickerYear.toString(),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = EInkTheme.Colors.primary
                )

                Box(
                    modifier = Modifier
                        .size(EInkTheme.Global.touchTarget)
                        .border(
                            width = EInkTheme.Global.borderWidth,
                            color = EInkTheme.Colors.borderStrong,
                            shape = RoundedCornerShape(EInkTheme.Global.cornerRadius)
                        )
                        .noRippleClickable { pickerYear += 1 },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "Next Year",
                        tint = EInkTheme.Colors.primary,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }

            // 12-Month Grid (3 columns x 4 rows)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (row in 0 until 4) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        for (col in 0 until 3) {
                            val mIdx = row * 3 + col
                            val isSelected = pickerYear == currentYear && mIdx == currentMonthZeroBased
                            val monthAccent = MONTH_ACCENT_COLORS.getOrElse(mIdx) { EInkTheme.Colors.primary }
                            val monthTint = MONTH_TINT_COLORS.getOrElse(mIdx) { EInkTheme.Colors.background }
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp)
                                    .border(
                                        width = if (isSelected) 2.dp else EInkTheme.Global.borderWidth,
                                        color = if (isSelected) Color(0xFF0F172A) else monthAccent,
                                        shape = RoundedCornerShape(EInkTheme.Global.cornerRadius)
                                    )
                                    .background(
                                        color = if (isSelected) monthAccent else monthTint,
                                        shape = RoundedCornerShape(EInkTheme.Global.cornerRadius)
                                    )
                                    .noRippleClickable {
                                        onSelectMonthYear(pickerYear, mIdx)
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = monthLabels[mIdx],
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = if (isSelected) Color.White else Color(0xFF0F172A),
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }
            }

            // Bottom row: [Jump to Today]  [Close]
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(42.dp)
                        .border(
                            width = EInkTheme.Global.borderWidth,
                            color = Color(0xFFD32F2F),
                            shape = RoundedCornerShape(EInkTheme.Global.cornerRadius)
                        )
                        .background(
                            color = Color(0xFFD32F2F),
                            shape = RoundedCornerShape(EInkTheme.Global.cornerRadius)
                        )
                        .noRippleClickable { onJumpToToday() },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Today",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White
                    )
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(42.dp)
                        .border(
                            width = EInkTheme.Global.borderWidth,
                            color = EInkTheme.Colors.borderStrong,
                            shape = RoundedCornerShape(EInkTheme.Global.cornerRadius)
                        )
                        .background(
                            color = EInkTheme.Colors.background,
                            shape = RoundedCornerShape(EInkTheme.Global.cornerRadius)
                        )
                        .noRippleClickable { onDismiss() },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Close",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = EInkTheme.Colors.primary
                    )
                }
            }
        }
    }
}

@Composable
fun PlannerHeaderActionButton(
    label: String,
    onClick: () -> Unit,
    accentColor: Color = Color(0xFF1E3A8A),
    tintBgColor: Color = Color(0xFFDBEAFE),
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .height(38.dp)
            .border(
                width = 1.8.dp,
                color = accentColor,
                shape = RoundedCornerShape(EInkTheme.Global.cornerRadius)
            )
            .background(
                color = tintBgColor,
                shape = RoundedCornerShape(EInkTheme.Global.cornerRadius)
            )
            .noRippleClickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Add,
            contentDescription = null,
            tint = accentColor,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            fontSize = EInkTheme.Typography.bodyLarge,
            fontWeight = FontWeight.ExtraBold,
            color = Color(0xFF0F172A)
        )
    }
}

/**
 * Palm-rejecting finger tap detector shared by Month and Day full-page canvas overlays.
 * Rejects stylus/eraser contacts, multi-finger/palm contacts, OS-canceled palm touches,
 * large contact areas (tracked via [DrawCanvas.recordAndroidTouchMetrics]), concurrent/recent
 * stylus hover or strokes, and slow palm rests (> [maxDurationMs]).
 */
internal fun Modifier.plannerFingerTap(
    maxMovePx: Float = 24f,
    maxDurationMs: Long = 280L,
    onTap: (Offset, IntSize) -> Unit
): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (down.type != PointerType.Touch || DrawCanvas.isCurrentTouchGestureRejectedForPalm()) {
            return@awaitEachGesture
        }

        val startPos = down.position
        val downTimeMs = down.uptimeMillis
        var upPos = startPos
        var upTimeMs = downTimeMs
        var rejected = false

        while (true) {
            val event = awaitPointerEvent()
            if (event.type == PointerEventType.Exit ||
                event.changes.any { it.type != PointerType.Touch } ||
                event.changes.count { it.pressed } > 1 ||
                DrawCanvas.isCurrentTouchGestureRejectedForPalm()
            ) {
                rejected = true
            }
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            upPos = change.position
            upTimeMs = change.uptimeMillis
            if (hypot((upPos.x - startPos.x).toDouble(), (upPos.y - startPos.y).toDouble()) > maxMovePx) {
                rejected = true
            }
            if (!change.pressed) {
                if (!rejected) change.consume()
                break
            }
        }

        if (rejected ||
            upTimeMs - downTimeMs > maxDurationMs ||
            DrawCanvas.isCurrentTouchGestureRejectedForPalm()
        ) {
            return@awaitEachGesture
        }
        onTap(upPos, size)
    }
}

/**
 * Transparent touch overlay over the Full-Page Month View canvas:
 * - Ignores [PointerType.Stylus] and rejects resting-hand/palm touches so you can write anywhere.
 * - Listens for clean single-finger taps on any visible day cell and opens that day in [PlannerViewMode.Day].
 */
@Composable
fun MonthGridTouchOverlay(
    grid: MonthGridModel?,
    onDayCellTapped: (MonthCellData) -> Unit,
    modifier: Modifier = Modifier
) {
    val currentGridState = rememberUpdatedState(grid)
    val currentCallback = rememberUpdatedState(onDayCellTapped)

    Box(
        modifier = modifier
            .fillMaxSize()
            .plannerFingerTap { upPos, overlaySize ->
                val activeGrid = currentGridState.value ?: return@plannerFingerTap
                val cellIndex = MonthPlannerGridState.hitTestCellIndex(
                    xPx = upPos.x,
                    yPx = upPos.y,
                    widthPx = overlaySize.width.toFloat(),
                    heightPx = overlaySize.height.toFloat(),
                    headerPx = MonthPlannerCanvasRenderer.WEEKDAY_HEADER_HEIGHT_PX,
                    rowCount = activeGrid.rowCount
                ) ?: return@plannerFingerTap

                val tappedCell = activeGrid.cells.getOrNull(cellIndex)
                if (tappedCell != null) {
                    DrawCanvas.forceUnfreeze()
                    currentCallback.value(tappedCell)
                }
            }
    )
}

/**
 * Handles background loading of the dynamic Month Grid (4–6 week rows including spillover days,
 * calendar events, and tasks), Back-button navigation from Day View to Month View, E-Ink canvas
 * redraw synchronization, and session state persistence.
 */
@Composable
fun MonthPlannerEffects(
    year: Int,
    monthZeroBased: Int,
    selectedDate: String,
    firstDayOfWeek: AppSettings.FirstDayOfWeek,
    datesWithNotes: Set<String>,
    datesWithEvents: Set<String>,
    datesWithTasks: Set<String>,
    loadEventsForDay: suspend (String) -> List<CalendarEvent>,
    onEnsureMemosTab: () -> Unit,
    syncRevision: Int = 0,
    onMonthYearSelected: (newYear: Int, newMonthZeroBased: Int) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val mode = PlannerNavigationState.mode
    val latestOnMonthYearSelected = rememberUpdatedState(onMonthYearSelected)

    DisposableEffect(Unit) {
        PlannerNavigationState.onSelectMonthYear = { newYear, newMonth ->
            latestOnMonthYearSelected.value(newYear, newMonth)
        }
        onDispose {
            PlannerNavigationState.onSelectMonthYear = null
        }
    }

    // Persist the active mode, selected date, and viewing month/year so BSPlan opens right where it left off
    LaunchedEffect(mode, selectedDate, year, monthZeroBased) {
        PlannerNavigationState.saveState(
            context = context,
            currentMode = mode,
            selectedDate = selectedDate,
            viewingYear = year,
            viewingMonthZeroBased = monthZeroBased
        )
    }

    BackHandler(enabled = mode == PlannerViewMode.Day) {
        DrawCanvas.flushUIUpdates()
        PlannerNavigationState.mode = PlannerViewMode.Month
    }

    LaunchedEffect(
        year,
        monthZeroBased,
        firstDayOfWeek,
        datesWithNotes,
        datesWithEvents,
        datesWithTasks,
        syncRevision
    ) {
        val previous = MonthPlannerGridState.currentGrid
        val grid = MonthPlannerGridState.loadMonthGrid(
            context = context,
            year = year,
            monthZeroBased = monthZeroBased,
            selectedDate = selectedDate,
            firstDayOfWeek = firstDayOfWeek,
            datesWithNotes = datesWithNotes,
            loadEventsForDay = loadEventsForDay
        )
        MonthPlannerGridState.currentGrid = grid
        if (PlannerNavigationState.mode == PlannerViewMode.Month && !monthGridVisuallyEqual(previous, grid)) {
            DrawCanvas.forceUpdate.emit(null)
        }
    }

    var lastMode by remember { mutableStateOf(mode) }
    LaunchedEffect(mode) {
        onEnsureMemosTab()
        if (mode == lastMode) return@LaunchedEffect
        lastMode = mode
        // Yield a single frame so Compose updates headers/overlays cleanly without waiting 1.5s.
        withFrameNanos { }
        DrawCanvas.forceUpdate.emit(null)
        DrawCanvas.forceUnfreeze()
    }
}

private fun monthGridVisuallyEqual(a: MonthGridModel?, b: MonthGridModel): Boolean {
    if (a == null) return false
    if (a.year != b.year || a.monthZeroBased != b.monthZeroBased || a.rowCount != b.rowCount) return false
    if (a.weekdayHeaders != b.weekdayHeaders || a.cells.size != b.cells.size) return false
    return a.cells.indices.all { i ->
        val ca = a.cells[i]
        val cb = b.cells[i]
        ca.date == cb.date &&
            ca.isCurrentMonth == cb.isCurrentMonth &&
            ca.isToday == cb.isToday &&
            ca.headerLabel == cb.headerLabel &&
            ca.events == cb.events &&
            ca.openTaskCount == cb.openTaskCount &&
            ca.taskPreviews == cb.taskPreviews &&
            ca.hasDayNotes == cb.hasDayNotes
    }
}

/**
 * Hides the legacy SharedToolbar in BSPlan's unified Month View and Day View (and moves the lambda out of MainScreen).
 */
@Composable
fun DayOnlyToolbarSection(content: @Composable () -> Unit) {
    // Hidden in BSPlan: both Month View and Day View use the unified full-page planner header & canvas.
    if (false) {
        content()
    }
}
