package dev.kobol.semantic

import dev.kobol.lexer.Lexer
import dev.kobol.parser.Parser
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Type-checking tests for NoSQL (Groups 13-14) language constructs.
 */
class NoSqlTypeCheckTest {

    private fun analyze(source: String): TypeChecker {
        val lines   = source.lines()
        val tokens  = Lexer(source, "test.kbl").tokenize()
        val program = Parser(tokens, "test.kbl").parseProgram()
        val checker = TypeChecker(lines)
        checker.analyze(program)
        return checker
    }

    private fun checkOk(source: String) {
        val tc = analyze(source)
        if (tc.diagnostics.hasErrors) {
            val msgs = tc.diagnostics.errors.joinToString("\n") { it.render() }
            error("Expected no errors but got:\n$msgs")
        }
    }

    // ── NoSQL CONNECT / DISCONNECT ──────────────────────────────────────────

    @Test fun `NOSQL CONNECT parses and type-checks`() {
        checkOk("""
            PROGRAM nosql-connect
            PROCEDURE main:
                NOSQL CONNECT TO "mongodb://localhost:27017" DATABASE "testdb"
                NOSQL DISCONNECT
            END-PROCEDURE
            END-PROGRAM
        """.trimIndent())
    }

    // ── FIND ────────────────────────────────────────────────────────────────

    @Test fun `FIND IN without WHERE auto-declares giving as list`() {
        checkOk("""
            PROGRAM nosql-find
            PROCEDURE main:
                FIND IN "orders" GIVING result-list
            END-PROCEDURE
            END-PROGRAM
        """.trimIndent())
    }

    @Test fun `FIND ONE IN auto-declares giving as object`() {
        checkOk("""
            PROGRAM nosql-findone
            PROCEDURE main:
                LET user-id : INTEGER = 42
                FIND ONE IN "users" GIVING user-doc
            END-PROCEDURE
            END-PROGRAM
        """.trimIndent())
    }

    // ── COUNT ───────────────────────────────────────────────────────────────

    @Test fun `COUNT IN auto-declares giving as integer`() {
        checkOk("""
            PROGRAM nosql-count
            PROCEDURE main:
                COUNT IN "orders" GIVING total
            END-PROCEDURE
            END-PROGRAM
        """.trimIndent())
    }

    // ── CACHE ───────────────────────────────────────────────────────────────

    @Test fun `CACHE CONNECT and DISCONNECT parse ok`() {
        checkOk("""
            PROGRAM cache-conn
            PROCEDURE main:
                CACHE CONNECT TO "redis://localhost:6379"
                CACHE DISCONNECT
            END-PROCEDURE
            END-PROGRAM
        """.trimIndent())
    }

    @Test fun `CACHE GET auto-declares giving as text`() {
        checkOk("""
            PROGRAM cache-get
            PROCEDURE main:
                CACHE GET "session-key" GIVING session-data
            END-PROCEDURE
            END-PROGRAM
        """.trimIndent())
    }

    @Test fun `CACHE SET without TTL parses ok`() {
        checkOk("""
            PROGRAM cache-set
            PROCEDURE main:
                CACHE SET "session-key" TO "my-value"
            END-PROCEDURE
            END-PROGRAM
        """.trimIndent())
    }

    @Test fun `CACHE SET with EXPIRES IN TTL parses ok`() {
        checkOk("""
            PROGRAM cache-set-ttl
            PROCEDURE main:
                CACHE SET "session-key" TO "my-value" EXPIRES IN 3600 SECONDS
            END-PROCEDURE
            END-PROGRAM
        """.trimIndent())
    }

    @Test fun `CACHE DELETE parses ok`() {
        checkOk("""
            PROGRAM cache-delete
            PROCEDURE main:
                CACHE DELETE "session-key"
            END-PROCEDURE
            END-PROGRAM
        """.trimIndent())
    }

    @Test fun `CACHE EXISTS auto-declares giving as boolean`() {
        checkOk("""
            PROGRAM cache-exists
            PROCEDURE main:
                CACHE EXISTS "session-key" GIVING found
            END-PROCEDURE
            END-PROGRAM
        """.trimIndent())
    }
}

