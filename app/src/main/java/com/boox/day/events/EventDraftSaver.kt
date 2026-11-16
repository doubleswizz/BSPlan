package com.boox.day.events

import com.boox.day.data.datastore.AppSettings
import com.boox.day.data.db.LocalCalendar
import com.boox.day.data.model.WritableCalendar
import com.boox.day.data.model.ProviderId
import com.boox.day.data.model.serviceName
import com.boox.day.ui.SnackConf
import com.boox.day.ui.SnackState
import com.boox.day.ui.dialogs.calendarForNewEvent
import com.boox.day.ui.dialogs.eventSaveTargets
import com.boox.day.sync.EventOperationResult
import com.boox.day.viewmodel.EventsViewModel

/** Where a new event goes when nobody picks: what Add Event starts with. */
data class EventTarget(
    val syncEnabled: Boolean,
    val calendar: WritableCalendar?,
    val serviceName: String?,
    val localCalendarId: String?,
    val localCalendarName: String? = null
) {
    val key: String
        get() = if (syncEnabled && calendar != null) {
            "cloud:${calendar.providerId.key}:${calendar.accountId}:${calendar.id}"
        } else {
            "local:${localCalendarId.orEmpty()}"
        }

    /** "Work (Google)", or "This device only · Default". */
    val label: String
        get() = calendar?.let {
            if (serviceName != null) "${it.name} ($serviceName)" else it.name
        } ?: if (!localCalendarName.isNullOrBlank()) {
            "This device only ($localCalendarName)"
        } else {
            "This device only"
        }
}

/** What MainScreen knows about accounts, for features outside it. */
object EventTargets {
    private var latest: () -> EventTarget = { EventTarget(false, null, null, null) }
    private var latestOptions: () -> List<EventTarget> = { listOf(latest()) }
    @Volatile private var preferredTargetKey: String? = null

    fun publish(target: () -> EventTarget) {
        latest = target
    }

    fun publish(defaultTarget: () -> EventTarget, options: () -> List<EventTarget>) {
        latest = defaultTarget
        latestOptions = options
    }

    fun options(): List<EventTarget> {
        val list = latestOptions().distinctBy { it.key }
        return list.ifEmpty { listOf(latest()) }
    }

    fun current(): EventTarget {
        val key = preferredTargetKey
        if (key != null) {
            options().firstOrNull { it.key == key }?.let { return it }
        }
        return latest()
    }

    fun select(target: EventTarget) {
        preferredTargetKey = target.key
    }
}

/** A new event, as Add Event hands it over. */
data class EventDraft(
    val calendarId: String,
    val title: String,
    val date: String,
    val startHour: Int,
    val startMinute: Int,
    val endHour: Int?,
    val endMinute: Int?,
    val isAllDay: Boolean,
    val location: String?,
    val description: String?,
    val attendees: String?,
    val syncEnabled: Boolean,
    val syncProviderId: ProviderId?,
    val syncAccountId: String?,
    val localCalendarId: String?
)

object DefaultEventTarget {
    /** The calendars Add Event offers: the first Google and Outlook account's, and every CalDAV account's. */
    fun offered(all: List<WritableCalendar>, firstGoogleAccountId: String?, firstOutlookAccountId: String?): List<WritableCalendar> =
        all.filter { calendar ->
            (calendar.providerId == ProviderId.GOOGLE && calendar.accountId == firstGoogleAccountId) ||
                (calendar.providerId == ProviderId.OUTLOOK && calendar.accountId == firstOutlookAccountId) ||
                calendar.providerId == ProviderId.CALDAV  // each CalDAV account is its own line
        }

    fun localCalendar(localCalendars: List<LocalCalendar>): String? =
        localCalendars.find { it.isDefault }?.id ?: localCalendars.firstOrNull()?.id

