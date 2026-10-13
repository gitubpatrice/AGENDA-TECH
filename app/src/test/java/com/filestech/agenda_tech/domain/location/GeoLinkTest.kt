package com.filestech.agenda_tech.domain.location

import com.filestech.agenda_tech.domain.model.Event
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class GeoLinkTest {

    @Test
    fun `builds a geo link from plain coordinates`() {
        assertThat(GeoLink.fromCoordinates("44.0512,5.0489"))
            .isEqualTo("geo:44.0512,5.0489?q=44.0512,5.0489")
    }

    @Test
    fun `tolerates the spacing people actually type`() {
        val expected = "geo:44.0512,5.0489?q=44.0512,5.0489"
        assertThat(GeoLink.fromCoordinates("  44.0512 , 5.0489  ")).isEqualTo(expected)
        assertThat(GeoLink.fromCoordinates("44.0512 ; 5.0489")).isEqualTo(expected)
    }

    @Test
    fun `adds a labelled pin when a label is given`() {
        assertThat(GeoLink.fromCoordinates("44.0512,5.0489", "Cabinet"))
            .isEqualTo("geo:44.0512,5.0489?q=44.0512,5.0489(Cabinet)")
    }

    @Test
    fun `escapes a label so it can never break the link`() {
        val uri = GeoLink.fromCoordinates("44.0512,5.0489", "Dr Martin & fils")!!
        assertThat(uri).doesNotContain(" ")
        assertThat(uri).doesNotContain("&")
    }

    @Test
    fun `a blank label is ignored rather than producing empty parentheses`() {
        assertThat(GeoLink.fromCoordinates("44.0512,5.0489", "   "))
            .isEqualTo("geo:44.0512,5.0489?q=44.0512,5.0489")
    }

    @Test
    fun `never emits scientific notation for a point near zero`() {
        // Double.toString() would give "5.0E-4", which maps apps don't read as a coordinate: the link
        // would open on the wrong place without any error (audit SEC2).
        val uri = GeoLink.fromCoordinates("0.0005,5.0489")!!
        assertThat(uri).doesNotContain("E-")
        assertThat(uri).doesNotContain("e-")
        assertThat(uri).isEqualTo("geo:0.0005,5.0489?q=0.0005,5.0489")
    }

    @Test
    fun `keeps a plain decimal for very small and very large valid values`() {
        assertThat(GeoLink.fromCoordinates("0.0000001,0.0000001")!!).doesNotContain("E")
        assertThat(GeoLink.fromCoordinates("0,0")).isEqualTo("geo:0,0?q=0,0")
    }

    @Test
    fun `handles negative coordinates`() {
        assertThat(GeoLink.fromCoordinates("-33.8688,-151.2093"))
            .isEqualTo("geo:-33.8688,-151.2093?q=-33.8688,-151.2093")
    }

    @Test
    fun `rejects what is not a coordinate pair`() {
        // Null rather than a broken intent: the caller hides the button instead.
        assertThat(GeoLink.fromCoordinates(null)).isNull()
        assertThat(GeoLink.fromCoordinates("")).isNull()
        assertThat(GeoLink.fromCoordinates("   ")).isNull()
        assertThat(GeoLink.fromCoordinates("Marseille")).isNull()
        assertThat(GeoLink.fromCoordinates("44.0512")).isNull() // only one number
        assertThat(GeoLink.fromCoordinates("44.0512,5.0489,12")).isNull() // three
        assertThat(GeoLink.fromCoordinates("abc,def")).isNull()
    }

    @Test
    fun `rejects out-of-range coordinates`() {
        assertThat(GeoLink.fromCoordinates("91,5")).isNull() // latitude > 90
        assertThat(GeoLink.fromCoordinates("-91,5")).isNull()
        assertThat(GeoLink.fromCoordinates("44,181")).isNull() // longitude > 180
        assertThat(GeoLink.fromCoordinates("44,-181")).isNull()
    }

    @Test
    fun `accepts the exact bounds`() {
        assertThat(GeoLink.fromCoordinates("90,180")).isNotNull()
        assertThat(GeoLink.fromCoordinates("-90,-180")).isNotNull()
    }

    @Test
    fun `isUsable agrees with fromCoordinates`() {
        assertThat(GeoLink.isUsable("44.0512,5.0489")).isTrue()
        assertThat(GeoLink.isUsable("Marseille")).isFalse()
        assertThat(GeoLink.isUsable(null)).isFalse()
    }

    @Test
    fun `an address becomes a search the maps app runs`() {
        assertThat(GeoLink.fromAddress("12 rue des Lices, 84000 Avignon"))
            .isEqualTo("geo:0,0?q=12+rue+des+Lices%2C+84000+Avignon")
    }

    @Test
    fun `an address can never break the link`() {
        val uri = GeoLink.fromAddress("1st & Pike?x=1#top")!!
        assertThat(uri).startsWith("geo:0,0?q=")
        assertThat(uri.removePrefix("geo:0,0?q=")).doesNotContainMatch("[ &?#=]")
    }

    @Test
    fun `a blank address gives no link`() {
        assertThat(GeoLink.fromAddress(null)).isNull()
        assertThat(GeoLink.fromAddress("   ")).isNull()
    }

    @Test
    fun `an event's coordinates win over its address, labelled with its place`() {
        val uri = GeoLink.forEvent(event(location = "Cabinet", address = "12 rue des Lices", gps = "44.0512,5.0489"))
        assertThat(uri).isEqualTo("geo:44.0512,5.0489?q=44.0512,5.0489(Cabinet)")
    }

    @Test
    fun `without coordinates, an event's postal address is searched, not its place name`() {
        val uri = GeoLink.forEvent(
            event(location = "Cabinet du Dr Martin", address = "12 rue des Lices", postalCode = "84000", city = "Avignon"),
        )
        assertThat(uri).isEqualTo("geo:0,0?q=12+rue+des+Lices%2C+84000+Avignon")
    }

    @Test
    fun `unusable coordinates fall back to the address`() {
        assertThat(GeoLink.forEvent(event(city = "Avignon", gps = "Marseille"))).isEqualTo("geo:0,0?q=Avignon")
    }

    @Test
    fun `an imported event with a place name only searches that name`() {
        assertThat(GeoLink.forEvent(event(location = "Gare de Lyon"))).isEqualTo("geo:0,0?q=Gare+de+Lyon")
    }

    @Test
    fun `coordinates without a place name are labelled with the title`() {
        assertThat(GeoLink.forEvent(event(gps = "44.0512,5.0489")))
            .isEqualTo("geo:44.0512,5.0489?q=44.0512,5.0489(Dentiste)")
    }

    @Test
    fun `an event without any place gives no link`() {
        assertThat(GeoLink.forEvent(event())).isNull()
        assertThat(GeoLink.forEvent(event(location = " ", address = ""))).isNull()
    }

    private fun event(
        location: String? = null,
        address: String? = null,
        postalCode: String? = null,
        city: String? = null,
        gps: String? = null,
    ) = Event(
        calendarId = 1L,
        title = "Dentiste",
        location = location,
        address = address,
        postalCode = postalCode,
        city = city,
        gpsCoordinates = gps,
        startUtcMillis = 0L,
        endUtcMillis = 0L,
        timeZoneId = "Europe/Paris",
    )
}
