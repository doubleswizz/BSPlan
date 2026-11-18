package com.bsplan.app.meetings

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.bsplan.app.actionitems.MemoPageText
import com.bsplan.app.data.db.AppDatabase
import com.bsplan.app.data.db.DayPage
import com.bsplan.app.data.db.Page
import com.bsplan.app.data.db.TextAnnotation
import com.bsplan.app.sync.EventMemoService
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/** Notes follow their event cleanly across local/sync updates. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MeetingPagesTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val day = "2026-11-03"
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = AppDatabase.getDatabase(context)
    }

    @After
    fun tearDown() {
        AppDatabase.closeDatabase()
    }

    private fun box(pageId: String, text: String, source: String = "manual") = TextAnnotation(
        id = UUID.randomUUID().toString(), content = text, x = 0f, y = 0f, width = 500f, height = 50f, fontSize = 30f, fontWeight = 400,
        textColor = 0, backgroundColor = 0, textAlign = "left", padding = 0f, pageId = pageId, sourceType = source
    )

    private fun meeting(eventId: String): Pair<String, String> = runBlocking {
        val notes = Page(linkedEventId = eventId).also { db.pageDao().create(it) }
        val recap = Page(linkedEventId = eventId).also { db.pageDao().create(it) }
        db.dayPageDao().insert(DayPage(date = day, pageIds = listOf(notes.id, recap.id), openPageId = notes.id))
        db.textAnnotationDao().insert(box(notes.id, "Budget agreed"))
        db.textAnnotationDao().insert(box(recap.id, "Recap · Team sync", MeetingPages.RECAP_SOURCE))
        notes.id to recap.id
    }

    @Test
    fun aSavedRecapIsNeverReadAsNotes() = runBlocking {
        val (notes, recap) = meeting("e1")
        val pages = MeetingPages.of(context, "e1")!!
        assertEquals(listOf(notes), pages.notes)
        assertEquals(listOf(recap), pages.recap)
        assertEquals(emptyList<String>(), MemoPageText.pageLines(context, null, recap))
    }

    @Test
    fun theFingerprintChangesWithTheNotes() = runBlocking {
        val (notes, _) = meeting("e2")
        val before = MeetingPages.fingerprint(context, listOf(notes))
        assertEquals(before, MeetingPages.fingerprint(context, listOf(notes)))
        db.textAnnotationDao().insert(box(notes, "One more line"))
        assertNotEquals(before, MeetingPages.fingerprint(context, listOf(notes)))
    }

    @Test
    fun aPushedEventKeepsItsNotes() = runBlocking {
        meeting("local-1")
        val service = EventMemoService(context)
        assertNull("the service's id finds nothing before", service.getLinkedMemo("cloud-1"))
        db.pageDao().relinkEvent("local-1", "cloud-1")
        assertEquals(2, service.getLinkedMemo("cloud-1")?.pageIds?.size)
    }

    @Test
    fun longTextSplitsAtLineEnds() {
        val pages = MeetingRecapSaver.pagesOf("one\ntwo\nthree\nfour") { it.lines().size <= 2 }
        assertEquals(listOf("one\ntwo", "three\nfour"), pages)
    }
}
