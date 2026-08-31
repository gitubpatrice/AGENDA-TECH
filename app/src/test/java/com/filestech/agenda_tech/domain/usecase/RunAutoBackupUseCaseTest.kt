package com.filestech.agenda_tech.domain.usecase

import com.filestech.agenda_tech.core.result.AppError
import com.filestech.agenda_tech.core.result.Outcome
import com.filestech.agenda_tech.domain.backup.AutoBackupOutcome
import com.filestech.agenda_tech.domain.settings.AppSettings
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.ZoneId

/**
 * The unattended run. Nobody is watching when this executes, so what these tests pin down is not the
 * happy path — it is that every way of failing is *recorded*, and that a failure never lets the app
 * claim it has a backup.
 *
 * A backup feature that fails quietly is worse than no backup feature: the user believes they are
 * covered right up to the day the phone is gone.
 */
class RunAutoBackupUseCaseTest {

    private val zone: ZoneId = ZoneId.of("Europe/Paris")

    /** 2026-08-31T10:00 in Paris — the ISO date this instant produces is what names the file. */
    private val now: Long = java.time.LocalDateTime.of(2026, 8, 31, 10, 0)
        .atZone(zone).toInstant().toEpochMilli()

    private val dispatcher = StandardTestDispatcher()
    private val export: ExportBackupUseCase = mockk()
    private val target = FakeAutoBackupTarget()
    private val secret = FakeAutoBackupSecret("motdepassefort".toCharArray())
    private val settings = FakeSettingsRepository(
        AppSettings(autoBackupEnabled = true, autoBackupFolderUri = "content://tree/backups"),
    )

    private fun useCase() = RunAutoBackupUseCase(
        settingsRepository = settings,
        secret = secret,
        target = target,
        exportBackup = export,
        io = dispatcher,
    )

    private fun exportSucceeds(events: Int = 12) {
        coEvery { export(any(), any()) } returns
            Outcome.Success(ExportBackupUseCase.Export(ByteArray(64), calendars = 2, events = events))
    }

    @Test
    fun `a successful run writes one dated file and rotates the folder`() = runTest(dispatcher) {
        exportSucceeds()

        val outcome = useCase()(now, zone)

        assertThat(outcome).isEqualTo(AutoBackupOutcome.OK)
        assertThat(target.written).containsExactly("agenda-tech-auto-2026-08-31.atbak")
        assertThat(target.prunedKeeping).isEqualTo(RunAutoBackupUseCase.KEEP)
    }

    @Test
    fun `a successful run is what moves the last-backup date`() = runTest(dispatcher) {
        exportSucceeds()

        useCase()(now, zone)

        val after = settings.current()
        assertThat(after.autoBackupLastOutcome).isEqualTo(AutoBackupOutcome.OK)
        assertThat(after.autoBackupLastRunAtUtcMillis).isEqualTo(now)
        assertThat(after.lastBackupAtUtcMillis).isEqualTo(now)
    }

    @Test
    fun `a failed run records itself but does NOT move the last-backup date`() = runTest(dispatcher) {
        // The whole point. `lastBackupAtUtcMillis` drives the "your agenda changed since your last
        // backup" nudge; moving it on a failure would silence the one warning the user still has, and
        // the app would be quietly telling them they are covered when nothing was written.
        target.writeSucceeds = false
        exportSucceeds()

        val outcome = useCase()(now, zone)

        assertThat(outcome).isEqualTo(AutoBackupOutcome.WRITE_FAILED)
        val after = settings.current()
        assertThat(after.autoBackupLastRunAtUtcMillis).isEqualTo(now)
        assertThat(after.lastBackupAtUtcMillis).isEqualTo(0L)
    }

    @Test
    fun `no folder means nothing is exported at all`() = runTest(dispatcher) {
        settings.update { it.copy(autoBackupFolderUri = null) }

        val outcome = useCase()(now, zone)

        assertThat(outcome).isEqualTo(AutoBackupOutcome.NO_FOLDER)
        coVerify(exactly = 0) { export(any(), any()) }
    }

    @Test
    fun `an unreachable folder is discovered before the second of PBKDF2 is spent`() = runTest(dispatcher) {
        // Sealing costs about a second of deliberate CPU. On a phone whose card is out, doing it first
        // and discovering afterwards would burn that second every single week, for nothing.
        target.writable = false

        val outcome = useCase()(now, zone)

        assertThat(outcome).isEqualTo(AutoBackupOutcome.FOLDER_UNAVAILABLE)
        coVerify(exactly = 0) { export(any(), any()) }
    }

    @Test
    fun `a password the Keystore can no longer produce is reported, not guessed around`() = runTest(dispatcher) {
        secret.clear()

        val outcome = useCase()(now, zone)

        assertThat(outcome).isEqualTo(AutoBackupOutcome.NO_PASSWORD)
        assertThat(target.written).isEmpty()
    }

    @Test
    fun `an export failure is recorded and writes nothing`() = runTest(dispatcher) {
        coEvery { export(any(), any()) } returns Outcome.Failure(AppError.Database(RuntimeException("boom")))

        val outcome = useCase()(now, zone)

        assertThat(outcome).isEqualTo(AutoBackupOutcome.EXPORT_FAILED)
        assertThat(target.written).isEmpty()
        assertThat(settings.current().autoBackupLastOutcome).isEqualTo(AutoBackupOutcome.EXPORT_FAILED)
    }

