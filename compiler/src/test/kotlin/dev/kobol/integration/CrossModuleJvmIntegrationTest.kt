package dev.kobol.integration

import dev.kobol.KobolCompiler
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.net.URLClassLoader
import kotlin.test.assertTrue

/**
 * Cross-module JVM integration harness (challenge enabler **E1**).
 *
 * The single-file [JvmIntegrationTest] cannot exercise cross-module PERFORM: a
 * library program declares a MODULE + EXPORTs, a consumer IMPORTs it, and the
 * consumer's bytecode links INVOKESTATIC straight into the library's JVM class.
 * That link is only proven by actually loading BOTH classes and running the
 * consumer — type-check alone never touches the descriptor.
 *
 * [compileAndRunProject] writes N source files into one output dir, runs the
 * real two-pass [KobolCompiler.compile] (registry pre-pass + emit), loads the
 * named entry class, and returns its captured stdout.
 */
class CrossModuleJvmIntegrationTest {

    @TempDir lateinit var outDir: File

    /** A single Kobol source file: the on-disk name (sans `.kbl`) drives the JVM class name. */
    data class Src(val fileName: String, val body: String)

    private fun jvmClassName(fileName: String): String =
        File(fileName).nameWithoutExtension.split("-")
            .joinToString("") { it.lowercase().replaceFirstChar { c -> c.uppercase() } }

    /**
     * Compile every [files] entry into [outDir] (one shared ModuleRegistry pass),
     * then load + run [entryFile]'s class and return its stdout.
     */
    private fun compileAndRunProject(files: List<Src>, entryFile: String): String {
        val sourceFiles = files.map { s ->
            File(outDir, s.fileName).apply { writeText(s.body.trimIndent()) }
        }
        val result = KobolCompiler.compile(sourceFiles, outDir)
        assertTrue(result.success,
            "Compilation failed:\n${result.errors.joinToString("\n")}")
        return runEntry(jvmClassName(entryFile))
    }

    /** Compile only; return the [KobolCompiler.CompileResult] for error/diagnostic assertions. */
    private fun compileProject(files: List<Src>): KobolCompiler.CompileResult {
        val sourceFiles = files.map { s ->
            File(outDir, s.fileName).apply { writeText(s.body.trimIndent()) }
        }
        return KobolCompiler.compile(sourceFiles, outDir)
    }

    private fun runEntry(className: String): String {
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

    // ─── E1: harness proof — sync cross-module PERFORM actually runs ─────────────

    @Test fun `sync cross-module PERFORM links and runs (E1 harness proof)`() {
        val lib = Src("MathUtils.kbl", """
            PROGRAM MathUtils
            MODULE kobol.math:
              EXPORT PROCEDURE Shout
            END-MODULE
            EXPORT PROCEDURE Shout USING n : INTEGER:
              DISPLAY "lib got {n}"
            END-PROCEDURE
        """)
        val consumer = Src("Consumer.kbl", """
            PROGRAM Consumer
            IMPORT kobol.math AS Math
            DATA:
              x : INTEGER = 7
            PROCEDURE Main:
              PERFORM Math.Shout USING x
              DISPLAY "---done---"
            END-PROCEDURE
        """)
        val out = compileAndRunProject(listOf(lib, consumer), "Consumer.kbl")
        assertTrue("lib got 7" in out, "cross-module call did not execute lib body; got: $out")
        assertTrue("---done---" in out, "consumer did not complete; got: $out")
    }

    // ─── F9: fire-and-forget cross-module async PERFORM links to the right descriptor ─

    @Test fun `fire-and-forget cross-module async PERFORM links and runs (F9)`() {
        // An exported async proc's JVM entry returns CompletableFuture regardless of
        // declared return. A cross-module PERFORM with no GIVING must link to
        // `(J)CompletableFuture` and discard the future — not to `(J)V` (which
        // type-checks clean then NoSuchMethodError at run = P3 landmine). A VOID
        // async proc is used so the wrapper is independent of the RETURNING-box path
        // (that separate wrapper bug is tracked as F19).
        val lib = Src("JobUtils.kbl", """
            PROGRAM JobUtils
            MODULE kobol.jobs:
              EXPORT PROCEDURE Fetch
            END-MODULE
            EXPORT ASYNC PROCEDURE Fetch USING n : INTEGER:
              DISPLAY "fetch {n}"
            END-PROCEDURE
        """)
        val consumer = Src("Consumer.kbl", """
            PROGRAM Consumer
            IMPORT kobol.jobs AS Jobs
            DATA:
              x : INTEGER = 5
            PROCEDURE Main:
              PERFORM Jobs.Fetch USING x
              DISPLAY "---done---"
            END-PROCEDURE
        """)
        val out = compileAndRunProject(listOf(lib, consumer), "Consumer.kbl")
        assertTrue("---done---" in out,
            "fire-and-forget async cross-module PERFORM must run to completion (no NoSuchMethodError); got: $out")
    }

    // ─── F10: cross-module async result capture (GIVING fut → AWAIT) ─────────────

    @Test fun `cross-module async PERFORM GIVING then AWAIT yields the value (F10)`() {
        // End-to-end proof that the descriptor fix (F9) + RETURNING-wrapper fix (F19)
        // compose: the consumer captures the exported async proc's CompletableFuture into
        // a FUTURE OF INTEGER, awaits it, and reads the computed result across the module
        // boundary — no asymmetry with a local ASYNC PERFORM.
        val lib = Src("JobUtils.kbl", """
            PROGRAM JobUtils
            MODULE kobol.jobs:
              EXPORT PROCEDURE Fetch
            END-MODULE
            EXPORT ASYNC PROCEDURE Fetch USING n : INTEGER RETURNING INTEGER:
              RETURN n * 2
            END-PROCEDURE
        """)
        val consumer = Src("Consumer.kbl", """
            PROGRAM Consumer
            IMPORT kobol.jobs AS Jobs
            DATA:
              fut : FUTURE OF INTEGER
              result : INTEGER = 0
            PROCEDURE Main:
              PERFORM Jobs.Fetch USING 21 GIVING fut
              AWAIT fut INTO result
              DISPLAY "result={result}"
              DISPLAY "---done---"
            END-PROCEDURE
        """)
        val out = compileAndRunProject(listOf(lib, consumer), "Consumer.kbl")
        assertTrue("result=42" in out, "cross-module awaited async result must be 42; got: $out")
        assertTrue("---done---" in out, "consumer must complete; got: $out")
    }
}
