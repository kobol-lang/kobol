package dev.kobol.integration

import dev.kobol.CompilerOptions
import dev.kobol.compileFile
import dev.kobol.semantic.ModuleRegistry
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.net.URLClassLoader
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * JVM integration tests: compile Kobol source → load bytecode → run → assert stdout.
 *
 * These catch codegen bugs that type-checker tests miss — the 396 existing tests
 * only verify parse + semantic layers, never execute bytecode.
 */
class JvmIntegrationTest {

    @TempDir lateinit var outDir: File

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun compileAndRun(src: String, programName: String = "T"): String {
        val source = File(outDir, "$programName.kbl").apply { writeText(src.trimIndent()) }
        // Capture stdout+stderr during compilation (diagnostics go to stdout, errors to stderr)
        val diagBaos = ByteArrayOutputStream()
        val diagStream = PrintStream(diagBaos)
        val savedOut = System.out; val savedErr = System.err
        System.setOut(diagStream); System.setErr(diagStream)
        val ok = try {
            compileFile(source, CompilerOptions(source, outDir), ModuleRegistry())
        } finally {
            diagStream.flush()
            System.setOut(savedOut); System.setErr(savedErr)
        }
        val diagOut = diagBaos.toString(Charsets.UTF_8)
        assertTrue(ok, "Compilation failed for $programName:\n$diagOut")
        return runCapturingStdout(programName)
    }

    private fun runCapturingStdout(programName: String): String {
        val className = programName.split("-").joinToString("") { it.lowercase().replaceFirstChar { c -> c.uppercase() } }
        val loader = URLClassLoader(arrayOf(outDir.toURI().toURL()), Thread.currentThread().contextClassLoader)
        val cls = loader.loadClass(className)

        val baos = ByteArrayOutputStream()
        val saved = System.out
        System.setOut(PrintStream(baos))
        return try {
            cls.getMethod("main", Array<String>::class.java).invoke(null, arrayOf<String>())
            baos.toString(Charsets.UTF_8)
        } finally {
            System.setOut(saved)
            loader.close()
        }
    }

    // -------------------------------------------------------------------------
    // Core statements
    // -------------------------------------------------------------------------

    @Test fun `DISPLAY literal`() {
        val out = compileAndRun("""
            PROGRAM T
            PROCEDURE Main:
              DISPLAY "hello world"
              DISPLAY "---done---"
            END-PROCEDURE
        """)
        assertTrue("hello world" in out)
    }

    @Test fun `DATA TEXT default initialized`() {
        val out = compileAndRun("""
            PROGRAM T
            DATA:
              msg : TEXT = "Kobol!"
            PROCEDURE Main:
              DISPLAY msg
              DISPLAY "---done---"
            END-PROCEDURE
        """)
        assertTrue("Kobol!" in out)
    }

    @Test fun `ADD item TO list then FOR EACH`() {
        val out = compileAndRun("""
            PROGRAM T
            DATA:
              items : LIST OF TEXT
            PROCEDURE Main:
              ADD "alpha" TO items
              ADD "beta"  TO items
              ADD "gamma" TO items
              FOR EACH item IN items:
                DISPLAY item
              END-FOR
              DISPLAY "---done---"
            END-PROCEDURE
        """)
        assertTrue("alpha" in out)
        assertTrue("beta" in out)
        assertTrue("gamma" in out)
    }

    @Test fun `ADD integer to list then sum`() {
        val out = compileAndRun("""
            PROGRAM T
            DATA:
              nums   : LIST OF INTEGER
              total  : INTEGER = 0
            PROCEDURE Main:
              ADD 10 TO nums
              ADD 20 TO nums
              ADD 30 TO nums
              FOR EACH n IN nums:
                ADD n TO total
              END-FOR
              DISPLAY total
              DISPLAY "---done---"
            END-PROCEDURE
        """)
        assertTrue("60" in out)
    }

