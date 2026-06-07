package dev.kobol.stdlib

import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KobolJsonTest {

    // A stand-in for a compiled Kobol record: a plain JVM object with fields.
    @Suppress("unused")
    private class Invoice(val invoiceId: Long, val amount: BigDecimal, val paid: Boolean)

    // ── compact ──────────────────────────────────────────────────────────

    @Test fun `compact object has no newlines`() {
        val json = KobolJson.toJson(Invoice(7L, BigDecimal("50.00"), false))
        assertEquals("""{"invoiceId":7,"amount":50.00,"paid":false}""", json)
    }

    @Test fun `compact map and list stay single line`() {
        assertEquals("""{"a":1,"b":[1,2]}""",
            KobolJson.toJson(linkedMapOf("a" to 1L, "b" to listOf(1L, 2L))))
    }

    // ── pretty (the #v4 fix) ───────────────────────────────────────────────

    @Test fun `pretty object indents fields — was a no-op before #v4`() {
        val json = KobolJson.toPrettyJson(Invoice(7L, BigDecimal("50.00"), false))
        // Records used to come out compact under PRETTY; now they indent like maps.
        assertEquals(
            "{\n" +
            "  \"invoiceId\": 7,\n" +
            "  \"amount\": 50.00,\n" +
            "  \"paid\": false\n" +
            "}",
            json,
        )
    }

    @Test fun `pretty nested object indents each level`() {
        val nested = linkedMapOf("outer" to Invoice(1L, BigDecimal("2.00"), true))
        val json = KobolJson.toPrettyJson(nested)
        assertEquals(
            "{\n" +
            "  \"outer\": {\n" +
            "    \"invoiceId\": 1,\n" +
            "    \"amount\": 2.00,\n" +
            "    \"paid\": true\n" +
            "  }\n" +
            "}",
            json,
        )
    }

    @Test fun `pretty differs from compact for a record`() {
        val inv = Invoice(7L, BigDecimal("50.00"), false)
        assertTrue(KobolJson.toPrettyJson(inv) != KobolJson.toJson(inv),
            "PRETTY must not equal compact for a record")
    }
}
