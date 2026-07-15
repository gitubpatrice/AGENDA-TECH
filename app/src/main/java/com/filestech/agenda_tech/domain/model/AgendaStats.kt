package com.filestech.agenda_tech.domain.model

/**
 * What the agenda holds, in the two numbers the prompts need — asked as one query rather than by
 * loading every row to count it.
 *
 * [lastChangeAtUtcMillis] is 0 on an empty agenda. Compared against the last backup, it answers the
 * only question worth asking before nagging someone: *is there anything new to lose?*
 */
data class AgendaStats(
    val eventCount: Int,
    val lastChangeAtUtcMillis: Long,
) {
    val isEmpty: Boolean get() = eventCount == 0
}