    @Test fun `PARALLEL FOR EACH runs all items`() {
        val out = compileAndRun("""
            PROGRAM T
            DATA:
              items : LIST OF TEXT
            PROCEDURE Main:
              ADD "x" TO items
              ADD "y" TO items
              ADD "z" TO items
              FOR EACH item IN items PARALLEL:
                DISPLAY item
              END-FOR
              DISPLAY "---done---"
            END-PROCEDURE
        """)
        assertTrue("x" in out)
        assertTrue("y" in out)
        assertTrue("z" in out)
    }

    @Test fun `CONCURRENT block runs both branches`() {
        val out = compileAndRun("""
            PROGRAM T
            PROCEDURE Main:
              CONCURRENT:
                PERFORM A
                PERFORM B
              END-CONCURRENT
              DISPLAY "---done---"
            END-PROCEDURE
            PROCEDURE A:
              DISPLAY "branch-a"
            END-PROCEDURE
            PROCEDURE B:
              DISPLAY "branch-b"
            END-PROCEDURE
        """)
        assertTrue("branch-a" in out)
        assertTrue("branch-b" in out)
    }

    // -------------------------------------------------------------------------
    // MONEY / DECIMAL (large-precision fixed-point)
    // -------------------------------------------------------------------------

    @Test fun `MONEY arithmetic stays exact`() {
        val out = compileAndRun("""
            PROGRAM T
            DATA:
              price    : MONEY(12.2) = 1.10
              quantity : INTEGER     = 3
              total    : MONEY(14.2)
            PROCEDURE Main:
              COMPUTE total = price * quantity
              DISPLAY total
              DISPLAY "---done---"
            END-PROCEDURE
        """)
        // 1.10 * 3 = 3.30 (BigDecimal exact, not 3.3000000000000003 like double)
        assertTrue("3.30" in out)
    }

    @Test fun `VALIDATE MUST BE on MONEY`() {
        val out = compileAndRun("""
            PROGRAM T
            DATA:
              amount : MONEY(10.2) = 25.00
            PROCEDURE Main:
              VALIDATE amount:
                MUST BE > 0 FAIL-MSG "must be positive"
              END-VALIDATE
              DISPLAY "valid"
              DISPLAY "---done---"
            END-PROCEDURE
        """)
        assertTrue("valid" in out)
    }

    @Test fun `PROCEDURE with INTEGER params correct slots`() {
        // Verifies multi-param procs with INTEGER (long, 2 JVM slots) don't corrupt subsequent slots.
        // "AddNums" avoids collision with Kobol keyword ADD.
        val out = compileAndRun("""
            PROGRAM T
            DATA:
              answer : INTEGER = 0
            PROCEDURE Main:
              PERFORM AddNums USING 10, 32
              DISPLAY answer
              DISPLAY "---done---"
            END-PROCEDURE
            PROCEDURE AddNums USING a : INTEGER, b : INTEGER:
              COMPUTE answer = a + b
            END-PROCEDURE
        """)
        assertTrue("42" in out)
    }

    @Test fun `LOG WITH multi-KV inline`() {
        // Verifies the do-while parser fix: multiple kv pairs on one line.
        // LOG goes to SLF4J (not stdout), so we just verify compile + run succeeds.
        val out = compileAndRun("""
            PROGRAM T
            DATA:
              total : INTEGER = 42
            PROCEDURE Main:
              LOG INFO "Result" WITH total: total
              DISPLAY "ok"
              DISPLAY "---done---"
            END-PROCEDURE
        """)
        // Parser fix confirmed if multi-KV compiles; test single-KV here as baseline
        assertTrue("ok" in out)
    }

    @Test fun `LOG WITH two KV inline`() {
        val out = compileAndRun("""
            PROGRAM T
            DATA:
              a : INTEGER = 1
              b : INTEGER = 2
            PROCEDURE Main:
              LOG INFO "Values" WITH a: a, b: b
              DISPLAY "ok"
              DISPLAY "---done---"
            END-PROCEDURE
        """)
        assertTrue("ok" in out)
    }

