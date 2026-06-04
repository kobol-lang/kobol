package dev.kobol.runtime

/**
 * Kobol runtime exception hierarchy.
 */
sealed class KobolException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause) {

    class FileNotFoundException(path: String) :
        KobolException("File not found: $path")

    class IoException(message: String, cause: Throwable? = null) :
        KobolException(message, cause)

    class ConversionException(from: String, to: String) :
        KobolException("Cannot convert '$from' to $to")

    class SizeException(field: String) :
        KobolException("Size overflow in field: $field")

    /** General application-level exception (for RAISE statements). */
    class ApplicationException(message: String) : KobolException(message)

    class JavaInteropException(message: String, cause: Throwable) :
        KobolException(message, cause)
}

/**
 * Thrown when a CONFIG section item cannot be loaded.
 * Separate from [KobolException] so the stack trace leads directly to
 * the misconfigured item without going through the sealed hierarchy.
 */
class KobolConfigError(message: String) : RuntimeException(message)