    /** What a new event in Add Event starts on, left untouched. [calendars] is [offered]. */
    fun of(
        calendars: List<WritableCalendar>,
        hasGoogle: Boolean,
        hasOutlook: Boolean,
        hasCalDav: Boolean,
        syncMode: AppSettings.SyncMode,
        localCalendars: List<LocalCalendar>
    ): EventTarget {
        val local = localCalendars.find { it.isDefault } ?: localCalendars.firstOrNull()
        val sync = syncMode == AppSettings.SyncMode.HYBRID && (hasGoogle || hasOutlook || hasCalDav)
        if (!sync) {
            return EventTarget(
                syncEnabled = false,
                calendar = null,
                serviceName = null,
                localCalendarId = local?.id,
                localCalendarName = local?.name
            )
        }
        val line = eventSaveTargets(calendars, hasGoogle, hasOutlook).firstOrNull()
        val calendar = line?.let { target ->
            calendarForNewEvent(calendars.filter { it.providerId == target.providerId && (target.accountId == null || it.accountId == target.accountId) })
        }
        return EventTarget(
            syncEnabled = calendar != null,
            calendar = calendar,
            serviceName = line?.name,
            localCalendarId = local?.id,
            localCalendarName = local?.name
        )
    }

    /** All writable cloud and local calendar targets available to choose from on Quick-Write. */
    fun allOptions(
        calendars: List<WritableCalendar>,
        hasGoogle: Boolean,
        hasOutlook: Boolean,
        hasCalDav: Boolean,
        syncMode: AppSettings.SyncMode,
        localCalendars: List<LocalCalendar>
    ): List<EventTarget> {
        val defaultLocal = localCalendars.find { it.isDefault } ?: localCalendars.firstOrNull()
        val localTargets = if (localCalendars.isNotEmpty()) {
            localCalendars.map { lc ->
                EventTarget(
                    syncEnabled = false,
                    calendar = null,
                    serviceName = null,
                    localCalendarId = lc.id,
                    localCalendarName = lc.name
                )
            }
        } else {
            listOf(EventTarget(syncEnabled = false, calendar = null, serviceName = null, localCalendarId = null))
        }

        val sync = syncMode == AppSettings.SyncMode.HYBRID && (hasGoogle || hasOutlook || hasCalDav)
        if (!sync) return localTargets.distinctBy { it.key }

        val cloudTargets = eventSaveTargets(calendars, hasGoogle, hasOutlook).flatMap { line ->
            calendars
                .filter { it.providerId == line.providerId && (line.accountId == null || it.accountId == line.accountId) }
                .map { cal ->
                    EventTarget(
                        syncEnabled = true,
                        calendar = cal,
                        serviceName = line.name.ifBlank { serviceName(cal.providerId, cal.accountType, cal.serverUrl) },
                        localCalendarId = defaultLocal?.id,
                        localCalendarName = defaultLocal?.name
                    )
                }
        }
        return (cloudTargets + localTargets).distinctBy { it.key }
    }
}

object EventDraftSaver {
    /** Saves a new event and says how it went, as Add Event always has. [onCreated] runs on success only. */
    fun create(viewModel: EventsViewModel, draft: EventDraft, successText: String? = "Event created", onFailed: (() -> Unit)? = null, onCreated: () -> Unit) {
        viewModel.createEvent(
            calendarId = draft.calendarId,
            title = draft.title,
            date = draft.date,
            startTimeHour = draft.startHour,
            startTimeMinute = draft.startMinute,
            endTimeHour = draft.endHour,
            endTimeMinute = draft.endMinute,
            isAllDay = draft.isAllDay,
            location = draft.location?.takeIf { it.isNotBlank() },
            description = draft.description?.takeIf { it.isNotBlank() },
            attendees = draft.attendees?.takeIf { it.isNotBlank() },
            syncEnabled = draft.syncEnabled,
            syncProviderId = draft.syncProviderId,
            syncAccountId = draft.syncAccountId,
            localCalendarId = draft.localCalendarId
        ) { result ->
            when (result) {
                is EventOperationResult.Success -> {
                    onCreated()
                    successText?.let { SnackState.globalSnackFlow.tryEmit(SnackConf(text = it, duration = SnackConf.DURATION_STANDARD)) }
                }
                is EventOperationResult.Error -> {
                    // Dialog stays open for retry
                    val errorMessage = viewModel.lastOperationMessage.value ?: result.message
                    SnackState.globalSnackFlow.tryEmit(SnackConf(text = errorMessage, duration = SnackConf.DURATION_ERROR))
                    onFailed?.invoke()
                }
                else -> {
                    // NoNetwork, NotSignedIn, PermissionDenied
                    val errorMessage = viewModel.lastOperationMessage.value ?: "Operation failed"
                    SnackState.globalSnackFlow.tryEmit(SnackConf(text = errorMessage, duration = SnackConf.DURATION_ERROR))
                    onFailed?.invoke()
                }
            }
        }
    }
}
