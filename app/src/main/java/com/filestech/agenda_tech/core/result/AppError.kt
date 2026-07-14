package com.filestech.agenda_tech.core.result

/**
 * Typed application errors. Never throw raw exceptions to UI layers; map to one of these.
 *
 * The set is intentionally scoped to what an offline, encrypted agenda actually surfaces:
 * storage/crypto failures, validation of user input, and the ICS import/export path
 * ([IcsParse]) planned for a later phase.
 */
sealed class AppError(open val cause: Throwable? = null) {

    data class Storage(override val cause: Throwable? = null) : AppError(cause)
    data class Crypto(val reason: String, override val cause: Throwable? = null) : AppError(cause)
    data class Database(override val cause: Throwable? = null) : AppError(cause)
    data class Validation(val message: String) : AppError()
    data class NotFound(val what: String) : AppError()
    data class Permission(val permission: String) : AppError()

    /** RFC 5545 (.ics) import failure — reserved for the interoperability phase. */
    data class IcsParse(val reason: String, override val cause: Throwable? = null) : AppError(cause)

    data class Cancelled(val reason: String? = null) : AppError()
    data class Unknown(override val cause: Throwable? = null) : AppError(cause)
}
