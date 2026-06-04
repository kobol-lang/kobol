package dev.kobol.semantic

import dev.kobol.lexer.Lexer
import dev.kobol.parser.Parser
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * TypeChecker tests for structured logging, concurrency, validation, variants, and configuration.
 */
class ConcurrencySecurityTypeCheckTest {

    private fun check(src: String): TypeChecker {
        val lines   = src.lines()
        val tokens  = Lexer(src, "test.kbl").tokenize()
        val program = Parser(tokens, "test.kbl").parseProgram()
        val tc      = TypeChecker(lines)
        tc.analyze(program)
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
        for (code in codes) assertTrue(code in actual, "Expected error $code; got $actual")
        return tc
    }

    // =========================================================================
    //  Structured Logging — LOG
    // =========================================================================

    @Test fun `LOG INFO with literal is clean`() {
        expectClean("""
            PROGRAM T
            PROCEDURE Main:
              LOG INFO "Server started on port 8080"
            END-PROCEDURE
        """.trimIndent())
    }

    @Test fun `LOG WARN with field reference is clean`() {
        expectClean("""
            PROGRAM T
            DATA:
              msg : TEXT = "caution"
            PROCEDURE Main:
              LOG WARN msg
            END-PROCEDURE
        """.trimIndent())
    }

    @Test fun `LOG with structured kv pairs is clean`() {
        expectClean("""
            PROGRAM T
            DATA:
              count : INTEGER = 0
            PROCEDURE Main:
              LOG INFO "Processed" WITH
                records: count
            END-PROCEDURE
        """.trimIndent())
    }

    // =========================================================================
    //  Concurrency — CONCURRENT
    // =========================================================================

    @Test fun `CONCURRENT block with two branches is clean`() {
        expectClean("""
            PROGRAM T
            PROCEDURE DoA:
              DISPLAY "a"
            END-PROCEDURE
            PROCEDURE DoB:
              DISPLAY "b"
            END-PROCEDURE
            PROCEDURE Main:
              CONCURRENT:
                PERFORM DoA
                PERFORM DoB
              END-CONCURRENT
            END-PROCEDURE
        """.trimIndent())
    }

    // =========================================================================
    //  Security — TEXT SENSITIVE
    // =========================================================================

    @Test fun `TEXT SENSITIVE field is clean`() {
        expectClean("""
            PROGRAM T
            DATA:
              password : TEXT SENSITIVE
            PROCEDURE Main:
              STOP RUN
            END-PROCEDURE
        """.trimIndent())
    }

    // =========================================================================
    //  Configuration — CONFIG
    // =========================================================================

    @Test fun `CONFIG section registers items as variables`() {
        expectClean("""
            PROGRAM T
            CONFIG:
              db-url : TEXT FROM ENV "DATABASE_URL" REQUIRED
            END-CONFIG
            PROCEDURE Main:
              DISPLAY db-url
            END-PROCEDURE
        """.trimIndent())
    }

    @Test fun `CONFIG item with DEFAULT integer is clean`() {
        expectClean("""
            PROGRAM T
            CONFIG:
              port : INTEGER FROM ENV "PORT" DEFAULT 8080
            END-CONFIG
            PROCEDURE Main:
              DISPLAY port
            END-PROCEDURE
        """.trimIndent())
    }

    // =========================================================================
    //  Validation — VALIDATE
    // =========================================================================

    @Test fun `VALIDATE MUST NOT BE EMPTY on TEXT is clean`() {
        expectClean("""
            PROGRAM T
            DATA:
              name : TEXT
            PROCEDURE Main:
              VALIDATE name:
                MUST NOT BE EMPTY
              END-VALIDATE
            END-PROCEDURE
        """.trimIndent())
    }

    @Test fun `VALIDATE MUST MATCH on TEXT is clean`() {
        expectClean("""
            PROGRAM T
            DATA:
              email : TEXT
            PROCEDURE Main:
              VALIDATE email:
                MUST MATCH "[a-z]+@[a-z]+"
              END-VALIDATE
            END-PROCEDURE
        """.trimIndent())
    }

    @Test fun `VALIDATE MUST BE greater-than on INTEGER is clean`() {
        expectClean("""
            PROGRAM T
            DATA:
              age : INTEGER
            PROCEDURE Main:
              VALIDATE age:
                MUST BE >= 0
              END-VALIDATE
            END-PROCEDURE
        """.trimIndent())
    }

    @Test fun `VALIDATE MUST LENGTH on TEXT is clean`() {
        expectClean("""
            PROGRAM T
            DATA:
              pin : TEXT
            PROCEDURE Main:
              VALIDATE pin:
                MUST LENGTH >= 4
              END-VALIDATE
            END-PROCEDURE
        """.trimIndent())
    }

    // =========================================================================
    //  Variant Types — VARIANT + MATCH
    // =========================================================================

    @Test fun `VARIANT declaration and MATCH exhaustive is clean`() {
        expectClean("""
            PROGRAM T
            VARIANT Status IS Active | Inactive END-VARIANT
            DATA:
              s : Status
            PROCEDURE Main:
              MATCH s:
                WHEN ACTIVE:
                  DISPLAY "on"
                WHEN INACTIVE:
                  DISPLAY "off"
              END-MATCH
            END-PROCEDURE
        """.trimIndent())
    }

    @Test fun `MATCH non-exhaustive raises E021`() {
        expectErrors("""
            PROGRAM T
            VARIANT Status IS Active | Inactive END-VARIANT
            DATA:
              s : Status
            PROCEDURE Main:
              MATCH s:
                WHEN ACTIVE:
                  DISPLAY "on"
              END-MATCH
            END-PROCEDURE
        """.trimIndent(), "E021")
    }

    @Test fun `MATCH with OTHERWISE satisfies exhaustiveness`() {
        expectClean("""
            PROGRAM T
            VARIANT Status IS Active | Inactive | Pending END-VARIANT
            DATA:
              s : Status
            PROCEDURE Main:
              MATCH s:
                WHEN ACTIVE:
                  DISPLAY "on"
                OTHERWISE:
                  DISPLAY "other"
              END-MATCH
            END-PROCEDURE
        """.trimIndent())
    }

    @Test fun `MATCH unknown case raises E022`() {
        expectErrors("""
            PROGRAM T
            VARIANT Status IS Active END-VARIANT
            DATA:
              s : Status
            PROCEDURE Main:
              MATCH s:
                WHEN UNKNOWNCASE:
                  DISPLAY "?"
              END-MATCH
            END-PROCEDURE
        """.trimIndent(), "E022")
    }
}
