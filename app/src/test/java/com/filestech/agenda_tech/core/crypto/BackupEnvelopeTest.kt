package com.filestech.agenda_tech.core.crypto

import com.filestech.agenda_tech.core.result.Outcome
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Guards the `.atbak` format's security properties. These are the claims the app makes to the user
 * about their backup file, so each one is asserted rather than assumed.
 *
 * Each case derives a real 600k-round key — slow by design; that cost *is* the protection.
 */
class BackupEnvelopeTest {

    private val envelope = BackupEnvelope(AeadCipher())
    private val secret = "Rendez-vous médical 14h — confidentiel".toByteArray()

    private fun password() = "correct horse battery".toCharArray()

    private fun sealed(): ByteArray =
        (envelope.seal(password(), secret) as Outcome.Success).value

    @Test
    fun `round-trips the payload`() {
        val opened = envelope.open(password(), sealed())

        assertThat(opened).isInstanceOf(Outcome.Success::class.java)
        assertThat((opened as Outcome.Success).value).isEqualTo(secret)
    }

    @Test
    fun `two seals of the same data differ — the salt and IV are random`() {
        // Identical files would leak that the agenda had not changed between two backups.
        assertThat(sealed()).isNotEqualTo(sealed())
    }

    @Test
    fun `a wrong password does not open the file`() {
        val opened = envelope.open("wrong horse battery".toCharArray(), sealed())

        assertThat(opened).isInstanceOf(Outcome.Failure::class.java)
    }

    @Test
    fun `a flipped ciphertext byte is rejected`() {
        val file = sealed().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }

