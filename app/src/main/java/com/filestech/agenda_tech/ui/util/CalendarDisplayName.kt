package com.filestech.agenda_tech.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.filestech.agenda_tech.R
import com.filestech.agenda_tech.domain.model.Calendar

/**
 * The name to SHOW for a calendar: the first-run calendar in the current language, any other
 * calendar under the name it was given.
 *
 * This is the only place the UI may read [Calendar.name] for display - six screens show a calendar
 * name, and a single one reading the raw field would show "Perso" on a German screen.
 * `CalendarDisplayNameIsTheOnlySeamTest` enforces it. Resolved with [stringResource], so it follows a
 * language change on the spot, like every other label.
 */
@Composable
fun Calendar.displayName(): String =
    if (hasSeededName) stringResource(R.string.default_calendar_name) else name
