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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boox.day.data.datastore.AppSettings
import com.boox.day.data.db.CalendarEvent
import com.boox.day.editor.DrawCanvas
import com.boox.day.editor.drawing.MonthPlannerCanvasRenderer
import com.boox.day.ui.noRippleClickable
import com.boox.day.ui.theme.EInkTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.text.DateFormatSymbols
import java.util.Locale
import kotlin.math.hypot

/**
 * Top header bar for the Full-Page Month View (`< JANUARY 2027 >` + `[+ Event]` `[+ Task]` `[⚙]`).
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
    onHelp: () -> Unit = {},
    onAbout: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    if (PlannerNavigationState.mode != PlannerViewMode.Month) return

    var showSettingsMenu by remember { mutableStateOf(false) }
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
            // Left: < MONTH YEAR >
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

                Text(
                    text = "$monthName $year",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = EInkTheme.Colors.primary,
                    modifier = Modifier
                        .noRippleClickable {
                            DrawCanvas.forceUnfreeze()
                            onTodayClick()
                        }
                        .padding(horizontal = 4.dp)
                )

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
                    onClick = {
                        DrawCanvas.forceUnfreeze()
                        onAddEventClick()
                    }
                )

                PlannerHeaderActionButton(
                    label = "Task",
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
                onHelp = {
                    showSettingsMenu = false
                    onHelp()
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

@Composable
fun PlannerHeaderActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .height(38.dp)
            .border(
                width = EInkTheme.Global.borderWidth,
                color = EInkTheme.Colors.borderStrong,
                shape = RoundedCornerShape(EInkTheme.Global.cornerRadius)
            )
            .background(
                color = EInkTheme.Colors.background,
                shape = RoundedCornerShape(EInkTheme.Global.cornerRadius)
            )
            .noRippleClickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Add,
            contentDescription = null,
            tint = EInkTheme.Colors.primary,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            fontSize = EInkTheme.Typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            color = EInkTheme.Colors.primary
        )
    }
}

/**
 * Transparent touch overlay over the Full-Page Month View canvas:
 * - Ignores [PointerType.Stylus] so the Boox stylus can write anywhere over the Month Grid with zero lag.
 * - Listens for finger taps ([PointerType.Touch]) on any of the 42 day cells (including spillover days)
 *   and opens that day in [PlannerViewMode.Day].
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
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // Let stylus strokes pass untouched to Onyx TouchHelper / DrawCanvas
                    if (down.type == PointerType.Stylus || down.type == PointerType.Eraser) {
                        return@awaitEachGesture
                    }

                    val startPos = down.position
                    var upPos = startPos
                    var movedTooFar = false

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        upPos = change.position
                        if (hypot((upPos.x - startPos.x).toDouble(), (upPos.y - startPos.y).toDouble()) > 28.0) {
                            movedTooFar = true
                        }
                        if (!change.pressed) {
                            change.consume()
                            break
                        }
                    }

                    if (movedTooFar) return@awaitEachGesture

                    val widthPx = size.width.toFloat()
                    val heightPx = size.height.toFloat()
                    val headerPx = MonthPlannerCanvasRenderer.WEEKDAY_HEADER_HEIGHT_PX

                    if (upPos.y <= headerPx || widthPx <= 0f || heightPx <= headerPx) {
                        return@awaitEachGesture
                    }

                    val col = ((upPos.x / (widthPx / 7f)).toInt()).coerceIn(0, 6)
                    val row = (((upPos.y - headerPx) / ((heightPx - headerPx) / 6f)).toInt()).coerceIn(0, 5)
                    val cellIndex = row * 7 + col

                    val tappedCell = currentGridState.value?.cells?.getOrNull(cellIndex)
                    if (tappedCell != null) {
                        DrawCanvas.forceUnfreeze()
                        currentCallback.value(tappedCell)
                    }
                }
            }
    )
}

/**
 * Handles background loading of the 42-day Month Grid (including spillover days, Google Calendar
 * events, and Google Tasks), Back-button navigation from Day View to Month View, and E-Ink canvas
 * redraw synchronization when switching between Month View and Day View.
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
    onEnsureMemosTab: () -> Unit
) {
    val context = LocalContext.current
    val mode = PlannerNavigationState.mode

    BackHandler(enabled = mode == PlannerViewMode.Day) {
        DrawCanvas.flushUIUpdates()
        PlannerNavigationState.mode = PlannerViewMode.Month
    }

    LaunchedEffect(
        year,
        monthZeroBased,
        selectedDate,
        firstDayOfWeek,
        datesWithNotes,
        datesWithEvents,
        datesWithTasks
    ) {
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
        if (PlannerNavigationState.mode == PlannerViewMode.Month) {
            DrawCanvas.forceUpdate.emit(null)
        }
    }

    var lastMode by remember { mutableStateOf(mode) }
    LaunchedEffect(mode) {
        onEnsureMemosTab()
        if (mode == lastMode) return@LaunchedEffect
        lastMode = mode
        val before = DrawCanvas.surfaceSize.value
        withTimeoutOrNull(1500L) {
            DrawCanvas.surfaceSize.first { it != null && it != before }
        }
        delay(120L)
        DrawCanvas.forceUpdate.emit(null)
        DrawCanvas.forceUnfreeze()
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


