package dev.kobol.stdlib

/**
 * kobol.text — String/TEXT functions callable from Kobol programs.
 */
object KobolText {

    // ── Case ─────────────────────────────────────────────────────────────

    @JvmStatic fun uppercase(s: String): String = s.uppercase()
    @JvmStatic fun lowercase(s: String): String = s.lowercase()

    // ── Trimming ──────────────────────────────────────────────────────────

    @JvmStatic fun trim(s: String): String       = s.trim()
    @JvmStatic fun trimStart(s: String): String  = s.trimStart()
    @JvmStatic fun trimEnd(s: String): String    = s.trimEnd()

    // ── Substring ─────────────────────────────────────────────────────────

    /** Extract [length] characters starting at 1-based [start]. */
    @JvmStatic fun substring(s: String, start: Long, length: Long): String {
        val from = (start - 1).coerceIn(0, s.length.toLong()).toInt()
        val to   = (from + length).coerceIn(0, s.length.toLong()).toInt()
        return s.substring(from, to)
    }

    // ── Search ────────────────────────────────────────────────────────────

    /** Returns 1-based position of [needle] in [haystack], or 0 if not found. */
    @JvmStatic fun find(haystack: String, needle: String): Long =
        (haystack.indexOf(needle) + 1).toLong().let { if (it == 0L) 0L else it }

    @JvmStatic fun contains(s: String, sub: String): Boolean = s.contains(sub)
    @JvmStatic fun startsWith(s: String, prefix: String): Boolean = s.startsWith(prefix)
    @JvmStatic fun endsWith(s: String, suffix: String): Boolean   = s.endsWith(suffix)

    // ── Replace ───────────────────────────────────────────────────────────

    @JvmStatic fun replace(s: String, old: String, new: String): String = s.replace(old, new)

    // ── Split / Combine ───────────────────────────────────────────────────

    @JvmStatic fun split(s: String, delimiter: String): List<String> = s.split(delimiter)

    /** Split [s] by [delimiter] into at most [limit] parts (0 = unlimited). */
    @JvmStatic fun split(s: String, delimiter: String, limit: Long): List<String> =
        s.split(delimiter, limit = limit.toInt())

    @JvmStatic fun combine(parts: List<String>, delimiter: String): String = parts.joinToString(delimiter)
    @JvmStatic fun combine(a: String, b: String): String = a + b

    // ── Length ────────────────────────────────────────────────────────────

    @JvmStatic fun length(s: String): Long = s.length.toLong()

    // ── Padding ───────────────────────────────────────────────────────────

    @JvmStatic fun padLeft(s: String, width: Long, pad: String = " "): String =
        s.padStart(width.toInt(), pad.firstOrNull() ?: ' ')

    @JvmStatic fun padRight(s: String, width: Long, pad: String = " "): String =
        s.padEnd(width.toInt(), pad.firstOrNull() ?: ' ')

    // ── Repeat ────────────────────────────────────────────────────────────

    @JvmStatic fun repeat(s: String, n: Long): String = s.repeat(n.toInt())

    // ── Blank / Empty ─────────────────────────────────────────────────────

    @JvmStatic fun isBlank(s: String): Boolean  = s.isBlank()
    @JvmStatic fun isEmpty(s: String): Boolean  = s.isEmpty()

    // ── Reverse ───────────────────────────────────────────────────────────

    /** Reverse [s] in codepoint order, correctly handling surrogate pairs. */
    @JvmStatic fun reverse(s: String): String {
        val cps = s.codePoints().toArray()
        var lo = 0; var hi = cps.size - 1
        while (lo < hi) { val t = cps[lo]; cps[lo] = cps[hi]; cps[hi] = t; lo++; hi-- }
        return String(cps, 0, cps.size)
    }

    // ── Hashing ───────────────────────────────────────────────────────────

    /**
     * Compute the cryptographic hash of [value] using the given [algorithm]
     * (e.g. "SHA-256", "SHA-512", "MD5") and return the lowercase hex string.
     *
     * Usage from Kobol:
     *   CALL kobol.text.hash USING myText, "SHA-256" GIVING hashResult
     */
    @JvmStatic fun hash(value: String, algorithm: String): String {
        val digest = java.security.MessageDigest.getInstance(algorithm)
        return digest.digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
