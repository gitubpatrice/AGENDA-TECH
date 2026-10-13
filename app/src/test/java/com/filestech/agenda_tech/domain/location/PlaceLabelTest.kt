package com.filestech.agenda_tech.domain.location

import com.filestech.agenda_tech.domain.model.Event
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class PlaceLabelTest {

    private fun event(
        location: String? = null,
        address: String? = null,
        postalCode: String? = null,
        city: String? = null,
        gps: String? = null,
    ) = Event(
        calendarId = 1L,
        title = "Rendez-vous",
        location = location,
        address = address,
        postalCode = postalCode,
        city = city,
        gpsCoordinates = gps,
        startUtcMillis = 0L,
        endUtcMillis = 0L,
        timeZoneId = "Europe/Paris",
    )

    private companion object {
        val NBSP = Char(0x00A0)
    }

    @Test
    fun `joins every field in reading order, postal code before the city`() {
        val label = PlaceLabel.of(
            event(
                location = "Cabinet du Dr Martin",
                address = "12 rue des Lices",
                postalCode = "84000",
                city = "Avignon",
            ),
        )
        assertThat(label).isEqualTo("Cabinet du Dr Martin, 12 rue des Lices, 84000${NBSP}Avignon")
    }

    @Test
    fun `postal code and city are held together by a no-break space`() {
        assertThat(PlaceLabel.of(event(postalCode = "84000", city = "Avignon"))).isEqualTo("84000${NBSP}Avignon")
    }

    @Test
    fun `a missing field leaves no empty separator behind`() {
        assertThat(PlaceLabel.of(event(address = "12 rue des Lices", city = "Avignon")))
            .isEqualTo("12 rue des Lices, Avignon")
        assertThat(PlaceLabel.of(event(postalCode = "84000"))).isEqualTo("84000")
        assertThat(PlaceLabel.of(event(location = "Mairie"))).isEqualTo("Mairie")
    }

    @Test
    fun `blank fields count as empty`() {
        assertThat(PlaceLabel.of(event(location = "  ", address = "", postalCode = " ", city = "Avignon ")))
            .isEqualTo("Avignon")
    }

    @Test
    fun `the address to search leaves out the place name and uses a plain space`() {
        assertThat(PlaceLabel.addressQuery("12 rue des Lices", "84000", "Avignon"))
            .isEqualTo("12 rue des Lices, 84000 Avignon")
        assertThat(PlaceLabel.addressQuery(null, null, "Avignon")).isEqualTo("Avignon")
        assertThat(PlaceLabel.addressQuery(" ", "", null)).isNull()
    }

    @Test
    fun `no place at all gives null, and coordinates alone are not a place to read`() {
        assertThat(PlaceLabel.of(event())).isNull()
        assertThat(PlaceLabel.of(event(gps = "44.0512, 5.0489"))).isNull()
    }
}
