package com.bsplan.app.meetings

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.bsplan.app.ai.MemoPageContent
import com.bsplan.app.ai.TaskEventMatcher
import com.bsplan.app.booxnotes.BooxNotesIntegration
import com.bsplan.app.data.db.AppDatabase
import com.bsplan.app.data.db.BooxNoteIndex
import com.bsplan.app.data.db.BooxNoteRepository
import com.bsplan.app.data.db.CalendarEvent
import com.bsplan.app.io.ExportText
import com.bsplan.app.search.SearchText
import com.bsplan.app.sync.EventMemoService
import com.bsplan.app.ui.ScreenCentre
import com.bsplan.app.ui.theme.InkaTheme
import com.bsplan.app.ui.theme.LayoutProfiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** An earlier time of this meeting: its day and first page of notes. */
data class EarlierMeeting(val date: String, val pageId: String?, val eventId: String?)

/** Finds earlier times of a meeting, without AI: events have no series id, so same calendar and same name. */
object EarlierMeetings {
    private const val LIMIT = 3
    private const val ID_CHUNK = 500

    /** The part of a CalDAV occurrence's id before its day (`file#day`). */
    private fun seriesOf(id: String) = id.substringBefore('#').takeIf { '#' in id }

    fun sameMeeting(event: CalendarEvent, other: CalendarEvent): Boolean {
        if (other.id == event.id || other.startTime >= event.startTime) return false
        val series = seriesOf(event.id)
        if (series != null && series == seriesOf(other.id)) return true
        return other.calendarId == event.calendarId && SearchText.fold(other.title).trim() == SearchText.fold(event.title).trim()
    }

    /** Newest first. [noted] are events with notes; [headers] are pages whose meeting header carries this title. */
    fun pick(event: CalendarEvent, noted: List<CalendarEvent>, headers: List<Pair<String, String>>): List<EarlierMeeting> {
        val fromEvents = noted.filter { sameMeeting(event, it) }.sortedByDescending { it.startTime }
            .map { EarlierMeeting(it.dayId, null, it.id) }
        // The event may be gone from sync, while its notes stay
        val fromHeaders = headers.filter { (date, _) -> date < event.dayId && fromEvents.none { it.date == date } }
            .sortedByDescending { it.first }
            .map { (date, pageId) -> EarlierMeeting(date, pageId, null) }
        return (fromEvents + fromHeaders).sortedByDescending { it.date }.take(LIMIT)
    }

    suspend fun find(context: Context, event: CalendarEvent): List<EarlierMeeting> = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val db = AppDatabase.getDatabase(app)
        val noted = EventMemoService(app).getLinkedEventIds().chunked(ID_CHUNK).flatMap { db.calendarEventDao().getByIds(it) }
        val alive = noted.mapTo(HashSet()) { it.id }
        val wanted = SearchText.fold(event.title).trim().removeSuffix("…")
        val headers = db.textAnnotationDao().getBySource(EventMemoService.HEADER_SOURCE)
            .filter { SearchText.fold(it.content).trim().removeSuffix("…").let { title -> title.isNotBlank() && (title == wanted || (it.content.endsWith("…") && wanted.startsWith(title))) } }
            // A page whose event still exists was judged above, calendar and all
            .filter { box -> db.pageDao().getById(box.pageId)?.linkedEventId !in alive }
            .mapNotNull { box -> db.dayPageDao().getDateByPageId(box.pageId)?.let { it to box.pageId } }
        pick(event, noted, headers)
    }
}

/** What Prepare shows, read on the tablet only. */
object MeetingPrepLoader {
    data class Prep(
        val earlier: EarlierMeeting?,
        val lastPoints: List<String>,
        val lastPicture: Bitmap?,
        val tasks: List<String>,
        val notes: List<BooxNoteIndex>,
        val people: String?
    )

    private const val TASK_LIMIT = 5
    private const val PICTURE_WIDTH = 700

    /** "With Anna, Tom and 3 others", from the addresses; no address is shown. */
    fun people(attendees: String?): String? {
        val names = attendees.orEmpty().split(',', '"', '[', ']', ' ').map { it.trim().removePrefix("mailto:") }.filter { '@' in it }
            .map { it.substringBefore('@').substringBefore('.').replaceFirstChar { c -> c.titlecase(Locale.getDefault()) } }
        return when {
            names.isEmpty() -> null
            names.size == 1 -> "With ${names[0]}"
            names.size == 2 -> "With ${names[0]} and ${names[1]}"
            else -> "With ${names[0]}, ${names[1]} and ${names.size - 2} ${if (names.size == 3) "other" else "others"}"
        }
    }

