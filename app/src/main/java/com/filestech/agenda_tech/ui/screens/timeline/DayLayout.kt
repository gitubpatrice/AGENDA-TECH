package com.filestech.agenda_tech.ui.screens.timeline

/**
 * A timed item placed on a day timeline: its clamped `[startMinute, endMinute)` within the day and
 * the column ([lane] of [laneCount]) it occupies so overlapping events sit side by side.
 */
data class PositionedItem<T>(
    val item: T,
    val startMinute: Int,
    val endMinute: Int,
    val lane: Int,
    val laneCount: Int,
)

/**
 * Pure column-assignment for a day/week timeline — no Android, fully unit-testable.
 *
 * Overlapping events are grouped into clusters (transitively overlapping), and within a cluster
 * each event is greedily placed in the first free column. Every event in a cluster reports the same
 * [PositionedItem.laneCount] (the cluster's column count) so the UI can size each block to
 * `1 / laneCount` of the width. Minutes are clamped to `[0, MINUTES_PER_DAY]`; a zero/negative
 * duration is given a small minimum so it stays tappable.
 */
object DayLayout {

    const val MINUTES_PER_DAY = 24 * 60
    const val MIN_EVENT_MINUTES = 15

    fun <T> layout(items: List<TimedItem<T>>): List<PositionedItem<T>> {
        if (items.isEmpty()) return emptyList()

        val normalized = items
            .map { it.clampedToDay() }
            .sortedWith(compareBy({ it.startMinute }, { it.endMinute }))

        val result = ArrayList<PositionedItem<T>>(normalized.size)
        var cluster = ArrayList<TimedItem<T>>()
        var clusterMaxEnd = Int.MIN_VALUE

        fun flushCluster() {
            if (cluster.isEmpty()) return
            result += assignLanes(cluster)
            cluster = ArrayList()
            clusterMaxEnd = Int.MIN_VALUE
        }

        for (event in normalized) {
            if (cluster.isNotEmpty() && event.startMinute >= clusterMaxEnd) {
                flushCluster()
            }
            cluster += event
            clusterMaxEnd = maxOf(clusterMaxEnd, event.endMinute)
        }
        flushCluster()

        return result
    }

    private fun <T> assignLanes(cluster: List<TimedItem<T>>): List<PositionedItem<T>> {
        val laneEndTimes = ArrayList<Int>() // laneEndTimes[i] = end minute of the last event in lane i
        val laneOf = IntArray(cluster.size)
        cluster.forEachIndexed { index, event ->
            val freeLane = laneEndTimes.indexOfFirst { it <= event.startMinute }
            if (freeLane >= 0) {
                laneEndTimes[freeLane] = event.endMinute
                laneOf[index] = freeLane
            } else {
                laneOf[index] = laneEndTimes.size
                laneEndTimes += event.endMinute
            }
        }
        val laneCount = laneEndTimes.size
        return cluster.mapIndexed { index, event ->
            PositionedItem(
                item = event.item,
                startMinute = event.startMinute,
                endMinute = event.endMinute,
                lane = laneOf[index],
                laneCount = laneCount,
            )
        }
    }

    private fun <T> TimedItem<T>.clampedToDay(): TimedItem<T> {
        val start = startMinute.coerceIn(0, MINUTES_PER_DAY - MIN_EVENT_MINUTES)
        val end = endMinute.coerceIn(start + MIN_EVENT_MINUTES, MINUTES_PER_DAY)
        return copy(startMinute = start, endMinute = end)
    }
}

/** Input to [DayLayout]: an item with its minute offsets from the start of the day. */
data class TimedItem<T>(
    val item: T,
    val startMinute: Int,
    val endMinute: Int,
)
