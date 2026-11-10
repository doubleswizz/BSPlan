package com.boox.day.ui.calendar

import android.view.MotionEvent
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.boox.day.data.datastore.GlobalAppSettings
import com.boox.day.data.db.CalendarEvent
import com.boox.day.data.db.Task
import com.boox.day.data.db.TaskRepository
import com.boox.day.editor.DrawCanvas
import com.boox.day.editor.drawing.DayPlannerCanvasRenderer
import com.boox.day.ui.MainScreenRequest
import com.boox.day.ui.MainScreenRequests
import com.boox.day.ui.ScreenCentre
import com.boox.day.ui.SnackConf
import com.boox.day.ui.SnackState
import com.boox.day.ui.noRippleClickable
import com.boox.day.ui.theme.EInkTheme
import com.boox.day.viewmodel.EventsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.hypot

/**
 * Unified top header bar for BSPlan (`Month` and `Day` views).
 * Consolidates both headers into a single composable call so `MainScreen`'s root lambda stays
 * well below the 64 KB JVM / 16,383 DEX instruction limit.
 */
@Composable
fun UnifiedPlannerHeaderBar(
    year: Int,
    monthZeroBased: Int,
    selectedDate: String,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onPreviousDay: () -> Unit,
    onNextDay: () -> Unit,
    onTodayClick: () -> Unit,
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
    val addEvent = { com.boox.day.ui.dialogs.QuickWriteUi.openForEvent(selectedDate) }
    val addTask = { com.boox.day.ui.dialogs.QuickWriteUi.openForTask(selectedDate) }

    if (PlannerNavigationState.mode == PlannerViewMode.Month) {
        MonthPlannerHeaderBar(
            year = year,
            monthZeroBased = monthZeroBased,
            onPreviousMonth = onPreviousMonth,
            onNextMonth = onNextMonth,
            onTodayClick = onTodayClick,
            onAddEventClick = addEvent,
            onAddTaskClick = addTask,
            onPreferences = onPreferences,
            onCalendarSettings = onCalendarSettings,
            onCalendarAccounts = onCalendarAccounts,
            onAiSettings = onAiSettings,
            onVoiceSettings = onVoiceSettings,
            onDataBackup = onDataBackup,
            onBooxNotesSettings = onBooxNotesSettings,
            onHelp = onHelp,
            onAbout = onAbout,
            modifier = modifier
        )
    } else {
        DayPlannerHeaderBar(
            selectedDate = selectedDate,
            onPreviousDay = onPreviousDay,
            onNextDay = onNextDay,
            onTodayClick = onTodayClick,
            onAddEventClick = addEvent,
            onAddTaskClick = addTask,
            onPreferences = onPreferences,
            onCalendarSettings = onCalendarSettings,
            onCalendarAccounts = onCalendarAccounts,
            onAiSettings = onAiSettings,
            onVoiceSettings = onVoiceSettings,
            onDataBackup = onDataBackup,
            onBooxNotesSettings = onBooxNotesSettings,
            onHelp = onHelp,
            onAbout = onAbout,
            modifier = modifier
        )
    }
}

/**
 * Top header bar for the Full-Page Day View (`< TUESDAY, JAN 5, 2027 >` + `[+ Event]` `[+ Task]` `[⚙]`).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun DayPlannerHeaderBar(
    selectedDate: String,
    onPreviousDay: () -> Unit,
    onNextDay: () -> Unit,
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
    if (PlannerNavigationState.mode != PlannerViewMode.Day) return

    var showSettingsMenu by remember { mutableStateOf(false) }
    val formattedDate = remember(selectedDate) {
        val parsed = runCatching { LocalDate.parse(selectedDate) }.getOrDefault(LocalDate.now())
        val formatter = DateTimeFormatter.ofPattern("EEEE, MMM d, yyyy", Locale.getDefault())
        parsed.format(formatter).uppercase(Locale.getDefault())
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
            // Left: < DAY_OF_WEEK, MONTH DAY, YEAR >
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(EInkTheme.Global.touchTarget)
                        .noRippleClickable {
                            DrawCanvas.forceUnfreeze()
                            onPreviousDay()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = "Previous Day",
                        tint = EInkTheme.Colors.primary,
                        modifier = Modifier.size(28.dp)
                    )
                }

                Text(
                    text = formattedDate,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = EInkTheme.Colors.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
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
                            onNextDay()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "Next Day",
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

/**
 * Synchronizes [DayPlannerState.currentDayModel] with the selected date's Google Calendar events
 * and Google Tasks, and triggers an E-Ink canvas redraw whenever the Day View model changes.
 */