    suspend fun load(context: Context, event: CalendarEvent): Prep = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val earlier = EarlierMeetings.find(app, event).firstOrNull()
        val points = earlier?.let { lastPoints(app, it) }.orEmpty()
        val db = AppDatabase.getDatabase(app)
        val day = LocalDate.parse(event.dayId)
        val open = db.taskDao().getIncompleteTasksWithContentForDateRange(day.minusDays(14).toString(), day.toString())
        val tasks = runCatching {
            TaskEventMatcher(app).findRelationships(open, listOf(event.toLocalEvent())).map { it.taskText }.distinct().take(TASK_LIMIT)
        }.getOrDefault(emptyList())
        val notes = runCatching { BooxNoteRepository(app).getNotesLinkedToEvent(event.id) }.getOrDefault(emptyList())
        val picture = if (earlier != null && points.isEmpty()) pageTop(app, earlier) else null
        Prep(earlier, points, picture, tasks, notes, people(event.attendees))
    }

    private suspend fun firstPage(context: Context, earlier: EarlierMeeting): String? =
        earlier.pageId ?: earlier.eventId?.let { MeetingPages.of(context, it)?.notes?.firstOrNull() }

    /** Notes nobody has read yet still show what they are: the top of the page, small. */
    private suspend fun pageTop(context: Context, earlier: EarlierMeeting): Bitmap? {
        val pageId = firstPage(context, earlier) ?: return null
        val page = runCatching { MemoPageContent.renderWholePage(context, pageId) }.getOrNull() ?: return null
        val top = Bitmap.createBitmap(page, 0, 0, page.width, minOf(page.height, page.width / 3))
        val scale = minOf(1f, PICTURE_WIDTH.toFloat() / top.width)
        return Bitmap.createScaledBitmap(top, maxOf(1, (top.width * scale).toInt()), maxOf(1, (top.height * scale).toInt()), true)
    }

    private suspend fun lastPoints(context: Context, earlier: EarlierMeeting): List<String> {
        val pageId = firstPage(context, earlier) ?: return emptyList()
        return ExportText.savedText(context, earlier.date, pageId)?.take(3).orEmpty()
    }
}

object MeetingPrepUi {
    var event by mutableStateOf<CalendarEvent?>(null)
        private set

    val isOpen: Boolean get() = event != null

    private var onLeaving: () -> Unit = {}

    /** [leaving] closes what opened this when the user goes on to a page or a note. */
    fun open(meeting: CalendarEvent, leaving: () -> Unit = {}) {
        onLeaving = leaving
        event = meeting
    }

    fun close() {
        event = null
    }

    fun leave() {
        onLeaving()
        close()
    }
}

@Composable
fun MeetingPrepHost() {
    val event = MeetingPrepUi.event ?: return
    InkaTheme { key(event.id) { PrepFlow(event) } }
}

@Composable
private fun PrepFlow(event: CalendarEvent) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var prep by remember { mutableStateOf<MeetingPrepLoader.Prep?>(null) }
    var page by remember { mutableIntStateOf(1) }

    LaunchedEffect(Unit) { prep = MeetingPrepLoader.load(context, event) }
    val loaded = prep ?: return

    val narrow = LayoutProfiles.isNarrowScreen
    val day = runCatching { DateTimeFormatter.ofPattern("MMM d", Locale.getDefault()).format(LocalDate.parse(loaded.earlier?.date)) }.getOrNull()
    val split = narrow && loaded.tasks.size + loaded.notes.size >= 3 && loaded.earlier != null
    val pages = if (split) 2 else 1
    val first = !split || page == 1
    val width = Modifier.width((LayoutProfiles.screenWidthDp * if (narrow) 0.94f else 0.85f).dp.coerceAtMost(700.dp))
    val start = Instant.ofEpochMilli(event.startTime).atZone(ZoneId.systemDefault())
    val line = meetingLine(event).ifBlank { start.toString() }

    fun takeNotes() {
        scope.launch {
            val memo = MeetingNotes.start(context, event)
            MeetingPrepUi.leave()
            if (memo != null) MeetingNotesUi.showPage(memo.date, memo.pageIds.first())
        }
    }

    fun openLast() {
        val earlier = loaded.earlier ?: return
        scope.launch {
            val pageId = earlier.pageId ?: earlier.eventId?.let { MeetingPages.of(context, it)?.notes?.firstOrNull() } ?: return@launch
            MeetingPrepUi.leave()
            MeetingNotesUi.showPage(earlier.date, pageId)
        }
    }

    Popup(popupPositionProvider = ScreenCentre, onDismissRequest = MeetingPrepUi::close, properties = PopupProperties(focusable = true)) {
        MeetingPrepCard(
            data = PrepUi(
                title = event.title,
                line = line,
                people = loaded.people,
                lastTime = day.takeIf { first },
                lastPoints = if (first) loaded.lastPoints else emptyList(),
                lastPicture = loaded.lastPicture?.takeIf { first }?.asImageBitmap(),
                tasks = if (!split || !first) loaded.tasks else emptyList(),
                notes = if (!split || !first) loaded.notes.map { "${it.title} (BOOX note)" } else emptyList()
            ),
            page = page.coerceIn(1, pages),
            pages = pages,
            modifier = width,
            onClose = MeetingPrepUi::close,
            onOpenLast = ::openLast,
            onOpenNote = { index ->
                loaded.notes.getOrNull(index)?.let { note ->
                    MeetingPrepUi.leave()
                    BooxNotesIntegration(context).openNote(note.uniqueId, note.nameInBoox)
                }
            },
            onPage = { page = it.coerceIn(1, pages) },
            onTakeNotes = ::takeNotes
        )
    }
}
