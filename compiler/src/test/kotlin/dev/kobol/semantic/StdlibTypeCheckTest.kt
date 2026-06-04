package dev.kobol.semantic

import dev.kobol.lexer.Lexer
import dev.kobol.parser.Parser
import org.junit.jupiter.api.Test

/**
 * Type-checker tests for stdlib builtin calls.
 * Each test verifies that a Kobol snippet using a stdlib function passes
 * semantic analysis without errors.
 */
class StdlibTypeCheckTest {

    private fun expectClean(src: String) {
        val lines   = src.lines()
        val tokens  = Lexer(src, "test.kbl").tokenize()
        val program = Parser(tokens, "test.kbl").parseProgram()
        val checker = TypeChecker(lines)
        checker.analyze(program)
        if (checker.diagnostics.hasErrors) {
            val msgs = checker.diagnostics.errors.joinToString("\n") { it.render() }
            error("Expected no errors but got:\n$msgs")
        }
    }

    // ── KobolText builtins ───────────────────────────────────────────────

    @Test fun `UPPERCASE builtin is clean`() = expectClean("""
        PROGRAM T
        DATA:
          s : TEXT = "hello"
          r : TEXT
        PROCEDURE Main:
          COMPUTE r = UPPERCASE(s)
        END-PROCEDURE
    """.trimIndent())

    @Test fun `LOWERCASE builtin is clean`() = expectClean("""
        PROGRAM T
        DATA:
          s : TEXT = "HELLO"
          r : TEXT
        PROCEDURE Main:
          COMPUTE r = LOWERCASE(s)
        END-PROCEDURE
    """.trimIndent())

    @Test fun `TRIM builtin is clean`() = expectClean("""
        PROGRAM T
        DATA:
          s : TEXT = "  hi  "
          r : TEXT
        PROCEDURE Main:
          COMPUTE r = TRIM(s)
        END-PROCEDURE
    """.trimIndent())

    @Test fun `LENGTH builtin returns INTEGER`() = expectClean("""
        PROGRAM T
        DATA:
          s : TEXT = "hello"
          n : INTEGER
        PROCEDURE Main:
          COMPUTE n = LENGTH(s)
        END-PROCEDURE
    """.trimIndent())

    @Test fun `FIND builtin returns INTEGER`() = expectClean("""
        PROGRAM T
        DATA:
          s : TEXT = "foobar"
          n : INTEGER
        PROCEDURE Main:
          COMPUTE n = FIND(s, "bar")
        END-PROCEDURE
    """.trimIndent())

    @Test fun `CONTAINS builtin returns BOOLEAN`() = expectClean("""
        PROGRAM T
        DATA:
          s : TEXT = "foobar"
          b : BOOLEAN
        PROCEDURE Main:
          COMPUTE b = CONTAINS(s, "foo")
        END-PROCEDURE
    """.trimIndent())

    @Test fun `SUBSTRING builtin is clean`() = expectClean("""
        PROGRAM T
        DATA:
          s : TEXT = "hello world"
          r : TEXT
        PROCEDURE Main:
          COMPUTE r = SUBSTRING(s, 1, 5)
        END-PROCEDURE
    """.trimIndent())

    @Test fun `REPLACE builtin is clean`() = expectClean("""
        PROGRAM T
        DATA:
          s : TEXT = "aaa"
          r : TEXT
        PROCEDURE Main:
          COMPUTE r = REPLACE(s, "a", "b")
        END-PROCEDURE
    """.trimIndent())

    @Test fun `COMBINE builtin is clean`() = expectClean("""
        PROGRAM T
        DATA:
          a : TEXT = "Hello"
          b : TEXT = " World"
          r : TEXT
        PROCEDURE Main:
          COMPUTE r = COMBINE(a, b)
        END-PROCEDURE
    """.trimIndent())

    @Test fun `IS-BLANK builtin returns BOOLEAN`() = expectClean("""
        PROGRAM T
        DATA:
          s : TEXT = "   "
          b : BOOLEAN
        PROCEDURE Main:
          COMPUTE b = IS-BLANK(s)
        END-PROCEDURE
    """.trimIndent())

