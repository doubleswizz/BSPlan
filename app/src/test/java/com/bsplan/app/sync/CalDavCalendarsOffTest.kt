package com.bsplan.app.sync

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bsplan.app.data.db.APP_DATABASE_FILE_NAME
import com.bsplan.app.data.db.AppDatabase
import com.bsplan.app.data.db.CalDavAccount
import com.bsplan.app.data.db.CalendarEvent
import com.bsplan.app.data.db.CalendarEventRepository
import com.bsplan.app.data.db.ConnectedAccountEntity
import com.bsplan.app.data.getDbDir
import com.bsplan.app.sync.outlook.OutlookAuthManager
import com.bsplan.app.sync.outlook.OutlookCalendarService
import com.bsplan.app.sync.provider.GoogleCalendarProvider
import com.bsplan.app.sync.provider.OutlookCalendarProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate

/**
 * Owner decision 78: a CalDAV account switched to "Calendars off" shows none of its events, and a
 * sync neither fetches them back nor asks to sign in for them. The app's database (the manager
 * reads it), no server: a fetch would fail.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CalDavCalendarsOffTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() = deleteDatabaseFiles()

    @After
    fun tearDown() {
        AppDatabase.closeDatabase()
        deleteDatabaseFiles()
    }

    @Test
    fun anAccountWithCalendarsOffHasNoEventsAndSyncLeavesItAlone(): Unit = runBlocking<Unit> {
        val db = AppDatabase.getDatabase(context)
        db.connectedAccountDao().upsert(
            ConnectedAccountEntity(id = "acc-1", providerId = "CALDAV", accountType = "CALDAV_OTHER", email = "me",
                displayName = null, isActive = true, createdAt = 1, serverUrl = "https://dav.example.com/")
        )
        db.calDavDao().upsertAccount(CalDavAccount("acc-1", "/p/", "/h/", "me", taskListCount = 1))
        db.calendarEventDao().upsertAll(listOf(
            CalendarEvent(id = "caldav:acc-1:e1", providerId = "CALDAV", accountId = "acc-1", calendarId = "/h/cal/", calendarName = "Calendar",
                title = "Dentist", dayId = "2026-11-05", endDayId = "2026-11-05", startTime = 1, endTime = 2)
        ))
        val manager = manager()

        manager.setCalDavCalendarsOn("acc-1", on = false)

        assertTrue("its events go", db.calendarEventDao().getByAccountId("acc-1").isEmpty())
        val result = manager.syncAccount("acc-1", LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30))
        assertTrue("nothing fetched, no sign-in asked: $result", result is SyncResult.Success)
        assertEquals(0, db.calendarEventDao().getByAccountId("acc-1").size)
    }

    private fun manager(): MultiAccountSyncManager {
        val auth = GoogleAuthManager(context)
        val events = CalendarEventRepository(context)
        val db = AppDatabase.getDatabase(context)
        val outlookAuth = OutlookAuthManager(context)
        return MultiAccountSyncManager(
            context,
            GoogleCalendarProvider(context, auth, CalendarSyncManager(context, auth, events), db.connectedAccountDao(), events),
            OutlookCalendarProvider(context, outlookAuth, OutlookCalendarService(outlookAuth), db.connectedAccountDao()),
            db.connectedAccountDao(), db.calendarSelectionDao(), events
        )
    }

    private fun deleteDatabaseFiles() {
        val file = File(getDbDir(context), APP_DATABASE_FILE_NAME)
        listOf("", "-wal", "-shm", "-journal").forEach { File(file.path + it).delete() }
    }
}
