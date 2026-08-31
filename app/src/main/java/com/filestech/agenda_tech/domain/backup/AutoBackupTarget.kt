package com.filestech.agenda_tech.domain.backup

/**
 * Where automatic backups are written — a folder the user picked once, kept behind this interface so
 * the domain never sees a `Uri`, a `ContentResolver` or the Storage Access Framework.
 *
 * The app holds no storage permission and never will: the only folder it can write to is the one the
 * user handed it through the system picker, and only for as long as that grant survives.
 */
interface AutoBackupTarget {

    /** True when the stored folder grant is still usable — the user can revoke it at any time. */
    suspend fun isWritable(): Boolean

    /**
     * The folder's display name, for the screen to show which one was chosen, or null if none is or
     * it can no longer be reached. Names it rather than showing the raw tree URI, which is an opaque
     * provider string that tells the user nothing about where their backups actually are.
     */
    suspend fun folderName(): String?

    /** Writes one backup file. Returns false rather than throwing: this runs unattended. */
    suspend fun write(fileName: String, bytes: ByteArray): Boolean

    /**
     * Deletes the oldest automatic backups, keeping the [keep] most recent.
     *
     * Only ever touches files the app itself wrote automatically — see
     * [com.filestech.agenda_tech.domain.usecase.RunAutoBackupUseCase.AUTO_PREFIX]. The user is
     * expected to point this at a folder they already use, possibly one holding backups they exported
     * by hand, and rotation must never be able to delete one of those.
     *
     * Returns how many files it removed.
     */
    suspend fun prune(keep: Int): Int
}
