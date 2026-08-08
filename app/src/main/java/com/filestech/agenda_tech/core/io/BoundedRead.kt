package com.filestech.agenda_tech.core.io

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Reads a user-picked file with a ceiling on how many bytes are ever read.
 *
 * ## What the ceiling does and does not bound
 *
 * It bounds the **bytes read**, which is what stops an arbitrarily large pick. It is not a bound on
 * peak memory: [ByteArrayOutputStream] grows by doubling, so its internal buffer can be roughly twice
 * the data, and `toByteArray()` copies it once more. A file at the 5 MiB `.ics` ceiling therefore
 * costs a transient peak of several times that, not 5 MiB. Said plainly here because an earlier
 * version of this KDoc claimed a "hard ceiling on how much is ever held in memory", which was simply
 * not true of this implementation (found by external review).
 *
 * That trade is accepted rather than engineered away: the ceilings are small enough that the peak is
 * comfortable on any device this app supports, and the alternative — streaming the parse — would mean
 * rewriting a codec and an envelope format to gain nothing a user would notice.
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

    /** Consecutive zero-byte reads tolerated before the stream is declared unreadable. */
    private const val MAX_EMPTY_READS = 64

    /**
     * Everything [input] holds, or **null** as soon as it exceeds [maxBytes] — at which point nothing
     * more is read. The caller closes [input]; the caller also decides what null means to the user,
     * since "too large" is a refusal to report, never a silent truncation.
     */
    fun readAtMost(input: InputStream, maxBytes: Long): ByteArray? {
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(CHUNK_BYTES)
        var total = 0L
        var emptyReads = 0
        while (true) {
            val read = input.read(chunk)
            if (read < 0) break
            if (read == 0) {
                // `InputStream.read` is contractually not allowed to return 0 for a non-empty buffer,
                // but the stream here comes from an arbitrary document provider through a wrapper we
                // do not control, and a wrapper that does it turns this loop into a spin that never
                // ends — the import stays on "in progress" for ever and burns an IO thread. Treated
                // as a stream that cannot deliver: refused, like any other unreadable pick.
                if (++emptyReads > MAX_EMPTY_READS) return null
            } else {
                emptyReads = 0
                total += read
                if (total > maxBytes) return null
                out.write(chunk, 0, read)
            }
        }
        return out.toByteArray()
    }
}
