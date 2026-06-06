package dev.kobol.semantic

import dev.kobol.lexer.Lexer
import dev.kobol.parser.Parser
import dev.kobol.parser.ast.Program
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TypeCheckerTest {

    private fun analyze(src: String): TypeChecker {
        val lines   = src.lines()
        val tokens  = Lexer(src, "test.kbl").tokenize()
        val program = Parser(tokens, "test.kbl").parseProgram()
        val checker = TypeChecker(lines)
        checker.analyze(program)
        return checker
    }

    private fun expectClean(src: String): TypeChecker {
        val tc = analyze(src)
        if (tc.diagnostics.hasErrors) {
            val msgs = tc.diagnostics.errors.joinToString("\n") { it.render() }
            error("Expected no errors but got:\n$msgs")
        }
        return tc
    }

    private fun expectErrors(src: String, vararg codes: String): TypeChecker {
        val tc = analyze(src)
        val actual = tc.diagnostics.errors.map { it.code }
        for (code in codes) assertTrue(code in actual, "Expected error $code; got $actual")
        return tc
    }

    // -------------------------------------------------------------------------
    // Clean programs
    // -------------------------------------------------------------------------

    @Test fun `hello world is clean`() {
        expectClean("""
            PROGRAM HelloWorld
            DATA:
              greeting : TEXT = "Hello"
            PROCEDURE Main:
              DISPLAY greeting
              STOP RUN
            END-PROCEDURE
        """.trimIndent())
    }

    // #19: uninitialized temporal types have no implicit default (the spec no longer
    // promises "current date"). Declaration-site warning W019, not a silent null NPE.
    @Test fun `uninitialized DATE warns W019`() {
        val tc = analyze("""
            PROGRAM T
            DATA:
              started : DATE
            PROCEDURE Main:
              DISPLAY "x"
            END-PROCEDURE
        """.trimIndent())
        assertTrue("W019" in tc.diagnostics.warnings.map { it.code }, "expected W019 for uninitialized DATE")
        assertFalse(tc.diagnostics.hasErrors, "W019 is a warning, not an error")
    }

    @Test fun `initialized DATE does not warn W019`() {
        val tc = analyze("""
            PROGRAM T
            DATA:
              started : DATE = TODAY()
            PROCEDURE Main:
              DISPLAY "x"
            END-PROCEDURE
        """.trimIndent())
        assertFalse("W019" in tc.diagnostics.warnings.map { it.code }, "initialized DATE must not warn")
    }

    @Test fun `procedure with parameters is clean`() {
        expectClean("""
            PROGRAM T
            PROCEDURE Greet USING who : TEXT:
              DISPLAY who
            END-PROCEDURE
            PROCEDURE Main:
              PERFORM Greet USING "Alice"
              STOP RUN
            END-PROCEDURE
        """.trimIndent())
    }

    @Test fun `arithmetic is clean`() {
        expectClean("""
            PROGRAM T
            DATA:
              total : DECIMAL(10,2) = 0
              tax   : DECIMAL(10,2) = 0
            PROCEDURE Main:
              ADD tax TO total
              STOP RUN
            END-PROCEDURE
        """.trimIndent())
    }

    @Test fun `record with field access is clean`() {
        expectClean("""
            PROGRAM T
            RECORD Customer:
              name : TEXT
              age  : INTEGER
            END-RECORD
            DATA:
              cust : Customer
            PROCEDURE Main:
              DISPLAY cust
              STOP RUN
            END-PROCEDURE
        """.trimIndent())
    }

    @Test fun `LET type inference infers type from RHS`() {
        val tc = expectClean("""
            PROGRAM T
            PROCEDURE Main:
              LET count = 42
              STOP RUN
            END-PROCEDURE
        """.trimIndent())
        // After analysis, count should be in the scope as INTEGER
        // (we verify via no errors rather than direct symbol inspection)
        assertFalse(tc.diagnostics.hasErrors)
    }

    @Test fun `FOR EACH loop var is scoped`() {
        expectClean("""
            PROGRAM T
            DATA:
              items : LIST OF TEXT
            PROCEDURE Main:
              FOR EACH item IN items:
                DISPLAY item
              END-FOR
              STOP RUN
            END-PROCEDURE
        """.trimIndent())
    }

    @Test fun `TRY with exception handler is clean`() {
        expectClean("""
            PROGRAM T
            PROCEDURE Main:
              TRY:
                STOP RUN
              ON FileError:
                DISPLAY "error"
              END-TRY
            END-PROCEDURE
        """.trimIndent())
    }

    @Test fun `RETURN with matching type is clean`() {
        expectClean("""
            PROGRAM T
            PROCEDURE GetValue RETURNING INTEGER:
              RETURN 42
            END-PROCEDURE
            PROCEDURE Main:
              STOP RUN
            END-PROCEDURE
        """.trimIndent())
    }

    // -------------------------------------------------------------------------
    // Error cases
    // -------------------------------------------------------------------------

    @Test fun `undefined variable emits E001`() {
        expectErrors("""
            PROGRAM T
            PROCEDURE Main:
              DISPLAY unknown-var
              STOP RUN
            END-PROCEDURE
        """.trimIndent(), "E001")
    }

    @Test fun `undefined procedure emits E006`() {
        expectErrors("""
            PROGRAM T
            PROCEDURE Main:
              PERFORM MissingProc
              STOP RUN
            END-PROCEDURE
        """.trimIndent(), "E006")
    }

    @Test fun `wrong argument count emits E008`() {
        expectErrors("""
            PROGRAM T
            PROCEDURE Calculate USING a : INTEGER, b : INTEGER:
              STOP RUN
            END-PROCEDURE
            PROCEDURE Main:
              PERFORM Calculate USING 1
              STOP RUN
            END-PROCEDURE
        """.trimIndent(), "E008")
    }

    @Test fun `RETURN outside returning procedure emits E010`() {
        expectErrors("""
            PROGRAM T
            PROCEDURE Main:
              RETURN 5
              STOP RUN
            END-PROCEDURE
        """.trimIndent(), "E010")
    }

    @Test fun `undefined record in struct literal emits E001`() {
        expectErrors("""
            PROGRAM T
            PROCEDURE Main:
              MOVE NoSuchRecord { x: 1 } TO something
              STOP RUN
            END-PROCEDURE
        """.trimIndent(), "E001")
    }

    @Test fun `did-you-mean is included in error message`() {
        val tc = analyze("""
            PROGRAM T
            DATA:
              invoice-total : DECIMAL(10,2)
            PROCEDURE Main:
              DISPLAY inovice-total
              STOP RUN
            END-PROCEDURE
        """.trimIndent())
        val err = tc.diagnostics.errors.firstOrNull { it.code == "E001" }
        assertTrue(err != null, "Expected E001")
        // "did you mean: invoice-total?" should appear
        assertTrue(err!!.message.contains("invoice", ignoreCase = true),
            "Expected did-you-mean suggestion in: ${err.message}")
    }

    // -------------------------------------------------------------------------
    // Type inference (ER-5)
    // -------------------------------------------------------------------------

    @Test fun `LET infers INTEGER from literal`() {
        val tc = expectClean("""
            PROGRAM T
            PROCEDURE Main:
              LET x = 10
              DISPLAY x
              STOP RUN
            END-PROCEDURE
        """.trimIndent())
        assertFalse(tc.diagnostics.hasErrors)
    }

    @Test fun `LET infers TEXT from string literal`() {
        val tc = expectClean("""
            PROGRAM T
            PROCEDURE Main:
              LET msg = "hello"
              DISPLAY msg
              STOP RUN
            END-PROCEDURE
        """.trimIndent())
        assertFalse(tc.diagnostics.hasErrors)
    }

    // -------------------------------------------------------------------------
    // SLEEP statement (Group 2)
    // -------------------------------------------------------------------------

    @Test fun `SLEEP MILLISECONDS is clean`() = expectClean("""
        PROGRAM T
        DATA:
          delay : INTEGER = 500
        PROCEDURE Main:
          SLEEP delay MILLISECONDS
          STOP RUN
        END-PROCEDURE
    """.trimIndent())

    @Test fun `SLEEP SECONDS literal is clean`() = expectClean("""
        PROGRAM T
        PROCEDURE Main:
          SLEEP 2 SECONDS
          STOP RUN
        END-PROCEDURE
    """.trimIndent())

    @Test fun `SLEEP MINUTES is clean`() = expectClean("""
        PROGRAM T
        PROCEDURE Main:
          SLEEP 1 MINUTES
          STOP RUN
        END-PROCEDURE
    """.trimIndent())

    @Test fun `SLEEP with non-INTEGER raises error`() {
        expectErrors("""
            PROGRAM T
            DATA:
              t : TEXT = "500"
            PROCEDURE Main:
              SLEEP t MILLISECONDS
              STOP RUN
            END-PROCEDURE
        """.trimIndent(), "E101")
    }

    // -------------------------------------------------------------------------
    // DEFINE TYPE aliases (Group 2)
    // -------------------------------------------------------------------------

    @Test fun `DEFINE TYPE alias resolves in DATA section`() = expectClean("""
        PROGRAM T
        DEFINE TYPE Rate IS DECIMAL(18, 8)
        DATA:
          exchange-rate : Rate
        PROCEDURE Main:
          COMPUTE exchange-rate = 1.23456789
          STOP RUN
        END-PROCEDURE
    """.trimIndent())

    @Test fun `DEFINE TYPE alias used as list element type`() = expectClean("""
        PROGRAM T
        DEFINE TYPE ItemCount IS INTEGER
        DATA:
          qty : ItemCount
        PROCEDURE Main:
          COMPUTE qty = 10
          STOP RUN
        END-PROCEDURE
    """.trimIndent())

    // -------------------------------------------------------------------------
    // TEST blocks and ASSERT statement (Group 3)
    // -------------------------------------------------------------------------

    @Test fun `TEST block with ASSERT true literal is clean`() = expectClean("""
        PROGRAM T
        DATA:
          x : INTEGER = 1
        PROCEDURE Main:
          STOP RUN
        END-PROCEDURE
        TEST "one equals one":
          ASSERT x = 1
        END-TEST
    """.trimIndent())

    @Test fun `ASSERT with non-BOOLEAN raises error`() {
        expectErrors("""
            PROGRAM T
            DATA:
              n : INTEGER = 5
            PROCEDURE Main:
              ASSERT n
            END-PROCEDURE
        """.trimIndent(), "E102")
    }

    @Test fun `UUID variable declared and UUID-GENERATE assigned is clean`() = expectClean("""
        PROGRAM T
        DATA:
          id : UUID
        PROCEDURE Main:
          COMPUTE id = UUID-GENERATE()
          STOP RUN
        END-PROCEDURE
    """.trimIndent())

    // IS postfix conditions (Group 4)

    @Test fun `IS POSITIVE used as BOOLEAN expression in IF is clean`() = expectClean("""
        PROGRAM T
        DATA:
          amount : INTEGER = 0
        PROCEDURE Main:
          IF amount IS POSITIVE:
            DISPLAY "positive"
          END-IF
          STOP RUN
        END-PROCEDURE
    """.trimIndent())

    @Test fun `IS NOT ZERO used as BOOLEAN expression is clean`() = expectClean("""
        PROGRAM T
        DATA:
          total : INTEGER = 1
          ok    : BOOLEAN
        PROCEDURE Main:
          COMPUTE ok = total IS NOT ZERO
          STOP RUN
        END-PROCEDURE
    """.trimIndent())

    @Test fun `DISPLAY JSON is clean for TEXT value`() = expectClean("""
        PROGRAM T
        DATA:
          msg : TEXT = "hello"
        PROCEDURE Main:
          DISPLAY JSON msg
          STOP RUN
        END-PROCEDURE
    """.trimIndent())

    @Test fun `named CONDITION used as BOOLEAN in IF is clean`() = expectClean("""
        PROGRAM T
        DATA:
          amount : INTEGER = 50
        CONDITION Is-Valid WHEN amount > 0
        PROCEDURE Main:
          IF Is-Valid:
            DISPLAY "valid"
          END-IF
          STOP RUN
        END-PROCEDURE
    """.trimIndent())

    @Test fun `WRITE JSON PRETTY to file is clean`() = expectClean("""
        PROGRAM T
        DATA:
          msg : TEXT = "hello"
        PROCEDURE Main:
          WRITE JSON msg TO "out.json" PRETTY
          STOP RUN
        END-PROCEDURE
    """.trimIndent())

    @Test fun `WRITE XML to file is clean`() = expectClean("""
        PROGRAM T
        DATA:
          msg : TEXT = "hello"
        PROCEDURE Main:
          WRITE XML msg TO "out.xml"
          STOP RUN
        END-PROCEDURE
    """.trimIndent())

    @Test fun `MATCH range pattern INTEGER is clean`() = expectClean("""
        PROGRAM T
        DATA:
          n : INTEGER = 5
        PROCEDURE Main:
          MATCH n:
            WHEN 1..10:
              DISPLAY "small"
            OTHERWISE:
              DISPLAY "large"
          END-MATCH
          STOP RUN
        END-PROCEDURE
    """.trimIndent())

    @Test fun `WITH PRECISION DECIMAL128 block is clean`() = expectClean("""
        PROGRAM T
        DATA:
          x : DECIMAL(18,8) = 1.5
          y : DECIMAL(18,8) = 0
        PROCEDURE Main:
          WITH PRECISION DECIMAL128:
            COMPUTE y = x * 3
          END-PRECISION
          STOP RUN
        END-PROCEDURE
    """.trimIndent())

    @Test fun `PARSE JSON AS type annotation is clean`() = expectClean("""
        PROGRAM T
        RECORD Invoice FIELDS
          amount : DECIMAL(18,2) = 0
        END-RECORD
        DATA:
          inv  : Invoice
          data : TEXT = "{}"
        PROCEDURE Main:
          PARSE JSON data INTO inv AS Invoice
          STOP RUN
        END-PROCEDURE
    """.trimIndent())

    @Test fun `MATCH type pattern TEXT AS binding is clean`() = expectClean("""
        PROGRAM T
        DATA:
          label : TEXT = "hello"
        PROCEDURE Main:
          MATCH label:
            WHEN TEXT AS s:
              DISPLAY s
            OTHERWISE:
              DISPLAY "?"
          END-MATCH
          STOP RUN
        END-PROCEDURE
    """.trimIndent())

    @Test fun `DIVIDE USING HALF-UP is clean`() = expectClean("""
        PROGRAM T
        DATA:
          total  : DECIMAL(18,2) = 10.0
          count  : DECIMAL(18,2) = 3.0
          result : DECIMAL(18,2) = 0
        PROCEDURE Main:
          DIVIDE total INTO count GIVING result USING HALF-UP
          STOP RUN
        END-PROCEDURE
    """.trimIndent())

    @Test fun `MODULE declaration with EXPORT is clean`() = expectClean("""
        MODULE billing.core VERSION "1.0":
          EXPORT PROCEDURE ApplyDiscount
          EXPORT PROCEDURE CalculateTax
        END-MODULE
        PROGRAM T
        PROCEDURE ApplyDiscount:
          STOP RUN
        END-PROCEDURE
        PROCEDURE CalculateTax:
          STOP RUN
        END-PROCEDURE
    """.trimIndent())

    @Test fun `MODULE EXPORT references non-existent symbol emits E210`() = expectErrors("""
        MODULE billing:
          EXPORT PROCEDURE NonExistent
        END-MODULE
        PROGRAM T
        PROCEDURE Main:
          STOP RUN
        END-PROCEDURE
    """.trimIndent(), "E210")

    @Test fun `IMPORT with VERSION constraint is clean`() = expectClean("""
        IMPORT java.time.LocalDate VERSION "11"
        PROGRAM T
        PROCEDURE Main:
          STOP RUN
        END-PROCEDURE
    """.trimIndent())

    @Test fun `TEST TABLE with COLUMNS and ROW is clean`() = expectClean("""
        PROGRAM T
        DATA:
          result : INTEGER = 0
        PROCEDURE Add USING a : INTEGER, b : INTEGER RETURNING INTEGER:
          RETURN a + b
        END-PROCEDURE
        TEST TABLE "addition table":
          COLUMNS: a, b, expected
          ROW: 1, 2, 3
          ROW: 10, 20, 30
          WHEN:
            COMPUTE result = a + b
          THEN:
            ASSERT result = expected
        END-TEST
    """.trimIndent())

    @Test fun `MOCK statement inside TEST is clean`() = expectClean("""
        PROGRAM T
        DATA:
          rate : INTEGER = 0
        PROCEDURE FetchRate RETURNING INTEGER:
          RETURN 5
        END-PROCEDURE
        TEST "mocked rate":
          MOCK FetchRate RETURNS 99
          COMPUTE rate = FetchRate()
          ASSERT rate = 99
        END-TEST
    """.trimIndent())

    @Test fun `MOCK unknown procedure emits E212`() {
        expectErrors("""
            PROGRAM T
            PROCEDURE Main:
              STOP RUN
            END-PROCEDURE
            TEST "bad mock":
              MOCK DoesNotExist RETURNS 1
              ASSERT TRUE
            END-TEST
        """.trimIndent(), "E212")
    }

    // -------------------------------------------------------------------------
    // Phase 12.2 — MATCH GuardPattern
    // -------------------------------------------------------------------------

    @Test fun `MATCH guard with literal pattern is clean`() {
        expectClean("""
            PROGRAM T
            DATA:
              score : INTEGER = 0
              result : TEXT = ""
            PROCEDURE Main:
              MATCH score:
                WHEN 100 IF score > 0:
                  MOVE "perfect" TO result
                OTHERWISE:
                  MOVE "other" TO result
              END-MATCH
            END-PROCEDURE
        """.trimIndent())
    }

    @Test fun `MATCH guard with range pattern is clean`() {
        expectClean("""
            PROGRAM T
            DATA:
              n : INTEGER = 42
              out : TEXT = ""
            PROCEDURE Main:
              MATCH n:
                WHEN 1..100 IF n > 50:
                  MOVE "high" TO out
                WHEN 1..100:
                  MOVE "low" TO out
                OTHERWISE:
                  MOVE "out" TO out
              END-MATCH
            END-PROCEDURE
        """.trimIndent())
    }

    @Test fun `MATCH guard with non-boolean condition emits E024`() {
        expectErrors("""
            PROGRAM T
            DATA:
              x : INTEGER = 0
            PROCEDURE Main:
              MATCH x:
                WHEN 1 IF x:
                  DISPLAY "hi"
              END-MATCH
            END-PROCEDURE
        """.trimIndent(), "E024")
    }

    // -------------------------------------------------------------------------
    // DEPRECATED attribute
    // -------------------------------------------------------------------------

    @Test fun `PERFORM of deprecated procedure warns W008`() {
        val tc = analyze("""
            PROGRAM DepTest
            PROCEDURE Main:
              PERFORM OldCalc
              STOP RUN
            END-PROCEDURE
            PROCEDURE OldCalc DEPRECATED "use NewCalc instead":
              DISPLAY "old"
            END-PROCEDURE
        """.trimIndent())
        val dep = tc.diagnostics.warnings.firstOrNull { it.code == "W008" }
        assertTrue(dep != null, "Expected W008; got ${tc.diagnostics.warnings.map { it.code }}")
        assertTrue("use NewCalc instead" in dep.message, "message lost: ${dep.message}")
    }

    @Test fun `non-deprecated procedure does not warn`() {
        val tc = analyze("""
            PROGRAM DepTest
            PROCEDURE Main:
              PERFORM DoWork
              STOP RUN
            END-PROCEDURE
            PROCEDURE DoWork:
              DISPLAY "work"
            END-PROCEDURE
        """.trimIndent())
        assertFalse(tc.diagnostics.warnings.any { it.code == "W008" })
    }
}
