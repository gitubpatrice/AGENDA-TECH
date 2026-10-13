package com.filestech.agenda_tech.system

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.filestech.agenda_tech.core.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The file a "Share as .ics" hands to another app.
 *
 * The agenda itself is encrypted; this file is not — it is what the user asked to send. So it is kept
 * as short-lived as sharing allows: in the app's private cache, readable by no other app except the one
 * picked in the share sheet (a temporary grant through the non-exported `FileProvider`), and only ever
 * one: each share deletes the previous file, and every start of the app deletes the last. It cannot be
 * deleted the moment it is sent: the receiving app may read it later — when the user taps Send.
 */
@Singleton
class SharedFiles @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /** Writes [content] as `<stem>.ics` and returns the `content://` link another app may be granted. */
    suspend fun calendarFile(stem: String, content: String): Uri = withContext(io) {
        val directory = directory()
        deleteAll(directory)
        val file = File(directory, "$stem.ics")
        file.writeText(content, Charsets.UTF_8)
        FileProvider.getUriForFile(context, "${context.packageName}$AUTHORITY_SUFFIX", file)
    }

    /** Deletes what a previous share left behind. */
    suspend fun clear() = withContext(io) { deleteAll(directory()) }

    private fun directory(): File = File(context.cacheDir, DIRECTORY).apply { mkdirs() }

    private fun deleteAll(directory: File) {
        directory.listFiles()?.forEach { it.delete() }
    }

    private companion object {
        /** Must match `res/xml/share_paths.xml`. */
        const val DIRECTORY = "shared"

        /** Must match the provider's authority in the manifest. */
        const val AUTHORITY_SUFFIX = ".share"
    }
}
