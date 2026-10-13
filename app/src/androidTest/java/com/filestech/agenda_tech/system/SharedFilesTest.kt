package com.filestech.agenda_tech.system

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileNotFoundException

/**
 * The shared `.ics` through the real FileProvider (security audit, 2026-10-13): each share under a link
 * of its own, and the file gone with its event, or once stale.
 *
 * Block bodies, not `= runBlocking { }`: JUnit 4 rejects the whole class when a test method returns a
 * value, and `runBlocking` returns its last expression.
 */
@RunWith(AndroidJUnit4::class)
class SharedFilesTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val shared = SharedFiles(context, Dispatchers.IO)

    @After
    fun tearDown() {
        runBlocking {
            shared.clock = { Long.MAX_VALUE }
            shared.clearStale()
        }
    }

    private fun read(uri: Uri): String? =
        try {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (_: FileNotFoundException) {
            null
        }

    @Test
    fun twoSharesOfOneEventGetTwoLinks() {
        runBlocking {
            // The same event shared twice — after adding a note, to someone else: the likeliest case.
            val first = shared.calendarFile(1, "Dentiste", "A")
            val second = shared.calendarFile(1, "Dentiste", "B")

            // A grant on the first link must not reach the second file.
            assertThat(second).isNotEqualTo(first)
            assertThat(read(first)).isNull()
            assertThat(read(second)).isEqualTo("B")
            // The name the receiving app shows is still the title.
            assertThat(second.lastPathSegment).isEqualTo("Dentiste.ics")
        }
    }

    @Test
    fun deletingTheEventDeletesItsShareAndNoOtherOne() {
        runBlocking {
            val uri = shared.calendarFile(7, "Kiné", "K")

            shared.forget(70)
            assertThat(read(uri)).isEqualTo("K")

            shared.forget(7)
            assertThat(read(uri)).isNull()
        }
    }

    @Test
    fun aStartOfTheAppKeepsARecentShareAndDeletesAnOldOne() {
        runBlocking {
            val uri = shared.calendarFile(3, "Réunion", "R")

            shared.clearStale()
            assertThat(read(uri)).isEqualTo("R")

            shared.clock = { System.currentTimeMillis() + SharedFiles.STALE_AFTER_MS + 60_000 }
            shared.clearStale()
            assertThat(read(uri)).isNull()
        }
    }
}
