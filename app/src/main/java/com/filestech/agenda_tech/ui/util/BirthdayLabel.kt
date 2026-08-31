package com.filestech.agenda_tech.ui.util

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.filestech.agenda_tech.R

/**
 * The title a list shows for an occurrence, with the age appended when it is a birthday.
 *
 * One implementation rather than the same two lines in the month list, the day/week timeline, the
 * all-day strip, the agenda list and the home-screen widget: five copies of a plural lookup would be
 * five chances to get French agreement wrong in one of them ("1 an" / "2 ans").
 *
 * [age] null — not a birthday, or an age that would be a lie (see
 * [com.filestech.agenda_tech.domain.birthday.BirthdayAge]) — gives the plain title back unchanged, so
 * a caller never has to ask whether it is dealing with a birthday.
 *
 * Takes [Resources] rather than being Composable-only because the Glance widget has a Context and no
 * `pluralStringResource`; [displayTitle] is the Compose-side wrapper over the same function.
 */
fun birthdayDisplayTitle(resources: Resources, title: String, age: Int?): String =
    if (age == null) title else "$title · " + resources.getQuantityString(R.plurals.birthday_age, age, age)

@Composable
fun displayTitle(title: String, age: Int?): String =
    birthdayDisplayTitle(LocalContext.current.resources, title, age)