        assertThat(envelope.open(password(), file)).isInstanceOf(Outcome.Failure::class.java)
    }

    @Test
    fun `tampering with the salt is rejected — the header is authenticated`() {
        // The salt is plaintext metadata. Without it being fed to GCM as AAD, an attacker could
        // rewrite it and the file would still open.
        val file = sealed().also { it[SALT_OFFSET] = (it[SALT_OFFSET] + 1).toByte() }

        assertThat(envelope.open(password(), file)).isInstanceOf(Outcome.Failure::class.java)
    }

    @Test
    fun `an iterations downgrade is rejected`() {
        // The attack this blocks: rewrite 600 000 rounds down to a cheap number, hand the file back,
        // and let the victim's own app re-derive a key that costs nothing to brute-force.
        val file = sealed()
        writeInt(file, ITERATIONS_OFFSET, 100_000)

        assertThat(envelope.open(password(), file)).isInstanceOf(Outcome.Failure::class.java)
    }

    @Test
    fun `an absurd iterations count is refused before any work is done`() {
        // Otherwise a hostile file could pin the CPU until the user force-quits.
        val file = sealed()
        writeInt(file, ITERATIONS_OFFSET, Int.MAX_VALUE)

        assertThat(envelope.recognise(file)).isEqualTo(BackupEnvelope.Recognition.Malformed)
        assertThat(envelope.open(password(), file)).isInstanceOf(Outcome.Failure::class.java)
    }

    @Test
    fun `a foreign file is not recognised as a backup`() {
        assertThat(envelope.recognise("this is a text file, not a backup".toByteArray()))
            .isEqualTo(BackupEnvelope.Recognition.NotABackup)
        assertThat(envelope.recognise(ByteArray(0))).isEqualTo(BackupEnvelope.Recognition.NotABackup)
        assertThat(envelope.recognise(sealed())).isInstanceOf(BackupEnvelope.Recognition.Openable::class.java)
    }

    @Test
    fun `a truncated file is rejected rather than crashing`() {
        val truncated = sealed().copyOfRange(0, 20)

        // Malformed and NOT NotABackup: it still carries our magic bytes, and the user is not going
        // to be told their damaged backup was never a backup.
        assertThat(envelope.recognise(truncated)).isEqualTo(BackupEnvelope.Recognition.Malformed)
        assertThat(envelope.open(password(), truncated)).isInstanceOf(Outcome.Failure::class.java)
    }

    @Test
    fun `the caller's password is wiped, even when the file is rejected`() {
        val onSuccess = password()
        envelope.open(onSuccess, sealed())
        assertThat(onSuccess).isEqualTo(CharArray(onSuccess.size) { ' ' })

        val onReject = password()
        envelope.open(onReject, "not a backup".toByteArray())
        assertThat(onReject).isEqualTo(CharArray(onReject.size) { ' ' })

        val onSeal = password()
        envelope.seal(onSeal, secret)
        assertThat(onSeal).isEqualTo(CharArray(onSeal.size) { ' ' })
    }

    @Test
    fun `a password shorter than the minimum is still sealed if it reaches the envelope`() {
        // The length rule belongs to the use case, not here: the envelope must stay a pure primitive
        // so the policy lives in exactly one place.
        assertThat(envelope.seal("short".toCharArray(), secret)).isInstanceOf(Outcome.Success::class.java)
    }

    @Test
    fun `a backup from a newer Agenda Tech is not disowned as "not a backup"`() {
        // The defect this pins: parseHeader collapsed "wrong magic" and "version I cannot read" into
        // one null, the UI turned that into "this file is not an Agenda Tech backup", and with
        // allowBackup=false that .atbak can be the ONLY copy of the user's agenda. Being told it is
        // not a backup is an instruction to delete it.
        val fromTheFuture = sealed()
        fromTheFuture[ENVELOPE_VERSION_OFFSET] = 0x02

        assertThat(envelope.recognise(fromTheFuture))
            .isEqualTo(BackupEnvelope.Recognition.UnsupportedVersion)
    }

    @Test
    fun `a backup written with a KDF this build does not know is reported the same way`() {
        // Same promise, other half: the class KDoc says the KDF may change "without invalidating
        // files already written". An older build meeting the newer KDF must say so, not disown it.
        val argon2 = sealed()
        argon2[KDF_ID_OFFSET] = 0x02

        assertThat(envelope.recognise(argon2)).isEqualTo(BackupEnvelope.Recognition.UnsupportedVersion)
    }

    @Test
    fun `the four verdicts stay distinct — one byte apart must not collapse into one answer`() {
        // Guards the whole point of splitting them: a single mapping mistake and the three refusals
        // become the same message again, silently.
        val notOurs = "this is a text file, not a backup".toByteArray()
        val tooNew = sealed().also { it[ENVELOPE_VERSION_OFFSET] = 0x7F }
        val damaged = sealed().copyOfRange(0, 20)
        val fine = sealed()

        assertThat(envelope.recognise(notOurs)).isEqualTo(BackupEnvelope.Recognition.NotABackup)
        assertThat(envelope.recognise(tooNew)).isEqualTo(BackupEnvelope.Recognition.UnsupportedVersion)
        assertThat(envelope.recognise(damaged)).isEqualTo(BackupEnvelope.Recognition.Malformed)
        assertThat(envelope.recognise(fine)).isInstanceOf(BackupEnvelope.Recognition.Openable::class.java)
    }

    @Test
    fun `a file from a newer version still wipes the password and never derives a key`() {
        // open() must refuse on the header alone: deriving a key would cost a second of CPU to reach
        // a conclusion already available, and the password must not survive the refusal.
        val fromTheFuture = sealed()
        fromTheFuture[ENVELOPE_VERSION_OFFSET] = 0x02
        val secret = password()

        val outcome = envelope.open(secret, fromTheFuture)

        assertThat(outcome).isInstanceOf(Outcome.Failure::class.java)
        assertThat(secret).isEqualTo(CharArray(secret.size) { ' ' })
    }

    private fun writeInt(file: ByteArray, offset: Int, value: Int) {
        file[offset] = (value ushr 24).toByte()
        file[offset + 1] = (value ushr 16).toByte()
        file[offset + 2] = (value ushr 8).toByte()
        file[offset + 3] = value.toByte()
    }

    private companion object {
        // Header layout: magic(5) | envVersion(1) | kdfId(1) | iterations(4) | saltLen(1) | salt(16)
        const val ENVELOPE_VERSION_OFFSET = 5
        const val KDF_ID_OFFSET = 6
        const val ITERATIONS_OFFSET = 7
        const val SALT_OFFSET = 12
    }
}
