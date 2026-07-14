package com.filestech.agenda_tech.data.device

import android.content.Context
import android.database.Cursor
import android.provider.CalendarContract
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads calendars and events already stored on the device by the system Calendar Provider
 * (`CalendarContract`) — Google, Exchange, local accounts alike. **Read-only and 100 % local**: no
 * network, no account access; Agenda Tech only sees what the OS has already synced onto the phone.
 *
 * Requires the `READ_CALENDAR` runtime permission (the caller gates on it). Recurring masters,
 * standalone events **and moved single occurrences** (`ORIGINAL_ID` set) are returned — only the
 * provider's deleted tombstones are skipped, so nothing the user can see in their calendar is lost.
 * The pure [DeviceEventMapper] turns each row into a domain event.
 */
@Singleton
class DeviceCalendarReader @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** Lists the device calendars available to import from. Empty on permission/query failure. */
    fun listCalendars(): List<DeviceCalendar> {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.CALENDAR_COLOR,
        )
        return runCatching {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                projection,
                null,
                null,
                "${CalendarContract.Calendars.CALENDAR_DISPLAY_NAME} ASC",
            )?.use { c ->
                buildList {
                    while (c.moveToNext()) {
                        add(
                            DeviceCalendar(
                                id = c.getLong(0),
                                displayName = c.getStringOrNull(1) ?: c.getStringOrNull(2) ?: "—",
                                accountName = c.getStringOrNull(2).orEmpty(),
                                colorArgb = if (c.isNull(3)) null else c.getInt(3),
                            ),
                        )
                    }
                }
            }.orEmpty()
        }.getOrElse {
            Timber.w(it, "DeviceCalendarReader: listCalendars failed")
            emptyList()
        }
    }

    /**
     * Reads up to [MAX_EVENTS] master / standalone events of a device calendar. The heavy Cursor work
     * runs on whatever dispatcher the caller provides (the use case wraps it in IO).
     */
    fun readEvents(calendarId: Long): List<DeviceEvent> {
        val projection = arrayOf(
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DESCRIPTION,
            CalendarContract.Events.EVENT_LOCATION,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.DURATION,
            CalendarContract.Events.ALL_DAY,
            CalendarContract.Events.EVENT_TIMEZONE,
            CalendarContract.Events.RRULE,
            CalendarContract.Events.EXDATE,
            // _SYNC_ID is unique per row (unlike UID_2445, which a series shares with its exceptions)
            // and stable across syncs for account calendars; _ID is the local fallback for unsynced ones.
            CalendarContract.Events._SYNC_ID,
            CalendarContract.Events._ID,
            // Moved-occurrence linkage: ORIGINAL_ID = master row id, ORIGINAL_INSTANCE_TIME = the
            // instant of the occurrence this row replaces (folded into the master's EXDATE on import).
            CalendarContract.Events.ORIGINAL_ID,
            CalendarContract.Events.ORIGINAL_INSTANCE_TIME,
        )
        // Everything the user can see: masters, standalone AND moved occurrences (ORIGINAL_ID set).
        // Skip only deleted tombstones — `DELETED` may be NULL on some providers, so guard for it.
        val selection = "${CalendarContract.Events.CALENDAR_ID} = ? " +
            "AND (${CalendarContract.Events.DELETED} IS NULL OR ${CalendarContract.Events.DELETED} != 1) " +
            "AND ${CalendarContract.Events.DTSTART} IS NOT NULL"
        return runCatching {
            context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                projection,
                selection,
                arrayOf(calendarId.toString()),
                "${CalendarContract.Events.DTSTART} ASC",
            )?.use { c ->
                buildList {
                    while (c.moveToNext() && size < MAX_EVENTS) {
                        val dtStart = if (c.isNull(3)) continue else c.getLong(3)
                        val deviceId = c.getLong(11)
                        // Stable per-row identity for idempotent re-import: sync id, else local row id.
                        val uid = c.getStringOrNull(10) ?: "rowid:$deviceId"
                        add(
                            DeviceEvent(
                                uid = uid,
                                title = c.getStringOrNull(0),
                                description = c.getStringOrNull(1),
                                location = c.getStringOrNull(2),
                                dtStartUtcMillis = dtStart,
                                dtEndUtcMillis = if (c.isNull(4)) null else c.getLong(4),
                                durationRfc = c.getStringOrNull(5),
                                allDay = c.getInt(6) == 1,
                                eventTimeZone = c.getStringOrNull(7),
                                rrule = c.getStringOrNull(8),
                                exDate = c.getStringOrNull(9),
                                deviceId = deviceId,
                                originalId = c.getStringOrNull(12)?.toLongOrNull(),
                                originalInstanceTime = if (c.isNull(13)) null else c.getLong(13),
                            ),
                        )
                    }
                }
            }.orEmpty()
        }.getOrElse {
            Timber.w(it, "DeviceCalendarReader: readEvents(%d) failed", calendarId)
            emptyList()
        }
    }

    private fun Cursor.getStringOrNull(index: Int): String? = if (isNull(index)) null else getString(index)

    private companion object {
        // A personal agenda is well under this; the cap bounds memory against a pathological provider.
        const val MAX_EVENTS = 20_000
    }
}
