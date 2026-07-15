package com.filestech.agenda_tech.domain.usecase

import com.filestech.agenda_tech.core.result.AppError
import com.filestech.agenda_tech.core.result.Outcome
import com.filestech.agenda_tech.domain.model.Event
import com.filestech.agenda_tech.domain.repository.EventRepository
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class UpsertEventUseCaseTest {

    private val repository = mockk<EventRepository>()
    private val useCase = UpsertEventUseCase(repository)

    private fun event(title: String) = Event(
        calendarId = 1L,
        title = title,
        startUtcMillis = 1_000L,
        endUtcMillis = 2_000L,
        timeZoneId = "UTC",
    )

    @Test
    fun `blank title is rejected without touching the repository`() = runTest {
        val result = useCase(event("   "))
        assertThat(result).isInstanceOf(Outcome.Failure::class.java)
        assertThat((result as Outcome.Failure).error).isInstanceOf(AppError.Validation::class.java)
        coVerify(exactly = 0) { repository.upsert(any()) }
    }

    @Test
    fun `a valid event is persisted with its title trimmed`() = runTest {
        coEvery { repository.upsert(any()) } returns 42L
        val result = useCase(event("  Réunion  "))
        assertThat(result).isEqualTo(Outcome.Success(42L))
        coVerify { repository.upsert(match { it.title == "Réunion" }) }
    }

    // --- asOverride ----------------------------------------------------------
    // Saving an override and excluding the instant it replaces from its master are one fact ("this
    // occurrence moved"), so they go through the repository's single atomic entry point. The master's
    // EXDATEs are exported verbatim to .ics and read by the reminder scheduler — a half-applied save
    // is not cosmetic.

    @Test
    fun `an override goes through the atomic entry point, never the plain upsert`() = runTest {
        coEvery { repository.upsertOverrideAtomic(any(), any(), any()) } returns 7L

        val result = useCase.asOverride(event("Sport"), masterId = 10L, originalStartUtcMillis = 5_000L)

        assertThat(result).isEqualTo(Outcome.Success(7L))
        coVerify { repository.upsertOverrideAtomic(any(), 10L, 5_000L) }
        // The plain path would write the override without touching the master.
        coVerify(exactly = 0) { repository.upsert(any()) }
    }

    @Test
    fun `an override's blank title is rejected without touching the repository`() = runTest {
        val result = useCase.asOverride(event("   "), masterId = 10L, originalStartUtcMillis = 5_000L)

        assertThat(result).isInstanceOf(Outcome.Failure::class.java)
        assertThat((result as Outcome.Failure).error).isInstanceOf(AppError.Validation::class.java)
        coVerify(exactly = 0) { repository.upsertOverrideAtomic(any(), any(), any()) }
    }

    @Test
    fun `an override's title is trimmed too`() = runTest {
        coEvery { repository.upsertOverrideAtomic(any(), any(), any()) } returns 7L

        useCase.asOverride(event("  Sport  "), masterId = 10L, originalStartUtcMillis = 5_000L)

        coVerify { repository.upsertOverrideAtomic(match { it.title == "Sport" }, any(), any()) }
    }
}
