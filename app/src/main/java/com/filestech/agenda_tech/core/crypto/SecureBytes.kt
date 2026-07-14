package com.filestech.agenda_tech.core.crypto

/**
 * Best-effort scrubbing of sensitive byte buffers. Note: JIT and immutable copies (FFI, JNI
 * buffers) can defeat this. Use only for memory you allocated yourself.
 */
fun ByteArray.wipe() {
    try {
        java.util.Arrays.fill(this, 0)
    } catch (_: UnsupportedOperationException) {
        // Unmodifiable buffer (rare on JVM byte[], common on Java ByteBuffer-backed arrays).
    }
}

fun CharArray.wipe() {
    try {
        java.util.Arrays.fill(this, ' ')
    } catch (_: UnsupportedOperationException) {
        // ignore
    }
}
