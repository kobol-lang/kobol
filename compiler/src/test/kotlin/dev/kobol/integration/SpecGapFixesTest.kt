package dev.kobol.integration

import dev.kobol.CompilerOptions
import dev.kobol.compileFile
import dev.kobol.semantic.ModuleRegistry
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.net.URLClassLoader
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * End-to-end verification for the compiler spec-gap fixes G1–G8 (forms LANGUAGE_SPEC.md documents
 * that `kobolc --check` previously rejected). Each test compiles Kobol to bytecode via the ASM
 * backend, runs it, and asserts behaviour — closing the spec-vs-impl landmine (priority 3).
 */
class SpecGapFixesTest {

    @TempDir lateinit var outDir: File

    private fun compiles(src: String, name: String = "T"): Boolean {
        val source = File(outDir, "$name.kbl").apply { writeText(src.trimIndent()) }
        val saved = System.out; val savedErr = System.err
        val sink = PrintStream(ByteArrayOutputStream())
        System.setOut(sink); System.setErr(sink)
        return try { compileFile(source, CompilerOptions(source, outDir), ModuleRegistry()) }
               finally { System.setOut(saved); System.setErr(savedErr) }
    }

    private fun compileAndRun(src: String, name: String = "T", stdin: String = ""): String {
        val source = File(outDir, "$name.kbl").apply { writeText(src.trimIndent()) }
        val diag = ByteArrayOutputStream()
        val savedOut = System.out; val savedErr = System.err
        System.setOut(PrintStream(diag)); System.setErr(PrintStream(diag))
        val ok = try { compileFile(source, CompilerOptions(source, outDir), ModuleRegistry()) }
                 finally { System.setOut(savedOut); System.setErr(savedErr) }
        assertTrue(ok, "Compilation failed for $name:\n$diag")

        val className = name.split("-").joinToString("") { it.lowercase().replaceFirstChar { c -> c.uppercase() } }
        val loader = URLClassLoader(arrayOf(outDir.toURI().toURL()), Thread.currentThread().contextClassLoader)
        val baos = ByteArrayOutputStream()
        val savedO = System.out; val savedIn = System.`in`
        System.setOut(PrintStream(baos))
        System.setIn(ByteArrayInputStream(stdin.toByteArray()))
        return try {
            loader.loadClass(className).getMethod("main", Array<String>::class.java).invoke(null, arrayOf<String>())
            baos.toString(Charsets.UTF_8)
        } finally {
            System.setOut(savedO); System.setIn(savedIn); loader.close()
        }
    }

    // ── G1: DISPLAY PROGRESS x OF y MESSAGE ─────────────────────────────────
    @Test fun `G1 DISPLAY PROGRESS compiles, runs, and renders`() {
        val out = compileAndRun("""
            PROGRAM T
            PROCEDURE Main:
              DISPLAY PROGRESS 52 OF 100 MESSAGE "Processing invoices"
              DISPLAY "done"
            END-PROCEDURE
        """)
        assertTrue("done" in out, "program after DISPLAY PROGRESS must run")
        // Formatting verified directly (the bar itself is TTY-gated, suppressed in CI).
        assertEquals(
            "Processing invoices  [=====>    ] 52/100 (52%)",
            dev.kobol.runtime.KobolDisplay.renderProgress(52, 100, "Processing invoices")
        )
    }

    // ── G2: ACCEPT FROM TERMINAL / ARGUMENT ─────────────────────────────────
    @Test fun `G2 ACCEPT FROM TERMINAL reads stdin`() {
        val out = compileAndRun("""
            PROGRAM T
            DATA:
              name : TEXT
            PROCEDURE Main:
              ACCEPT name FROM TERMINAL PROMPT "Name? "
              DISPLAY "Hello {name}"
            END-PROCEDURE
        """, stdin = "Alice\n")
        assertTrue("Hello Alice" in out, "got: $out")
    }

    @Test fun `G2 ACCEPT FROM ARGUMENT falls back to DEFAULT and coerces`() {
        val out = compileAndRun("""
            PROGRAM T
            DATA:
              batch : INTEGER
            PROCEDURE Main:
              ACCEPT batch FROM ARGUMENT "--batch-size" DEFAULT 500
              DISPLAY "batch={batch}"
            END-PROCEDURE
        """)
        assertTrue("batch=500" in out, "got: $out")
    }

    // ── G3: WRITE XML ... ROOT ──────────────────────────────────────────────
    @Test fun `G3 WRITE XML ROOT overrides root element`() {
        val path = File(outDir, "g3.xml").absolutePath.replace('\\', '/')
        compileAndRun("""
            PROGRAM T
            RECORD Invoice:
              id : INTEGER
            END-RECORD
            DATA:
              inv : Invoice
            PROCEDURE Main:
              MOVE 7 TO inv.id
              WRITE XML inv TO "$path" ROOT "InvoiceDocument"
            END-PROCEDURE
        """)
        val xml = File(path).readText()
        assertTrue("<InvoiceDocument" in xml, "root not overridden: $xml")
    }

