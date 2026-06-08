package dev.kobol.lsp

import dev.kobol.lexer.Lexer
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Drift guard for the VS Code TextMate grammar (#F2).
 *
 * Every word the lexer reserves ([Lexer.KEYWORDS]) must also be highlighted by
 * the extension's grammar, or `.kbl` source will show reserved words as plain
 * identifiers. This test fails if the grammar falls behind the lexer — e.g. a
 * new keyword is added to the language but never to `kobol.tmLanguage.json`.
 *
 * The grammar is allowed to match EXTRA words (contextual clause keywords,
 * builtins, comment tags) — only the reserved ⊆ grammar direction is enforced.
 */
class GrammarKeywordSyncTest {

    @Test fun `every reserved keyword is highlighted by the TextMate grammar`() {
        val grammar = locateGrammar().readText()
        // All uppercase tokens that appear inside any "match"/"begin" regex.
        val grammarWords = Regex("[A-Z][A-Z0-9-]+").findAll(grammar).map { it.value }.toSet()

        val missing = Lexer.KEYWORDS.keys.toSortedSet() - grammarWords
        assertTrue(
            missing.isEmpty(),
            "extension/syntaxes/kobol.tmLanguage.json is missing reserved keywords: $missing",
        )
    }

    /** Walk up from the working directory to find the extension grammar. */
    private fun locateGrammar(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            for (rel in listOf(
                "extension/syntaxes/kobol.tmLanguage.json",
                "kobol/extension/syntaxes/kobol.tmLanguage.json",
            )) {
                val f = File(dir, rel)
                if (f.isFile) return f
            }
            dir = dir.parentFile
        }
        fail("Could not locate kobol.tmLanguage.json from ${System.getProperty("user.dir")}")
    }
}
