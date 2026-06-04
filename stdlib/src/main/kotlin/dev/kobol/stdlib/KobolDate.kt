package dev.kobol.stdlib

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * kobol.date — Date/time functions callable from Kobol programs.
 */
object KobolDate {

    // ── Current date/time ────────────────────────────────────────────────

    @JvmStatic fun today(): LocalDate      = LocalDate.now()
    @JvmStatic fun now(): LocalDateTime    = LocalDateTime.now()

    // ── Construction ─────────────────────────────────────────────────────

    @JvmStatic fun date(year: Long, month: Long, day: Long): LocalDate =
        LocalDate.of(year.toInt(), month.toInt(), day.toInt())

    // ── Parts ─────────────────────────────────────────────────────────────

    @JvmStatic fun year(d: LocalDate): Long  = d.year.toLong()
    @JvmStatic fun month(d: LocalDate): Long = d.monthValue.toLong()
    @JvmStatic fun day(d: LocalDate): Long   = d.dayOfMonth.toLong()

    // ── Arithmetic ────────────────────────────────────────────────────────

    @JvmStatic fun addDays(d: LocalDate, n: Long): LocalDate    = d.plusDays(n)
    @JvmStatic fun addMonths(d: LocalDate, n: Long): LocalDate  = d.plusMonths(n)
    @JvmStatic fun addYears(d: LocalDate, n: Long): LocalDate   = d.plusYears(n)

    /** Number of days between two dates (positive when [end] is after [start]). */
    @JvmStatic fun dateDiff(start: LocalDate, end: LocalDate): Long =
        ChronoUnit.DAYS.between(start, end)

    // ── Formatting / Parsing ──────────────────────────────────────────────

    /** Format [d] using a [pattern] string (e.g. "yyyy-MM-dd"). */
    @JvmStatic fun formatDate(d: LocalDate, pattern: String): String =
        d.format(DateTimeFormatter.ofPattern(pattern))

    /** Parse a date string with the given [pattern]. */
    @JvmStatic fun parseDate(s: String, pattern: String): LocalDate =
        LocalDate.parse(s, DateTimeFormatter.ofPattern(pattern))

    /** ISO 8601 formatting (e.g. "2026-05-27"). */
    @JvmStatic fun isoDate(d: LocalDate): String  = d.format(DateTimeFormatter.ISO_LOCAL_DATE)

    // ── Comparison helpers ────────────────────────────────────────────────

    @JvmStatic fun isBefore(a: LocalDate, b: LocalDate): Boolean = a.isBefore(b)
    @JvmStatic fun isAfter(a: LocalDate, b: LocalDate): Boolean  = a.isAfter(b)
    @JvmStatic fun isEqual(a: LocalDate, b: LocalDate): Boolean  = a.isEqual(b)
}
