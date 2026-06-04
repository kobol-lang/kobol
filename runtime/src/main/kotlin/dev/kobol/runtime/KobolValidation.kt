package dev.kobol.runtime

/**
 * Thrown when a VALIDATE statement constraint is violated.
 * Carries the field name and the violated constraint description
 * for clear, actionable error messages.
 */
class KobolValidationError(
    message: String,
    val fieldName: String = "",
    cause: Throwable? = null,
) : RuntimeException(message, cause)
