package com.filestech.agenda_tech.ui.screens.timeline

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class DayLayoutTest {

    private fun item(id: String, startMinute: Int, endMinute: Int) = TimedItem(id, startMinute, endMinute)

    @Test
    fun `sequential non-overlapping events all sit in a single lane`() {
        val result = DayLayout.layout(
            listOf(
                item("a", 9 * 60, 10 * 60),
                item("b", 10 * 60, 11 * 60),
                item("c", 11 * 60, 12 * 60),
            ),
        )
        assertThat(result.map { it.laneCount }).containsExactly(1, 1, 1)
        assertThat(result.map { it.lane }).containsExactly(0, 0, 0)
    }

    @Test
    fun `two overlapping events split into two lanes`() {
        val result = DayLayout.layout(
            listOf(
                item("a", 9 * 60, 10 * 60 + 30),
                item("b", 10 * 60, 11 * 60),
            ),
        ).associateBy { it.item }

        assertThat(result.getValue("a").laneCount).isEqualTo(2)
        assertThat(result.getValue("b").laneCount).isEqualTo(2)
        assertThat(setOf(result.getValue("a").lane, result.getValue("b").lane)).containsExactly(0, 1)
    }

    @Test
    fun `a freed lane is reused by a later non-overlapping event in the same cluster`() {
        // a: 9-11, b: 9:30-10 (overlaps a -> lane 1), c: 10:15-11 (b freed lane 1, a still busy).
        val result = DayLayout.layout(
            listOf(
                item("a", 9 * 60, 11 * 60),
                item("b", 9 * 60 + 30, 10 * 60),
                item("c", 10 * 60 + 15, 11 * 60),
            ),
        ).associateBy { it.item }

        assertThat(result.getValue("a").lane).isEqualTo(0)
        assertThat(result.getValue("b").lane).isEqualTo(1)
        assertThat(result.getValue("c").lane).isEqualTo(1) // reuses b's freed lane
        // The whole cluster is 2 lanes wide.
        assertThat(result.values.map { it.laneCount }.toSet()).containsExactly(2)
    }

    @Test
    fun `separate clusters get independent lane counts`() {
        val result = DayLayout.layout(
            listOf(
                item("a", 9 * 60, 10 * 60),
                item("b", 9 * 60, 10 * 60), // overlaps a -> cluster of 2
                item("c", 14 * 60, 15 * 60), // separate cluster of 1
            ),
        ).associateBy { it.item }

        assertThat(result.getValue("a").laneCount).isEqualTo(2)
        assertThat(result.getValue("b").laneCount).isEqualTo(2)
        assertThat(result.getValue("c").laneCount).isEqualTo(1)
    }

    @Test
    fun `minutes are clamped to the day and given a minimum height`() {
        val result = DayLayout.layout(listOf(item("a", -30, -10))).single()
        assertThat(result.startMinute).isAtLeast(0)
        assertThat(result.endMinute).isAtMost(DayLayout.MINUTES_PER_DAY)
        assertThat(result.endMinute - result.startMinute).isAtLeast(DayLayout.MIN_EVENT_MINUTES)
    }

    @Test
    fun `empty input yields empty output`() {
        assertThat(DayLayout.layout(emptyList<TimedItem<String>>())).isEmpty()
    }
}
