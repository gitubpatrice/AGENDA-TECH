package com.filestech.agenda_tech.domain.recurrence

/**
 * A scan allowance shared by all the events expanded in **one** pass (audit F8).
 *
 * [RecurrenceExpander] already caps the iterations spent on a single event, which stops one rule
 * from looping forever. It does nothing about the total: a window that has to expand N events pays
 * that cap N times over, and the per-event bound says nothing about N. An import can raise N at
 * will, so a file full of rules whose base sits far before the query window makes every render walk
 * millions of iterations on the default dispatcher — the views stop producing frames while each
 * event stays individually within its limit.
 *
 * Passing one instance across a pass turns those independent caps into a single ceiling on the work
 * a render may cost. Events are expanded in order, so exhausting the budget truncates the tail of a
 * pathological agenda rather than dropping an arbitrary event from a healthy one.
 *
 * **Not thread-safe, by construction.** A budget belongs to one pass, and a pass runs on a single
 * dispatcher; sharing one across threads would make the accounting meaningless rather than merely
 * racy. Create one per pass — never hold one in a field.
 */
class ExpansionBudget(private val maxIterations: Int = DEFAULT_MAX_ITERATIONS) {

    init {
        require(maxIterations > 0) { "expansion budget must be > 0, was $maxIterations" }
    }

    private var used = 0

    /** True when the allowance is spent and the pass should stop expanding. */
    val isExhausted: Boolean get() = used >= maxIterations

    /** Iterations charged so far — for diagnostics only. */
    val spent: Int get() = used

    /**
     * Charges one iteration. Returns false once the allowance is spent, and keeps returning false:
     * a caller that stops on the first false never has to ask again.
     */
    fun tryConsume(): Boolean {
        if (used >= maxIterations) return false
        used++
        return true
    }

    /**
     * The allowance one of [ways] equal shares gets — how a pass total is divided instead of raced for.
     *
     * A single shared instance is right for a render, where the pass either finishes or the tail is
     * simply not drawn this frame. It is wrong for the reboot pass: there, "the tail is not processed"
     * means those alarms are never re-armed, and the first pathological series takes the whole
     * allowance from everyone behind it. Splitting keeps the same total ceiling — `ways × (total /
     * ways) ≤ total` — while making one series unable to spend anyone else's share.
     *
     * Never returns zero: an allowance of nothing is indistinguishable from an exhausted budget, and
     * a caller cannot tell "you get one iteration" from "stop asking".
     */
    fun shareSize(ways: Int): Int = maxOf(1, maxIterations / maxOf(1, ways))

    companion object {
        /**
         * Ceiling for one render pass.
         *
         * Sized so a real agenda never reaches it — a year of daily events costs a few thousand
         * iterations — while still bounding the work a hostile import can demand to something that
         * completes in well under a frame budget's worth of arithmetic.
         */
        const val DEFAULT_MAX_ITERATIONS = 1_000_000
    }
}
