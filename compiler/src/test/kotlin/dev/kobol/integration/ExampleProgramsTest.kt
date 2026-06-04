package dev.kobol.integration

import dev.kobol.CompilerOptions
import dev.kobol.compileFile
import dev.kobol.semantic.ModuleRegistry
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end tests for the shipped example programs in `examples/`.
 *
 * Unlike [JvmIntegrationTest], these compile the *actual* example sources and run
 * each in a **separate JVM** — required because the examples call STOP RUN
 * (`System.exit`), which would otherwise terminate the test runner. They are the
 * regression guard that keeps codegen changes from silently breaking the examples
 * (the historical whack-a-mole: tests green, examples broken).
 */
class ExampleProgramsTest {

    @TempDir lateinit var outDir: File

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Walk up from the working directory until the repo root (settings.gradle.kts) is found. */
    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").exists()) return dir
            dir = dir.parentFile
        }
        error("Could not locate repo root (settings.gradle.kts) from ${File("").absolutePath}")
    }

    private fun exampleFile(name: String): File {
        val f = File(repoRoot(), "examples/$name")
        assertTrue(f.exists(), "Example source not found: ${f.absolutePath}")
        return f
    }

    private fun className(programFileName: String): String =
        programFileName.removeSuffix(".kbl").split("-")
            .joinToString("") { it.lowercase().replaceFirstChar { c -> c.uppercase() } }

    /**
     * Compile an example to [outDir] then run it in a fresh JVM, returning its stdout.
     * [dataDir] (if given) is exposed to the program as `kobol.data.dir` for FILES I/O.
     */
    private fun compileAndRunExample(exampleName: String, dataDir: File? = null): Pair<Int, String> {
        val source = exampleFile(exampleName)
        val ok = compileFile(source, CompilerOptions(source, outDir), ModuleRegistry())
        assertTrue(ok, "Compilation failed for $exampleName")

        val javaBin = File(System.getProperty("java.home"), "bin/java").absolutePath
        val cp = outDir.absolutePath + File.pathSeparator + System.getProperty("java.class.path")
        val cmd = mutableListOf(javaBin, "-cp", cp)
        if (dataDir != null) cmd += "-Dkobol.data.dir=${dataDir.absolutePath}"
        cmd += className(exampleName)

        val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val output = proc.inputStream.bufferedReader().readText()
        proc.waitFor()
        return proc.exitValue() to output
    }

    private fun writeData(dir: File, fileName: String, content: String) {
        dir.mkdirs()
        File(dir, fileName).writeText(content.trimIndent() + "\n")
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test fun `hello-world runs`() {
        val (exit, out) = compileAndRunExample("hello-world.kbl")
        assertEquals(0, exit, "hello-world exited non-zero:\n$out")
        assertTrue("Hello, World!" in out, "missing greeting:\n$out")
    }

    @Test fun `invoice-processor processes CSV and writes report`(@TempDir dataDir: File) {
        writeData(dataDir, "InvoiceFile.csv", """
            invoice-id,customer-name,amount,due-date,paid,status-code
            1001,Acme Corp,1250.00,2026-05-01,true,X
            1002,Globex Ltd,890.50,2026-04-15,false,O
            1003,Initech,4200.75,2026-06-30,false,P
        """)
        val (exit, out) = compileAndRunExample("invoice-processor.kbl", dataDir)
        assertEquals(0, exit, "invoice-processor exited non-zero:\n$out")
        assertTrue("Total invoices  :  3" in out, "wrong invoice count:\n$out")
        assertTrue("Paid          :  1" in out, "wrong paid count:\n$out")
        assertTrue("Overdue       :  1" in out, "wrong overdue count:\n$out")
        assertTrue("Pending       :  1" in out, "wrong pending count:\n$out")
        // MONEY(10.2) fixed-point: late fee 890.50 * 1.5 / 100 = 13.3575 must rescale
        // to the field's declared scale (2dp, HALF_UP) → 13.36, not 13.3575/13.358.
        assertTrue("13.36" in out, "late fee not rescaled to MONEY scale:\n$out")
        assertTrue("13.3575" !in out && "13.358" !in out, "fixed-point scale leaked extra digits:\n$out")
    }

    @Test fun `customer-report reads CSV, filters, sorts top-5`(@TempDir dataDir: File) {
        writeData(dataDir, "CustomerFile.csv", """
            customer-id,name,segment,balance,credit-limit,active
            1,Acme Corp,PREMIUM,75000.00,50000.00,true
            2,Inactive Co,STANDARD,99999.00,10000.00,false
            3,Initech,PREMIUM,68000.00,40000.00,true
            4,Soylent Co,PREMIUM,52000.00,60000.00,true
        """)
        val (exit, out) = compileAndRunExample("customer-report.kbl", dataDir)
        assertEquals(0, exit, "customer-report exited non-zero:\n$out")
        assertTrue("Loaded  4  customers." in out, "wrong load count:\n$out")
        // active filter excludes "Inactive Co" even though it has the highest balance
        assertTrue("Inactive Co" !in out.substringAfter("Top 5"), "inactive customer leaked into top-5:\n$out")
        // sorted DESCENDING: Acme (75000) appears before Initech (68000)
        val top = out.substringAfter("Top 5")
        assertTrue(top.indexOf("Acme Corp") < top.indexOf("Initech"), "top-5 not sorted descending:\n$out")
    }
}
