package dev.kobol.lexer

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * Drift guard for LANGUAGE_SPEC.md §1.4 "Keywords (Reserved)".
 *
 * The lexer's [Lexer.KEYWORDS] table is the single source of truth for which
 * words are reserved. This test fails if the spec's §1.4 list drifts from it —
 * either a reserved word is missing from the docs (the contract lies) or the
 * docs list a word the lexer does not actually reserve.
 *
 * On failure it prints a ready-to-paste, alphabetically sorted 5-column block
 * so §1.4 can be regenerated mechanically rather than hand-maintained (#16).
 */
class KeywordSpecSyncTest {

    @Test fun `spec 1_4 keyword list matches the lexer keyword table`() {
        val canonical = Lexer.KEYWORDS.keys.toSortedSet()

        val spec = locateSpec()
        val specBlock = extractSection(spec.readText(), "### 1.4 Keywords")
        val specWords = Regex("[A-Z][A-Z-]*")
            .findAll(specBlock)
            .map { it.value }
            .toSortedSet()

        val missingFromSpec = canonical - specWords   // reserved but undocumented
        val extraInSpec = specWords - canonical        // documented but not reserved

        if (missingFromSpec.isNotEmpty() || extraInSpec.isNotEmpty()) {
            fail(
                buildString {
                    appendLine("LANGUAGE_SPEC.md §1.4 is out of sync with Lexer.KEYWORDS.")
                    if (missingFromSpec.isNotEmpty())
                        appendLine("  Reserved but MISSING from §1.4: $missingFromSpec")
                    if (extraInSpec.isNotEmpty())
                        appendLine("  Listed in §1.4 but NOT reserved by the lexer: $extraInSpec")
                    appendLine("  Spec file: ${spec.absolutePath}")
                    appendLine("  Replace the §1.4 code block with this canonical list:")
                    appendLine(renderGrid(canonical))
                }
            )
        }
        assertEquals(canonical, specWords)
    }

    /** Walk up from the working directory to find docs/LANGUAGE_SPEC.md. */
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

    /** Return the contents of the first ``` fenced block after [heading]. */
    private fun extractSection(text: String, heading: String): String {
        val lines = text.lines()
        val start = lines.indexOfFirst { it.trimStart().startsWith(heading) }
        require(start >= 0) { "Heading '$heading' not found in spec" }
        val fenceOpen = (start until lines.size).first { lines[it].trimStart().startsWith("```") }
        val fenceClose = ((fenceOpen + 1) until lines.size).first { lines[it].trimStart().startsWith("```") }
        return lines.subList(fenceOpen + 1, fenceClose).joinToString("\n")
    }

    /** Render a sorted word set as a left-aligned 5-column grid for pasting. */
    private fun renderGrid(words: Collection<String>): String {
        val cols = 5
        val width = (words.maxOfOrNull { it.length } ?: 0) + 2
        return words.chunked(cols).joinToString("\n") { row ->
            row.joinToString("") { it.padEnd(width) }.trimEnd()
        }
    }
}
