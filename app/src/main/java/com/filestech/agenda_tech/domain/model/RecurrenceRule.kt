package com.filestech.agenda_tech.domain.model

/**
 * A recurrence rule attached to an [Event], modelled on the subset of RFC 5545 `RRULE` an
 * agenda needs. A null recurrence on the event means "single occurrence".
 *
 * Invariants (enforced in [init]):
 *  - [interval] ≥ 1.
 *  - [count] and [untilUtcMillis] are mutually exclusive (an RRULE has at most one bound).
 *  - [byWeekdays] is only meaningful for [RecurrenceFreq.WEEKLY]; it is ignored otherwise.
 *
 * [exDatesUtcMillis] holds the start instants of cancelled occurrences (RFC 5545 `EXDATE`) —
 * "delete this one occurrence" without breaking the series.
 *
 * NOTE (phase 2 — NOT implemented in the scaffold): the actual expansion of a rule into concrete
 * occurrence instants (respecting time zone / DST) and the RFC 5545 text (de)serialisation for
 * `.ics` interop live in a dedicated `RecurrenceExpander` / `IcsCodec`. This value object stays a
 * pure, structured description — no expansion logic here.
 */
data class RecurrenceRule(
    val freq: RecurrenceFreq,
    val interval: Int = 1,
    val byWeekdays: Set<Weekday> = emptySet(),
    val count: Int? = null,
    val untilUtcMillis: Long? = null,
    val exDatesUtcMillis: List<Long> = emptyList(),
) {
    init {
        require(interval >= 1) { "RRULE interval must be >= 1, was $interval" }
        require(count == null || untilUtcMillis == null) {
            "RRULE cannot set both COUNT and UNTIL"
        }
        require(count == null || count >= 1) { "RRULE count must be >= 1, was $count" }
    }

    /** True when the rule is open-ended (neither a COUNT nor an UNTIL bound). */
    val isInfinite: Boolean get() = count == null && untilUtcMillis == null
}
