package dev.kobol.stdlib

import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * kobol.convert — Type-conversion functions callable from Kobol programs.
 *
 * Each function is named TO-<TYPE> in Kobol source; the JVM name is the
 * camelCase equivalent (e.g. CALL kobol.convert.TO-INTEGER ... becomes
 * KobolConvert.toInteger(...)).
 */
object KobolConvert {

    // ── TO-TEXT ───────────────────────────────────────────────────────────

    @JvmStatic fun toText(n: Long): String           = n.toString()
    @JvmStatic fun toText(n: Int): String            = n.toString()
    @JvmStatic fun toText(n: BigDecimal): String     = n.toPlainString()
    @JvmStatic fun toText(b: Boolean): String        = if (b) "TRUE" else "FALSE"
    @JvmStatic fun toText(d: LocalDate): String      = d.format(DateTimeFormatter.ISO_LOCAL_DATE)
    @JvmStatic fun toText(any: Any?): String         = any?.toString() ?: ""

    // ── TO-INTEGER ────────────────────────────────────────────────────────

    @JvmStatic fun toInteger(s: String): Long        = s.trim().toLong()
    @JvmStatic fun toInteger(n: BigDecimal): Long    = n.toLong()
    @JvmStatic fun toInteger(b: Boolean): Long       = if (b) 1L else 0L

    /** Returns null on parse failure instead of throwing. */
    @JvmStatic fun toIntegerOrNull(s: String): Long? = s.trim().toLongOrNull()

    // ── TO-DECIMAL ────────────────────────────────────────────────────────

    @JvmStatic fun toDecimal(s: String): BigDecimal      = BigDecimal(s.trim())
    @JvmStatic fun toDecimal(n: Long): BigDecimal        = BigDecimal(n)
    @JvmStatic fun toDecimal(n: Int): BigDecimal         = BigDecimal(n)
    @JvmStatic fun toDecimalOrNull(s: String): BigDecimal? =
        runCatching { BigDecimal(s.trim()) }.getOrNull()

    // ── TO-DATE ───────────────────────────────────────────────────────────

    /** Parse ISO-8601 date string ("yyyy-MM-dd"). */
    @JvmStatic fun toDate(s: String): LocalDate = LocalDate.parse(s.trim())

    @JvmStatic fun toDate(s: String, pattern: String): LocalDate =
        LocalDate.parse(s.trim(), DateTimeFormatter.ofPattern(pattern))

    @JvmStatic fun toDateOrNull(s: String): LocalDate? =
        runCatching { LocalDate.parse(s.trim()) }.getOrNull()

    // ── TO-BOOLEAN ────────────────────────────────────────────────────────

    @JvmStatic fun toBoolean(s: String): Boolean =
        when (s.trim().uppercase()) {
            "TRUE", "YES", "1" -> true
            else               -> false
        }

    @JvmStatic fun toBoolean(n: Long): Boolean    = n != 0L
    @JvmStatic fun toBoolean(n: BigDecimal): Boolean = n.signum() != 0
}
