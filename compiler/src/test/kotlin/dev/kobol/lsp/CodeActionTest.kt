package dev.kobol.lsp

import org.eclipse.lsp4j.*
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * Verifies the generalized did-you-mean quick-fix (#F10): the suggestion-based
 * "Change to 'X'" action is offered for ANY diagnostic code, not only E001.
 */
class CodeActionTest {

    private val svc = KobolTextDocumentService(KobolLanguageServer())

    private fun actionsFor(code: String, message: String): List<CodeAction> {
        val diag = Diagnostic(
            Range(Position(3, 2), Position(3, 9)),
            message,
            DiagnosticSeverity.Error,
            "kobolc",
        )
        diag.setCode(code)
        val params = CodeActionParams(
            TextDocumentIdentifier("file:///t.kbl"),
            Range(Position(3, 2), Position(3, 9)),
            CodeActionContext(listOf(diag)),
        )
        return svc.codeAction(params).get().mapNotNull { it.right }
    }

    @Test fun `did-you-mean fix is offered for a non-E001 code`() {
        val actions = actionsFor("E002", "unknown type 'Custmer' — did you mean: Customer?")
        assertTrue(
            actions.any { it.title == "Change to 'Customer'" && it.kind == CodeActionKind.QuickFix },
            "expected a 'Change to Customer' quick-fix, got ${actions.map { it.title }}",
        )
    }

    @Test fun `no suggestion fix when the message has none`() {
        val actions = actionsFor("E012", "Duplicate name 'X' in DATA section")
        assertTrue(actions.none { it.title.startsWith("Change to") })
    }
}