    // ── KobolMath builtins ───────────────────────────────────────────────

    @Test fun `ABS of INTEGER is clean`() = expectClean("""
        PROGRAM T
        DATA:
          n : INTEGER = -5
          r : INTEGER
        PROCEDURE Main:
          COMPUTE r = ABS(n)
        END-PROCEDURE
    """.trimIndent())

    @Test fun `MAX of two INTEGERs is clean`() = expectClean("""
        PROGRAM T
        DATA:
          a : INTEGER = 3
          b : INTEGER = 7
          r : INTEGER
        PROCEDURE Main:
          COMPUTE r = MAX(a, b)
        END-PROCEDURE
    """.trimIndent())

    @Test fun `MIN of two INTEGERs is clean`() = expectClean("""
        PROGRAM T
        DATA:
          a : INTEGER = 3
          b : INTEGER = 7
          r : INTEGER
        PROCEDURE Main:
          COMPUTE r = MIN(a, b)
        END-PROCEDURE
    """.trimIndent())

    @Test fun `SIGN builtin returns INTEGER`() = expectClean("""
        PROGRAM T
        DATA:
          n : INTEGER = -5
          s : INTEGER
        PROCEDURE Main:
          COMPUTE s = SIGN(n)
        END-PROCEDURE
    """.trimIndent())

    // ── KobolDate builtins ───────────────────────────────────────────────

    @Test fun `TODAY builtin returns DATE`() = expectClean("""
        PROGRAM T
        DATA:
          d : DATE
        PROCEDURE Main:
          COMPUTE d = TODAY()
        END-PROCEDURE
    """.trimIndent())

    @Test fun `ISO-DATE builtin returns TEXT`() = expectClean("""
        PROGRAM T
        DATA:
          d : DATE
          s : TEXT
        PROCEDURE Main:
          COMPUTE d = TODAY()
          COMPUTE s = ISO-DATE(d)
        END-PROCEDURE
    """.trimIndent())

    @Test fun `DATE-DIFF returns INTEGER`() = expectClean("""
        PROGRAM T
        DATA:
          start-date : DATE
          end-date   : DATE
          diff       : INTEGER
        PROCEDURE Main:
          COMPUTE start-date = TODAY()
          COMPUTE end-date   = TODAY()
          COMPUTE diff       = DATE-DIFF(start-date, end-date)
        END-PROCEDURE
    """.trimIndent())

    @Test fun `ADD-DAYS builtin returns DATE`() = expectClean("""
        PROGRAM T
        DATA:
          d    : DATE
          plus : DATE
        PROCEDURE Main:
          COMPUTE d    = TODAY()
          COMPUTE plus = ADD-DAYS(d, 7)
        END-PROCEDURE
    """.trimIndent())

    // ── KobolConvert builtins ────────────────────────────────────────────

    @Test fun `TO-TEXT from INTEGER is clean`() = expectClean("""
        PROGRAM T
        DATA:
          n : INTEGER = 42
          s : TEXT
        PROCEDURE Main:
          COMPUTE s = TO-TEXT(n)
        END-PROCEDURE
    """.trimIndent())

    @Test fun `TO-INTEGER from TEXT is clean`() = expectClean("""
        PROGRAM T
        DATA:
          s : TEXT = "123"
          n : INTEGER
        PROCEDURE Main:
          COMPUTE n = TO-INTEGER(s)
        END-PROCEDURE
    """.trimIndent())

    @Test fun `TO-BOOLEAN from TEXT is clean`() = expectClean("""
        PROGRAM T
        DATA:
          s : TEXT = "TRUE"
          b : BOOLEAN
        PROCEDURE Main:
          COMPUTE b = TO-BOOLEAN(s)
        END-PROCEDURE
    """.trimIndent())

    // ── New Group-1 builtins ─────────────────────────────────────────────

    @Test fun `REVERSE builtin returns TEXT`() = expectClean("""
        PROGRAM T
        DATA:
          s : TEXT = "hello"
          r : TEXT
        PROCEDURE Main:
          COMPUTE r = REVERSE(s)
        END-PROCEDURE
    """.trimIndent())

