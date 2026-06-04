package dev.kobol.stdlib

import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class KobolDateTest {

    private val jan1 = LocalDate.of(2026, 1, 1)
    private val feb1 = LocalDate.of(2026, 2, 1)
    private val dec31 = LocalDate.of(2025, 12, 31)

    // ── today / now ──────────────────────────────────────────────────────

    @Test fun `today returns a LocalDate`()       = assertNotNull(KobolDate.today())
    @Test fun `now returns a LocalDateTime`()     = assertNotNull(KobolDate.now())

    // ── date construction ─────────────────────────────────────────────────

    @Test fun `date builds from parts`() {
        val d = KobolDate.date(2026L, 5L, 27L)
        assertEquals(LocalDate.of(2026, 5, 27), d)
    }

    // ── part extraction ───────────────────────────────────────────────────

    @Test fun `year extracts year`()              = assertEquals(2026L, KobolDate.year(jan1))
    @Test fun `month extracts month`()            = assertEquals(1L,    KobolDate.month(jan1))
    @Test fun `day extracts day`()                = assertEquals(1L,    KobolDate.day(jan1))

    // ── arithmetic ────────────────────────────────────────────────────────

    @Test fun `addDays adds days`()               = assertEquals(LocalDate.of(2026, 1, 8),  KobolDate.addDays(jan1, 7L))
    @Test fun `addMonths adds months`()           = assertEquals(feb1,                      KobolDate.addMonths(jan1, 1L))
    @Test fun `addYears adds years`()             = assertEquals(LocalDate.of(2027, 1, 1),  KobolDate.addYears(jan1, 1L))

    // ── dateDiff ─────────────────────────────────────────────────────────

    @Test fun `dateDiff returns positive when end after start`() = assertEquals(31L, KobolDate.dateDiff(jan1, feb1))
    @Test fun `dateDiff returns negative when end before start`() = assertEquals(-1L, KobolDate.dateDiff(jan1, dec31))

    // ── formatting / parsing ──────────────────────────────────────────────

    @Test fun `formatDate with pattern`() {
        assertEquals("01/01/2026", KobolDate.formatDate(jan1, "MM/dd/yyyy"))
    }

    @Test fun `parseDate round-trips`() {
        val s = "2026-05-27"
        assertEquals(LocalDate.of(2026, 5, 27), KobolDate.parseDate(s, "yyyy-MM-dd"))
    }

    @Test fun `isoDate returns ISO format`() {
        assertEquals("2026-01-01", KobolDate.isoDate(jan1))
    }

    // ── comparison ───────────────────────────────────────────────────────

    @Test fun `isBefore true`()                   = assertTrue(KobolDate.isBefore(dec31, jan1))
    @Test fun `isBefore false`()                  = assertFalse(KobolDate.isBefore(jan1, dec31))
    @Test fun `isAfter true`()                    = assertTrue(KobolDate.isAfter(feb1, jan1))
    @Test fun `isEqual true`()                    = assertTrue(KobolDate.isEqual(jan1, LocalDate.of(2026, 1, 1)))
    @Test fun `isEqual false`()                   = assertFalse(KobolDate.isEqual(jan1, feb1))
}
