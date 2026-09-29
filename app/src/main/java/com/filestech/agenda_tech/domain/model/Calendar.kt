package com.filestech.agenda_tech.domain.model

/**
 * A local calendar — a named, coloured container that events belong to. An agenda holds one or
 * more calendars (e.g. "Perso", "Travail"), each independently toggled visible in the views.
 *
 * `id == 0L` denotes an unsaved calendar (Room autogenerates the real id on insert).
 */
data class Calendar(
    val id: Long = 0L,
    val name: String,
    val color: CalendarColor = CalendarColor.DEFAULT,
    val isVisible: Boolean = true,
    /** The default calendar new events land in when the user doesn't pick one. Exactly one should be true. */
    val isDefault: Boolean = false,
    /**
     * Stable link to the external source this calendar was imported from (e.g. `"device:6"` for the
     * device Calendar Provider). Null for user-created calendars. Lets a re-import reuse the same
     * calendar instead of creating a duplicate.
     */
    val sourceId: String? = null,
) {
    /**
     * True for the calendar the app created on first run, as long as it still carries the name it was
     * created with. That name was written in the language of that first run and never changes, so a
     * user who installed the app in French and later switched it to German would keep reading "Perso"
     * forever. The UI shows the current language's name instead (`ui/util/CalendarDisplayName.kt`).
     *
     * An imported calendar is never one: a Google calendar called "Personal" keeps its own name.
     *
     * Known limit, accepted (same as Notes Tech's inbox): renaming a calendar to exactly one of these
     * names makes it read as the default one again. Telling the two apart would need a stored
     * "renamed" flag and a schema migration, for a case this rare.
     */
    val hasSeededName: Boolean
        get() = sourceId == null && name in SEEDED_NAMES

    companion object {
        /**
         * Every name the first-run calendar has been created with: `default_calendar_name` in each
         * shipped language (Spanish shares "Personal" with English). It has only ever come from that
         * resource, never from a literal (checked in the history back to 5a228b7, 2026-07-14).
         * `SeededCalendarNamesTest` fails when a language is added or renamed without this set.
         */
        val SEEDED_NAMES: Set<String> = setOf("Personal", "Perso", "Persönlich", "Personale")
    }
}