@Composable
fun DayPlannerEffects(
    selectedDate: String,
    currentTasks: List<Task>,
    eventsViewModel: EventsViewModel
) {
    val eventsForDay by remember(selectedDate, eventsViewModel) {
        eventsViewModel.getEventsForDate(selectedDate)
    }.collectAsState(initial = emptyList())

    LaunchedEffect(selectedDate, eventsForDay, currentTasks, PlannerNavigationState.mode) {
        val model = DayPlannerState.buildModel(
            dateStr = selectedDate,
            events = eventsForDay,
            tasks = currentTasks
        )
        DayPlannerState.currentDayModel = model
        if (PlannerNavigationState.mode == PlannerViewMode.Day) {
            DrawCanvas.forceUpdate.emit(null)
        }
    }
}

/**
 * Transparent touch overlay over the Full-Page Day View canvas:
 * - Ignores [PointerType.Stylus] and [PointerType.Eraser] so the user can write with zero lag
 *   anywhere over the Day Page (including over the Schedule, Tasks, and Tuesday/Notes sections).
 * - Handles finger taps ([PointerType.Touch]) in the Top 25% Dashboard:
 *   - Left column (`TODAY'S SCHEDULE`): opens the tapped event's management popup ([MainScreenRequest.ShowEvent]).
 *   - Right column (`GOOGLE TASKS`): tapping a checkbox toggles task completion; tapping the task title
 *     or `[>]` opens the Day Task Management Popup.
 */
@Composable
fun DayPlannerTouchOverlay(
    dayModel: DayPlannerModel?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentModelState = rememberUpdatedState(dayModel)
    val taskRepository = remember { TaskRepository(context.applicationContext) }
    var managingTaskRow by remember { mutableStateOf<DayTaskRow?>(null) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // Let stylus and tail eraser pass directly to Onyx TouchHelper / DrawCanvas
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
                    val dashboardBottomY = heightPx * DayPlannerCanvasRenderer.DASHBOARD_HEIGHT_FRACTION
                    val rowsTopY = DayPlannerCanvasRenderer.SECTION_HEADER_HEIGHT_PX

                    // Only intercept finger taps inside the Top 25% Dashboard rows
                    if (upPos.y < rowsTopY || upPos.y > dashboardBottomY || widthPx <= 0f) {
                        return@awaitEachGesture
                    }

                    val rowsAreaHeight = (dashboardBottomY - rowsTopY - 4f).coerceAtLeast(50f)
                    val rowHeight = rowsAreaHeight / DayPlannerCanvasRenderer.DASHBOARD_MAX_ROWS
                    val rowIndex = ((upPos.y - rowsTopY) / rowHeight).toInt()
                        .coerceIn(0, DayPlannerCanvasRenderer.DASHBOARD_MAX_ROWS - 1)

                    val midX = widthPx * 0.5f
                    val model = currentModelState.value ?: return@awaitEachGesture

                    if (upPos.x < midX) {
                        // Left column: TODAY'S SCHEDULE
                        val scheduleRow = model.scheduleRows.getOrNull(rowIndex) ?: return@awaitEachGesture
                        DrawCanvas.forceUnfreeze()
                        MainScreenRequests.request(MainScreenRequest.ShowEvent(scheduleRow.event))
                    } else {
                        // Right column: GOOGLE TASKS
                        val taskRow = model.taskRows.getOrNull(rowIndex) ?: return@awaitEachGesture
                        DrawCanvas.forceUnfreeze()
                        val checkboxZoneRightX = midX + 64f
                        if (upPos.x <= checkboxZoneRightX) {
                            // Toggle task completion directly
                            val targetTask = taskRow.task
                            val nextCompleted = !targetTask.isCompleted
                            scope.launch(Dispatchers.IO) {
                                taskRepository.setCompleted(targetTask.id, nextCompleted)
                                MainScreenRequests.request(MainScreenRequest.ReloadTasks)
                            }
                        } else {
                            // Open Task Management Popup
                            managingTaskRow = taskRow
                        }
                    }
                }
            }
    )

    managingTaskRow?.let { taskRow ->
        DayTaskManagePopup(
            taskRow = taskRow,
            taskRepository = taskRepository,
            onDismiss = { managingTaskRow = null }
        )
    }
}

