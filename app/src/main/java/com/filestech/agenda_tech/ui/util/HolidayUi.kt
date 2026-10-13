package com.filestech.agenda_tech.ui.util

import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.filestech.agenda_tech.R
import com.filestech.agenda_tech.domain.holiday.Holiday
import com.filestech.agenda_tech.domain.holiday.HolidayName

/*
 * How a public holiday looks, in every view that shows one — the month's three displays, the day's list,
 * the week, the day and the agenda: its date in red, as a paper calendar prints it, and its name in
 * grey. Not an event: nothing here can be tapped, edited or deleted. One file, so the views cannot drift
 * apart.
 */

/** The red a holiday is marked with: the theme's, which keeps its contrast in the dark theme too. */
@Composable
fun holidayColor(): Color = MaterialTheme.colorScheme.error

/** The holiday's name in the app's language; a day standing in for one says so. */
fun holidayName(resources: Resources, holiday: Holiday): String {
    val name = resources.getString(holiday.name.label)
    return if (holiday.observed) resources.getString(R.string.holiday_observed, name) else name
}

@Composable
fun holidayName(holiday: Holiday): String = holidayName(LocalResources.current, holiday)

/**
 * A holiday inside a calendar cell or an all-day strip: its name on a grey label, shaped like the events
 * beside it — [contentPadding] is theirs — but grey, since it is not one of them.
 */
@Composable
fun HolidayChip(
    holiday: Holiday,
    style: TextStyle,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
    contentPadding: PaddingValues = PaddingValues(horizontal = 3.dp),
) {
    Text(
        text = holidayName(holiday),
        style = style,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.extraSmall)
            .padding(contentPadding),
    )
}

/** A holiday in a list — the day's list, the agenda: a red flag and its name, above the day's events. */
@Composable
fun HolidayLine(holiday: Holiday, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.Flag,
            contentDescription = null,
            tint = holidayColor(),
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = holidayName(holiday),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@get:StringRes
private val HolidayName.label: Int
    get() = when (this) {
        HolidayName.NEW_YEAR -> R.string.holiday_new_year
        HolidayName.EPIPHANY -> R.string.holiday_epiphany
        HolidayName.GOOD_FRIDAY -> R.string.holiday_good_friday
        HolidayName.EASTER_SUNDAY -> R.string.holiday_easter_sunday
        HolidayName.EASTER_MONDAY -> R.string.holiday_easter_monday
        HolidayName.LABOUR_DAY -> R.string.holiday_labour_day
        HolidayName.ASCENSION -> R.string.holiday_ascension
        HolidayName.WHIT_SUNDAY -> R.string.holiday_whit_sunday
        HolidayName.WHIT_MONDAY -> R.string.holiday_whit_monday
        HolidayName.CORPUS_CHRISTI -> R.string.holiday_corpus_christi
        HolidayName.ASSUMPTION -> R.string.holiday_assumption
        HolidayName.ALL_SAINTS -> R.string.holiday_all_saints
        HolidayName.IMMACULATE_CONCEPTION -> R.string.holiday_immaculate_conception
        HolidayName.CHRISTMAS -> R.string.holiday_christmas
        HolidayName.ST_STEPHEN -> R.string.holiday_st_stephen
        HolidayName.SECOND_CHRISTMAS_DAY -> R.string.holiday_second_christmas_day
        HolidayName.BOXING_DAY -> R.string.holiday_boxing_day
        HolidayName.NATIONAL_DAY -> R.string.holiday_national_day
        HolidayName.VICTORY_1945 -> R.string.holiday_victory_1945
        HolidayName.ARMISTICE_1918 -> R.string.holiday_armistice_1918
        HolidayName.EUROPE_DAY -> R.string.holiday_europe_day
        HolidayName.GERMAN_UNITY_DAY -> R.string.holiday_german_unity_day
        HolidayName.REFORMATION_DAY -> R.string.holiday_reformation_day
        HolidayName.REPENTANCE_DAY -> R.string.holiday_repentance_day
        HolidayName.LIBERATION_DAY -> R.string.holiday_liberation_day
        HolidayName.REPUBLIC_DAY -> R.string.holiday_republic_day
        HolidayName.ST_FRANCIS -> R.string.holiday_st_francis
        HolidayName.NATIONAL_UNITY_DAY -> R.string.holiday_national_unity_day
        HolidayName.ITALIAN_UNIFICATION -> R.string.holiday_italian_unification
        HolidayName.CONSTITUTION_DAY -> R.string.holiday_constitution_day
        HolidayName.EARLY_MAY_BANK_HOLIDAY -> R.string.holiday_early_may_bank_holiday
        HolidayName.SPRING_BANK_HOLIDAY -> R.string.holiday_spring_bank_holiday
        HolidayName.SPECIAL_BANK_HOLIDAY -> R.string.holiday_special_bank_holiday
        HolidayName.MARTIN_LUTHER_KING_DAY -> R.string.holiday_martin_luther_king_day
        HolidayName.WASHINGTONS_BIRTHDAY -> R.string.holiday_washingtons_birthday
        HolidayName.MEMORIAL_DAY -> R.string.holiday_memorial_day
        HolidayName.JUNETEENTH -> R.string.holiday_juneteenth
        HolidayName.INDEPENDENCE_DAY -> R.string.holiday_independence_day
        HolidayName.LABOR_DAY_US -> R.string.holiday_labor_day_us
        HolidayName.COLUMBUS_DAY -> R.string.holiday_columbus_day
        HolidayName.VETERANS_DAY -> R.string.holiday_veterans_day
        HolidayName.THANKSGIVING -> R.string.holiday_thanksgiving
    }