    // ── G4: DIVIDE-USING <mode> BY ──────────────────────────────────────────
    @Test fun `G4 DIVIDE-USING applies the rounding mode`() {
        val out = compileAndRun("""
            PROGRAM T
            DATA:
              up   : DECIMAL(10,4)
              even : DECIMAL(10,4)
            PROCEDURE Main:
              COMPUTE up   = 10 DIVIDE-USING UP BY 3
              COMPUTE even = 10 / 3
              DISPLAY "up={up} even={even}"
            END-PROCEDURE
        """)
        // 10/3 at the dividend's scale (0): UP → 4, HALF-EVEN default → 3.
        assertTrue("up=4" in out && "even=3" in out, "rounding mode not applied: $out")
    }

    // ── G5: TEXT SENSITIVE ENCRYPTED USING alg KEY k ────────────────────────
    @Test fun `G5 field-level ENCRYPTED declaration compiles and the field works`() {
        val out = compileAndRun("""
            PROGRAM T
            DATA:
              pan-key : TEXT SENSITIVE
              pan     : TEXT(19) SENSITIVE ENCRYPTED USING AES-256-GCM KEY pan-key
            PROCEDURE Main:
              MOVE "4111111111111111" TO pan
              DISPLAY "pan={pan}"
            END-PROCEDURE
        """)
        assertTrue("pan=4111111111111111" in out, "got: $out")
    }

    // ── G6: IF NOT CONFIRM expr ─────────────────────────────────────────────
    @Test fun `G6 CONFIRM yields a boolean from stdin`() {
        val aborted = compileAndRun("""
            PROGRAM T
            PROCEDURE Main:
              IF NOT CONFIRM "Proceed?":
                DISPLAY "aborted"
              ELSE:
                DISPLAY "go"
              END-IF
            END-PROCEDURE
        """, name = "T", stdin = "n\n")
        assertTrue("aborted" in aborted, "got: $aborted")

        val go = compileAndRun("""
            PROGRAM T2
            PROCEDURE Main:
              IF NOT CONFIRM "Proceed?":
                DISPLAY "aborted"
              ELSE:
                DISPLAY "go"
              END-IF
            END-PROCEDURE
        """, name = "T2", stdin = "y\n")
        assertTrue("go" in go && "aborted" !in go, "got: $go")
    }

    @Test fun `G6 CONFIRM with an interpolated INTEGER does not miscompile`() {
        // Regression: `IF NOT CONFIRM "...{int}..."` previously type-check-passed then threw
        // VerifyError (long_2nd not assignable to Object) — the NOT operand was never checked,
        // so the interpolated long fell back to Object.toString. EOF -> CONFIRM=false -> NOT-branch.
        val out = compileAndRun("""
            PROGRAM T4
            DATA:
              record-count : INTEGER = 42
            PROCEDURE Main:
              IF NOT CONFIRM "Archive {record-count} records?":
                DISPLAY "aborted {record-count}"
              END-IF
            END-PROCEDURE
        """, name = "T4")
        assertTrue("aborted 42" in out, "interpolated INTEGER in NOT CONFIRM must render: $out")
    }

    // ── G7: PARSE XML ... NAMESPACES ────────────────────────────────────────
    @Test fun `G7 PARSE XML NAMESPACES validates and parses on a match`() {
        val out = compileAndRun("""
            PROGRAM T
            RECORD Doc:
              id : INTEGER
            END-RECORD
            DATA:
              xml : TEXT = '<Doc xmlns="urn:good"><id>5</id></Doc>'
              d   : Doc
            PROCEDURE Main:
              PARSE XML xml INTO d AS Doc NAMESPACES "p" : "urn:good"
              DISPLAY "id={d.id}"
            END-PROCEDURE
        """)
        assertTrue("id=5" in out, "got: $out")
    }

    @Test fun `G7 PARSE XML NAMESPACES rejects a namespace mismatch`() {
        var threw = false
        try {
            compileAndRun("""
                PROGRAM T3
                RECORD Doc:
                  id : INTEGER
                END-RECORD
                DATA:
                  xml : TEXT = '<Doc xmlns="urn:WRONG"><id>5</id></Doc>'
                  d   : Doc
                PROCEDURE Main:
                  PARSE XML xml INTO d AS Doc NAMESPACES "p" : "urn:good"
                END-PROCEDURE
            """, name = "T3")
        } catch (e: Throwable) {
            threw = true
            val msg = generateSequence(e) { it.cause }.mapNotNull { it.message }.joinToString(" | ")
            assertTrue("namespace mismatch" in msg, "wrong error: $msg")
        }
        assertTrue(threw, "namespace mismatch must raise an error")
    }

