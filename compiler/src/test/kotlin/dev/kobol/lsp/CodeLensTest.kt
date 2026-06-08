package dev.kobol.lsp

import dev.kobol.lexer.Lexer
import dev.kobol.parser.Parser
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Verifies inline Run / Run-Tests code lenses (#F11). */
class CodeLensTest {

    private val svc = KobolTextDocumentService(KobolLanguageServer())

    private fun lensesFor(src: String) =
        svc.buildCodeLenses(Parser(Lexer(src, "t.kbl").tokenize(), "t.kbl").parseProgram())

    @Test fun `Main gets a Run lens and each TEST gets a Run-Tests lens`() {
        val src = """
            PROGRAM Demo VERSION "1.0"
            TEST "adds":
              ASSERT 1 = 1
            END-TEST
            PROCEDURE Main:
              STOP RUN
            END-PROCEDURE
        """.trimIndent()

        val lenses = lensesFor(src)
        val titles = lenses.map { it.command.title }
        assertEquals(1, titles.count { it == "▶ Run" }, "expected one Run lens on Main")
        assertEquals(1, titles.count { it == "▶ Run Tests" }, "expected one Run-Tests lens on the TEST")
        assertTrue(lenses.all { it.command.command.startsWith("kobol.") }, "lenses must invoke kobol.* commands")
    }

    @Test fun `no Main means no Run lens`() {
        val src = """
            PROGRAM Demo VERSION "1.0"
            PROCEDURE Helper:
              STOP RUN
            END-PROCEDURE
        """.trimIndent()
        assertTrue(lensesFor(src).none { it.command.title == "▶ Run" })
    }
}