    @Test
    fun `a target that throws is turned into a recorded failure, not an exception`() = runTest(dispatcher) {
        // This runs inside a WorkManager Worker: an exception escaping here is a crash in a background
        // process with no user in front of it, and the run would leave no trace of having failed.
        // (Named for what it checks: the Worker's own guard is not exercised from here — that was a
        // fair objection from the gpt-5.2 review of 2026-08-31.)
        target.throwOnWrite = true
        exportSucceeds()

        val outcome = useCase()(now, zone)

        assertThat(outcome).isEqualTo(AutoBackupOutcome.WRITE_FAILED)
        assertThat(settings.current().autoBackupLastOutcome).isEqualTo(AutoBackupOutcome.WRITE_FAILED)
    }

    @Test
    fun `settings that refuse to be written do not turn into an exception`() = runTest(dispatcher) {
        // The contract says this never throws, and recording the result is itself a DataStore write
        // that can fail — a full disk is enough. Before the fix, a successful backup whose outcome
        // could not be saved threw straight out of the Worker.
        exportSucceeds()
        settings.updateThrows = true

        val outcome = useCase()(now, zone)

        assertThat(outcome).isEqualTo(AutoBackupOutcome.OK)
        assertThat(target.written).hasSize(1)
    }

    @Test
    fun `turning the switch off mid-run stops the file being written`() = runTest(dispatcher) {
        // Sealing takes about a second and WorkManager's cancellation is not instantaneous, so the
        // switch can go off while a run is in flight. The point of a switch is that it is obeyed.
        exportSucceeds()
        settings.update { it.copy(autoBackupEnabled = false) }

        val outcome = useCase()(now, zone)

        assertThat(target.written).isEmpty()
        // Nothing recorded either: a failure line about a run the user themselves cancelled is noise.
        assertThat(settings.current().autoBackupLastOutcome).isEqualTo(AutoBackupOutcome.NEVER_RUN)
        assertThat(outcome).isEqualTo(AutoBackupOutcome.NEVER_RUN)
    }
}

/**
 * Which files rotation is allowed to delete.
 *
 * The most dangerous rule in the feature: the app is pointed at a folder the user already uses, very
 * possibly the one holding the backups they exported by hand, and this predicate is the only thing
 * between rotation and those files.
 */
class AutomaticBackupFileNameTest {

    @Test
    fun `only the files this feature writes are rotatable`() {
        assertThat(RunAutoBackupUseCase.isAutomaticBackupFile("agenda-tech-auto-2026-08-31.atbak")).isTrue()
    }

    @Test
    fun `a manual export is never touched, though the names nearly match`() {
        // `ExportBackupUseCase.fileName` produces exactly this. Only the "auto-" segment separates the
        // user's own backup from one rotation may delete — a prefix check on "agenda-tech-" alone
        // would quietly delete the file they made on purpose.
        assertThat(RunAutoBackupUseCase.isAutomaticBackupFile("agenda-tech-2026-08-31.atbak")).isFalse()
    }

    @Test
    fun `nothing else in the user's folder is rotatable`() {
        listOf(
            "impots-2025.pdf",
            "agenda-tech-auto-2026-08-31.atbak.bak",
            "agenda-tech-auto-2026-08-31.txt",
            "AGENDA-TECH-AUTO-2026-08-31.atbak",
            "copie-agenda-tech-auto-2026-08-31.atbak",
            "",
        ).forEach { name ->
            assertThat(RunAutoBackupUseCase.isAutomaticBackupFile(name)).isFalse()
        }
    }

    @Test
    fun `a file that merely starts with the prefix is not one of ours`() {
        // The gap a prefix-and-extension check left open, and it was not theoretical: a user's own
        // `agenda-tech-auto-notes.atbak` matched it and rotation would have deleted their file.
        listOf(
            "agenda-tech-auto-notes.atbak",
            "agenda-tech-auto-.atbak",
            "agenda-tech-auto-2026-08.atbak",
            "agenda-tech-auto-2026-08-31-copie.atbak",
        ).forEach { name ->
            assertThat(RunAutoBackupUseCase.isAutomaticBackupFile(name)).isFalse()
        }
    }

    @Test
    fun `a half-written temporary file is collectable but never a backup`() {
        val temp = RunAutoBackupUseCase.tempNameFor("agenda-tech-auto-2026-08-31.atbak")

        // Rotation must not count it as a recovery point, and pruning must be able to collect it.
        assertThat(RunAutoBackupUseCase.isAutomaticBackupFile(temp)).isFalse()
        assertThat(RunAutoBackupUseCase.isAbandonedTempFile(temp)).isTrue()
        // But it must not become a licence to delete anything else that happens to start with "tmp-".
        assertThat(RunAutoBackupUseCase.isAbandonedTempFile("tmp-mes-notes.atbak")).isFalse()
        assertThat(RunAutoBackupUseCase.isAbandonedTempFile("agenda-tech-2026-08-31.atbak")).isFalse()
    }
}
