package com.filestech.agenda_tech.domain.repository

import com.filestech.agenda_tech.domain.model.Calendar
import com.filestech.agenda_tech.domain.model.Event

/**
 * The two ends of the backup path, kept out of the ordinary repositories: reading the whole agenda
 * at once, and replacing it wholesale.
 */
interface BackupRepository {

    /** Every reminder in the agenda, as (event id → minutes-before), for the export snapshot. */
    suspend fun reminderMinutesByEventId(): Map<Long, List<Int>>

    /**
     * Wipes the agenda and rewrites it from a backup, **atomically**: either the whole file lands or
     * nothing changes. A restore that half-succeeds would leave the user with neither their old
     * agenda nor their new one — the one outcome a backup exists to prevent.
     *
     * Rows keep the ids carried by the file, which preserves the calendar/parent/event links.
     */
    suspend fun replaceAll(
        calendars: List<Calendar>,
        events: List<Event>,
        remindersByEventId: Map<Long, List<Int>>,
    )
}
