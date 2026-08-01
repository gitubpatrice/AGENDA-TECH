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
        require(interval in 1..MAX_INTERVAL) {
            "RRULE interval must be in 1..$MAX_INTERVAL, was $interval"
        }
        require(count == null || untilUtcMillis == null) {
            "RRULE cannot set both COUNT and UNTIL"
        }
        require(count == null || count >= 1) { "RRULE count must be >= 1, was $count" }
    }

    /** True when the rule is open-ended (neither a COUNT nor an UNTIL bound). */
    val isInfinite: Boolean get() = count == null && untilUtcMillis == null

    /**
     * The same rule with [instantUtcMillis] cancelled (RFC 5545 `EXDATE`) — what "delete this one
     * occurrence" and "move this one occurrence" both do to the master.
     *
     * Idempotent: excluding an already-excluded instant returns the rule unchanged, so a re-save
     * cannot grow a list of duplicates — and that list is exported verbatim to `.ics`.
     */
    fun excluding(instantUtcMillis: Long): RecurrenceRule =
        if (instantUtcMillis in exDatesUtcMillis) {
            this
        } else {
            copy(exDatesUtcMillis = exDatesUtcMillis + instantUtcMillis)
        }

    companion object {
        /**
         * Upper bound on [interval] (audit F1/F5/F7).
         *
         * The expander turns an interval into a year offset, and `YearMonth.of` throws for a year
         * outside +/-999,999,999 — an uncaught exception on the render path of every calendar view
         * and of the widget, which made one imported event enough to crash the app on every launch
         * with no in-app way to reach and delete it. The editor always clamped; the import paths did
         * not. The bound lives here so no future ingestion path can reintroduce the hole, and it is
         * enforced in [init] rather than left to callers.
         */
        const val MAX_INTERVAL = 999
    }
}
