package dev.kobol.lsp

import org.junit.jupiter.api.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals

/** Verifies the shared depth-indent computation (#F14) used by formatting + onTypeFormatting. */
class IndentFormattingTest {

    private val svc = KobolTextDocumentService(KobolLanguageServer())

    @Test fun `nested blocks are re-indented to their depth`() {
        val lines = listOf(
            "PROCEDURE Main:",
            "DISPLAY \"hi\"",        // should indent to depth 1
            "IF x:",                 // depth 1
            "DISPLAY \"y\"",         // depth 2
            "END-IF",                // back to depth 1
            "END-PROCEDURE",         // depth 0
        )
        val edits = svc.computeIndentEdits(lines, 2)
        // line 1 (DISPLAY) needs 2 spaces, line 3 (inner DISPLAY) needs 4.
        val byLine = edits.associate { it.range.start.line to it.newText }
        assertEquals("  ", byLine[1], "first DISPLAY should be indented 2 spaces")
        assertEquals("    ", byLine[3], "inner DISPLAY should be indented 4 spaces")
        // END-PROCEDURE should be at column 0 (no edit needed, already 0).
        assertTrue(byLine[5] == null || byLine[5] == "", "END-PROCEDURE stays at depth 0")
    }

    @Test fun `already-correct lines produce no edits`() {
        val lines = listOf("PROCEDURE Main:", "  STOP RUN", "END-PROCEDURE")
        assertTrue(svc.computeIndentEdits(lines, 2).isEmpty())
    }
}
