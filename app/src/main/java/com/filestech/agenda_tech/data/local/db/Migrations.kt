package com.filestech.agenda_tech.data.local.db

import androidx.room.migration.Migration

/**
 * Forward, additive Room migrations. Empty at schema v1 — the first migration lands when v2 adds
 * a column/table. Migrations MUST be additive (never rewrite existing rows) and MUST NOT change
 * the SQLCipher passphrase, so an `adb install -r` upgrade never re-prompts setup or loses data.
 */
object Migrations {
    val ALL: Array<Migration> = emptyArray()
}
