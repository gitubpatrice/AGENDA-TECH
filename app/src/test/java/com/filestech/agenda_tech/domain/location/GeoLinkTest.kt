package com.filestech.agenda_tech.domain.location

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
}
