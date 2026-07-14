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
)
