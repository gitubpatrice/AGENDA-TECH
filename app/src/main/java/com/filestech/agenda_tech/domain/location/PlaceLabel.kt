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
    fun of(event: Event): String? =
        listOfNotNull(
            event.location.filled(),
            postalAddress(event.address, event.postalCode, event.city, townSeparator = NO_BREAK_SPACE),
        ).joinToString(", ").ifEmpty { null }

    /**
     * The postal address alone (`12 rue des Lices, 84000 Avignon`), for a maps app to search; null when
     * none of its three fields is filled in. The place's name is left out — `Cabinet du Dr Martin` is
     * not something a maps app can find — and the space is a plain one: this text is a query, not a
     * line to wrap.
     */
    fun addressQuery(address: String?, postalCode: String?, city: String?): String? =
        postalAddress(address, postalCode, city, townSeparator = " ")

    private fun postalAddress(address: String?, postalCode: String?, city: String?, townSeparator: String): String? {
        val town = listOfNotNull(postalCode.filled(), city.filled()).joinToString(townSeparator)
        return listOfNotNull(address.filled(), town.ifEmpty { null }).joinToString(", ").ifEmpty { null }
    }

    private fun String?.filled(): String? = this?.trim()?.ifEmpty { null }

    private val NO_BREAK_SPACE = Char(0x00A0).toString()
}
