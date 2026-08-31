package com.filestech.agenda_tech.data.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.filestech.agenda_tech.di.IoDispatcher
import com.filestech.agenda_tech.domain.backup.AutoBackupTarget
import com.filestech.agenda_tech.domain.repository.SettingsRepository
import com.filestech.agenda_tech.domain.usecase.ExportBackupUseCase
import com.filestech.agenda_tech.domain.usecase.RunAutoBackupUseCase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes automatic backups into the folder the user picked, through the Storage Access Framework.
 *
 * The app declares no storage permission. Its entire access is the tree URI the system picker handed
 * back, persisted with [android.content.ContentResolver.takePersistableUriPermission] — a grant the
 * user can withdraw from Settings › Apps at any moment, and which also dies if the folder is on a
 * removed SD card. Every operation therefore re-checks instead of assuming, and reports failure
 * rather than throwing: this runs with nobody watching.
 */
@Singleton
class SafAutoBackupTarget @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    @IoDispatcher private val io: CoroutineDispatcher,
) : AutoBackupTarget {

    override suspend fun isWritable(): Boolean = withContext(io) { folder() != null }

    override suspend fun folderName(): String? = withContext(io) { folder()?.name }

    /**
     * Writes the file **beside** the previous one, then swaps.
     *
     * The obvious version — delete the old file, create the new — loses a perfectly good backup any
     * time the second half fails: the card comes out, the grant is withdrawn, the provider errors.
     * The user is then left with neither the old file nor a new one, on the one day that matters.
     * That is what a review of 2026-08-31 caught, and it is why the existing file is not touched until
     * the replacement is fully on disk.
     *
     * A half-written temp is deleted rather than left behind, and its name is outside
     * [RunAutoBackupUseCase.isAutomaticBackupFile] anyway, so a crash mid-write can never leave
     * something that looks like a backup and refuses to open.
     */
    override suspend fun write(fileName: String, bytes: ByteArray): Boolean = withContext(io) {
        val folder = folder() ?: return@withContext false
        val tempName = RunAutoBackupUseCase.tempNameFor(fileName)

        val temp = runCatching {
            // A leftover from a previous killed run would otherwise make SAF invent a second name.
            folder.findFile(tempName)?.takeIf { it.isFile }?.delete()
            folder.createFile(ExportBackupUseCase.MIME_TYPE, tempName)
        }.getOrElse {
            Timber.e(it, "AutoBackup: could not create the temporary file")
            null
        } ?: return@withContext false

        val streamed = runCatching {
            context.contentResolver.openOutputStream(temp.uri, "wt")?.use { out ->
                out.write(bytes)
                out.flush()
                true
            } ?: false
        }.getOrElse {
            Timber.e(it, "AutoBackup: writing %s failed", fileName)
            false
        }

        if (!streamed) {
            runCatching { temp.delete() }
            return@withContext false
        }

        // Only now is the previous backup touched.
        runCatching {
            folder.findFile(fileName)?.takeIf { it.isFile }?.delete()
            temp.renameTo(fileName)
        }.getOrElse {
            Timber.e(it, "AutoBackup: could not put %s in place", fileName)
            runCatching { temp.delete() }
            false
        }
    }

    /**
     * Deletes the oldest automatic backups beyond [keep], and any abandoned temporary file.
     *
     * The temp sweep is here because a run killed between "wrote the temp" and "renamed it" leaves one
     * behind, and nothing else would ever collect it.
     */
    override suspend fun prune(keep: Int): Int = withContext(io) {
        val folder = folder() ?: return@withContext 0
        runCatching {
            val files = folder.listFiles().filter { it.isFile }
            val staleTemps = files.count { file ->
                RunAutoBackupUseCase.isAbandonedTempFile(file.name.orEmpty()) && file.delete()
            }
            val rotated = files
                .filter { RunAutoBackupUseCase.isAutomaticBackupFile(it.name.orEmpty()) }
                // One entry per name. A provider can list the same name twice — a stale entry left by a
                // file removed behind its back, for instance — and rotation would then count it as two
                // recovery points and delete one real backup too many. Seen on a Galaxy S9 on
                // 2026-08-31: files removed with `adb rm`, behind the provider, left exactly that.
                .distinctBy { it.name }
                // The names carry ISO dates, so lexicographic order IS chronological order.
                .sortedByDescending { it.name }
                .drop(keep)
                .count { it.delete() }
            staleTemps + rotated
        }.getOrElse {
            Timber.e(it, "AutoBackup: pruning failed")
            0
        }
    }

    /**
     * The chosen folder, or null when it cannot be written right now.
     *
     * Checks the persisted grant explicitly rather than relying on [DocumentFile.canWrite] alone:
     * once the user revokes it, the resolver still builds a `DocumentFile` and only the eventual
     * `openOutputStream` throws — too late, and inside the write path.
     */
    private suspend fun folder(): DocumentFile? {
        val uriString = settingsRepository.current().autoBackupFolderUri ?: return null
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return null
        val held = context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isWritePermission
        }
        if (!held) {
            Timber.w("AutoBackup: the folder grant is gone")
            return null
        }
        return runCatching { DocumentFile.fromTreeUri(context, uri) }
            .getOrNull()
            ?.takeIf { it.isDirectory && it.canWrite() }
    }

    companion object {
        /** Flags to pass to `takePersistableUriPermission` when the user picks the folder. */
        const val PERSIST_FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    }
}
