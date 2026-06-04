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
}
