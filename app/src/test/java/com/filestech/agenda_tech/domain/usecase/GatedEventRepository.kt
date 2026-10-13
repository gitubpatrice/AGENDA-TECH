package com.filestech.agenda_tech.domain.usecase

import com.filestech.agenda_tech.domain.model.Event
import com.filestech.agenda_tech.domain.repository.EventRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onStart

/**
 * An event repository slow to read the window a screen has just moved to: the first window asked for
 * answers at once, so the screen has a state to keep, and every other one waits for [gate] to open.
 * What a screen shows in between is the question its tests ask.
 */
internal class GatedEventRepository(private val delegate: EventRepository) : EventRepository by delegate {

    val gate = CompletableDeferred<Unit>()
    private var firstWindowStart: Long? = null

    override fun observeForExpansion(windowStartUtcMillis: Long, windowEndUtcMillis: Long): Flow<List<Event>> {
        val first = firstWindowStart ?: windowStartUtcMillis.also { firstWindowStart = it }
        val rows = delegate.observeForExpansion(windowStartUtcMillis, windowEndUtcMillis)
        return if (windowStartUtcMillis == first) rows else rows.onStart { gate.await() }
    }
}
