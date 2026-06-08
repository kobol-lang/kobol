package dev.kobol.lsp

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies the shared whole-word occurrence finder (#F7) that backs
 * references / documentHighlight / rename.
 */
class DocumentHighlightTest {

    private val svc = KobolTextDocumentService(KobolLanguageServer())

    @Test fun `finds every whole-word occurrence, case-insensitively`() {
        val lines = listOf(
            "MOVE total TO grand-total",
            "ADD 1 TO total",
            "DISPLAY total-count",   // 'total-count' must NOT match 'total'
        )
        val ranges = svc.findWordOccurrences(lines, "TOTAL")

        // Two real 'total' occurrences (line 0 col 5, line 1 col 9); the
        // 'total' inside 'total-count' is rejected as a partial word.
        assertEquals(2, ranges.size, "expected exactly two whole-word matches, got $ranges")
        assertTrue(ranges.all { it.end.character - it.start.character == 5 }, "ranges must span the token length")
    }

    @Test fun `no occurrences for an absent token`() {
        val ranges = svc.findWordOccurrences(listOf("DISPLAY \"hi\""), "MISSING")
        assertTrue(ranges.isEmpty())
    }
}
