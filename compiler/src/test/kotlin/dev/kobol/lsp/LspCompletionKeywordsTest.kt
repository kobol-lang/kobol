package dev.kobol.lsp

import dev.kobol.lexer.Lexer
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Drift guard for LSP completion (#F1).
 *
 * [KOBOL_KEYWORDS] is derived from [Lexer.KEYWORDS] (the single source of truth
 * for reserved words) plus a small contextual-clause set. These tests fail if
 * the completion list ever stops offering a reserved word — e.g. if someone
 * reverts to a hand-maintained literal that drifts from the lexer.
 */
class LspCompletionKeywordsTest {

    @Test fun `completion offers every reserved lexer keyword`() {
        val missing = Lexer.KEYWORDS.keys - KOBOL_KEYWORDS.toSet()
        assertTrue(missing.isEmpty(), "Completion KOBOL_KEYWORDS is missing reserved words: $missing")
    }

    @Test fun `completion keyword list has no duplicates`() {
        val dups = KOBOL_KEYWORDS.groupingBy { it }.eachCount().filter { it.value > 1 }
        assertEquals(emptyMap(), dups, "Duplicate completion keywords: ${dups.keys}")
    }
}
