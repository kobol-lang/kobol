package dev.kobol.runtime

import java.io.File

object KobolTest {
    @JvmStatic fun assertTrue(condition: Boolean, description: String) {
        if (!condition) throw AssertionError("KOBOL ASSERT FAILED: $description")
    }

    @JvmStatic fun assertTrue(condition: Boolean) {
        if (!condition) throw AssertionError("KOBOL ASSERT FAILED")
    }

    // -------------------------------------------------------------------------
    // Test runner invoked by generated $Tests classes
    // -------------------------------------------------------------------------

    data class TestResult(val name: String, val passed: Boolean, val error: Throwable?)

    private val results = mutableListOf<TestResult>()
    private var suiteName = ""

    @JvmStatic fun begin(suite: String) { suiteName = suite; results.clear() }
    @JvmStatic fun pass(name: String)  { results.add(TestResult(name, true,  null)) }
    @JvmStatic fun fail(name: String, e: Throwable) { results.add(TestResult(name, false, e)) }

    @JvmStatic fun finish(): Int {
        val failed = results.count { !it.passed }
        writeJUnitXml(suiteName, results)
        printSummary()
        return failed
    }

    private fun printSummary() {
        val passed = results.count { it.passed }
        val total  = results.size
        println("KOBOL TEST SUITE: $suiteName")
        for (r in results) {
            val mark = if (r.passed) "PASS" else "FAIL"
            println("  [$mark] ${r.name}${if (!r.passed) " — ${r.error?.message}" else ""}")
        }
        println("  -------")
        println("  $passed/$total PASS")
    }

    private fun writeJUnitXml(suite: String, results: List<TestResult>) {
        val sb = StringBuilder()
        val failures = results.count { !it.passed }
        sb.appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        sb.appendLine("<testsuite name=\"$suite\" tests=\"${results.size}\" failures=\"$failures\" errors=\"0\">")
        for (r in results) {
            sb.append("  <testcase name=\"${escapeXml(r.name)}\" classname=\"$suite\"")
            if (r.passed) {
                sb.appendLine("/>")
            } else {
                sb.appendLine(">")
                val msg = escapeXml(r.error?.message ?: "assertion failed")
                sb.appendLine("    <failure message=\"$msg\">${escapeXml(r.error?.toString() ?: "")}</failure>")
                sb.appendLine("  </testcase>")
            }
        }
        sb.appendLine("</testsuite>")
        try {
            File("TEST-$suite.xml").writeText(sb.toString())
        } catch (_: Exception) {
            // Best-effort XML output — ignore write failures
        }
    }

    private fun escapeXml(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")
}

