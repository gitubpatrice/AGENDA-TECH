package com.filestech.agenda_tech.ui.screens.month

/**
 * Why the backup reminder is showing — so the card can say something true.
 *
 * "No backup" would be a lie to someone who exported three months ago; "your calendar changed since
 * your last backup" would be a lie to someone who never made one. One boolean could not tell them
 * apart, and the whole point of this reminder is that it is believed.
 */
enum class BackupPromptReason {
    /** Never exported, and there is now enough in the agenda to be worth losing. */
    NEVER,

    /** Exported once, but the agenda has moved on since — and that was a while ago. */
    STALE,
}
