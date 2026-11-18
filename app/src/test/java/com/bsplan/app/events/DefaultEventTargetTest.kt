package com.bsplan.app.events

import com.bsplan.app.data.datastore.AppSettings
import com.bsplan.app.data.db.LocalCalendar
import com.bsplan.app.data.model.WritableCalendar
import com.bsplan.app.data.model.ProviderId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where a new event goes with nothing picked: the same place Add Event starts on (Nov 2026). */
class DefaultEventTargetTest {
    private fun calendar(id: String, provider: ProviderId, account: String, primary: Boolean = false, default: Boolean = false) =
        WritableCalendar(id = id, name = id, accountId = account, accountEmail = "$account@x", providerId = provider, isPrimary = primary, isDefault = default)

    private val local = listOf(LocalCalendar(id = "l1", name = "One", sortOrder = 0), LocalCalendar(id = "l2", name = "Two", sortOrder = 1, isDefault = true))
    private val hybrid = AppSettings.SyncMode.HYBRID

    private fun target(calendars: List<WritableCalendar>, google: Boolean = false, outlook: Boolean = false, caldav: Boolean = false, mode: AppSettings.SyncMode = hybrid) =
        DefaultEventTarget.of(calendars, google, outlook, caldav, mode, local)

    @Test
    fun standaloneStaysOnTheTablet() {
        val result = target(listOf(calendar("g", ProviderId.GOOGLE, "a", primary = true)), google = true, mode = AppSettings.SyncMode.STANDALONE)
        assertFalse(result.syncEnabled)
        assertNull(result.calendar)
        assertEquals("l2", result.localCalendarId)
    }

    @Test
    fun noAccountStaysOnTheTablet() {
        assertFalse(target(emptyList()).syncEnabled)
    }

    @Test
    fun googleComesBeforeOutlookAndCalDav() {
        val calendars = listOf(
            calendar("c", ProviderId.CALDAV, "c1"),
            calendar("o", ProviderId.OUTLOOK, "o1", primary = true),
            calendar("g", ProviderId.GOOGLE, "g1", primary = true)
        )
        assertEquals("g", target(calendars, google = true, outlook = true, caldav = true).calendar?.id)
        assertEquals("o", target(calendars, outlook = true, caldav = true).calendar?.id)
    }

    @Test
    fun theAccountsDefaultCalendarComesBeforeItsMainOne() {
        val calendars = listOf(
            calendar("main", ProviderId.GOOGLE, "g1", primary = true),
            calendar("chosen", ProviderId.GOOGLE, "g1", default = true)
        )
        assertEquals("chosen", target(calendars, google = true).calendar?.id)
    }

    @Test
    fun onlyTheFirstCalDavAccountsCalendarsAreLookedAt() {
        val calendars = listOf(
            calendar("first", ProviderId.CALDAV, "c1"),
            calendar("other", ProviderId.CALDAV, "c2", default = true)
        )
        assertEquals("first", target(calendars, caldav = true).calendar?.id)
    }

    @Test
    fun onlyTheFirstGoogleAndOutlookAccountsAreOffered() {
        val calendars = listOf(
            calendar("g1", ProviderId.GOOGLE, "first"),
            calendar("g2", ProviderId.GOOGLE, "second"),
            calendar("o2", ProviderId.OUTLOOK, "second"),
            calendar("c", ProviderId.CALDAV, "any")
        )
        assertEquals(listOf("g1", "c"), DefaultEventTarget.offered(calendars, "first", null).map { it.id })
    }

    @Test
    fun anAccountWithNoCalendarsLoadedYetSavesOnTheTablet() {
        val result = target(emptyList(), google = true)
        assertFalse(result.syncEnabled)
        assertTrue(result.localCalendarId == "l2")
    }

    @Test
    fun allOptionsListsOfferedCloudCalendarsAndLocalCalendars() {
        val calendars = listOf(
            calendar("g1", ProviderId.GOOGLE, "g1", primary = true),
            calendar("g2", ProviderId.GOOGLE, "g1"),
            calendar("c1", ProviderId.CALDAV, "c1")
        )
        val options = DefaultEventTarget.allOptions(calendars, hasGoogle = true, hasOutlook = false, hasCalDav = true, hybrid, local)
        assertEquals(listOf("g1 (Google)", "g2 (Google)", "c1 (CalDAV)", "This device only (One)", "This device only (Two)"), options.map { it.label })

        EventTargets.publish(
            defaultTarget = { options.first() },
            options = { options }
        )
        assertEquals("g1 (Google)", EventTargets.current().label)
        EventTargets.select(options[1])
        assertEquals("g2 (Google)", EventTargets.current().label)
        EventTargets.select(options[3])
        assertEquals("This device only (One)", EventTargets.current().label)
    }
}
