package com.filestech.agenda_tech.data.local.db

/**
 * Loads the SQLCipher native library for tests that touch `net.zetetic.database.sqlcipher` **before**
 * [DatabaseFactory] has had a chance to do it.
 *
 * The production path loads it inside `DatabaseFactory.build()`, which is private and only reached
 * once a database is being built. A test that opens or creates a SQLCipher file directly — to stand in
 * for a legacy file, or to hand Room's `MigrationTestHelper` a `SupportOpenHelperFactory` — runs before
 * that and would die on an `UnsatisfiedLinkError` that says nothing about what it was testing.
 *
 * `System.loadLibrary` is a no-op on an already-loaded library, so calling this more than once is
 * harmless — which is why it is a plain function and not a lazy singleton.
 */
internal object SqlCipherNative {
    fun load() {
        System.loadLibrary("sqlcipher")
    }
}