    // -------------------------------------------------------------------------
    // Java interop via CALL — original-case method names
    // -------------------------------------------------------------------------

    @Test fun `CALL with original-case method name`() {
        val out = compileAndRun("""
            PROGRAM T
            IMPORT java.time.LocalDate
            DATA:
              today : DATE
            PROCEDURE Main:
              CALL LocalDate.now GIVING today
              DISPLAY "date-ok"
              DISPLAY "---done---"
            END-PROCEDURE
        """)
        assertTrue("date-ok" in out)
    }

    // -------------------------------------------------------------------------
    // Challenge #13 — SUM pipeline terminal (FILTER WHERE NOT + TRANSFORM TO + SUM)
    // -------------------------------------------------------------------------

    @Test fun `SUM pipeline with filter and transform`() {
        // Distinct record variables per element (avoids the separate ADD-record aliasing
        // bug where reusing one buffer makes every list entry point at the same object).
        val out = compileAndRun("""
            PROGRAM T
            RECORD Sale:
              product : TEXT(20)
              amount  : MONEY(12.2)
              paid    : BOOLEAN
            DATA:
              sales : LIST OF Sale
              s1    : Sale
              s2    : Sale
              s3    : Sale
              unpaid-total : MONEY(14.2) = 0
            PROCEDURE Main:
              MOVE "Widget" TO s1.product
              MOVE 100.00 TO s1.amount
              MOVE FALSE TO s1.paid
              ADD s1 TO sales
              MOVE "Gadget" TO s2.product
              MOVE 250.00 TO s2.amount
              MOVE TRUE TO s2.paid
              ADD s2 TO sales
              MOVE "Gizmo" TO s3.product
              MOVE 75.00 TO s3.amount
              MOVE FALSE TO s3.paid
              ADD s3 TO sales
              COMPUTE unpaid-total = sales FILTER WHERE NOT paid TRANSFORM TO amount SUM
              DISPLAY "unpaid total: " unpaid-total
              DISPLAY "---done---"
            END-PROCEDURE
        """)
        // unpaid = Widget 100.00 + Gizmo 75.00 = 175.00 (Gadget paid → excluded)
        assertTrue("175.00" in out, "expected unpaid total 175.00, got:\n$out")
    }

    @Test fun `SUM over plain decimal list`() {
        val out = compileAndRun("""
            PROGRAM T
            DATA:
              nums  : LIST OF MONEY(12.2)
              total : MONEY(14.2) = 0
            PROCEDURE Main:
              ADD 10.50 TO nums
              ADD 4.25 TO nums
              COMPUTE total = nums SUM
              DISPLAY total
              DISPLAY "---done---"
            END-PROCEDURE
        """)
        assertTrue("14.75" in out, "expected 14.75, got:\n$out")
    }

    // -------------------------------------------------------------------------
    // Challenge #22 — UUID expressions (generate, nil, compare, display)
    // -------------------------------------------------------------------------

    @Test fun `UUID generate compare and display`() {
        val out = compileAndRun("""
            PROGRAM T
            DATA:
              transaction-id : UUID
            PROCEDURE Main:
              LET transaction-id = UUID-GENERATE()
              IF transaction-id <> UUID-NIL():
                DISPLAY "uuid ok"
              END-IF
              DISPLAY "---done---"
            END-PROCEDURE
        """)
        assertTrue("uuid ok" in out, "expected generated UUID != nil, got:\n$out")
    }

    @Test fun `UUID nil equals nil`() {
        val out = compileAndRun("""
            PROGRAM T
            DATA:
              a : UUID
            PROCEDURE Main:
              LET a = UUID-NIL()
              IF a = UUID-NIL():
                DISPLAY "nil match"
              END-IF
              DISPLAY "---done---"
            END-PROCEDURE
        """)
        assertTrue("nil match" in out, "expected nil == nil, got:\n$out")
    }

