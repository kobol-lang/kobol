package dev.kobol.stdlib

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KobolConvertTest {

    // ── toText ───────────────────────────────────────────────────────────

    @Test fun `toText from long`()        = assertEquals("42",     KobolConvert.toText(42L))
    @Test fun `toText from negative`()    = assertEquals("-1",     KobolConvert.toText(-1L))
    @Test fun `toText from boolean true`() = assertEquals("TRUE",  KobolConvert.toText(true))
    @Test fun `toText from boolean false`() = assertEquals("FALSE",KobolConvert.toText(false))
    @Test fun `toText from null`()        = assertEquals("",       KobolConvert.toText(null))

    // ── toInteger ────────────────────────────────────────────────────────

    @Test fun `toInteger from string`()   = assertEquals(123L, KobolConvert.toInteger("123"))
    @Test fun `toInteger trims whitespace`() = assertEquals(7L, KobolConvert.toInteger("  7  "))
    @Test fun `toIntegerOrNull valid`()   = assertEquals(5L, KobolConvert.toIntegerOrNull("5"))
    @Test fun `toIntegerOrNull invalid`() = assertNull(KobolConvert.toIntegerOrNull("abc"))

    // ── toDecimal ────────────────────────────────────────────────────────

    @Test fun `toDecimal from string`() {
        assertEquals(0, KobolConvert.toDecimal("3.14").compareTo(java.math.BigDecimal("3.14")))
    }
    @Test fun `toDecimalOrNull valid`()  = assertNotNull(KobolConvert.toDecimalOrNull("1.5"))
    @Test fun `toDecimalOrNull invalid`() = assertNull(KobolConvert.toDecimalOrNull("not-a-number"))

    // ── toDate ───────────────────────────────────────────────────────────

    @Test fun `toDate from ISO string`() {
        val d = KobolConvert.toDate("2026-05-27")
        assertEquals(java.time.LocalDate.of(2026, 5, 27), d)
    }
    @Test fun `toDate with pattern`() {
        val d = KobolConvert.toDate("27/05/2026", "dd/MM/yyyy")
        assertEquals(java.time.LocalDate.of(2026, 5, 27), d)
    }
    @Test fun `toDateOrNull valid`()  = assertNotNull(KobolConvert.toDateOrNull("2026-01-01"))
    @Test fun `toDateOrNull invalid`() = assertNull(KobolConvert.toDateOrNull("not-a-date"))

    // ── toBoolean ────────────────────────────────────────────────────────

    @Test fun `toBoolean TRUE`()              = assertTrue(KobolConvert.toBoolean("TRUE"))
    @Test fun `toBoolean YES`()               = assertTrue(KobolConvert.toBoolean("YES"))
    @Test fun `toBoolean 1`()                 = assertTrue(KobolConvert.toBoolean("1"))
    @Test fun `toBoolean false string`()      = assertFalse(KobolConvert.toBoolean("FALSE"))
    @Test fun `toBoolean from long non-zero`() = assertTrue(KobolConvert.toBoolean(1L))
    @Test fun `toBoolean from long zero`()    = assertFalse(KobolConvert.toBoolean(0L))
}
