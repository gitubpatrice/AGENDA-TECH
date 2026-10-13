package com.filestech.agenda_tech.domain.location

import com.filestech.agenda_tech.domain.model.Event

/**
 * One line saying where an event takes place, for a list that has no room for the editor's separate
 * fields: the place's name, the street, then postal code and city as an envelope writes them
 * (`Cabinet du Dr Martin, 12 rue des Lices, 84000 Avignon`). Pure — unit-testable.
 *
 * The GPS coordinates are left out: they say nothing to someone reading a list.
 */
object PlaceLabel {

    /**
     * Null when none of the place fields is filled in, so the caller draws no empty line. Postal code
     * and city are held together by a no-break space: wrapped, the line left `84000` at the end of one
     * line and `Avignon` alone on the next (seen on the S9).
     */
    fun of(event: Event): String? {
        val town = listOfNotNull(event.postalCode.filled(), event.city.filled()).joinToString(NO_BREAK_SPACE)
        return listOfNotNull(event.location.filled(), event.address.filled(), town.ifEmpty { null })
            .joinToString(", ")
            .ifEmpty { null }
    }

    private fun String?.filled(): String? = this?.trim()?.ifEmpty { null }

    private val NO_BREAK_SPACE = Char(0x00A0).toString()
}