    // -------------------------------------------------------------------------
    // Challenge #7 — negative decimal/money literal (was `ineg` on BigDecimal)
    // -------------------------------------------------------------------------

    @Test fun `negative money literal loads`() {
        val out = compileAndRun("""
            PROGRAM T
            DATA:
              m : MONEY(12.2) = 0
            PROCEDURE Main:
              MOVE -5.00 TO m
              DISPLAY m
              DISPLAY "---done---"
            END-PROCEDURE
        """)
        assertTrue("-5.00" in out, "expected -5.00, got:\n$out")
    }

    @Test fun `negate decimal expression`() {
        val out = compileAndRun("""
            PROGRAM T
            DATA:
              a : DECIMAL(10, 2) = 7.25
              b : DECIMAL(10, 2)
            PROCEDURE Main:
              COMPUTE b = -a
              DISPLAY b
              DISPLAY "---done---"
            END-PROCEDURE
        """)
        assertTrue("-7.25" in out, "expected -7.25, got:\n$out")
    }

    // -------------------------------------------------------------------------
    // Challenge #8 — SMALLINT initialization (was long-into-int VerifyError)
    // -------------------------------------------------------------------------

    @Test fun `SMALLINT field initializes`() {
        val out = compileAndRun("""
            PROGRAM T
            DATA:
              x : SMALLINT = 5
            PROCEDURE Main:
              DISPLAY x
              DISPLAY "---done---"
            END-PROCEDURE
        """)
        assertTrue("5" in out, "expected 5, got:\n$out")
    }

    @Test fun `SMALLINT MOVE from integer literal`() {
        val out = compileAndRun("""
            PROGRAM T
            DATA:
              x : SMALLINT = 0
            PROCEDURE Main:
              MOVE 42 TO x
              DISPLAY x
              DISPLAY "---done---"
            END-PROCEDURE
        """)
        assertTrue("42" in out, "expected 42, got:\n$out")
    }

    // -------------------------------------------------------------------------
    // Challenge #9 — MULTIPLY/DIVIDE ... GIVING coerces long→BigDecimal
    // -------------------------------------------------------------------------

    @Test fun `MULTIPLY integer BY money GIVING coerces`() {
        val out = compileAndRun("""
            PROGRAM T
            DATA:
              quantity   : INTEGER    = 3
              unit-price : MONEY(12.2) = 1.10
              line-total : MONEY(14.2)
            PROCEDURE Main:
              MULTIPLY quantity BY unit-price GIVING line-total
              DISPLAY line-total
              DISPLAY "---done---"
            END-PROCEDURE
        """)
        // 3 * 1.10 = 3.30 exact (the canonical invoice line-total from spec §8.3)
        assertTrue("3.30" in out, "expected 3.30, got:\n$out")
    }

    @Test fun `MULTIPLY agrees with COMPUTE on same operands`() {
        val out = compileAndRun("""
            PROGRAM T
            DATA:
              quantity   : INTEGER    = 3
              unit-price : MONEY(12.2) = 1.10
              via-verb   : MONEY(14.2)
              via-compute: MONEY(14.2)
            PROCEDURE Main:
              MULTIPLY quantity BY unit-price GIVING via-verb
              COMPUTE via-compute = unit-price * quantity
              DISPLAY via-verb
              DISPLAY via-compute
              DISPLAY "---done---"
            END-PROCEDURE
        """)
        // Both paths must produce the same exact value — challenge #9's core complaint
        assertEquals(2, Regex("3\\.30").findAll(out).count(), "both verb and COMPUTE should yield 3.30:\n$out")
    }

    // -------------------------------------------------------------------------
    // Challenge #11 — numeric builtins accept INTEGER-literal arguments
    // -------------------------------------------------------------------------

