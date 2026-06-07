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

    /** Compile only (no run); return whether compilation succeeded. Diagnostics are swallowed. */
    private fun compiles(src: String, programName: String = "T"): Boolean {
        val source = File(outDir, "$programName.kbl").apply { writeText(src.trimIndent()) }
        val saved = System.out; val savedErr = System.err
        val sink = PrintStream(ByteArrayOutputStream())
        System.setOut(sink); System.setErr(sink)
        return try { compileFile(source, CompilerOptions(source, outDir), ModuleRegistry()) }
               finally { System.setOut(saved); System.setErr(savedErr) }
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

    @Test fun `uninitialized top-level MONEY defaults to zero (no NPE)`() {
        // Top-level DATA item of MONEY/DECIMAL with no initializer must default to
        // BigDecimal.ZERO — exactly like a MONEY field inside a record (whose no-arg
        // ctor zero-inits it). Previously it fell to the clinit `else` branch and was
        // left JVM-null → NullPointerException on first use.
        val out = compileAndRun("""
            PROGRAM T
            DATA:
              bal : MONEY
              rate : DECIMAL(18,8)
            PROCEDURE Main:
              DISPLAY bal
              DISPLAY rate
              DISPLAY "---done---"
            END-PROCEDURE
        """)
        assertTrue("0" in out, "uninit MONEY/DECIMAL should display as zero, got: $out")
        assertTrue("---done---" in out, "program must run to completion (no NPE), got: $out")
    }

    @Test fun `uninitialized UUID defaults to Nil UUID (no NPE)`() {
        // Spec §6: UUID defaults to the Nil UUID. Applies to BOTH a top-level DATA
        // item (clinit path) AND a UUID field inside a record (no-arg ctor path) —
        // previously both were left JVM-null → NPE on first use.
        val out = compileAndRun("""
            PROGRAM T
            RECORD Box:
              tag : UUID
            DATA:
              id : UUID
              b  : Box
            PROCEDURE Main:
              DISPLAY id
              DISPLAY b.tag
              DISPLAY "---done---"
            END-PROCEDURE
        """)
        assertTrue("00000000-0000-0000-0000-000000000000" in out,
            "uninit UUID (top-level + record field) should display as Nil UUID, got: $out")
        assertTrue("---done---" in out, "program must run to completion (no NPE), got: $out")
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

    // F1 — nullary variant case by BARE name (no parens): `MOVE Pending TO st`.
    // Before the fix this went through the Reference path → `E001: Undefined name 'PENDING'`;
    // only the parenthesised `Pending()` form constructed.
    @Test fun `nullary variant case by bare name constructs`() {
        val out = compileAndRun("""
            PROGRAM T
            VARIANT OrderStatus IS
              Pending
              | Shipped WITH tracking : TEXT
            DATA:
              st : OrderStatus
            PROCEDURE Main:
              MOVE Pending TO st
              MATCH st:
                WHEN Pending:
                  DISPLAY "is pending"
                WHEN Shipped WITH tracking:
                  DISPLAY "shipped {tracking}"
              END-MATCH
              DISPLAY "---done---"
            END-PROCEDURE
        """)
        assertTrue("is pending" in out, "bare-name nullary variant should construct + match Pending arm, got:\n$out")
    }

    // F4 — nested string literal inside {…} interpolation, PLAIN quotes (no escaping).
    // The interpolation body is re-lexed as an expression; a nested `"hi"` must lex + run.
    @Test fun `nested plain-quote string in interpolation runs`() {
        val out = compileAndRun("""
            PROGRAM T
            PROCEDURE Main:
              DISPLAY "val {UPPERCASE "hi"}"
              DISPLAY "---done---"
            END-PROCEDURE
        """)
        assertTrue("val HI" in out, "nested plain-quote string in interpolation should run, got:\n$out")
    }

    // F4 — the escaped form `\"hi\"` inside interpolation must NOT crash the lexer with a
    // cryptic "Unexpected character: '\'"; it should compile (treated as a nested string).
    @Test fun `escaped-quote string in interpolation does not crash lexer`() {
        assertTrue(
            compiles("""
                PROGRAM T
                PROCEDURE Main:
                  DISPLAY "val {UPPERCASE \"hi\"}"
                  DISPLAY "---done---"
                END-PROCEDURE
            """),
            "escaped-quote nested string in interpolation should compile, not error on '\\'"
        )
    }

    // F12 — construct a 3rd-party / classpath object: `NEW Owner WITH args` → JVM <init>.
    // Result is JAVA-OBJECT, bound via LET, then used as an instance-CALL receiver.
    @Test fun `NEW constructs a 3rd-party object with a constructor argument`() {
        val out = compileAndRun("""
            PROGRAM T
            IMPORT "java.lang.StringBuilder" AS SB
            DATA:
              result : TEXT
            PROCEDURE Main:
              LET b = NEW SB WITH "hello world"
              CALL b.toString GIVING result
              DISPLAY "built: {result}"
              DISPLAY "---done---"
            END-PROCEDURE
        """)
        assertTrue("built: hello world" in out, "NEW with a String ctor arg should construct + run, got:\n$out")
    }

    // F12 — no-argument constructor form: `NEW Owner` (no WITH).
    @Test fun `NEW constructs a 3rd-party object with no arguments`() {
        val out = compileAndRun("""
            PROGRAM T
            IMPORT "java.lang.StringBuilder" AS SB
            DATA:
              result : TEXT
            PROCEDURE Main:
              LET b = NEW SB
              CALL b.toString GIVING result
              DISPLAY "empty: [{result}]"
              DISPLAY "---done---"
            END-PROCEDURE
        """)
        assertTrue("empty: []" in out, "no-arg NEW should construct an empty StringBuilder, got:\n$out")
    }

    // F30 — NEW resolves its constructor off the classpath (like a CALL), so a real int-param ctor
    // links even though a Kobol INTEGER is a long. StringBuilder(int capacity) is (I)V; the old
    // guess built `<init>(J)V` (INTEGER→long) → NoSuchMethodError 'java.lang.StringBuilder.<init>(long)'
    // at run, type-checks clean = P3 landmine. resolveByArgs picks (I)V and narrows the arg via
    // F22's guarded Math.toIntExact.
    @Test fun `NEW resolves an int-param constructor and narrows the INTEGER arg (F30)`() {
        val out = compileAndRun("""
            PROGRAM T
            IMPORT "java.lang.StringBuilder" AS SB
            DATA:
              result : TEXT
            PROCEDURE Main:
              LET b = NEW SB WITH 16
              CALL b.append WITH "hi"
              CALL b.toString GIVING result
              DISPLAY "built: [{result}]"
              DISPLAY "---done---"
            END-PROCEDURE
        """)
        assertTrue("built: [hi]" in out,
            "NEW SB WITH 16 must link the StringBuilder(int) ctor (not the guessed (long)), got:\n$out")
        assertTrue("---done---" in out, "program must complete; got:\n$out")
    }

    // ─── F15 #6 — Kotlin `suspend` function → FUTURE bridge ──────────────────────────
    // A `suspend fun f(): T` compiles to `f(Continuation)Object` on the JVM. A Kobol CALL that
    // passes no Continuation guessed a zero-arg `f()` → NoSuchMethodError at run, type-checks
    // clean = P3 landmine. The fix recognises the @Metadata `isSuspend` flag and bridges the call
    // through KobolContinuation into a CompletableFuture, so the existing FUTURE/AWAIT machinery
    // consumes the result (P1 — reuse, no new async surface; P6 — one async rule).

    @Test fun `static Kotlin suspend function bridges to a FUTURE and AWAIT yields the value (F15)`() {
        val out = compileAndRun("""
            PROGRAM T
            IMPORT "dev.kobol.testfixture.KotlinSuspendApiKt" AS SUS
            DATA:
              fut    : FUTURE OF TEXT
              result : TEXT
            PROCEDURE Main:
              CALL SUS.suspendValue GIVING fut
              AWAIT fut INTO result
              DISPLAY "got: [{result}]"
              DISPLAY "---done---"
            END-PROCEDURE
        """)
        assertTrue("got: [suspended]" in out,
            "suspend suspendValue() must bridge to a FUTURE resolving to its value; got:\n$out")
        assertTrue("---done---" in out, "program must complete; got:\n$out")
    }

    @Test fun `instance Kotlin suspend method with an argument bridges to a FUTURE (F15)`() {
        val out = compileAndRun("""
            PROGRAM T
            IMPORT "dev.kobol.testfixture.SuspendService" AS SVC
            DATA:
              fut    : FUTURE OF TEXT
              result : TEXT
            PROCEDURE Main:
              LET s = NEW SVC
              CALL s.greet WITH "Ada" GIVING fut
              AWAIT fut INTO result
              DISPLAY "greet: [{result}]"
              DISPLAY "---done---"
            END-PROCEDURE
        """)
        assertTrue("greet: [hi Ada]" in out,
            "instance suspend greet(String) must bridge its receiver + arg through the Continuation; got:\n$out")
        assertTrue("---done---" in out, "program must complete; got:\n$out")
    }

    // F6 (decision lock) — an uninitialized DECIMAL default zero is REPRESENTED identically to an
    // explicit `= 0`: both are stored as `BigDecimal.ZERO` (scale 0). The F6 decision (keep the
    // stored default == explicit zero) still holds; F18 only changed how a DECIMAL is DISPLAYED —
    // at its declared scale. So both now show "0.00000000" (declared scale 8), and crucially they
    // remain CONSISTENT with each other. This locks default == explicit-zero at the display layer.
    @Test fun `uninitialized DECIMAL default matches explicit zero (F6)`() {
        val out = compileAndRun("""
            PROGRAM T
            DATA:
              d  : DECIMAL(18,8)
              d2 : DECIMAL(18,8) = 0
            PROCEDURE Main:
              DISPLAY "uninit=[{d}]"
              DISPLAY "init0=[{d2}]"
              DISPLAY "---done---"
            END-PROCEDURE
        """)
        assertTrue("uninit=[0.00000000]" in out, "uninitialized DECIMAL(18,8) should display at declared scale, got:\n$out")
        assertTrue("init0=[0.00000000]" in out, "explicit DECIMAL(18,8) = 0 should display at declared scale, got:\n$out")
    }

    // F18 — DISPLAY and string interpolation render a DECIMAL/MONEY at its DECLARED scale (zero-pad
    // up to it), not the raw BigDecimal scale: DECIMAL(18,8) holding 1.5 shows "1.50000000",
    // MONEY(10,2) holding 5 shows "5.00". Pad-only — a value carrying MORE fraction digits than
    // declared is shown in full (never rounded), so DISPLAY can never hide stored precision (P4).
    @Test fun `DISPLAY renders a DECIMAL at its declared scale, padding (F18)`() {
        val out = compileAndRun("""
            PROGRAM T
            DATA:
              d : DECIMAL(18,8) = 1.5
              m : MONEY(10,2)   = 5
            PROCEDURE Main:
              DISPLAY "d=[{d}]"
              DISPLAY "m=[" m "]"
              DISPLAY "---done---"
            END-PROCEDURE
        """)
        assertTrue("d=[1.50000000]" in out, "DECIMAL(18,8) 1.5 should pad to declared scale, got:\n$out")
        assertTrue("5.00" in out, "MONEY(10,2) 5 should pad to 5.00, got:\n$out")
        assertTrue("---done---" in out, "program must run to completion, got:\n$out")
    }

    // F18 (P4 guard) — a value carrying MORE fraction digits than the declared scale is shown in
    // FULL, never rounded down, so DISPLAY cannot silently hide stored precision. A DECIMAL(10,2)
    // computed to 3 fraction digits via full-precision division must still print all 3.
    @Test fun `DISPLAY never rounds away fraction digits beyond declared scale (F18)`() {
        // `wide` is declared scale 1 but a LET initializer is not rescaled to the declared scale,
        // so it stores the literal's true scale (3). Pad-only must show all 3 digits ("0.125"),
        // NOT round down to the declared scale ("0.1") — that would hide stored precision (P4).
        val out = compileAndRun("""
            PROGRAM T
            PROCEDURE Main:
              LET wide : DECIMAL(10,1) = 0.125
              DISPLAY "wide=[{wide}]"
              DISPLAY "---done---"
            END-PROCEDURE
        """)
        assertTrue("wide=[0.125]" in out, "value with more fraction digits than declared must print in full (not rounded), got:\n$out")
    }

    // F7 — field-init dedup must preserve the two-phase ordering: a DATA initializer that
    // REFERENCES a CONFIG value has to run in the main() prologue (AFTER config load), not only
    // in <clinit> (before config). If the shared helper collapsed the phases, `doubled` would
    // stay 0 (config unloaded at class-load). Locks the behavior the refactor must keep.
    @Test fun `DATA initializer referencing CONFIG runs after config load (F7)`() {
        val out = compileAndRun("""
            PROGRAM T
            CONFIG:
              base-rate : INTEGER FROM ENV "F7_BASE_RATE" DEFAULT 10
            DATA:
              doubled : INTEGER = base-rate * 2
            PROCEDURE Main:
              DISPLAY "doubled={doubled}"
              DISPLAY "---done---"
            END-PROCEDURE
        """)
        assertTrue("doubled=20" in out, "config-referencing DATA initializer must run post-config (20), got:\n$out")
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

    // F16 — variant-typed list elements need NO snapshot (the bug F2 split out is unreachable).
    // A RECORD buffer is a single reused instance whose fields are mutated in place (MOVE x TO
    // buf.field), so ADD must snapshot it. A VARIANT has no such mutation path: case fields are
    // not a dotted lvalue (TypeCheckerTest rejects `st.tracking` with E002), so the only way to
    // "change" the holder `st` is to reassign it with a freshly-constructed instance
    // (MOVE Shipped("B") TO st), which never touches the instance already stored in the list.
    // Hence two ADDs of the same holder keep their distinct values with no copy() needed.
    @Test fun `variant list elements keep distinct values without snapshot (F16)`() {
        val out = compileAndRun("""
            PROGRAM T
            VARIANT OrderStatus IS
              Pending
              | Shipped WITH tracking : TEXT
            DATA:
              orders : LIST OF OrderStatus
              st     : OrderStatus
            PROCEDURE Main:
              MOVE Shipped("A") TO st
              ADD st TO orders
              MOVE Shipped("B") TO st
              ADD st TO orders
              FOR EACH o IN orders:
                MATCH o:
                  WHEN Pending:
                    DISPLAY "pending"
                  WHEN Shipped WITH tracking:
                    DISPLAY "tr={tracking}"
                END-MATCH
              END-FOR
              DISPLAY "---done---"
            END-PROCEDURE
        """)
        assertTrue("tr=A" in out, "first variant element must keep value A (no aliasing), got:\n$out")
        assertTrue("tr=B" in out, "second variant element must be B, got:\n$out")
    }

    // F2 — `MOVE rec TO rec` must snapshot the source buffer, not alias it. Previously the
    // destination held the live reference, so mutating the source after the MOVE leaked into
    // the destination too.
    @Test fun `MOVE record to record snapshots the value`() {
        val out = compileAndRun("""
            PROGRAM T
            RECORD Item:
              name : TEXT
              qty  : INTEGER
            DATA:
              a : Item
              b : Item
            PROCEDURE Main:
              MOVE "apple" TO a.name
              MOVE 1 TO a.qty
              MOVE a TO b
              MOVE "banana" TO a.name
              MOVE 2 TO a.qty
              DISPLAY "b={b.name} {b.qty}"
              DISPLAY "a={a.name} {a.qty}"
              DISPLAY "---done---"
            END-PROCEDURE
        """)
        assertTrue("b=apple 1" in out, "MOVE dest must keep its snapshot, got:\n$out")
        assertTrue("a=banana 2" in out, "source must reflect later mutation, got:\n$out")
    }

    // F2 — `PUT rec TO map` must snapshot the record value, not alias the live buffer.
    @Test fun `MAP PUT record value snapshots the value`() {
        val out = compileAndRun("""
            PROGRAM T
            RECORD Item:
              name : TEXT
              qty  : INTEGER
            DATA:
              m   : MAP OF TEXT TO Item
              buf : Item
              got : Item
            PROCEDURE Main:
              MOVE "apple" TO buf.name
              MOVE 1 TO buf.qty
              PUT buf TO m WITH KEY "k"
              MOVE "banana" TO buf.name
              MOVE 2 TO buf.qty
              GET m KEY "k" INTO got
              DISPLAY "got={got.name} {got.qty}"
              DISPLAY "---done---"
            END-PROCEDURE
        """)
        assertTrue("got=apple 1" in out, "map value must keep its snapshot, got:\n$out")
    }

    // F2 (variant leg) — constructing a VARIANT case with a RECORD-typed field arg must snapshot
    // the record buffer, not alias it. `copy()` is shallow by contract ("nested records are copied
    // at their own copy sites" — AsmEmitter.emitRecordCopy) and a variant case field IS such a site.
    // Before the fix `Full(buf)` stored the live `buf` reference into the variant's field, so a later
    // `MOVE … TO buf.field` leaked into the already-constructed variant (read back via MATCH).
    @Test fun `variant construction snapshots a record-typed field argument (F2)`() {
        val out = compileAndRun("""
            PROGRAM T
            RECORD Item:
              name : TEXT
              qty  : INTEGER
            VARIANT Wrap IS
              Empty
              | Full WITH it : Item
            DATA:
              buf : Item
              w   : Wrap
            PROCEDURE Main:
              MOVE "apple" TO buf.name
              MOVE 1 TO buf.qty
              MOVE Full(buf) TO w
              MOVE "banana" TO buf.name
              MOVE 2 TO buf.qty
              MATCH w:
                WHEN Empty:
                  DISPLAY "empty"
                WHEN Full WITH it:
                  DISPLAY "w={it.name} {it.qty}"
              END-MATCH
              DISPLAY "---done---"
            END-PROCEDURE
        """)
        assertTrue("w=apple 1" in out, "variant case field must keep its snapshot, not alias buf, got:\n$out")
    }

    // F2 (record-literal leg) — a RECORD literal with a RECORD-typed field must snapshot the nested
    // record value, not alias it. Same shallow-copy contract: the nested-record field of a literal is
    // one of the "own copy sites". Before the fix `Box { it: buf }` aliased `buf`; the outer MOVE's
    // shallow copy preserved that shared reference, so mutating `buf` leaked into `bx.it`.
    @Test fun `record literal snapshots a nested record-typed field (F2)`() {
        val out = compileAndRun("""
            PROGRAM T
            RECORD Item:
              name : TEXT
              qty  : INTEGER
            RECORD Box:
              it : Item
            DATA:
              buf : Item
              bx  : Box
            PROCEDURE Main:
              MOVE "apple" TO buf.name
              MOVE 1 TO buf.qty
              MOVE Box { it: buf } TO bx
              MOVE "banana" TO buf.name
              MOVE 2 TO buf.qty
              DISPLAY "bx={bx.it.name} {bx.it.qty}"
              DISPLAY "---done---"
            END-PROCEDURE
        """)
        assertTrue("bx=apple 1" in out, "nested record field must keep its snapshot, not alias buf, got:\n$out")
    }

    // Read-hole (found during F2) — a second-level record field read (`bx.it.name`) loads the
    // intermediate record via a GETFIELD whose descriptor erases to Object (records erase); the
    // next GETFIELD on the concrete record class then needs a CHECKCAST or the verifier rejects it
    // ("Object not assignable to T${'$'}Item"). One-level reads worked only because a root DATA field
    // loads concrete via GETSTATIC. No aliasing here — pure nested read must just verify and run.
    @Test fun `nested record field reads without a VerifyError`() {
        val out = compileAndRun("""
            PROGRAM T
            RECORD Item:
              name : TEXT
              qty  : INTEGER
            RECORD Box:
              it : Item
            DATA:
              bx : Box
            PROCEDURE Main:
              MOVE Box { it: Item { name: "apple", qty: 7 } } TO bx
              DISPLAY "bx={bx.it.name} {bx.it.qty}"
              DISPLAY "---done---"
            END-PROCEDURE
        """)
        assertTrue("bx=apple 7" in out, "nested record field read must verify and yield the stored value, got:\n$out")
    }

    // (D) Write-hole — a deep field-chain WRITE (`bx.it.qty`) previously only walked ONE level:
    // emitStore treated parts[1] ("it", a record = category-1) as the final field and PUTFIELDed the
    // value there, picking the rotation by `it`'s wideness. For an INTEGER value (a category-2 long)
    // that emitted `SWAP` over a long → java.lang.VerifyError ("long_2nd is not assignable to
    // category1 type"), and even for reference values it wrote the wrong (intermediate) field. The
    // store path must walk the full chain (parts[0..n-2] as record loads) and PUTFIELD the FINAL
    // field with the FINAL field's wideness — mirroring loadRef's chain walk.
    @Test fun `nested record field write of an INTEGER verifies and stores the value (D)`() {
        val out = compileAndRun("""
            PROGRAM T
            RECORD Item:
              name : TEXT
              qty  : INTEGER
            RECORD Box:
              it : Item
            DATA:
              bx : Box
            PROCEDURE Main:
              MOVE Box { it: Item { name: "apple", qty: 7 } } TO bx
              MOVE 9 TO bx.it.qty
              MOVE "pear" TO bx.it.name
              DISPLAY "bx={bx.it.name} {bx.it.qty}"
              DISPLAY "---done---"
            END-PROCEDURE
        """)
        assertTrue("bx=pear 9" in out, "deep field-chain write must verify and update the final field, got:\n$out")
    }

    // (C) Deep value semantics — `MOVE a TO b` snapshots via the synthetic `copy()`, but `copy()`
    // was SHALLOW: it reference-copied each field, so a record-typed field left `b.it === a.it`.
    // Mutating the source's nested field (`a.it.qty`) then leaked into the already-copied `b`.
    // Value semantics must hold to ALL depths: emitRecordCopy must `copy()` record-typed fields
    // recursively. (Reachable only now that deep nested writes verify — bug (D) above.)
    @Test fun `MOVE deep-copies a nested record field (C)`() {
        val out = compileAndRun("""
            PROGRAM T
            RECORD Item:
              name : TEXT
              qty  : INTEGER
            RECORD Box:
              it : Item
            DATA:
              a : Box
              b : Box
            PROCEDURE Main:
              MOVE Box { it: Item { name: "apple", qty: 7 } } TO a
              MOVE a TO b
              MOVE 99 TO a.it.qty
              MOVE "banana" TO a.it.name
              DISPLAY "b={b.it.name} {b.it.qty}"
              DISPLAY "a={a.it.name} {a.it.qty}"
              DISPLAY "---done---"
            END-PROCEDURE
        """)
        assertTrue("b=apple 7" in out, "MOVE dest must deep-copy nested record, not alias a.it, got:\n$out")
        assertTrue("a=banana 99" in out, "source nested mutation must be visible on the source, got:\n$out")
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

    // F3 — verify the spec §13 collection ops that DO run: LENGTH on both a list and a map.
    @Test fun `LENGTH on list and map runs`() {
        val out = compileAndRun("""
            PROGRAM T
            DATA:
              xs : LIST OF INTEGER
              m  : MAP OF TEXT TO INTEGER
              n  : INTEGER = 0
            PROCEDURE Main:
              ADD 10 TO xs
              ADD 20 TO xs
              PUT 1 TO m WITH KEY "a"
              PUT 2 TO m WITH KEY "b"
              PUT 3 TO m WITH KEY "c"
              COMPUTE n = LENGTH xs
              DISPLAY "list={n}"
              COMPUTE n = LENGTH m
              DISPLAY "map={n}"
              DISPLAY "---done---"
            END-PROCEDURE
        """)
        assertTrue("list=2" in out, "LENGTH on a list, got:\n$out")
        assertTrue("map=3"  in out, "LENGTH on a map, got:\n$out")
    }

    // F3 — the spec §13 ops that were documented but NEVER implemented (no parser support):
    // `ADD … AT FIRST`, `REMOVE … FROM <list>`, `REMOVE <map> KEY k`. Cut from the spec.
    // This guards against a silent half-implementation re-appearing as a documented landmine.
    @Test fun `unimplemented collection ops are rejected (spec §13 cut)`() {
        for (stmt in listOf(
            "ADD 1 TO xs AT FIRST",
            "REMOVE 1 FROM xs",
            "REMOVE m KEY \"a\"",
        )) {
            val ok = compiles("""
                PROGRAM T
                DATA:
                  xs : LIST OF INTEGER
                  m  : MAP OF TEXT TO INTEGER
                PROCEDURE Main:
                  $stmt
                END-PROCEDURE
            """)
            assertTrue(!ok, "expected '$stmt' to be rejected (unimplemented), but it compiled")
        }
    }

    // F19: a local ASYNC RETURNING procedure, awaited, must yield its computed value.
    // The async runnable wrapper boxed the CompletableFuture (loaded BEFORE the result)
    // instead of the result → VerifyError at class load. Never executed before E1, so the
    // bug sat latent. Awaiting the future proves the wrapper completes the CF with the value.
    @Test fun `local async RETURNING procedure awaited yields its value (F19)`() {
        val out = compileAndRun("""
            PROGRAM T
            ASYNC PROCEDURE Doubler USING n : INTEGER RETURNING INTEGER:
              RETURN n * 2
            END-PROCEDURE
            DATA:
              f : FUTURE OF INTEGER
              result : INTEGER = 0
            PROCEDURE Main:
              PERFORM Doubler USING 21 GIVING f
              AWAIT f INTO result
              DISPLAY "result={result}"
              DISPLAY "---done---"
            END-PROCEDURE
        """)
        assertTrue("result=42" in out, "awaited async result must be 42; got: $out")
        assertTrue("---done---" in out, "program must complete; got: $out")
    }

    // ─── E2 (increment-1): classpath-aware CALL descriptor resolution ────────────

    @Test fun `fire-and-forget interop CALL links to the real return descriptor (E2)`() {
        // CALL System.lineSeparator with no GIVING: emitCall used to guess the return
        // descriptor as Ljava/lang/Object; (the no-GIVING default) → emits
        // System.lineSeparator()Ljava/lang/Object; which does NOT exist → NoSuchMethodError
        // at run, type-checks clean (P3 landmine). The real method is ()Ljava/lang/String;.
        // E2 reads java.lang.System off the classpath, finds the unique no-arg lineSeparator,
        // and emits its real descriptor so the call links and the program completes.
        val out = compileAndRun("""
            PROGRAM T
            PROCEDURE Main:
              CALL System.lineSeparator
              DISPLAY "---done---"
            END-PROCEDURE
        """)
        assertTrue("---done---" in out,
            "interop CALL must link to the real descriptor and run to completion; got: $out")
    }

    // ─── E2 (increment-2): overload coercion ranking (F13) + return propagation (F14) ──

    @Test fun `interop CALL widens an int arg to a double-only overload (F13)`() {
        // Math.sqrt has ONE overload, (D)D. A Kobol INTEGER arg emits a long (J). Inc-1 only
        // used the real descriptor when params matched the emitted args EXACTLY (J != D), so it
        // fell back to the guess and emitted sqrt(J)… → NoSuchMethodError at run (P3 landmine).
        // F13 ranks the candidate, sees J widens to D, emits L2D before the call, links sqrt(D)D,
        // and F14 converts the real double return into the declared DECIMAL (BigDecimal) GIVING var.
        val out = compileAndRun("""
            PROGRAM T
            DATA:
              result : DECIMAL = 0
            PROCEDURE Main:
              CALL Math.sqrt WITH 16 GIVING result
              DISPLAY result
              DISPLAY "---done---"
        """)
        assertTrue("4.0" in out, "sqrt(16) must widen int→double, link, and yield 4.0; got:\n$out")
        assertTrue("---done---" in out, "program must complete; got:\n$out")
    }

    @Test fun `interop CALL picks an overload by widening a mismatched arg width (F13)`() {
        // Math.max has int/long/float/double overloads (4 at arity 2). A SMALLINT (I) and an
        // INTEGER (J) arg make the Kobol-side guess "(IJ)" → Math.max(int,long) does NOT exist
        // → NoSuchMethodError at run, type-checks clean (P3 landmine). F13 ranks by coercion
        // cost, widens the SMALLINT I→J, and picks (JJ)J — the cheapest viable overload.
        val out = compileAndRun("""
            PROGRAM T
            DATA:
              a : SMALLINT = 3
              b : INTEGER = 7
              n : INTEGER = 0
            PROCEDURE Main:
              CALL Math.max WITH a, b GIVING n
              DISPLAY n
              DISPLAY "---done---"
        """)
        assertTrue(out.lineSequence().any { it.trim() == "7" },
            "Math.max(SMALLINT 3, INTEGER 7) must widen+resolve to (long,long) and yield 7; got:\n$out")
        assertTrue("---done---" in out, "program must complete; got:\n$out")
    }

    // ─── F21: instance-receiver interop CALL routed through the classpath resolver ────

    @Test fun `instance CALL links to the real return descriptor and widens it (F21)`() {
        // String.length() returns int — ()I. A Kobol INTEGER GIVING target is a long, so the
        // instance branch guessed length()J → NoSuchMethodError at run, type-checks clean
        // (the same P3 landmine F13/F14 fixed for static calls, on the INVOKEVIRTUAL path).
        // F21 routes the typed receiver (TEXT → java/lang/String) through resolveByArgs,
        // links the real length()I, and widens the int return I→J into the INTEGER var.
        val out = compileAndRun("""
            PROGRAM T
            DATA:
              s : TEXT = "hello"
              n : INTEGER = 0
            PROCEDURE Main:
              CALL s.length GIVING n
              DISPLAY n
              DISPLAY "---done---"
        """)
        assertTrue(out.lineSequence().any { it.trim() == "5" },
            "String.length() must link (int return) and widen to 5; got:\n$out")
        assertTrue("---done---" in out, "program must complete; got:\n$out")
    }

    @Test fun `instance CALL picks the String overload and widens its int return (F21)`() {
        // String.indexOf is overloaded — indexOf(int) and indexOf(String). With a TEXT arg the
        // ranker excludes indexOf(int) (a String can't coerce to int) and picks indexOf(String),
        // whose int return (()I → here (Ljava/lang/String;)I) widens into the INTEGER GIVING var.
        // The old guess indexOf(Ljava/lang/String;)J named a non-existent return → NoSuchMethodError.
        val out = compileAndRun("""
            PROGRAM T
            DATA:
              s : TEXT = "hello"
              n : INTEGER = 0
            PROCEDURE Main:
              CALL s.indexOf WITH "lo" GIVING n
              DISPLAY n
              DISPLAY "---done---"
        """)
        assertTrue(out.lineSequence().any { it.trim() == "3" },
            "\"hello\".indexOf(\"lo\") must resolve the String overload and yield 3; got:\n$out")
        assertTrue("---done---" in out, "program must complete; got:\n$out")
    }

    @Test fun `interop CALL narrows an INTEGER arg into a Java int param (F22)`() {
        // String.substring(int) takes int — (I)Ljava/lang/String;. A Kobol INTEGER is a long (J).
        // JvmCoercion forbade narrowing, so resolveByArgs excluded the only overload and the call
        // fell back to the guess substring(J) → NoSuchMethodError 'java.lang.String.substring(long)',
        // type-checks clean = P3 landmine. F22 allows the guarded J→I narrowing (Math.toIntExact:
        // throws on real overflow, never silently truncates), so the int-param overload links.
        val out = compileAndRun("""
            PROGRAM T
            DATA:
              s : TEXT = "hello"
              i : INTEGER = 2
              t : TEXT = ""
            PROCEDURE Main:
              CALL s.substring WITH i GIVING t
              DISPLAY t
              DISPLAY "---done---"
        """)
        assertTrue(out.lineSequence().any { it.trim() == "llo" },
            "\"hello\".substring(2) must link the int-param overload and yield \"llo\"; got:\n$out")
        assertTrue("---done---" in out, "program must complete; got:\n$out")
    }

    @Test fun `instance CALL on a NEW-constructed object resolves against its concrete class (F21)`() {
        // A value from NEW is typed JAVA-OBJECT. Before F21-full the type erased the concrete class
        // to java/lang/Object, so an instance CALL on it guessed a method on Object →
        // NoSuchMethodError 'java.lang.Object.append(...)', type-checks clean = P3 landmine.
        // F21-full carries the NEW owner through the JAVA-OBJECT type, so the receiver resolves to
        // java/lang/StringBuilder and the real append(String)/toString() link.
        val out = compileAndRun("""
            PROGRAM T
            IMPORT "java.lang.StringBuilder" AS SB
            DATA:
              t : TEXT
            PROCEDURE Main:
              LET bldr = NEW SB
              CALL bldr.append WITH "hi"
              CALL bldr.append WITH "there"
              CALL bldr.toString GIVING t
              DISPLAY t
              DISPLAY "---done---"
            END-PROCEDURE
        """)
        assertTrue(out.lineSequence().any { it.trim() == "hithere" },
            "NEW StringBuilder append/toString must resolve on the concrete class; got:\n$out")
        assertTrue("---done---" in out, "program must complete; got:\n$out")
    }

    @Test fun `instance CALL on a var whose name collides with an IMPORT alias resolves to the var (F23)`() {
        // The local `sb` uppercases to `SB`, the SAME key as `IMPORT … AS SB`. Pre-F23 emitCall
        // checked the import alias BEFORE the local, so `CALL sb.append` mis-routed to an
        // INVOKESTATIC on java.lang.StringBuilder → wrong descriptor at run, type-checks clean =
        // P3 landmine. The more-specific scope (a real variable) must win.
        val out = compileAndRun("""
            PROGRAM T
            IMPORT "java.lang.StringBuilder" AS SB
            DATA:
              t : TEXT
            PROCEDURE Main:
              LET sb = NEW SB
              CALL sb.append WITH "hi"
              CALL sb.append WITH "there"
              CALL sb.toString GIVING t
              DISPLAY t
              DISPLAY "---done---"
            END-PROCEDURE
        """)
        assertTrue(out.lineSequence().any { it.trim() == "hithere" },
            "a variable must win over a same-named IMPORT alias; got:\n$out")
        assertTrue("---done---" in out, "program must complete; got:\n$out")
    }

    @Test fun `JAVA-OBJECT carries its class across a procedure boundary (F26)`() {
        // F21-full carries a NEW object's concrete class only within LOCAL flow (the COMPUTE_FRAMES
        // slot type). Crossing a procedure boundary erases it: a USING param typed by an imported
        // class name was a dangling RecordRefType, and the instance CALL pushed the receiver with no
        // CHECKCAST → guess/VerifyError. F26: an IMPORT-alias type carries the owner, and the
        // receiver is CHECKCAST to the resolved class, so `b.append` links java.lang.StringBuilder
        // inside a procedure that received the builder from its caller.
        val out = compileAndRun("""
            PROGRAM T
            IMPORT "java.lang.StringBuilder" AS SB
            DATA:
              result : TEXT
            PROCEDURE AppendWorld USING b : SB:
              CALL b.append WITH "world"
            END-PROCEDURE
            PROCEDURE Main:
              LET sb = NEW SB
              CALL sb.append WITH "hello "
              PERFORM AppendWorld USING sb
              CALL sb.toString GIVING result
              DISPLAY result
              DISPLAY "---done---"
            END-PROCEDURE
        """)
        assertTrue(out.lineSequence().any { it.trim() == "hello world" },
            "the builder mutated inside AppendWorld must be the same object; got:\n$out")
        assertTrue("---done---" in out, "program must complete; got:\n$out")
    }

    @Test fun `interop CALL packs trailing args into a varargs parameter (F24)`() {
        // String.format(String, Object...) is varargs — descriptor (String,[Object])String. With
        // no varargs support a 3-arg call mis-linked the fixed-arity-3 format(Locale,String,Object[])
        // (the ranker's ref→ref CHECKCAST let "%s-%s" coerce to Locale) → ClassCastException at run,
        // type-checks clean = P3 landmine. F24 matches the varargs overload, packs the trailing args
        // into an Object[], and links the real descriptor (and being cheaper, beats the Locale match).
        val out = compileAndRun("""
            PROGRAM T
            IMPORT "java.lang.String" AS Str
            DATA:
              result : TEXT
            PROCEDURE Main:
              CALL Str.format WITH "%s-%s", "a", "b" GIVING result
              DISPLAY result
              DISPLAY "---done---"
            END-PROCEDURE
        """)
        assertTrue(out.lineSequence().any { it.trim() == "a-b" },
            "String.format must pack varargs and yield \"a-b\"; got:\n$out")
        assertTrue("---done---" in out, "program must complete; got:\n$out")
    }

    // ─── #5 — Kotlin/Java property accessors via `obj.field` ──────────────────────────
    // `obj.field` on a JAVA-OBJECT used to be a blind hardcode: the type checker typed ANY field as
    // TEXT and codegen emitted nothing (`else -> break`), leaving the object itself on the stack —
    // a P3 landmine (type-checks clean, wrong value at run). The fix resolves the property's getter
    // off the compile classpath (the SAME path a `CALL obj.getField` takes, P1), infers the real
    // property type, and emits the getter call.
    @Test fun `obj field reads a Kotlin property via its getter (#5)`() {
        val out = compileAndRun("""
            PROGRAM T
            IMPORT "dev.kobol.testfixture.KotlinBean" AS Bean
            PROCEDURE Main:
              LET b = NEW Bean WITH "Ada"
              DISPLAY b.name
              DISPLAY "score={b.score}"
              IF b.active:
                DISPLAY "is-active"
              END-IF
              DISPLAY "---done---"
            END-PROCEDURE
        """)
        assertTrue(out.lineSequence().any { it.trim() == "Ada" },
            "b.name must read the String property via getName(); got:\n$out")
        assertTrue("score=42" in out, "b.score must read the int property via getScore(); got:\n$out")
        assertTrue("is-active" in out, "b.active must read the Boolean property via getActive(); got:\n$out")
        assertTrue("---done---" in out, "program must complete; got:\n$out")
    }

    // ─── F14 — interop CALL as an expression (CALL in COMPUTE/LET position) ───────────
    // A `CALL` in expression position must resolve its REAL return type at type-check time
    // (E2 classpath read), type the LHS from it, and leave the coerced value on the stack —
    // not the type-check-clean→runtime-crash landmine an opaque Object guess would create.

    @Test fun `static interop CALL as an expression types the LET from the real return (F14)`() {
        // Math.max(long,long) → long; the LET infers INTEGER (J) from the real descriptor, no
        // annotation. Auto-imported java.lang.Math (JAVA_LANG_OWNERS), no explicit IMPORT.
        val out = compileAndRun("""
            PROGRAM T
            DATA:
              a : INTEGER = 7
              b : INTEGER = 12
            PROCEDURE Main:
              LET n = CALL Math.max WITH a, b
              DISPLAY "n={n}"
              DISPLAY "---done---"
            END-PROCEDURE
        """)
        assertTrue("n=12" in out, "CALL Math.max WITH 7, 12 should yield 12; got:\n$out")
        assertTrue("---done---" in out, "program must complete; got:\n$out")
    }

    @Test fun `instance interop CALL as an expression resolves the receiver return type (F14)`() {
        // s.substring(int) → String; COMPUTE target is TEXT, the real return coerces cleanly.
        // The INTEGER arg (J) narrows into the int param via F22's guarded Math.toIntExact.
        val out = compileAndRun("""
            PROGRAM T
            DATA:
              s : TEXT = "hello"
              start : INTEGER = 1
              tail : TEXT
            PROCEDURE Main:
              COMPUTE tail = CALL s.substring WITH start
              DISPLAY "tail={tail}"
              DISPLAY "---done---"
            END-PROCEDURE
        """)
        assertTrue("tail=ello" in out, "CALL s.substring WITH 1 should yield \"ello\"; got:\n$out")
        assertTrue("---done---" in out, "program must complete; got:\n$out")
    }

    @Test fun `CALL expression result feeds directly into a larger expression (F14)`() {
        // The CALL-expr value composes: COMPUTE doubled = (CALL Math.max ...) * 2.
        // Proves the call leaves a usable typed value on the stack, not a discarded statement.
        val out = compileAndRun("""
            PROGRAM T
            DATA:
              a : INTEGER = 5
              b : INTEGER = 9
              doubled : INTEGER = 0
            PROCEDURE Main:
              COMPUTE doubled = (CALL Math.max WITH a, b) * 2
              DISPLAY "doubled={doubled}"
              DISPLAY "---done---"
            END-PROCEDURE
        """)
        assertTrue("doubled=18" in out, "(max(5,9)) * 2 should be 18; got:\n$out")
        assertTrue("---done---" in out, "program must complete; got:\n$out")
    }

    // -------------------------------------------------------------------------
    // v2 foundation miscompiles — builtin call-boundary / field-init codegen.
    // Each previously type-checked clean then died with a VerifyError at class
    // load; these load+run the bytecode, which is what catches the regression.
    // -------------------------------------------------------------------------

    @Test fun `POWER builtin computes a decimal result (v5)`() {
        // POWER is typed DECIMAL; codegen now routes it through the BigDecimal
        // helper (not the int power(JJ)J overload that mismatched the operands).
        val out = compileAndRun("""
            PROGRAM T
            DATA:
              raised : DECIMAL(12,4)
            PROCEDURE Main:
              COMPUTE raised = POWER(2, 8)
              DISPLAY "raised={raised}"
              DISPLAY "---done---"
            END-PROCEDURE
        """)
        assertTrue("raised=256" in out, "POWER(2,8) should be 256; got:\n$out")
        assertTrue("---done---" in out, "program must complete; got:\n$out")
    }

    @Test fun `SIGN of a decimal yields an INTEGER (v8)`() {
        // SIGN returns a Kobol INTEGER (JVM long); the result drops into a long
        // slot without the int-to-long widening gap that crashed at load.
        val out = compileAndRun("""
            PROGRAM T
            DATA:
              neg-val : DECIMAL(10,2) = -8.50
              s : INTEGER
            PROCEDURE Main:
              COMPUTE s = SIGN(neg-val)
              DISPLAY "s={s}"
              DISPLAY "---done---"
            END-PROCEDURE
        """)
        assertTrue("s=-1" in out, "SIGN(-8.50) should be -1; got:\n$out")
        assertTrue("---done---" in out, "program must complete; got:\n$out")
    }

    @Test fun `MATCH range pattern over MONEY (v3)`() {
        // Decimal/Money range bounds compare with BigDecimal.compareTo, not the
        // String.compareTo path that crashed on the language's flagship type.
        val out = compileAndRun("""
            PROGRAM T
            DATA:
              amount : MONEY = 5000.00
              bucket : TEXT
            PROCEDURE Main:
              MATCH amount:
                WHEN 0.01 .. 999.99:
                  MOVE "SMALL" TO bucket
                WHEN 1000.00 .. 9999.99:
                  MOVE "MEDIUM" TO bucket
                OTHERWISE:
                  MOVE "LARGE" TO bucket
              END-MATCH
              DISPLAY "bucket={bucket}"
              DISPLAY "---done---"
            END-PROCEDURE
        """)
        assertTrue("bucket=MEDIUM" in out, "5000.00 falls in 1000..9999.99; got:\n$out")
        assertTrue("---done---" in out, "program must complete; got:\n$out")
    }

    @Test fun `bare nullary builtin as a DATE field initializer (v1)`() {
        // The spec's documented form `d : DATE = TODAY` (no parens). A bare nullary
        // builtin lowers to the builtin call, yielding a LocalDate — not a phantom
        // Object-typed field load that the verifier rejected in <clinit>.
        val out = compileAndRun("""
            PROGRAM T
            DATA:
              a-date : DATE = TODAY
            PROCEDURE Main:
              DISPLAY "date-set={a-date}"
              DISPLAY "---done---"
            END-PROCEDURE
        """)
        assertTrue("date-set=" in out, "bare TODAY initializer must run; got:\n$out")
        assertTrue("---done---" in out, "program must complete; got:\n$out")
    }

    // -------------------------------------------------------------------------
    // v2 prose surface — English-prose numeric builtins + SPLIT (spec §12, §11.2),
    // and v7 list indexing (spec §11.2). These parsed-then-ran end to end.
    // -------------------------------------------------------------------------

    @Test fun `prose numeric builtin forms compile load and run (v2)`() {
        // Every spec §12 prose form: ROUND..TO[..USING], MAX/MIN a,b, MOD..BY,
        // POWER..BY, SQRT/ABS/SIGN/FLOOR/CEIL x, plus an arithmetic ROUND head
        // (`ROUND base * pct TO 2`) to prove the value before TO is a full expression.
        val out = compileAndRun("""
            PROGRAM T
            DATA:
              amount : DECIMAL(12,4) = 2.5450
              a-val  : DECIMAL(10,2) = 12.34
              b-val  : DECIMAL(10,2) = 56.78
              neg    : DECIMAL(10,2) = -8.50
              rate   : DECIMAL(10,2) = 3.70
              base   : DECIMAL(10,2) = 10.00
              pct    : DECIMAL(10,4) = 0.0750
              he : DECIMAL(12,2)
              hu : DECIMAL(12,2)
              mx : DECIMAL(10,2)
              mn : DECIMAL(10,2)
              rem : INTEGER
              pw : DECIMAL(12,4)
              rt : DECIMAL(12,4)
              av : DECIMAL(10,2)
              sg : INTEGER
              fl : DECIMAL(12,4)
              ce : DECIMAL(12,4)
              tx : DECIMAL(12,2)
            PROCEDURE Main:
              COMPUTE he  = ROUND amount TO 2
              COMPUTE hu  = ROUND amount TO 2 USING HALF-UP
              COMPUTE mx  = MAX a-val, b-val
              COMPUTE mn  = MIN a-val, b-val
              COMPUTE rem = MOD 17 BY 5
              COMPUTE pw  = POWER 2 BY 8
              COMPUTE rt  = SQRT 16
              COMPUTE av  = ABS neg
              COMPUTE sg  = SIGN neg
              COMPUTE fl  = FLOOR rate
              COMPUTE ce  = CEIL rate
              COMPUTE tx  = ROUND base * pct TO 2
              DISPLAY "he={he}"
              DISPLAY "hu={hu}"
              DISPLAY "mx={mx}"
              DISPLAY "mn={mn}"
              DISPLAY "rem={rem}"
              DISPLAY "pw={pw}"
              DISPLAY "rt={rt}"
              DISPLAY "av={av}"
              DISPLAY "sg={sg}"
              DISPLAY "fl={fl}"
              DISPLAY "ce={ce}"
              DISPLAY "tx={tx}"
              DISPLAY "---done---"
            END-PROCEDURE
        """)
        assertTrue("he=2.54" in out, "ROUND 2.545 TO 2 HALF-EVEN = 2.54; got:\n$out")
        assertTrue("hu=2.55" in out, "ROUND 2.545 TO 2 USING HALF-UP = 2.55; got:\n$out")
        assertTrue("mx=56.78" in out, "MAX 12.34, 56.78 = 56.78; got:\n$out")
        assertTrue("mn=12.34" in out, "MIN 12.34, 56.78 = 12.34; got:\n$out")
        assertTrue("rem=2" in out, "MOD 17 BY 5 = 2; got:\n$out")
        assertTrue("pw=256" in out, "POWER 2 BY 8 = 256; got:\n$out")
        assertTrue("rt=4.0000" in out, "SQRT 16 = 4; got:\n$out")
        assertTrue("av=8.50" in out, "ABS -8.50 = 8.50; got:\n$out")
        assertTrue("sg=-1" in out, "SIGN -8.50 = -1; got:\n$out")
        assertTrue("fl=3.0000" in out, "FLOOR 3.70 = 3; got:\n$out")
        assertTrue("ce=4.0000" in out, "CEIL 3.70 = 4; got:\n$out")
        assertTrue("tx=0.75" in out, "ROUND 10.00 * 0.0750 TO 2 = 0.75; got:\n$out")
        assertTrue("---done---" in out, "program must complete; got:\n$out")
    }

    @Test fun `SPLIT prose form with list indexing (v2 plus v7)`() {
        // The spec's marquee §11.2 example: SPLIT … BY … then read elements back by
        // 1-based index. Exercises both the prose SPLIT and `list[i]` (TEXT element).
        val out = compileAndRun("""
            PROGRAM T
            PROCEDURE Main:
              LET parts = SPLIT "GL-001-0042" BY "-"
              DISPLAY "f1={parts[1]}"
              DISPLAY "f2={parts[2]}"
              DISPLAY "f3={parts[3]}"
              LET limited = SPLIT "a:b:c:d" BY ":" LIMIT 2
              DISPLAY "lim1={limited[1]}"
              DISPLAY "lim2={limited[2]}"
              DISPLAY "---done---"
            END-PROCEDURE
        """)
        assertTrue("f1=GL" in out, "parts[1] = GL; got:\n$out")
        assertTrue("f2=001" in out, "parts[2] = 001; got:\n$out")
        assertTrue("f3=0042" in out, "parts[3] = 0042; got:\n$out")
        assertTrue("lim1=a" in out, "LIMIT 2 first part = a; got:\n$out")
        assertTrue("lim2=b:c:d" in out, "LIMIT 2 last part holds the remainder; got:\n$out")
        assertTrue("---done---" in out, "program must complete; got:\n$out")
    }

    @Test fun `list indexing unboxes an INTEGER element (v7)`() {
        // A LIST OF INTEGER element is a boxed Long; `nums[2]` unboxes it into the
        // INTEGER (long) slot via castFromObject.
        val out = compileAndRun("""
            PROGRAM T
            DATA:
              nums : LIST OF INTEGER
              n : INTEGER
            PROCEDURE Main:
              ADD 10 TO nums
              ADD 20 TO nums
              ADD 30 TO nums
              COMPUTE n = nums[2]
              DISPLAY "n={n}"
              DISPLAY "---done---"
            END-PROCEDURE
        """)
        assertTrue("n=20" in out, "nums[2] (1-based) = 20; got:\n$out")
        assertTrue("---done---" in out, "program must complete; got:\n$out")
    }
}