    @Test fun `SPLIT builtin returns LIST OF TEXT`() = expectClean("""
        PROGRAM T
        DATA:
          csv  : TEXT = "a,b,c"
          parts : LIST OF TEXT
        PROCEDURE Main:
          COMPUTE parts = SPLIT(csv, ",")
        END-PROCEDURE
    """.trimIndent())

    @Test fun `SPLIT with LIMIT returns LIST OF TEXT`() = expectClean("""
        PROGRAM T
        DATA:
          csv   : TEXT = "a:b:c:d"
          parts : LIST OF TEXT
        PROCEDURE Main:
          COMPUTE parts = SPLIT(csv, ":", 2)
        END-PROCEDURE
    """.trimIndent())

    @Test fun `ROUND with mode returns DECIMAL`() = expectClean("""
        PROGRAM T
        DATA:
          n : DECIMAL = 2.345
          r : DECIMAL
        PROCEDURE Main:
          COMPUTE r = ROUND(n, 2, "HALF-UP")
        END-PROCEDURE
    """.trimIndent())

    @Test fun `ROUND-WITH-MODE builtin returns DECIMAL`() = expectClean("""
        PROGRAM T
        DATA:
          n : DECIMAL = 2.675
          r : DECIMAL
        PROCEDURE Main:
          COMPUTE r = ROUND-WITH-MODE(n, 2, "HALF-DOWN")
        END-PROCEDURE
    """.trimIndent())

    @Test fun `SCALE-OF builtin returns INTEGER`() = expectClean("""
        PROGRAM T
        DATA:
          n : DECIMAL = 2.50
          s : INTEGER
        PROCEDURE Main:
          COMPUTE s = SCALE-OF(n)
        END-PROCEDURE
    """.trimIndent())

    @Test fun `PRECISION-OF builtin returns INTEGER`() = expectClean("""
        PROGRAM T
        DATA:
          n : DECIMAL = 123.45
          p : INTEGER
        PROCEDURE Main:
          COMPUTE p = PRECISION-OF(n)
        END-PROCEDURE
    """.trimIndent())

    // ── KobolUuid builtins ──────────────────────────────────────────────

    @Test fun `UUID-GENERATE builtin returns UUID type`() = expectClean("""
        PROGRAM T
        DATA:
          id : UUID
        PROCEDURE Main:
          COMPUTE id = UUID-GENERATE()
        END-PROCEDURE
    """.trimIndent())

    @Test fun `UUID-NIL builtin returns UUID type`() = expectClean("""
        PROGRAM T
        DATA:
          id : UUID
        PROCEDURE Main:
          COMPUTE id = UUID-NIL()
        END-PROCEDURE
    """.trimIndent())

    @Test fun `UUID-FROM-TEXT builtin returns UUID type`() = expectClean("""
        PROGRAM T
        DATA:
          id : UUID
        PROCEDURE Main:
          COMPUTE id = UUID-FROM-TEXT("00000000-0000-0000-0000-000000000000")
        END-PROCEDURE
    """.trimIndent())

    @Test fun `UUID-TO-TEXT builtin returns TEXT`() = expectClean("""
        PROGRAM T
        DATA:
          id : UUID
          s  : TEXT
        PROCEDURE Main:
          COMPUTE id = UUID-GENERATE()
          COMPUTE s  = UUID-TO-TEXT(id)
        END-PROCEDURE
    """.trimIndent())

    // ── IS postfix conditions ─────────────────────────────────────────────

    @Test fun `IS POSITIVE condition is clean`() = expectClean("""
        PROGRAM T
        DATA:
          n : INTEGER = 5
          b : BOOLEAN
        PROCEDURE Main:
          COMPUTE b = n IS POSITIVE
        END-PROCEDURE
    """.trimIndent())

    @Test fun `IS NEGATIVE condition is clean`() = expectClean("""
        PROGRAM T
        DATA:
          n : INTEGER = -3
          b : BOOLEAN
        PROCEDURE Main:
          COMPUTE b = n IS NEGATIVE
        END-PROCEDURE
    """.trimIndent())

