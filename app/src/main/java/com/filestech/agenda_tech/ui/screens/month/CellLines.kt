package com.filestech.agenda_tech.ui.screens.month

/**
 * How the lines of a month cell are shared between its events when the titles are shown.
 *
 * A calendar that gives every event exactly one line cuts a title after six or seven characters even
 * when the rest of the cell is empty. Here every event shown first gets one line, then the lines left
 * over go — one at a time, in order — to the titles that still need one. A lone event therefore reads
 * on three or four lines, and a busy day still shows one line per event.
 *
 * Pure, so it can be tested without a screen: the caller measures how many lines each title would
 * take at the cell's width, and how many lines the cell holds.
 */
object CellLines {

    /**
     * [linesPerEvent] covers the first events, in order; [hidden] is how many more did not fit. The
     * count of the hidden ones takes a line of its own, except when the cell holds a single line:
     * then it shares that line with the first title ([moreInline]).
     */
    data class Plan(val linesPerEvent: List<Int>, val hidden: Int, val moreInline: Boolean = false)

    /**
     * @param needs the lines each title would take unshortened, in display order.
     * @param lines the lines the cell holds.
     */
    fun plan(needs: List<Int>, lines: Int): Plan = when {
        needs.isEmpty() -> Plan(emptyList(), 0)
        lines <= 0 -> Plan(emptyList(), needs.size)
        // More events than lines: one line each, and a line says how many are missing — a silently
        // dropped event would read as a free evening. On a single line, the first title and the count
        // share it: a title and "+2" say more than a row of dots.
        needs.size > lines && lines == 1 -> Plan(listOf(1), needs.size - 1, moreInline = true)
        needs.size > lines -> Plan(List(lines - 1) { 1 }, needs.size - (lines - 1))
        else -> Plan(spread(needs, lines), 0)
    }

    /** Every title one line, then the spare lines one at a time, in order, to those that need more. */
    private fun spread(needs: List<Int>, lines: Int): List<Int> {
        val given = IntArray(needs.size) { 1 }
        var left = lines - needs.size
        var gave = true
        while (left > 0 && gave) {
            gave = false
            for (i in needs.indices) {
                if (left > 0 && given[i] < needs[i]) {
                    given[i]++
                    left--
                    gave = true
                }
            }
        }
        return given.toList()
    }
}