    @Test fun `SQRT accepts integer argument`() {
        val out = compileAndRun("""
            PROGRAM T
            DATA:
              r : DECIMAL(18, 8)
            PROCEDURE Main:
              COMPUTE r = SQRT(16)
              DISPLAY r
              DISPLAY "---done---"
            END-PROCEDURE
        """)
        assertTrue("4" in out, "expected sqrt(16)=4, got:\n$out")
    }

    @Test fun `FLOOR accepts integer argument`() {
        val out = compileAndRun("""
            PROGRAM T
            DATA:
              r : DECIMAL(18, 8)
            PROCEDURE Main:
              COMPUTE r = FLOOR(7)
              DISPLAY r
              DISPLAY "---done---"
            END-PROCEDURE
        """)
        assertTrue("7" in out, "expected floor(7)=7, got:\n$out")
    }

    // -------------------------------------------------------------------------
    // Challenge #2 — DIVIDE ... BY ... GIVING (English direction) + INTO regression
    // -------------------------------------------------------------------------

    @Test fun `DIVIDE BY GIVING English direction`() {
        // money / money keeps both operands BigDecimal (isolates the parse-direction
        // fix from the still-open long to BigDecimal coercion cluster #9/#11).
        val out = compileAndRun("""
            PROGRAM T
            DATA:
              total   : MONEY(12.2) = 100.00
              divisor : MONEY(12.2) = 4.00
              avg     : MONEY(14.2)
            PROCEDURE Main:
              DIVIDE total BY divisor GIVING avg
              DISPLAY avg
              DISPLAY "---done---"
            END-PROCEDURE
        """)
        assertTrue("25.00" in out, "expected 100.00 / 4.00 = 25.00, got:\n$out")
    }

    @Test fun `DIVIDE INTO GIVING still works (no regression)`() {
        val out = compileAndRun("""
            PROGRAM T
            DATA:
              total : MONEY(12.2) = 100.00
              r     : MONEY(14.2)
            PROCEDURE Main:
              DIVIDE 4.00 INTO total GIVING r
              DISPLAY r
              DISPLAY "---done---"
            END-PROCEDURE
        """)
        assertTrue("25.00" in out, "expected total / 4.00 = 25.00, got:\n$out")
    }

    // -------------------------------------------------------------------------
    // Challenge #20 — DISPLAY JSON x PRETTY (modifier after the expression)
    // -------------------------------------------------------------------------

    @Test fun `DISPLAY JSON PRETTY after expression`() {
        val out = compileAndRun("""
            PROGRAM T
            RECORD Item:
              name : TEXT(10)
              qty  : INTEGER
            DATA:
              it : Item
            PROCEDURE Main:
              MOVE "widget" TO it.name
              MOVE 3 TO it.qty
              DISPLAY JSON it PRETTY
              DISPLAY "---done---"
            END-PROCEDURE
        """)
        assertTrue("widget" in out, "expected JSON output to contain field value, got:\n$out")
        assertTrue("---done---" in out)
    }

    // -------------------------------------------------------------------------
    // Challenge #3 — DEFINE TYPE Name IS <type> (was an unsatisfiable parse branch)
    // -------------------------------------------------------------------------

    @Test fun `DEFINE TYPE alias compiles and runs`() {
        val out = compileAndRun("""
            PROGRAM T
            DEFINE TYPE Rate IS DECIMAL(18, 8)
            DATA:
              r : Rate = 1.50000000
            PROCEDURE Main:
              DISPLAY r
              DISPLAY "---done---"
            END-PROCEDURE
        """)
        assertTrue("1.5" in out, "expected alias-typed value displayed, got:\n$out")
    }

