package com.filestech.agenda_tech.core.io

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * Audit F4 — the ceiling has to bound the read itself.
 *
 * The interesting assertion is not "a big file is refused": it is that refusing it costs a bounded
 * amount of memory. A guard that reads everything and then decides is not a guard.
 */
class BoundedReadTest {

    @Test
    fun `a file under the ceiling is returned whole`() {
        val bytes = ByteArray(1000) { it.toByte() }
        val read = BoundedRead.readAtMost(ByteArrayInputStream(bytes), 5000)
        assertThat(read).isEqualTo(bytes)
    }

    @Test
    fun `a file exactly at the ceiling is accepted`() {
        // The boundary is the half of a cap that gets it wrong: an off-by-one here refuses a file the
        // user is told is small enough.
        val bytes = ByteArray(1000) { it.toByte() }
        assertThat(BoundedRead.readAtMost(ByteArrayInputStream(bytes), 1000)).isEqualTo(bytes)
    }

    @Test
    fun `one byte over the ceiling is refused`() {
        val bytes = ByteArray(1001)
        assertThat(BoundedRead.readAtMost(ByteArrayInputStream(bytes), 1000)).isNull()
    }

    @Test
    fun `an empty file reads as empty, not as a refusal`() {
        assertThat(BoundedRead.readAtMost(ByteArrayInputStream(ByteArray(0)), 1000)).isEqualTo(ByteArray(0))
    }

    @Test
    fun `an oversized file stops being read instead of being read and then measured`() {
        // The point of the whole helper. A stream that would happily serve gigabytes must be dropped
        // shortly after the ceiling, not drained first — so this counts what was actually served.
        val endless = CountingEndlessStream()
        val ceiling = 256L * 1024

        assertThat(BoundedRead.readAtMost(endless, ceiling)).isNull()

        // One 64 KiB chunk of slack past the ceiling: the read stops at a chunk boundary.
        assertThat(endless.served).isAtMost(ceiling + 64L * 1024)
    }

    @Test
    fun `a stream that dribbles bytes is still read whole`() {
        // read() is allowed to return fewer bytes than asked for, and a content provider streaming
        // over IPC routinely does. A loop that treated a short read as end-of-stream would truncate a
        // legitimate file — silently, since the result would still parse as far as it went.
        val bytes = ByteArray(5000) { (it % 251).toByte() }
        val dribbling = object : InputStream() {
            private val source = ByteArrayInputStream(bytes)
            override fun read(): Int = source.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int = source.read(b, off, minOf(len, 7))
        }
        assertThat(BoundedRead.readAtMost(dribbling, 100_000)).isEqualTo(bytes)
    }

    /** An input stream with no end, counting how many bytes it was actually asked to hand over. */
    private class CountingEndlessStream : InputStream() {
        var served = 0L
            private set

        override fun read(): Int {
            served++
            return 0
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            served += len
            return len
        }
    }
}
