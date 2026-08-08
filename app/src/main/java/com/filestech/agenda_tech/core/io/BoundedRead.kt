package com.filestech.agenda_tech.core.io

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Reads a user-picked file with a hard ceiling on how much is ever held in memory.
 *
 * ## Why the cap has to bound the READ, not the result
 *
 * Both file pickers in this app have to accept every MIME type — there is none registered for
 * `.atbak`, and `.ics` is inconsistently declared by document providers — so a mis-tapped video is a
 * normal thing to be handed. Reading the stream whole and *then* measuring it would be the very
 * out-of-memory the ceiling exists to prevent.
 *
 * ## Why not ask the provider for the size first
 *
 * Audit F4 — the `.ics` importer asked `ParcelFileDescriptor.statSize` and trusted the answer, which
 * fails three different ways: `openFileDescriptor` returning null skipped the check entirely, a
 * provider free to report any size could report a small one and then serve gigabytes, and a provider
 * that genuinely does not know the size reports `-1`, which the check rejected — refusing a perfectly
 * legitimate file. A declared size is a hint from an untrusted party; counting the bytes as they
 * arrive is a fact.
 *
 * Not `readBytes()` (unbounded) nor `readNBytes()` (Java 9, absent before Android 13 while this app
 * targets 8.0).
 */
object BoundedRead {

    private const val CHUNK_BYTES = 64 * 1024

    /**
     * Everything [input] holds, or **null** as soon as it exceeds [maxBytes] — at which point nothing
     * more is read. The caller closes [input]; the caller also decides what null means to the user,
     * since "too large" is a refusal to report, never a silent truncation.
     */
    fun readAtMost(input: InputStream, maxBytes: Long): ByteArray? {
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(CHUNK_BYTES)
        var total = 0L
        while (true) {
            val read = input.read(chunk)
            if (read < 0) break
            total += read
            if (total > maxBytes) return null
            out.write(chunk, 0, read)
        }
        return out.toByteArray()
    }
}