    // #6 — OR/AND in a CONDITION used to crash ASM frame compute with
    // ArrayIndexOutOfBoundsException. Short-circuit codegen must leave one int
    // on the stack on both exit paths. Verify it compiles, runs, AND is correct.
    @Test fun `CONDITION with OR compiles runs and short-circuits`() {
        val out = compileAndRun("""
            PROGRAM T
            DATA:
              s : TEXT(1) = "D"
            CONDITION Inactive WHEN s = "I" OR s = "D"
            PROCEDURE Main:
              IF Inactive:
                DISPLAY "MATCH"
              ELSE:
                DISPLAY "MISS"
              END-IF
              DISPLAY "---done---"
            END-PROCEDURE
        """)
        assertTrue("MATCH" in out, "OR-condition right branch should match s=\"D\", got:\n$out")
        assertTrue("MISS" !in out, "should not take ELSE, got:\n$out")
    }

    @Test fun `CONDITION with OR false branch`() {
        val out = compileAndRun("""
            PROGRAM T
            DATA:
              s : TEXT(1) = "A"
            CONDITION Inactive WHEN s = "I" OR s = "D"
            PROCEDURE Main:
              IF Inactive:
                DISPLAY "MATCH"
              ELSE:
                DISPLAY "MISS"
              END-IF
            END-PROCEDURE
        """)
        assertTrue("MISS" in out, "neither OR value matches s=\"A\", got:\n$out")
        assertTrue("MATCH" !in out, "should not match, got:\n$out")
    }

    // #14 — java.lang auto-imported for a bare CALL (no IMPORT needed).
    @Test fun `CALL System currentTimeMillis without import`() {
        val out = compileAndRun("""
            PROGRAM T
            DATA:
              ts : INTEGER = 0
            PROCEDURE Main:
              CALL System.currentTimeMillis GIVING ts
              IF ts > 0:
                DISPLAY "POSITIVE"
              ELSE:
                DISPLAY "NONPOS"
              END-IF
              DISPLAY "---done---"
            END-PROCEDURE
        """)
        assertTrue("POSITIVE" in out, "System.currentTimeMillis should return a positive long, got:\n$out")
    }

    // #17 — escaped braces \{ \} are literal, so JSON literals and regex {n,m}
    // quantifiers survive in an interpolating string.
    @Test fun `escaped braces are literal in string`() {
        val out = compileAndRun("""
            PROGRAM T
            DATA:
              j : TEXT = "\{\"id\":7\}"
            PROCEDURE Main:
              DISPLAY j
              DISPLAY "---done---"
            END-PROCEDURE
        """)
        assertTrue("{\"id\":7}" in out, "escaped braces should produce literal JSON, got:\n$out")
    }

    @Test fun `CONDITION with AND and NOT compiles and runs`() {
        val out = compileAndRun("""
            PROGRAM T
            DATA:
              bal  : INTEGER = 100
              paid : BOOLEAN = false
            CONDITION Owing WHEN bal > 0 AND NOT paid
            PROCEDURE Main:
              IF Owing:
                DISPLAY "owing"
              ELSE:
                DISPLAY "clear"
              END-IF
            END-PROCEDURE
        """)
        assertTrue("owing" in out, "bal>0 AND NOT paid should hold, got:\n$out")
        assertTrue("clear" !in out, "should not take ELSE, got:\n$out")
    }

    // #18 — VARIANT case construction (positional). Before the fix the case name fell through
    // to "unknown builtin", the args dangled, MOVE stored a non-case value and MATCH missed.
    @Test fun `VARIANT positional construction matches in MATCH`() {
        val out = compileAndRun("""
            PROGRAM T
            VARIANT OrderStatus IS
              Pending
              | Shipped WITH tracking : TEXT
              | Closed  WITH reason   : TEXT
            DATA:
              st : OrderStatus
            PROCEDURE Main:
              MOVE Shipped("TRK123") TO st
              MATCH st:
                WHEN Pending:
                  DISPLAY "pending"
                WHEN Shipped WITH tracking:
                  DISPLAY "shipped {tracking}"
                WHEN Closed WITH reason:
                  DISPLAY "closed {reason}"
              END-MATCH
              DISPLAY "---done---"
            END-PROCEDURE
        """)
        assertTrue("shipped TRK123" in out, "positional variant construction should match Shipped arm, got:\n$out")
    }

