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
}
