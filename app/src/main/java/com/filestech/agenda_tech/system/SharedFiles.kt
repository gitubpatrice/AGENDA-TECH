package com.filestech.agenda_tech.system

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.VisibleForTesting
import androidx.core.content.FileProvider
import com.filestech.agenda_tech.core.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The file a "Share as .ics" hands to another app.
 *
 * The agenda itself is encrypted; this file is not — it is what the user asked to send. So it is kept
 * as short-lived as sharing allows: in the app's private cache, readable by no other app except the one
 * picked in the share sheet (a temporary grant through the non-exported `FileProvider`), and only ever
 * one: each share deletes the previous file. Deleting the event deletes its file — so do deleting its
 * calendar, clearing the imported calendars and restoring a backup — and a start of the app deletes one
 * left from an earlier session. It cannot be deleted the moment it is sent: the receiving app may read
 * it later — when the user taps Send.
 *
 * Each share lives in a folder of its own, named after the event and a random part (security audit,
 * 2026-10-13). The file name is the title, so two shares of one title used to get the same
 * `content://` link — and an app granted the first one could read the second, sent to another app. A
 * fresh link per share, and the grant withdrawn whenever its file is deleted, keep each grant to the one
 * file it was given for.
 */
@Singleton
class SharedFiles @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    @VisibleForTesting
    internal var clock: () -> Long = System::currentTimeMillis

    /**
     * One operation at a time (external review, 2026-10-13): two shares listing the folder together kept
     * two files — "only ever one" was not — or deleted the other's file before the app it was sent to
     * could read it; a start's clean-up could do the same.
     */
    private val folder = Mutex()

    /**
     * Writes [content] as `<stem>.ics`, for the event [eventId] — the series, for an occurrence — and
     * returns the `content://` link another app may be granted.
     */
    suspend fun calendarFile(eventId: Long, stem: String, content: String): Uri = locked {
        val root = root()
        root.listFiles()?.forEach(::deleteShare)
        val folder = File(root, "$eventId$SEPARATOR${UUID.randomUUID()}").apply { mkdirs() }
        val file = File(folder, "$stem.ics")
        file.writeText(content, Charsets.UTF_8)
        uriFor(file)
    }

    /** Deletes what was shared of the event [eventId]: it is gone from the agenda, so is its copy. */
    suspend fun forget(eventId: Long) = locked {
        root().listFiles()
            ?.filter { it.name.startsWith("$eventId$SEPARATOR") }
            ?.forEach(::deleteShare)
    }

    /**
     * Deletes every shared file, for the deletions that are not one event's: a calendar deleted, the
     * imported calendars cleared, a backup restored over the agenda. Only the editor knew which event it
     * deleted, so these left the copy behind, readable by the app it was sent to (external review,
     * 2026-10-13). There is only ever one file: nothing is lost that was not a copy of what went.
     */
    suspend fun forgetAll() = locked { root().listFiles()?.forEach(::deleteShare) }

    /**
     * Deletes what a share left behind more than [STALE_AFTER_MS] ago. Not everything: the app may start
     * again — for a reminder, the widget — while the app the user shared to has not read its file yet.
     */
    suspend fun clearStale() = locked {
        val now = clock()
        root().listFiles()
            ?.filter { now - it.lastModified() > STALE_AFTER_MS }
            ?.forEach(::deleteShare)
    }

    private suspend fun <T> locked(block: () -> T): T = folder.withLock { withContext(io) { block() } }

    private fun root(): File = File(context.cacheDir, DIRECTORY).apply { mkdirs() }

    private fun uriFor(file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}$AUTHORITY_SUFFIX", file)

    /** A share folder, its file, and the grant on that file — not just the bytes. */
    private fun deleteShare(entry: File) {
        val files = if (entry.isDirectory) entry.listFiles().orEmpty().toList() else listOf(entry)
        files.forEach { file ->
            runCatching { context.revokeUriPermission(uriFor(file), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            file.delete()
        }
        if (entry.isDirectory) entry.delete()
    }

    internal companion object {
        /** Must match `res/xml/share_paths.xml`. */
        const val DIRECTORY = "shared"

        /** Must match the provider's authority in the manifest. */
        const val AUTHORITY_SUFFIX = ".share"

        /** Between the event id and the random part of a share folder's name. */
        const val SEPARATOR = "-"

        /** Long enough for any app to read what it was handed, short enough not to keep a copy around. */
        const val STALE_AFTER_MS = 60 * 60 * 1000L
    }
}