    @Test fun `IS ZERO condition is clean`() = expectClean("""
        PROGRAM T
        DATA:
          n : INTEGER = 0
          b : BOOLEAN
        PROCEDURE Main:
          COMPUTE b = n IS ZERO
        END-PROCEDURE
    """.trimIndent())

    @Test fun `IS NOT ZERO condition is clean`() = expectClean("""
        PROGRAM T
        DATA:
          n : INTEGER = 1
          b : BOOLEAN
        PROCEDURE Main:
          COMPUTE b = n IS NOT ZERO
        END-PROCEDURE
    """.trimIndent())

    @Test fun `IS POSITIVE in IF condition is clean`() = expectClean("""
        PROGRAM T
        DATA:
          balance : INTEGER = 100
        PROCEDURE Main:
          IF balance IS POSITIVE:
            DISPLAY "positive"
          END-IF
        END-PROCEDURE
    """.trimIndent())

    @Test fun `IS EMPTY on TEXT is clean`() = expectClean("""
        PROGRAM T
        DATA:
          s : TEXT = ""
          b : BOOLEAN
        PROCEDURE Main:
          COMPUTE b = s IS EMPTY
        END-PROCEDURE
    """.trimIndent())

    @Test fun `IS BLANK on TEXT is clean`() = expectClean("""
        PROGRAM T
        DATA:
          s : TEXT = "  "
          b : BOOLEAN
        PROCEDURE Main:
          COMPUTE b = s IS BLANK
        END-PROCEDURE
    """.trimIndent())

    // ── DISPLAY JSON ──────────────────────────────────────────────────────

    @Test fun `DISPLAY JSON is clean`() = expectClean("""
        PROGRAM T
        DATA:
          name : TEXT = "Alice"
        PROCEDURE Main:
          DISPLAY JSON name
        END-PROCEDURE
    """.trimIndent())

    @Test fun `DISPLAY JSON PRETTY is clean`() = expectClean("""
        PROGRAM T
        DATA:
          name : TEXT = "Bob"
        PROCEDURE Main:
          DISPLAY JSON PRETTY name
        END-PROCEDURE
    """.trimIndent())

    // ── Named Conditions ──────────────────────────────────────────────────

    @Test fun `named CONDITION is clean`() = expectClean("""
        PROGRAM T
        DATA:
          balance : INTEGER = 100
        CONDITION Account-Positive WHEN balance > 0
        PROCEDURE Main:
          IF Account-Positive:
            DISPLAY "positive"
          END-IF
        END-PROCEDURE
    """.trimIndent())

    @Test fun `WRITE JSON to file is clean`() = expectClean("""
        PROGRAM T
        DATA:
          name : TEXT = "Alice"
        PROCEDURE Main:
          WRITE JSON name TO "output.json"
        END-PROCEDURE
    """.trimIndent())

    @Test fun `DISPLAY STYLED is clean`() = expectClean("""
        PROGRAM T
        DATA:
          msg : TEXT = "hello"
        PROCEDURE Main:
          DISPLAY STYLED msg BOLD COLOR "GREEN"
        END-PROCEDURE
    """.trimIndent())

    // ── DISPLAY XML ────────────────────────────────────────────────────────

    @Test fun `DISPLAY XML is clean`() = expectClean("""
        PROGRAM T
        DATA:
          name : TEXT = "Alice"
        PROCEDURE Main:
          DISPLAY XML name
        END-PROCEDURE
    """.trimIndent())

    @Test fun `DISPLAY XML PRETTY is clean`() = expectClean("""
        PROGRAM T
        DATA:
          name : TEXT = "Bob"
        PROCEDURE Main:
          DISPLAY XML PRETTY name
        END-PROCEDURE
    """.trimIndent())

    @Test fun `WRITE XML to file is clean`() = expectClean("""
        PROGRAM T
        DATA:
          name : TEXT = "Alice"
        PROCEDURE Main:
          WRITE XML name TO "output.xml"
        END-PROCEDURE
    """.trimIndent())