    // #18 — named-argument construction; fields reordered to declared order.
    @Test fun `VARIANT named construction matches in MATCH`() {
        val out = compileAndRun("""
            PROGRAM T
            VARIANT Event IS
              Vec WITH dx : INTEGER, dy : INTEGER
            DATA:
              e : Event
            PROCEDURE Main:
              MOVE Vec(dy: 7, dx: 3) TO e
              MATCH e:
                WHEN Vec WITH dx, dy:
                  DISPLAY "move {dx} {dy}"
              END-MATCH
              DISPLAY "---done---"
            END-PROCEDURE
        """)
        assertTrue("move 3 7" in out, "named args must bind to declared fields by name, got:\n$out")
    }

    // #15 — multi-line struct literal (field per line, no commas).
    @Test fun `multi-line struct literal parses and runs`() {
        val out = compileAndRun("""
            PROGRAM T
            RECORD Point:
              x : INTEGER
              y : INTEGER
            END-RECORD
            DATA:
              p : Point
            PROCEDURE Main:
              MOVE Point {
                x: 4
                y: 9
              } TO p
              DISPLAY "pt {p.x} {p.y}"
              DISPLAY "---done---"
            END-PROCEDURE
        """)
        assertTrue("pt 4 9" in out, "multi-line struct literal should populate fields, got:\n$out")
    }

    // #5 — English-prose string operations (spec §11). Call form FUNC(args) still works;
    // these add the prose forms that are the language's readability thesis.
    @Test fun `prose string ops UPPERCASE LENGTH SUBSTRING FIND COMBINE`() {
        val out = compileAndRun("""
            PROGRAM T
            DATA:
              full-name : TEXT = "John Smith"
              up   : TEXT
              sub  : TEXT
              comb : TEXT
              len  : INTEGER
              pos  : INTEGER
            PROCEDURE Main:
              COMPUTE up   = UPPERCASE full-name
              COMPUTE len  = LENGTH full-name
              COMPUTE sub  = SUBSTRING full-name FROM 1 FOR 4
              COMPUTE pos  = FIND "Smith" IN full-name
              COMPUTE comb = COMBINE full-name " " full-name
              DISPLAY "up={up} len={len} sub={sub} pos={pos}"
              DISPLAY "comb={comb}"
              DISPLAY "---done---"
            END-PROCEDURE
        """)
        assertTrue("up=JOHN SMITH" in out, "UPPERCASE prose, got:\n$out")
        assertTrue("len=10" in out, "LENGTH prose, got:\n$out")
        assertTrue("sub=John" in out, "SUBSTRING x FROM 1 FOR 4, got:\n$out")
        assertTrue("pos=6" in out, "FIND needle IN haystack (1-based index of 'Smith'), got:\n$out")
        assertTrue("comb=John Smith John Smith" in out, "COMBINE variadic prose, got:\n$out")
    }

    // #5 — prose and call forms must agree, and a variable whose name collides with a prose
    // builtin (e.g. `len`) must still work as a plain reference.
    @Test fun `prose and call string ops agree and builtin-named var still works`() {
        val out = compileAndRun("""
            PROGRAM T
            DATA:
              s : TEXT = "abcdef"
              a : TEXT
              b : TEXT
              len : INTEGER = 99
            PROCEDURE Main:
              COMPUTE a = UPPERCASE s
              COMPUTE b = UPPERCASE(s)
              DISPLAY "agree={a}{b} len={len}"
              DISPLAY "---done---"
            END-PROCEDURE
        """)
        assertTrue("agree=ABCDEFABCDEF" in out, "prose and call UPPERCASE should match, got:\n$out")
        assertTrue("len=99" in out, "a variable named `len` must remain a plain reference, got:\n$out")
    }

