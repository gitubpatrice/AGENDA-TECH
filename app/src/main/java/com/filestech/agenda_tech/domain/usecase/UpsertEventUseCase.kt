package com.filestech.agenda_tech.domain.usecase

import com.filestech.agenda_tech.core.result.AppError
import com.filestech.agenda_tech.core.result.Outcome
import com.filestech.agenda_tech.domain.model.Event
import com.filestech.agenda_tech.domain.repository.EventRepository
import javax.inject.Inject

/**
 * Validates then persists an [Event]. Trims the title and rejects blank ones, returning a typed
 * [AppError.Validation] the editor surfaces inline.
 *
 * The start/end ordering is not re-checked here: [Event] enforces `end >= start` at construction,
 * so an invalid interval can never reach this use case (the editor validates dates before building
 * the [Event]).
 */
class UpsertEventUseCase @Inject constructor(
    private val repository: EventRepository,
) {
    suspend operator fun invoke(event: Event): Outcome<Long> {
        val validated = validate(event) ?: return Outcome.Failure(AppError.Validation("blank title"))
        return Outcome.Success(repository.upsert(validated))
    }

    /**
     * Saves a per-occurrence override, excluding the instant it replaces from its master **in the same
     * transaction**. Same validation as [invoke] — the rule lives in one place.
     *
     * Separate entry point rather than a flag: the two differ in what they write, not in how they
     * validate, and an override that saved without excluding its own instant would leave the master
     * generating an occurrence it no longer owns.
     */
    suspend fun asOverride(event: Event, masterId: Long, originalStartUtcMillis: Long): Outcome<Long> {
        val validated = validate(event) ?: return Outcome.Failure(AppError.Validation("blank title"))
        return Outcome.Success(repository.upsertOverrideAtomic(validated, masterId, originalStartUtcMillis))
    }

    /** The trimmed event, or null when its title is blank. */
    private fun validate(event: Event): Event? =
        event.title.trim().takeIf { it.isNotEmpty() }?.let { event.copy(title = it) }
}
