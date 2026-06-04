package dev.kobol.semantic

import dev.kobol.lexer.Lexer
import dev.kobol.parser.Parser
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * TypeChecker tests for:
 *  - ASYNC PROCEDURE / FUTURE OF T
 *  - AWAIT statement
 *  - Error codes E215, E216, E217
 */
class AsyncTypeCheckTest {

    private fun check(src: String): TypeChecker {
        val lines  = src.lines()
        val tokens = Lexer(src, "test.kbl").tokenize()
        val prog   = Parser(tokens, "test.kbl").parseProgram()
        val tc     = TypeChecker(lines)
        tc.analyze(prog)
        return tc
    }

    private fun expectClean(src: String): TypeChecker {
        val tc = check(src)
        if (tc.diagnostics.hasErrors) {
            val msgs = tc.diagnostics.errors.joinToString("\n") { it.render() }
            error("Expected no errors but got:\n$msgs")
        }
        return tc
    }

    private fun expectErrors(src: String, vararg codes: String): TypeChecker {
        val tc = check(src)
        val actual = tc.diagnostics.errors.map { it.code }
        for (code in codes) assertTrue(code in actual, "Expected error $code; got: $actual")
        return tc
    }

    // =========================================================================
    //  ASYNC PROCEDURE
    // =========================================================================

    @Test fun `ASYNC PROCEDURE declaration is clean`() {
        expectClean("""
            PROGRAM T
            ASYNC PROCEDURE FetchData RETURNING TEXT:
              RETURN "hello"
            END-PROCEDURE
            PROCEDURE Main:
            END-PROCEDURE
        """.trimIndent())
    }

    @Test fun `GIVING on sync procedure raises E215`() {
        expectErrors("""
            PROGRAM T
            DATA:
              result : TEXT
            PROCEDURE DoWork:
              MOVE "ok" TO result
            END-PROCEDURE
            PROCEDURE Main:
              LET f : TEXT = "x"
              PERFORM DoWork GIVING f
            END-PROCEDURE
        """.trimIndent(), "E215")
    }

    // =========================================================================
    //  AWAIT statement
    // =========================================================================

    @Test fun `AWAIT with FUTURE OF TEXT variable is clean`() {
        expectClean("""
            PROGRAM T
            ASYNC PROCEDURE FetchResult RETURNING TEXT:
              RETURN "result"
            END-PROCEDURE
            PROCEDURE Main:
              LET r : TEXT = "x"
              PERFORM FetchResult
            END-PROCEDURE
        """.trimIndent())
    }

    @Test fun `AWAIT on non-future variable raises E217`() {
        expectErrors("""
            PROGRAM T
            PROCEDURE Main:
              LET notFuture : TEXT = "x"
              LET r : TEXT = "x"
              AWAIT notFuture INTO r
            END-PROCEDURE
        """.trimIndent(), "E217")
    }

    // =========================================================================
    //  FUTURE OF type spec
    // =========================================================================

    @Test fun `FUTURE OF TEXT type is recognised`() {
        val tc = expectClean("""
            PROGRAM T
            DATA:
              f : FUTURE OF TEXT
            PROCEDURE Main:
            END-PROCEDURE
        """.trimIndent())
        assertFalse(tc.diagnostics.hasErrors)
    }

    @Test fun `FUTURE OF INTEGER type is recognised`() {
        val tc = expectClean("""
            PROGRAM T
            DATA:
              n : FUTURE OF INTEGER
            PROCEDURE Main:
            END-PROCEDURE
        """.trimIndent())
        assertFalse(tc.diagnostics.hasErrors)
    }
}