    // #10 — integer `**` previously emitted `L2D; L2D` (the 2nd L2D hit the already-converted
    // double, not the 2nd-from-top long) → VerifyError. Now routes through KobolMath.power(JJ)J.
    @Test fun `integer power operator computes 2 ** 10`() {
        val out = compileAndRun("""
            PROGRAM T
            DATA:
              k : INTEGER = 0
            PROCEDURE Main:
              COMPUTE k = 2 ** 10
              DISPLAY "k={k}"
              DISPLAY "---done---"
            END-PROCEDURE
        """)
        assertTrue("k=1024" in out, "2 ** 10 should be 1024, got:\n$out")
    }

    // #10 — decimal `**` previously fell to the `else` branch and emitted nothing → the
    // result was silently the wrong operand (`2.0 ** 3.0` printed 3.00). Now exact via
    // KobolMath.power(BigDecimal, Int) with intValueExact on the exponent.
    @Test fun `decimal power operator is exact for integer exponent`() {
        val out = compileAndRun("""
            PROGRAM T
            DATA:
              d : DECIMAL(10,2) = 0.00
            PROCEDURE Main:
              COMPUTE d = 2.0 ** 3.0
              DISPLAY "d={d}"
              DISPLAY "---done---"
            END-PROCEDURE
        """)
        assertTrue("d=8.00" in out, "2.0 ** 3.0 should be 8.00, got:\n$out")
    }

    // #23 — `ADD buf TO list` must snapshot the record buffer. Previously it appended the
    // live reference, so mutating `buf` after the first ADD changed the stored element too
    // (both rows printed "banana 2"). Now each ADD stores a shallow copy.
    @Test fun `ADD record buffer to list snapshots the value`() {
        val out = compileAndRun("""
            PROGRAM T
            RECORD Item:
              name : TEXT
              qty  : INTEGER
            DATA:
              items : LIST OF Item
              buf   : Item
            PROCEDURE Main:
              MOVE "apple" TO buf.name
              MOVE 1 TO buf.qty
              ADD buf TO items
              MOVE "banana" TO buf.name
              MOVE 2 TO buf.qty
              ADD buf TO items
              FOR EACH it IN items:
                DISPLAY "{it.name} {it.qty}"
              END-FOR
              DISPLAY "---done---"
            END-PROCEDURE
        """)
        assertTrue("apple 1" in out, "first element must keep its snapshot value, got:\n$out")
        assertTrue("banana 2" in out, "second element should be banana 2, got:\n$out")
    }

    // #12 — MAP PUT/GET previously did not parse at all ("Unexpected token 'PUT'"). Now they
    // are contextual statements over a java.util.Map (LinkedHashMap): PUT inserts/overwrites,
    // GET looks up and unboxes into the target.
    @Test fun `MAP PUT and GET round-trip with overwrite`() {
        val out = compileAndRun("""
            PROGRAM T
            DATA:
              ages : MAP OF TEXT TO INTEGER
              a : INTEGER = 0
            PROCEDURE Main:
              PUT 42 TO ages WITH KEY "alice"
              PUT 7  TO ages WITH KEY "bob"
              GET ages KEY "alice" INTO a
              DISPLAY "alice={a}"
              GET ages KEY "bob" INTO a
              DISPLAY "bob={a}"
              PUT 99 TO ages WITH KEY "alice"
              GET ages KEY "alice" INTO a
              DISPLAY "alice2={a}"
              DISPLAY "---done---"
            END-PROCEDURE
        """)
        assertTrue("alice=42" in out, "GET after PUT should read 42, got:\n$out")
        assertTrue("bob=7" in out, "GET second key should read 7, got:\n$out")
        assertTrue("alice2=99" in out, "PUT on an existing key should overwrite, got:\n$out")
    }
}