    // ── G9: ASSERT RAISES ExceptionType: stmt ───────────────────────────────
    @Test fun `G9 ASSERT RAISES passes when the body raises the named exception`() {
        val out = compileAndRun("""
            PROGRAM T
            PROCEDURE Boom:
              RAISE ApplicationError "kaboom"
            END-PROCEDURE
            PROCEDURE Main:
              ASSERT RAISES ApplicationError: PERFORM Boom
              DISPLAY "raises-ok"
            END-PROCEDURE
        """)
        assertTrue("raises-ok" in out, "ASSERT RAISES must pass + continue when body raises: $out")
    }

    @Test fun `G9 ASSERT RAISES fails when the body raises nothing`() {
        var threw = false
        try {
            compileAndRun("""
                PROGRAM T2
                PROCEDURE Main:
                  ASSERT RAISES ApplicationError: DISPLAY "did nothing"
                  DISPLAY "should-not-reach"
                END-PROCEDURE
            """, name = "T2")
        } catch (e: Throwable) {
            threw = true
            val msg = generateSequence(e) { it.cause }.mapNotNull { it.message }.joinToString(" | ")
            assertTrue("Expected ApplicationError to be raised" in msg, "wrong failure: $msg")
        }
        assertTrue(threw, "ASSERT RAISES must fail (AssertionError) when nothing is raised")
    }

    // ── G10: INTEGER-literal argument into a DECIMAL/MONEY parameter ────────
    @Test fun `G10 PERFORM USING int literal into a DECIMAL param widens, no ASM frame error`() {
        val out = compileAndRun("""
            PROGRAM T
            PROCEDURE ChargeCard USING amount : DECIMAL(10, 2):
              IF amount <= 0:
                RAISE ApplicationError "amount must be positive"
              END-IF
              DISPLAY "charged " amount
            END-PROCEDURE
            PROCEDURE Main:
              PERFORM ChargeCard USING 42
              ASSERT RAISES ApplicationError: PERFORM ChargeCard USING -5
              DISPLAY "g10-ok"
            END-PROCEDURE
        """)
        assertTrue("charged " in out, "int 42 must widen to DECIMAL and run: $out")
        assertTrue("g10-ok" in out, "ASSERT RAISES with int -5 arg must pass + continue: $out")
    }

    @Test fun `G10 COMPUTE captures a DECIMAL-returning proc called with an int literal arg`() {
        val out = compileAndRun("""
            PROGRAM T2
            PROCEDURE WithTax USING base : DECIMAL(10, 2) RETURNING DECIMAL(10, 2):
              RETURN base + 1.00
            END-PROCEDURE
            PROCEDURE Main:
              LET total = WithTax(10)
              DISPLAY "total " total
            END-PROCEDURE
        """, name = "T2")
        assertTrue("11.00" in out, "int arg into DECIMAL param of a RETURNING proc must widen: $out")
    }

    // ── G8: MUST MATCH regex quantifier — escaped + raw forms compile ───────
    @Test fun `G8 MUST MATCH accepts escaped braces and raw strings`() {
        // Escaped braces in an interpolating string (spec §19.1 canonical form):
        assertTrue(compiles("""
            PROGRAM T
            PROCEDURE Check USING code : TEXT:
              VALIDATE code:
                MUST MATCH "[A-Z0-9]\{8,20\}"
            END-PROCEDURE
            PROCEDURE Main:
              DISPLAY "ok"
            END-PROCEDURE
        """), "escaped-brace regex must compile")

        // Raw single-quoted string (no interpolation) — alternative escape hatch:
        assertTrue(compiles("""
            PROGRAM T2
            PROCEDURE Check USING code : TEXT:
              VALIDATE code:
                MUST MATCH '[A-Z0-9]{8,20}'
            END-PROCEDURE
            PROCEDURE Main:
              DISPLAY "ok"
            END-PROCEDURE
        """, name = "T2"), "raw-string regex must compile")

        // Unescaped braces in a double-quoted string are interpolation by design → rejected.
        assertFalse(compiles("""
            PROGRAM T3
            PROCEDURE Check USING code : TEXT:
              VALIDATE code:
                MUST MATCH "[A-Z0-9]{8,20}"
            END-PROCEDURE
            PROCEDURE Main:
              DISPLAY "ok"
            END-PROCEDURE
        """, name = "T3"), "unescaped braces are interpolation, not a regex quantifier")
    }
}
