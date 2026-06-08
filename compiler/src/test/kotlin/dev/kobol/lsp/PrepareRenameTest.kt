package dev.kobol.lsp

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull

/** Verifies the renameable-word range computation backing prepareRename (#F8). */
class PrepareRenameTest {

    private val svc = KobolTextDocumentService(KobolLanguageServer())

    @Test fun `spans the whole identifier under the cursor`() {
        val lines = listOf("  MOVE grand-total TO x")
        // cursor anywhere inside 'grand-total' (starts at col 7)
        val r = svc.wordRangeAt(lines, 0, 10)
        assertNotNull(r)
        assertEquals("grand-total", lines[0].substring(r.start.character, r.end.character))
    }

    @Test fun `returns null on whitespace`() {
        assertNull(svc.wordRangeAt(listOf("  MOVE x"), 0, 0))
    }

    @Test fun `returns null past end of short line`() {
        assertNull(svc.wordRangeAt(listOf(""), 0, 5))
    }
}
