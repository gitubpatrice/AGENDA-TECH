package com.filestech.agenda_tech.core.crypto

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class PinHasherTest {

    @Test
    fun `newSalt is 16 bytes and unique across calls`() {
        val a = PinHasher.newSalt()
        val b = PinHasher.newSalt()
        assertThat(a).hasLength(16)
        assertThat(b).hasLength(16)
        assertThat(a).isNotEqualTo(b)
    }

    @Test
    fun `hash is deterministic for the same pin and salt`() {
        val salt = PinHasher.newSalt()
        val h1 = PinHasher.hash("1234".toCharArray(), salt)
        val h2 = PinHasher.hash("1234".toCharArray(), salt)
        assertThat(h1).hasLength(32) // 256-bit key
        assertThat(h1).isEqualTo(h2)
    }

    @Test
    fun `hash differs for a different salt`() {
        val h1 = PinHasher.hash("1234".toCharArray(), PinHasher.newSalt())
        val h2 = PinHasher.hash("1234".toCharArray(), PinHasher.newSalt())
        assertThat(h1).isNotEqualTo(h2)
    }

    @Test
    fun `matches accepts the correct pin`() {
        val salt = PinHasher.newSalt()
        val hash = PinHasher.hash("246810".toCharArray(), salt)
        assertThat(PinHasher.matches("246810".toCharArray(), salt, hash)).isTrue()
    }

    @Test
    fun `matches rejects a wrong pin`() {
        val salt = PinHasher.newSalt()
        val hash = PinHasher.hash("246810".toCharArray(), salt)
        assertThat(PinHasher.matches("000000".toCharArray(), salt, hash)).isFalse()
    }

    @Test
    fun `hash wipes the caller's char array`() {
        val salt = PinHasher.newSalt()
        val pin = "1234".toCharArray()
        PinHasher.hash(pin, salt)
        assertThat(pin).isEqualTo(charArrayOf(' ', ' ', ' ', ' '))
    }
}