    @Test fun `WRITE XML PRETTY to file is clean`() = expectClean("""
        PROGRAM T
        DATA:
          name : TEXT = "Alice"
        PROCEDURE Main:
          WRITE XML name TO "output.xml" PRETTY
        END-PROCEDURE
    """.trimIndent())

    // ── MATCH range patterns ───────────────────────────────────────────────

    @Test fun `MATCH range pattern on INTEGER is clean`() = expectClean("""
        PROGRAM T
        DATA:
          score : INTEGER = 75
        PROCEDURE Main:
          MATCH score:
            WHEN 90..100:
              DISPLAY "A"
            WHEN 80..89:
              DISPLAY "B"
            OTHERWISE:
              DISPLAY "C"
          END-MATCH
        END-PROCEDURE
    """.trimIndent())

    // ── WITH PRECISION ─────────────────────────────────────────────────────

    @Test fun `WITH PRECISION block is clean`() = expectClean("""
        PROGRAM T
        DATA:
          rate : DECIMAL(18,8) = 1.23456789
          result : DECIMAL(18,8) = 0
        PROCEDURE Main:
          WITH PRECISION DECIMAL128:
            COMPUTE result = rate * 2
          END-PRECISION
        END-PROCEDURE
    """.trimIndent())

    // ── PARSE AS / AS LIST ─────────────────────────────────────────────────

    @Test fun `PARSE JSON into record AS LIST is clean`() = expectClean("""
        PROGRAM T
        DATA:
          raw   : TEXT = "[\"a\",\"b\"]"
          items : LIST OF TEXT
        PROCEDURE Main:
          PARSE JSON raw INTO items AS LIST
        END-PROCEDURE
    """.trimIndent())

    @Test fun `PARSE XML into record AS LIST is clean`() = expectClean("""
        PROGRAM T
        DATA:
          raw   : TEXT = "<items><item>a</item></items>"
          items : LIST OF TEXT
        PROCEDURE Main:
          PARSE XML raw INTO items AS LIST
        END-PROCEDURE
    """.trimIndent())

    // ── MATCH type patterns ────────────────────────────────────────────────

    @Test fun `MATCH type pattern INTEGER AS binding is clean`() = expectClean("""
        PROGRAM T
        DATA:
          n : INTEGER = 42
        PROCEDURE Main:
          MATCH n:
            WHEN INTEGER AS x:
              DISPLAY x
            OTHERWISE:
              DISPLAY "?"
          END-MATCH
        END-PROCEDURE
    """.trimIndent())

    // ── DIVIDE USING mode ──────────────────────────────────────────────────

    @Test fun `DIVIDE USING HALF-EVEN is clean`() = expectClean("""
        PROGRAM T
        DATA:
          total  : DECIMAL(18,2) = 10.0
          parts  : DECIMAL(18,2) = 3.0
          result : DECIMAL(18,2) = 0
        PROCEDURE Main:
          DIVIDE total INTO parts GIVING result USING HALF-EVEN
        END-PROCEDURE
    """.trimIndent())

    // ── WITH PRECISION full MathContext threading ──────────────────────────

    @Test fun `WITH PRECISION DECIMAL64 block with arithmetic is clean`() = expectClean("""
        PROGRAM T
        DATA:
          a : DECIMAL(18,8) = 1.23456789
          b : DECIMAL(18,8) = 9.87654321
          c : DECIMAL(18,8) = 0
        PROCEDURE Main:
          WITH PRECISION DECIMAL64:
            COMPUTE c = a * b
            DIVIDE a INTO b GIVING c
          END-PRECISION
        END-PROCEDURE
    """.trimIndent())

    @Test fun `nested WITH PRECISION blocks are clean`() = expectClean("""
        PROGRAM T
        DATA:
          x : DECIMAL(18,8) = 3.14159265
          y : DECIMAL(18,8) = 0
        PROCEDURE Main:
          WITH PRECISION DECIMAL128:
            WITH PRECISION DECIMAL64:
              COMPUTE y = x * x
            END-PRECISION
            COMPUTE y = y * x
          END-PRECISION
        END-PROCEDURE
    """.trimIndent())

