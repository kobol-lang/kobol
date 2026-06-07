package dev.kobol.runtime

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * Runtime support for Kobol DECIMAL and MONEY types.
 *
 * All operations use HALF_EVEN (banker's) rounding by default, which is the
 * standard for financial calculations.
 */
object KobolDecimal {

    val DEFAULT_ROUNDING: RoundingMode = RoundingMode.HALF_EVEN

    /** Parses a string into a BigDecimal safely; throws [KobolException.ConversionException] on failure. */
    fun fromString(s: String): BigDecimal =
        runCatching { BigDecimal(s.trim()) }
            .getOrElse { throw KobolException.ConversionException(s, "DECIMAL") }

    fun fromLong(n: Long): BigDecimal = BigDecimal.valueOf(n)

    fun add(a: BigDecimal, b: BigDecimal, scale: Int): BigDecimal =
        a.add(b).setScale(scale, DEFAULT_ROUNDING)

    fun subtract(a: BigDecimal, b: BigDecimal, scale: Int): BigDecimal =
        a.subtract(b).setScale(scale, DEFAULT_ROUNDING)

    fun multiply(a: BigDecimal, b: BigDecimal, scale: Int): BigDecimal =
        a.multiply(b).setScale(scale, DEFAULT_ROUNDING)

    fun divide(a: BigDecimal, b: BigDecimal, scale: Int): BigDecimal {
        if (b.compareTo(BigDecimal.ZERO) == 0)
            throw KobolException.ApplicationException("Division by zero")
        return a.divide(b, scale, DEFAULT_ROUNDING)
    }

    fun round(value: BigDecimal, scale: Int): BigDecimal =
        value.setScale(scale, DEFAULT_ROUNDING)

    /**
     * Round [value] to [scale] decimal places using the specified [mode].
     * Accepted modes (case-insensitive): HALF-UP, HALF-DOWN, HALF-EVEN,
     * UP, DOWN, CEILING, FLOOR, UNNECESSARY.
     */
    fun roundWithMode(value: BigDecimal, scale: Int, mode: String): BigDecimal {
        val rm = when (mode.uppercase()) {
            "HALF-UP"     -> RoundingMode.HALF_UP
            "HALF-DOWN"   -> RoundingMode.HALF_DOWN
            "HALF-EVEN"   -> RoundingMode.HALF_EVEN
            "UP"          -> RoundingMode.UP
            "DOWN"        -> RoundingMode.DOWN
            "CEILING"     -> RoundingMode.CEILING
            "FLOOR"       -> RoundingMode.FLOOR
            "UNNECESSARY" -> RoundingMode.UNNECESSARY
            else -> throw KobolException.ApplicationException("Unknown rounding mode: $mode")
        }
        return value.setScale(scale, rm)
    }

    fun truncate(value: BigDecimal, scale: Int): BigDecimal =
        value.setScale(scale, RoundingMode.DOWN)

    fun abs(value: BigDecimal): BigDecimal = value.abs()

    fun max(a: BigDecimal, b: BigDecimal): BigDecimal = a.max(b)

    fun min(a: BigDecimal, b: BigDecimal): BigDecimal = a.min(b)

    fun toDisplayString(value: BigDecimal, scale: Int): String =
        value.setScale(scale, DEFAULT_ROUNDING).toPlainString()

    /**
     * Render [value] for DISPLAY / string interpolation at its field's declared [scale] (F18).
     * Zero-pads when the value carries fewer fraction digits than declared — a `DECIMAL(18,8)`
     * holding `0` or `1.5` shows `0.00000000` / `1.50000000`, matching the declared precision.
     *
     * Pad-only: a value that already carries MORE fraction digits than declared is shown in
     * full (never rounded), so DISPLAY can never hide stored precision (priority 4). Because
     * the scale only ever increases here, the no-rounding-mode [BigDecimal.setScale] is exact
     * and cannot throw. Display-only — the stored BigDecimal is untouched. [toPlainString] keeps
     * the output out of scientific notation.
     */
    @JvmStatic
    fun toDisplayPadded(value: BigDecimal, scale: Int): String =
        (if (value.scale() < scale) value.setScale(scale) else value).toPlainString()
}
