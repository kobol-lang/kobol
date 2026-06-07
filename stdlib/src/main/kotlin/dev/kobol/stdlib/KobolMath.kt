package dev.kobol.stdlib

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * kobol.math — Mathematical functions callable from Kobol programs.
 *
 * All functions are @JvmStatic so the ASM emitter can invoke them with
 * INVOKESTATIC dev/kobol/stdlib/KobolMath <name> <descriptor>.
 *
 * Numeric arguments are accepted as Long (INTEGER), Int (SMALLINT), or
 * BigDecimal (DECIMAL / MONEY); overloads cover both cases.
 */
object KobolMath {

    // ── Absolute value ────────────────────────────────────────────────────

    @JvmStatic fun abs(n: Long): Long           = kotlin.math.abs(n)
    @JvmStatic fun abs(n: Int): Int             = kotlin.math.abs(n)
    @JvmStatic fun abs(n: BigDecimal): BigDecimal = n.abs()

    // ── Rounding ──────────────────────────────────────────────────────────

    /** Round [n] to [scale] decimal places using HALF_EVEN (banker's rounding). */
    @JvmStatic @JvmOverloads
    fun round(n: BigDecimal, scale: Int = 0): BigDecimal =
        n.setScale(scale, RoundingMode.HALF_EVEN)

    /**
     * Round [n] to [scale] decimal places using the specified [mode].
     * Accepted modes (case-insensitive): HALF-UP, HALF-DOWN, HALF-EVEN,
     * UP, DOWN, CEILING, FLOOR, UNNECESSARY.
     */
    @JvmStatic fun roundWithMode(n: BigDecimal, scale: Int, mode: String): BigDecimal {
        val rm = when (mode.uppercase()) {
            "HALF-UP"     -> RoundingMode.HALF_UP
            "HALF-DOWN"   -> RoundingMode.HALF_DOWN
            "HALF-EVEN"   -> RoundingMode.HALF_EVEN
            "UP"          -> RoundingMode.UP
            "DOWN"        -> RoundingMode.DOWN
            "CEILING"     -> RoundingMode.CEILING
            "FLOOR"       -> RoundingMode.FLOOR
            "UNNECESSARY" -> RoundingMode.UNNECESSARY
            else -> throw IllegalArgumentException("Unknown rounding mode: $mode")
        }
        return n.setScale(scale, rm)
    }

    /** Truncate (round toward zero) to [scale] decimal places. */
    @JvmStatic @JvmOverloads
    fun truncate(n: BigDecimal, scale: Int = 0): BigDecimal =
        n.setScale(scale, RoundingMode.DOWN)

    // ── Min / Max ─────────────────────────────────────────────────────────

    @JvmStatic fun max(a: Long, b: Long): Long               = maxOf(a, b)
    @JvmStatic fun max(a: BigDecimal, b: BigDecimal): BigDecimal = a.max(b)
    @JvmStatic fun min(a: Long, b: Long): Long               = minOf(a, b)
    @JvmStatic fun min(a: BigDecimal, b: BigDecimal): BigDecimal = a.min(b)

    // ── Modulo ────────────────────────────────────────────────────────────

    @JvmStatic fun mod(a: Long, b: Long): Long               = a % b
    @JvmStatic fun mod(a: BigDecimal, b: BigDecimal): BigDecimal = a.remainder(b)

    // ── Power ─────────────────────────────────────────────────────────────

    @JvmStatic fun power(base: Long, exp: Long): Long {
        require(exp >= 0) { "Negative exponent not supported for INTEGER POWER" }
        var result = 1L
        repeat(exp.toInt()) { result *= base }
        return result
    }

    @JvmStatic @JvmOverloads
    fun power(base: BigDecimal, exp: Int, mc: MathContext = MathContext.DECIMAL128): BigDecimal =
        base.pow(exp, mc)

    // ── Square root ───────────────────────────────────────────────────────

    @JvmStatic @JvmOverloads
    fun sqrt(n: BigDecimal, mc: MathContext = MathContext.DECIMAL128): BigDecimal =
        n.sqrt(mc)

    // ── Floor / Ceiling ───────────────────────────────────────────────────

    @JvmStatic fun floor(n: BigDecimal): BigDecimal = n.setScale(0, RoundingMode.FLOOR)
    @JvmStatic fun ceil(n: BigDecimal): BigDecimal  = n.setScale(0, RoundingMode.CEILING)

    // ── Sign ──────────────────────────────────────────────────────────────

    // Kobol INTEGER is a JVM `long`, so SIGN returns Long (not Int) — the result is
    // stored directly into an INTEGER slot with no widening (#v8: the Int overload
    // produced an `int` that the verifier rejected in a `long_2nd` slot).
    @JvmStatic fun sign(n: Long): Long       = n.compareTo(0L).toLong()
    @JvmStatic fun sign(n: BigDecimal): Long = n.signum().toLong()

    // ── Rounding — Long-scale overloads (Kobol INTEGER is JVM long) ──────

    /**
     * Round [n] to [scale] decimal places using HALF_EVEN (banker's rounding).
     * The [scale] parameter accepts a Kobol INTEGER (JVM long).
     */
    @JvmStatic fun round(n: BigDecimal, scale: Long): BigDecimal =
        n.setScale(scale.toInt(), RoundingMode.HALF_EVEN)

    /**
     * Round [n] to [scale] decimal places using the named [mode].
     * The [scale] parameter accepts a Kobol INTEGER (JVM long).
     */
    @JvmStatic fun roundWithMode(n: BigDecimal, scale: Long, mode: String): BigDecimal =
        roundWithMode(n, scale.toInt(), mode)

    /** Truncate to [scale] decimal places; [scale] is a Kobol INTEGER (JVM long). */
    @JvmStatic fun truncate(n: BigDecimal, scale: Long): BigDecimal =
        n.setScale(scale.toInt(), RoundingMode.DOWN)

    // ── Exact aggregate sum ───────────────────────────────────────────────

    /**
     * Exact sum of a list of DECIMAL/MONEY values using BigDecimal addition,
     * avoiding the precision loss of floating-point summation.
     */
    @JvmStatic fun exactSum(list: List<BigDecimal>): BigDecimal =
        list.fold(BigDecimal.ZERO) { acc, x -> acc.add(x) }

    /**
     * Exact sum of a list of INTEGER values (Kobol INTEGER = JVM Long).
     */
    @JvmStatic fun sumLong(list: List<Long>): Long = list.sumOf { it }

    // ── Scale / Precision inspection ──────────────────────────────────────

    /** Returns the scale (number of fractional digits) of a DECIMAL/MONEY value. */
    @JvmStatic fun scaleOf(n: BigDecimal): Long = n.scale().toLong()

    /** Returns the total number of significant digits of a DECIMAL/MONEY value. */
    @JvmStatic fun precisionOf(n: BigDecimal): Long = n.precision().toLong()

    // ── Sign predicates ───────────────────────────────────────────────────

    @JvmStatic fun isPositive(n: Long): Boolean       = n > 0L
    @JvmStatic fun isPositive(n: BigDecimal): Boolean = n.signum() > 0

    @JvmStatic fun isNegative(n: Long): Boolean       = n < 0L
    @JvmStatic fun isNegative(n: BigDecimal): Boolean = n.signum() < 0

    @JvmStatic fun isZero(n: Long): Boolean           = n == 0L
    @JvmStatic fun isZero(n: BigDecimal): Boolean     = n.signum() == 0
}
