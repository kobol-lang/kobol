package dev.kobol.runtime

/**
 * Runtime support for Kobol TEXT operations.
 */
object KobolText {

    /** Concatenate any number of parts, converting each to String. */
    fun combine(vararg parts: Any?): String = parts.joinToString("") { it?.toString() ?: "" }

    fun uppercase(s: String): String = s.uppercase()
    fun lowercase(s: String): String = s.lowercase()
    fun trim(s: String): String = s.trim()
    fun trimLeft(s: String): String = s.trimStart()
    fun trimRight(s: String): String = s.trimEnd()

    /**
     * Extract a substring.
     * @param s      Source string
     * @param from   1-based start position
     * @param length Maximum number of characters to extract
     */
    fun substring(s: String, from: Int, length: Int): String {
        val start = (from - 1).coerceAtLeast(0)
        val end   = (start + length).coerceAtMost(s.length)
        return if (start >= s.length) "" else s.substring(start, end)
    }

    /** Returns 1-based position of [needle] in [haystack], or 0 if not found. */
    fun find(needle: String, haystack: String): Int {
        val idx = haystack.indexOf(needle)
        return if (idx < 0) 0 else idx + 1
    }

    fun replace(s: String, from: String, to: String): String = s.replace(from, to)

    /** Split a string into a list using a delimiter. */
    fun split(s: String, delimiter: String): List<String> = s.split(delimiter)

    /**
     * Pad string on the right to exactly [width] characters (for TEXT(n) fixed fields).
     * Truncates if longer than [width].
     */
    fun padRight(s: String, width: Int): String = when {
        s.length >= width -> s.substring(0, width)
        else              -> s.padEnd(width)
    }

    fun padLeft(s: String, width: Int): String = when {
        s.length >= width -> s.substring(s.length - width)
        else              -> s.padStart(width)
    }

    fun length(s: String): Int = s.length
}
