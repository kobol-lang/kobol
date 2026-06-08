package dev.kobol.lsp

import dev.kobol.lexer.Lexer
import dev.kobol.parser.Parser
import dev.kobol.semantic.TypeChecker
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies the pure semantic-token computation (#F6): identifiers that resolve
 * to a top-level symbol get classified by kind, encoded in the LSP 5-int delta
 * format.
 */
class SemanticTokensTest {

    private fun tokensFor(src: String): List<Int> {
        val tokens  = Lexer(src, "t.kbl").tokenize()
        val program = Parser(tokens, "t.kbl").parseProgram()
        val checker = TypeChecker(src.lines())
        checker.analyze(program)
        return computeSemanticTokens(src.lines(), checker)
    }

    @Test fun `procedure references are classified as function tokens`() {
        val src = """
            PROGRAM Demo VERSION "1.0"
            PROCEDURE Helper:
              DISPLAY "hi"
            END-PROCEDURE
            PROCEDURE Main:
              PERFORM Helper
              STOP RUN
            END-PROCEDURE
        """.trimIndent()

        val data = tokensFor(src)
        assertTrue(data.isNotEmpty(), "expected semantic tokens for a program with procedures")
        assertEquals(0, data.size % 5, "token data must be a flat 5-ints-per-token stream")

        // Every emitted token's type index must be a valid legend index.
        val typeIndices = data.indices.filter { it % 5 == 3 }.map { data[it] }
        assertTrue(typeIndices.all { it in SEMANTIC_TOKEN_TYPES.indices }, "type index out of legend range")
        assertTrue(
            typeIndices.contains(SEMTOK_FUNCTION),
            "expected at least one function-classified identifier (Helper / Main)",
        )
    }

    @Test fun `record and variant names are classified as struct and enum`() {
        val src = """
            PROGRAM Demo VERSION "1.0"
            RECORD Customer:
              name : TEXT
            END-RECORD
            VARIANT Shape IS
              Circle |
              Square
            END-VARIANT
            DATA:
              c : Customer
            PROCEDURE Main:
              STOP RUN
            END-PROCEDURE
        """.trimIndent()

        val data = tokensFor(src)
        val typeIndices = data.indices.filter { it % 5 == 3 }.map { data[it] }
        assertTrue(typeIndices.contains(SEMTOK_STRUCT), "expected a struct-classified token (Customer)")
        assertTrue(typeIndices.contains(SEMTOK_ENUM), "expected an enum-classified token (Shape)")
    }
}
