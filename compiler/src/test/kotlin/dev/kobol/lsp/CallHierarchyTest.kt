package dev.kobol.lsp

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Verifies the call-site extraction backing call hierarchy (#F12). */
class CallHierarchyTest {

    private val svc = KobolTextDocumentService(KobolLanguageServer())

    @Test fun `collects PERFORM and DO targets, including module-qualified`() {
        val lines = listOf(
            "PROCEDURE Main:",          // 0
            "  PERFORM Helper",         // 1
            "  DO billing.Charge",      // 2 — module-qualified → bare 'Charge'
            "  DISPLAY \"no call here\"",// 3
            "END-PROCEDURE",            // 4
        )
        val sites = svc.procedureCallSites(lines, 0, 4)
        val callees = sites.map { it.first }

        assertEquals(listOf("HELPER", "CHARGE"), callees)
        // The range for 'Helper' must point at the name, not the PERFORM keyword.
        val helperRange = sites.first { it.first == "HELPER" }.second
        assertEquals("Helper", lines[1].substring(helperRange.start.character, helperRange.end.character))
        val chargeRange = sites.first { it.first == "CHARGE" }.second
        assertEquals("Charge", lines[2].substring(chargeRange.start.character, chargeRange.end.character))
    }

    @Test fun `ignores lines without calls`() {
        val lines = listOf("PROCEDURE P:", "  MOVE 1 TO x", "END-PROCEDURE")
        assertTrue(svc.procedureCallSites(lines, 0, 2).isEmpty())
    }
}
