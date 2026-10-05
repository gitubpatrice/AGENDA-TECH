package com.filestech.agenda_tech.ui.screens.month

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * How a month cell shares its lines between titles. The rule worth guarding is the one a calendar
 * usually gets wrong: free lines must go to the titles that need them, instead of every title being
 * cut to one line in a cell that is half empty.
 */
class CellLinesTest {

    @Test
    fun `an empty day plans nothing`() {
        assertThat(CellLines.plan(needs = emptyList(), lines = 4)).isEqualTo(CellLines.Plan(emptyList(), 0))
    }

    @Test
    fun `a lone long title takes every line of the cell`() {
        assertThat(CellLines.plan(needs = listOf(6), lines = 4).linesPerEvent).containsExactly(4)
    }

    @Test
    fun `a short title keeps one line and leaves the rest unused`() {
        // A title that fits on one line must not be handed lines it cannot use — they would be blank.
        assertThat(CellLines.plan(needs = listOf(1), lines = 4).linesPerEvent).containsExactly(1)
    }

    @Test
    fun `free lines go to the titles that need them, in order`() {
        // Five lines, three titles: each gets one, then the two spare go to the first and the third,
        // since the second already fits.
        val plan = CellLines.plan(needs = listOf(3, 1, 3), lines = 5)

        assertThat(plan.linesPerEvent).containsExactly(2, 1, 2).inOrder()
        assertThat(plan.hidden).isEqualTo(0)
    }

    @Test
    fun `spare lines are dealt one at a time, not all to the first title`() {
        val plan = CellLines.plan(needs = listOf(4, 4), lines = 4)

        assertThat(plan.linesPerEvent).containsExactly(2, 2).inOrder()
    }

    @Test
    fun `as many titles as lines gives one line each and hides none`() {
        val plan = CellLines.plan(needs = listOf(2, 2, 2), lines = 3)

        assertThat(plan.linesPerEvent).containsExactly(1, 1, 1)
        assertThat(plan.hidden).isEqualTo(0)
    }

    @Test
    fun `more titles than lines keeps the last line to say how many are missing`() {
        // Four lines, six events: three shown, and "+3" on the fourth line. Dropping them silently
        // would make a full evening look free.
        val plan = CellLines.plan(needs = listOf(1, 1, 1, 1, 1, 1), lines = 4)

        assertThat(plan.linesPerEvent).containsExactly(1, 1, 1)
        assertThat(plan.hidden).isEqualTo(3)
        assertThat(plan.linesPerEvent.size + plan.hidden).isEqualTo(6)
    }

    @Test
    fun `a cell with room for one line shares it between the first title and the count`() {
        // Measured on the S9 with the backup banner shown: one line per cell, and days of two or more
        // events fell back to dots — the titles display showing no title on its busiest days.
        val plan = CellLines.plan(needs = listOf(2, 1, 1), lines = 1)

        assertThat(plan).isEqualTo(CellLines.Plan(listOf(1), hidden = 2, moreInline = true))
    }

    @Test
    fun `a lone event in a one-line cell needs no count`() {
        assertThat(CellLines.plan(needs = listOf(3), lines = 1)).isEqualTo(CellLines.Plan(listOf(1), hidden = 0))
    }

    @Test
    fun `with two lines or more the count takes a line of its own`() {
        val plan = CellLines.plan(needs = listOf(1, 1, 1), lines = 2)

        assertThat(plan).isEqualTo(CellLines.Plan(listOf(1), hidden = 2, moreInline = false))
    }

    @Test
    fun `a cell with no room at all shows no title`() {
        val plan = CellLines.plan(needs = listOf(2), lines = 0)

        assertThat(plan.linesPerEvent).isEmpty()
        assertThat(plan.hidden).isEqualTo(1)
    }
}
