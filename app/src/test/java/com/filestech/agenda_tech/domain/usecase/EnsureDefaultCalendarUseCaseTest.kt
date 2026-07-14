package com.filestech.agenda_tech.domain.usecase

import com.filestech.agenda_tech.domain.repository.CalendarRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class EnsureDefaultCalendarUseCaseTest {

    private val repository = mockk<CalendarRepository>(relaxed = true)
    private val useCase = EnsureDefaultCalendarUseCase(repository)

    @Test
    fun `creates a single visible default calendar when none exist`() = runTest {
        coEvery { repository.count() } returns 0
        useCase("Perso")
        coVerify(exactly = 1) {
            repository.upsert(match { it.isDefault && it.isVisible && it.name == "Perso" })
        }
    }

    @Test
    fun `does nothing when a calendar already exists`() = runTest {
        coEvery { repository.count() } returns 3
        useCase("Perso")
        coVerify(exactly = 0) { repository.upsert(any()) }
    }
}