/**
 * E-Ink Task Management Popup opened when tapping a task row (`[>]`) in the Full-Page Day View.
 * Allows editing task text, toggling completion, setting priority, moving to tomorrow/next week, or deleting.
 */
@Composable
private fun DayTaskManagePopup(
    taskRow: DayTaskRow,
    taskRepository: TaskRepository,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val task = taskRow.task
    var editedText by remember(task.id) { mutableStateOf(task.typedText ?: taskRow.title) }
    var selectedPriority by remember(task.id) { mutableStateOf(task.priority) }
    var isCompleted by remember(task.id) { mutableStateOf(task.isCompleted) }

    // Pause raw drawing while modal popup is open
    androidx.compose.runtime.DisposableEffect(Unit) {
        DrawCanvas.isModalOverlayOpen.value = true
        onDispose {
            DrawCanvas.isModalOverlayOpen.value = false
        }
    }

    Popup(
        popupPositionProvider = ScreenCentre,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true, dismissOnBackPress = true, dismissOnClickOutside = true)
    ) {
        Column(
            modifier = Modifier
                .width(460.dp)
                .background(EInkTheme.Colors.background, RoundedCornerShape(EInkTheme.Global.cornerRadius))
                .border(2.dp, EInkTheme.Colors.borderStrong, RoundedCornerShape(EInkTheme.Global.cornerRadius))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "MANAGE TASK",
                fontSize = 18.sp,
                fontWeight = FontWeight.ExtraBold,
                color = EInkTheme.Colors.primary
            )

            // Editable task title
            BasicTextField(
                value = editedText,
                onValueChange = { editedText = it.replace("\n", "") },
                singleLine = true,
                textStyle = TextStyle(
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    color = EInkTheme.Colors.primary
                ),
                cursorBrush = SolidColor(EInkTheme.Colors.primary),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.5.dp, EInkTheme.Colors.borderStrong, RoundedCornerShape(4.dp))
                    .background(Color.White, RoundedCornerShape(4.dp))
                    .padding(12.dp)
            )

            // Status & Priority row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Complete toggle button
                Box(
                    modifier = Modifier
                        .height(42.dp)
                        .border(1.5.dp, EInkTheme.Colors.borderStrong, RoundedCornerShape(4.dp))
                        .background(
                            if (isCompleted) EInkTheme.Colors.primary else Color.White,
                            RoundedCornerShape(4.dp)
                        )
                        .noRippleClickable { isCompleted = !isCompleted }
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (isCompleted) "✓ Completed" else "Mark Complete",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isCompleted) Color.White else EInkTheme.Colors.primary
                    )
                }

                // Priority chips: None, A, B, C
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("" to "None", "A" to "A", "B" to "B", "C" to "C").forEach { (prio, label) ->
                        val active = selectedPriority == prio
                        Box(
                            modifier = Modifier
                                .height(42.dp)
                                .border(1.5.dp, EInkTheme.Colors.borderStrong, RoundedCornerShape(4.dp))
                                .background(
                                    if (active) EInkTheme.Colors.primary else Color.White,
                                    RoundedCornerShape(4.dp)
                                )
                                .noRippleClickable { selectedPriority = prio }
                                .padding(horizontal = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = label,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (active) Color.White else EInkTheme.Colors.primary
                            )
                        }
                    }
                }
            }

            // Quick Move Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val parsedDay = remember(task.dayDate) {
                    runCatching { LocalDate.parse(task.dayDate) }.getOrDefault(LocalDate.now())
                }
                val tomorrowDate = remember(parsedDay) { parsedDay.plusDays(1).toString() }
                val nextWeekDate = remember(parsedDay) { parsedDay.plusDays(7).toString() }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(42.dp)
                        .border(1.dp, EInkTheme.Colors.borderStrong, RoundedCornerShape(4.dp))
                        .noRippleClickable {
                            scope.launch(Dispatchers.IO) {
                                val canvasH = GlobalAppSettings.tasksCanvasHeight.takeIf { it > 0f } ?: 1200f
                                taskRepository.moveTaskWithStrokes(task, tomorrowDate, 0, canvasH, context)
                                MainScreenRequests.request(MainScreenRequest.ReloadTasks)
                                SnackState.globalSnackFlow.tryEmit(
                                    SnackConf(text = "Moved task to tomorrow ($tomorrowDate)", duration = SnackConf.DURATION_QUICK)
                                )
                                withContext(Dispatchers.Main) { onDismiss() }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text("Move → Tomorrow", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = EInkTheme.Colors.primary)
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(42.dp)
                        .border(1.dp, EInkTheme.Colors.borderStrong, RoundedCornerShape(4.dp))
                        .noRippleClickable {
                            scope.launch(Dispatchers.IO) {
                                val canvasH = GlobalAppSettings.tasksCanvasHeight.takeIf { it > 0f } ?: 1200f
                                taskRepository.moveTaskWithStrokes(task, nextWeekDate, 0, canvasH, context)
                                MainScreenRequests.request(MainScreenRequest.ReloadTasks)
                                SnackState.globalSnackFlow.tryEmit(
                                    SnackConf(text = "Moved task to +7 days ($nextWeekDate)", duration = SnackConf.DURATION_QUICK)
                                )
                                withContext(Dispatchers.Main) { onDismiss() }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text("Move → Next Week", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = EInkTheme.Colors.primary)
                }
            }

            // Footer: Delete | Cancel | Save
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .height(44.dp)
                        .border(1.5.dp, EInkTheme.Colors.borderStrong, RoundedCornerShape(4.dp))
                        .noRippleClickable {
                            scope.launch(Dispatchers.IO) {
                                val canvasH = GlobalAppSettings.tasksCanvasHeight.takeIf { it > 0f } ?: 1200f
                                taskRepository.clearTaskSlot(task, canvasH)
                                MainScreenRequests.request(MainScreenRequest.ReloadTasks)
                                withContext(Dispatchers.Main) { onDismiss() }
                            }
                        }
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Delete", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = EInkTheme.Colors.primary)
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

                    Box(
                        modifier = Modifier
                            .height(44.dp)
                            .background(EInkTheme.Colors.primary, RoundedCornerShape(4.dp))
                            .noRippleClickable {
                                val cleanTitle = editedText.trim()
                                scope.launch(Dispatchers.IO) {
                                    val canvasH = GlobalAppSettings.tasksCanvasHeight.takeIf { it > 0f } ?: 1200f
                                    if (cleanTitle.isNotEmpty()) {
                                        taskRepository.setTypedText(task.id, cleanTitle)
                                    }
                                    taskRepository.setCompleted(task.id, isCompleted)
                                    taskRepository.setPriority(task.id, selectedPriority, canvasH)
                                    MainScreenRequests.request(MainScreenRequest.ReloadTasks)
                                    withContext(Dispatchers.Main) { onDismiss() }
                                }
                            }
                            .padding(horizontal = 22.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Save", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
            }
        }
    }
}
