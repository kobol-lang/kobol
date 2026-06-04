package dev.kobol.parser

import dev.kobol.lexer.Lexer
import dev.kobol.parser.ast.*
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Parser tests for structured logging, CONCURRENT blocks, and parallel iteration.
 */
class ConcurrencyLoggingParserTest {

    private fun parse(src: String): Program {
        val tokens = Lexer(src, "test.kbl").tokenize()
        return Parser(tokens, "test.kbl").parseProgram()
    }

    // -------------------------------------------------------------------------
    // LOG statement
    // -------------------------------------------------------------------------

    @Test fun `LOG INFO with literal`() {
        val p = parse("""
            PROGRAM T
            PROCEDURE Main:
              LOG INFO "Server started"
            END-PROCEDURE
        """.trimIndent())
        val stmt = p.procedures[0].body[0]
        assertIs<LogStatement>(stmt)
        assertEquals(LogLevel.INFO, stmt.level)
        assertIs<Literal>(stmt.message)
        assertEquals("Server started", (stmt.message as Literal).value)
        assertTrue(stmt.kvPairs.isEmpty())
    }

    @Test fun `LOG WARN with identifier`() {
        val p = parse("""
            PROGRAM T
            DATA:
              msg : TEXT = "oops"
            PROCEDURE Main:
              LOG WARN msg
            END-PROCEDURE
        """.trimIndent())
        val stmt = p.procedures[0].body[0]
        assertIs<LogStatement>(stmt)
        assertEquals(LogLevel.WARN, stmt.level)
        assertIs<Reference>(stmt.message)
    }

    @Test fun `LOG ERROR with structured kv pairs`() {
        val p = parse("""
            PROGRAM T
            DATA:
              user-id : INTEGER = 42
            PROCEDURE Main:
              LOG ERROR "Authentication failed" WITH
                USERID: user-id
                ACTION: "login"
            END-PROCEDURE
        """.trimIndent())
        val stmt = p.procedures[0].body[0]
        assertIs<LogStatement>(stmt)
        assertEquals(LogLevel.ERROR, stmt.level)
        assertEquals(2, stmt.kvPairs.size)
        assertEquals("USERID", stmt.kvPairs[0].key)
        assertEquals("ACTION", stmt.kvPairs[1].key)
    }

    @Test fun `LOG TRACE and LOG DEBUG levels`() {
        val p = parse("""
            PROGRAM T
            PROCEDURE Main:
              LOG TRACE "trace msg"
              LOG DEBUG "debug msg"
            END-PROCEDURE
        """.trimIndent())
        val body = p.procedures[0].body
        assertEquals(LogLevel.TRACE, (body[0] as LogStatement).level)
        assertEquals(LogLevel.DEBUG, (body[1] as LogStatement).level)
    }

    // -------------------------------------------------------------------------
    // CONCURRENT block
    // -------------------------------------------------------------------------

    @Test fun `CONCURRENT block with two branches`() {
        val p = parse("""
            PROGRAM T
            PROCEDURE DoA:
              DISPLAY "A"
            END-PROCEDURE
            PROCEDURE DoB:
              DISPLAY "B"
            END-PROCEDURE
            PROCEDURE Main:
              CONCURRENT:
                PERFORM DoA
                PERFORM DoB
              END-CONCURRENT
            END-PROCEDURE
        """.trimIndent())
        val stmt = p.procedures.last().body[0]
        assertIs<ConcurrentBlock>(stmt)
        assertEquals(2, stmt.branches.size)
    }

    @Test fun `CONCURRENT block fail mode defaults to WAIT_ALL`() {
        val p = parse("""
            PROGRAM T
            PROCEDURE Main:
              CONCURRENT:
                DISPLAY "x"
              END-CONCURRENT
            END-PROCEDURE
        """.trimIndent())
        val stmt = p.procedures[0].body[0] as ConcurrentBlock
        assertEquals(ConcurrentFailMode.WAIT_ALL, stmt.failMode)
    }

    // -------------------------------------------------------------------------
    // PARALLEL FOR EACH
    // -------------------------------------------------------------------------

    @Test fun `PARALLEL FOR EACH basic`() {
        val p = parse("""
            PROGRAM T
            DATA:
              items : LIST OF TEXT
            PROCEDURE Main:
              FOR EACH item IN items PARALLEL:
                DISPLAY item
              END-FOR
            END-PROCEDURE
        """.trimIndent())
        val stmt = p.procedures[0].body[0] as ParallelForEachStatement
        assertEquals("ITEM", stmt.variable)
        assertIs<Reference>(stmt.iterable)
        assertEquals("ITEMS", (stmt.iterable as Reference).name)
    }

    @Test fun `PARALLEL FOR EACH with MAX-THREADS`() {
        val p = parse("""
            PROGRAM T
            DATA:
              nums : LIST OF INTEGER
            PROCEDURE Main:
              FOR EACH n IN nums PARALLEL MAX-THREADS 4:
                DISPLAY n
              END-FOR
            END-PROCEDURE
        """.trimIndent())
        val stmt = p.procedures[0].body[0] as ParallelForEachStatement
        assertEquals(4L, (stmt.maxThreads as Literal).value)
    }
}
