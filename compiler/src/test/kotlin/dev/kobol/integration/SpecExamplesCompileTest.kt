package dev.kobol.integration

import dev.kobol.CompilerOptions
import dev.kobol.compileFile
import dev.kobol.semantic.ModuleRegistry
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.test.fail

/**
 * Docs-in-CI gate: every runnable `kobol` example in LANGUAGE_SPEC.md must compile.
 *
 * The spec is mostly illustrative *fragments* (a lone `MOVE`, a `DATA:` section, a
 * single `WHEN` arm) that are not standalone compilation units, so a blanket
 * "compile every fenced block" check is meaningless. Instead a block is enrolled
 * iff it is a complete program — it has a top-level `PROGRAM` header — or the author
 * opts it in with a `-- @compile` marker on its first line (for script-mode examples
 * that have no `PROGRAM` header). Enrolled blocks are type-checked (`--check`); any
 * that no longer compile fail this test, so doc examples cannot silently rot (#3/#6/
 * #17/#21-class drift). See the "@compile" note in LANGUAGE_SPEC.md.
 */
class SpecExamplesCompileTest {

    @TempDir lateinit var workDir: File

    private data class Block(val index: Int, val specLine: Int, val code: String)

    @Test fun `enrolled kobol examples in LANGUAGE_SPEC compile`() {
        val spec = locateSpec()
        val enrolled = extractKobolBlocks(spec.readText()).filter(::isEnrolled)

        val failures = mutableListOf<String>()
        enrolled.forEach { block ->
            val diag = compileCheck(block)
            if (diag != null) {
                val firstLine = block.code.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
                failures += "  ${spec.name}:${block.specLine} (\"$firstLine\")\n" +
                    diag.lineSequence().filter { it.isNotBlank() }.take(3).joinToString("\n") { "      $it" }
            }
        }

        if (failures.isNotEmpty()) {
            fail(
                "Enrolled spec examples failed to compile (${failures.size}/${enrolled.size}):\n" +
                    failures.joinToString("\n")
            )
        }
        check(enrolled.isNotEmpty()) { "No enrolled examples found — extraction logic is broken." }
    }

    /** A block is checked if it is a full program or is explicitly opted in. */
    private fun isEnrolled(b: Block): Boolean {
        val firstNonBlank = b.code.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        if (firstNonBlank.startsWith("-- @compile")) return true
        return b.code.lineSequence().any { it.trimStart().startsWith("PROGRAM ") }
    }

    /** Returns null on success, or captured diagnostics on failure. */
    private fun compileCheck(block: Block): String? {
        val file = File(workDir, "spec_b${block.index}.kbl").apply { writeText(block.code) }
        val baos = ByteArrayOutputStream()
        val stream = PrintStream(baos)
        val savedOut = System.out; val savedErr = System.err
        System.setOut(stream); System.setErr(stream)
        val ok = try {
            compileFile(file, CompilerOptions(file, workDir, checkOnly = true), ModuleRegistry())
        } catch (e: Throwable) {
            stream.println("threw ${e::class.simpleName}: ${e.message}"); false
        } finally {
            stream.flush(); System.setOut(savedOut); System.setErr(savedErr)
        }
        return if (ok) null else baos.toString(Charsets.UTF_8)
    }

    /** Extract the contents of every ```kobol fenced block, with its 1-based start line. */
    private fun extractKobolBlocks(text: String): List<Block> {
        val lines = text.lines()
        val blocks = mutableListOf<Block>()
        var i = 0; var idx = 0
        while (i < lines.size) {
            if (lines[i].trimStart().startsWith("```kobol")) {
                val start = i + 1
                val body = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                    body.appendLine(lines[i]); i++
                }
                blocks += Block(idx++, start + 1, body.toString())
            }
            i++
        }
        return blocks
    }

    private fun locateSpec(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            for (rel in listOf("docs/LANGUAGE_SPEC.md", "kobol/docs/LANGUAGE_SPEC.md")) {
                val f = File(dir, rel)
                if (f.isFile) return f
            }
            dir = dir.parentFile
        }
        fail("Could not locate LANGUAGE_SPEC.md from ${System.getProperty("user.dir")}")
    }
}