    // ── Group 10b: Phase 14 — ROUND statement & WITH PRECISION ROUNDING ────

    @Test fun `ROUND statement default mode is clean`() = expectClean("""
        PROGRAM T
        DATA:
          amount : DECIMAL(18,2) = 12.345
        PROCEDURE Main:
          ROUND amount TO 2
        END-PROCEDURE
    """.trimIndent())

    @Test fun `ROUND statement with USING mode is clean`() = expectClean("""
        PROGRAM T
        DATA:
          amount : DECIMAL(18,2) = 12.345
        PROCEDURE Main:
          ROUND amount TO 2 USING HALF-UP
        END-PROCEDURE
    """.trimIndent())

    @Test fun `WITH PRECISION ROUNDING HALF-UP block is clean`() = expectClean("""
        PROGRAM T
        DATA:
          a : DECIMAL(18,8) = 1.5
          b : DECIMAL(18,8) = 0
        PROCEDURE Main:
          WITH PRECISION DECIMAL128 ROUNDING HALF-UP:
            COMPUTE b = a * 2
          END-PRECISION
        END-PROCEDURE
    """.trimIndent())

    @Test fun `WITH PRECISION ROUNDING HALF-EVEN block is clean`() = expectClean("""
        PROGRAM T
        DATA:
          x : DECIMAL(20,6) = 3.14159
          y : DECIMAL(20,6) = 0
        PROCEDURE Main:
          WITH PRECISION DECIMAL64 ROUNDING HALF-EVEN:
            COMPUTE y = x * x
          END-PRECISION
        END-PROCEDURE
    """.trimIndent())

    @Test fun `SUM pipeline on DECIMAL list is clean`() = expectClean("""
        PROGRAM T
        DATA:
          amounts : LIST OF DECIMAL(18,2)
          total   : DECIMAL(18,2) = 0
        PROCEDURE Main:
          COMPUTE total = amounts SUM
        END-PROCEDURE
    """.trimIndent())

    @Test fun `SUM pipeline on INTEGER list is clean`() = expectClean("""
        PROGRAM T
        DATA:
          counts : LIST OF INTEGER
          total  : INTEGER = 0
        PROCEDURE Main:
          COMPUTE total = counts SUM
        END-PROCEDURE
    """.trimIndent())

    // ── Group 11: HTTP client ────────────────────────────────────────────────

    @Test fun `CALL http GET is clean`() = expectClean("""
        PROGRAM T
        DATA:
          response : TEXT = ""
        PROCEDURE Main:
          CALL http.GET USING "https://example.com" GIVING response
          STOP RUN
        END-PROCEDURE
    """.trimIndent())

    @Test fun `CALL http POST with body is clean`() = expectClean("""
        PROGRAM T
        DATA:
          result : TEXT = ""
        PROCEDURE Main:
          CALL http.POST USING "https://example.com" BODY "hello" GIVING result
          STOP RUN
        END-PROCEDURE
    """.trimIndent())

    // ── Group 11: JDBC bridge ────────────────────────────────────────────────

    @Test fun `CALL jdbc connect and disconnect is clean`() = expectClean("""
        PROGRAM T
        PROCEDURE Main:
          CALL jdbc.connect USING "jdbc:h2:mem:test"
          CALL jdbc.disconnect
          STOP RUN
        END-PROCEDURE
    """.trimIndent())

    @Test fun `CALL jdbc execute is clean`() = expectClean("""
        PROGRAM T
        PROCEDURE Main:
          CALL jdbc.connect USING "jdbc:h2:mem:test"
          CALL jdbc.execute USING "CREATE TABLE foo (id INT)"
          CALL jdbc.disconnect
          STOP RUN
        END-PROCEDURE
    """.trimIndent())

    // ── Group 12: REST server ────────────────────────────────────────────────

    @Test fun `SERVER AT PORT is clean`() = expectClean("""
        PROGRAM T
        PROCEDURE Main:
          SERVER AT PORT 8080:
            ENDPOINT GET "/hello":
              RESPOND WITH "Hello, World!"
          END-SERVER
          STOP RUN
        END-PROCEDURE
    """.trimIndent())
}
