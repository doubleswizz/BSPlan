package com.bsplan.app.meetings

import android.content.Context
import com.bsplan.app.ai.MemoPageContent
import com.bsplan.app.data.db.AppDatabase
import com.bsplan.app.data.db.CalendarEvent
import com.bsplan.app.io.ExportText
import com.bsplan.app.sync.EventMemoService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A meeting's pages (Nov 2026). A saved recap sits on a page linked to the meeting, so it is told apart
 * from the notes by its text box's source: read back as notes, a recap would feed the next recap.
 */
object MeetingPages {
    const val RECAP_SOURCE = "meeting_recap"
    /** A longer meeting is read up to here, and the sheet says so. */
    const val MAX_PAGES = 12

    data class Pages(val date: String, val notes: List<String>, val recap: List<String>)

    /** [skipped]: pages past [MAX_PAGES]. */
    data class Read(val text: String, val pages: Int, val skipped: Int)

    /** The meeting a memo page belongs to, a tablet-only one too; null for any other page. */
    suspend fun eventOf(context: Context, pageId: String): CalendarEvent? = withContext(Dispatchers.IO) {
        val db = AppDatabase.getDatabase(context.applicationContext)
        val eventId = db.pageDao().getById(pageId)?.linkedEventId ?: return@withContext null
        db.calendarEventDao().getById(eventId) ?: db.localEventDao().getById(eventId)?.toCalendarEvent()
    }

    /** Null when the meeting has no pages. */
    suspend fun of(context: Context, eventId: String): Pages? {
        val memo = EventMemoService(context.applicationContext).getLinkedMemo(eventId) ?: return null
        val (recap, notes) = memo.pageIds.partition { isRecap(context, it) }
        return Pages(memo.date, notes, recap)
    }

    private suspend fun isRecap(context: Context, pageId: String): Boolean =
        MemoPageContent.load(context, pageId).let { page ->
            page.strokes.isEmpty() && page.textAnnotations.any { it.sourceType == RECAP_SOURCE }
        }

    /** Changes when the notes change, without reading them: a repeat costs nothing. */
    suspend fun fingerprint(context: Context, pageIds: List<String>): String = withContext(Dispatchers.Default) {
        val parts = mutableListOf<String>()
        for (pageId in pageIds) {
            val page = MemoPageContent.load(context, pageId)
            val ink = page.strokes.sumOf { it.id.hashCode().toLong() }
            val typed = page.textAnnotations.filter { it.sourceType != EventMemoService.HEADER_SOURCE }.joinToString { it.content }.hashCode()
            parts += "${page.strokes.size}:$ink:$typed"
        }
        parts.joinToString("|")
    }

    /**
     * The notes' words from saved readings and typed boxes.
     */
    suspend fun readText(context: Context, date: String, pageIds: List<String>): Read {
        val read = pageIds.take(MAX_PAGES)
        val parts = read.map { pageId ->
            val typed = com.bsplan.app.actionitems.MemoPageText.pageLines(context, null, pageId)
            val saved = ExportText.savedText(context, date, pageId)
            val ink = saved?.joinToString("\n").orEmpty()
            (listOf(ink) + typed).filter { it.isNotBlank() }.joinToString("\n")
        }
        return Read(parts.filter { it.isNotBlank() }.joinToString("\n\n"), read.size, pageIds.size - read.size)
    }
}
