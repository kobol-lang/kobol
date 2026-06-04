package dev.kobol.stdlib

import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KobolMathTest {

    // ── abs ──────────────────────────────────────────────────────────────

    @Test fun `abs of positive long is unchanged`()  = assertEquals(5L,  KobolMath.abs(5L))
    @Test fun `abs of negative long is negated`()    = assertEquals(5L,  KobolMath.abs(-5L))
    @Test fun `abs of negative decimal`()            = assertEquals(bd("3.14"), KobolMath.abs(bd("-3.14")))

    // ── round / truncate ─────────────────────────────────────────────────

    @Test fun `round half-even to 2 places`() {
        // HALF_EVEN (banker's rounding): tie-breaks round toward even last digit
        assertEquals(bd("2.54"), KobolMath.round(bd("2.545"), 2))  // 4 is even → round down
        assertEquals(bd("2.54"), KobolMath.round(bd("2.535"), 2))  // 3 is odd  → round up
        assertEquals(bd("2.56"), KobolMath.round(bd("2.565"), 2))  // 6 is even → round down
        assertEquals(bd("2"),    KobolMath.round(bd("2.5"),   0))  // 2 is even → round down
    }

    @Test fun `truncate drops fractional digits`() {
        assertEquals(bd("3.14"), KobolMath.truncate(bd("3.149"), 2))
        assertEquals(bd("3"),    KobolMath.truncate(bd("3.9"),   0))
    }

    // ── floor / ceil ─────────────────────────────────────────────────────

    @Test fun `floor rounds toward negative infinity`() {
        assertEquals(bd("3"),  KobolMath.floor(bd("3.7")))
        assertEquals(bd("-4"), KobolMath.floor(bd("-3.1")))
    }

    @Test fun `ceil rounds toward positive infinity`() {
        assertEquals(bd("4"),  KobolMath.ceil(bd("3.1")))
        assertEquals(bd("-3"), KobolMath.ceil(bd("-3.9")))
    }

    // ── max / min ────────────────────────────────────────────────────────

    @Test fun `max of two longs`()       = assertEquals(10L, KobolMath.max(3L, 10L))
    @Test fun `min of two longs`()       = assertEquals(3L,  KobolMath.min(3L, 10L))
    @Test fun `max of two decimals`()    = assertEquals(bd("9.9"), KobolMath.max(bd("1.1"), bd("9.9")))
    @Test fun `min of two decimals`()    = assertEquals(bd("1.1"), KobolMath.min(bd("1.1"), bd("9.9")))

    // ── mod ──────────────────────────────────────────────────────────────

    @Test fun `mod of longs`()           = assertEquals(1L, KobolMath.mod(10L, 3L))

    // ── power ────────────────────────────────────────────────────────────

    @Test fun `power of longs`()         = assertEquals(8L, KobolMath.power(2L, 3L))
    @Test fun `power zero exponent`()    = assertEquals(1L, KobolMath.power(99L, 0L))

    // ── sqrt ─────────────────────────────────────────────────────────────

    @Test fun `sqrt of 4`() {
        val r = KobolMath.sqrt(bd("4"))
        assertEquals(0, r.compareTo(bd("2")))
    }

    // ── sign ─────────────────────────────────────────────────────────────

    @Test fun `sign of positive`()       = assertEquals(1,  KobolMath.sign(7L))
    @Test fun `sign of zero`()           = assertEquals(0,  KobolMath.sign(0L))
    @Test fun `sign of negative`()       = assertEquals(-1, KobolMath.sign(-3L))

    // ── Phase 14: Long-scale overloads ──────────────────────────────────────

    @Test fun `round with Long scale uses HALF-EVEN`() =
        assertEquals(bd("2.54"), KobolMath.round(bd("2.545"), 2L))

    @Test fun `roundWithMode HALF-UP rounds up at midpoint`() =
        assertEquals(bd("2.5"), KobolMath.roundWithMode(bd("2.45"), 1L, "HALF-UP"))

    @Test fun `roundWithMode HALF-DOWN rounds down at midpoint`() =
        assertEquals(bd("2.4"), KobolMath.roundWithMode(bd("2.45"), 1L, "HALF-DOWN"))

    @Test fun `roundWithMode UP rounds away from zero`() =
        assertEquals(bd("2.5"), KobolMath.roundWithMode(bd("2.41"), 1L, "UP"))

    @Test fun `roundWithMode DOWN truncates`() =
        assertEquals(bd("2.4"), KobolMath.roundWithMode(bd("2.49"), 1L, "DOWN"))

    @Test fun `roundWithMode CEILING rounds toward positive infinity`() =
        assertEquals(bd("2.5"), KobolMath.roundWithMode(bd("2.41"), 1L, "CEILING"))

    @Test fun `roundWithMode FLOOR rounds toward negative infinity`() =
        assertEquals(bd("-2.5"), KobolMath.roundWithMode(bd("-2.41"), 1L, "FLOOR"))

    // ── Phase 14: exact aggregate sums ──────────────────────────────────────

    @Test fun `exactSum of non-empty list`() =
        assertEquals(bd("30.00"), KobolMath.exactSum(listOf(bd("10.00"), bd("20.00"))))

    @Test fun `exactSum of empty list returns zero`() =
        assertEquals(bd("0"), KobolMath.exactSum(emptyList()))

    @Test fun `sumLong sums list of longs`() =
        assertEquals(60L, KobolMath.sumLong(listOf(10L, 20L, 30L)))

    @Test fun `sumLong of empty list is zero`() =
        assertEquals(0L, KobolMath.sumLong(emptyList()))

    // ── Phase 14: scale/precision inspection ────────────────────────────────

    @Test fun `scaleOf returns number of fractional digits`() =
        assertEquals(2L, KobolMath.scaleOf(bd("12.34")))

    @Test fun `scaleOf integer value is zero`() =
        assertEquals(0L, KobolMath.scaleOf(bd("42")))

    @Test fun `precisionOf counts significant digits`() =
        assertEquals(4L, KobolMath.precisionOf(bd("12.34")))

    private fun bd(s: String) = BigDecimal(s)
}
