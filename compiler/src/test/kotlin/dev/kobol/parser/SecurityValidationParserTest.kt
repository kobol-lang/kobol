package dev.kobol.parser

import dev.kobol.lexer.Lexer
import dev.kobol.parser.ast.*
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Parser tests for VALIDATE constraints, VARIANT types, MATCH expressions, CONFIG sections, and TEXT SENSITIVE.
 */
class SecurityValidationParserTest {

    private fun parse(src: String): Program {
        val tokens = Lexer(src, "test.kbl").tokenize()
        return Parser(tokens, "test.kbl").parseProgram()
    }

    // -------------------------------------------------------------------------
    // TEXT SENSITIVE
    // -------------------------------------------------------------------------

    @Test fun `TEXT SENSITIVE field`() {
        val p = parse("""
            PROGRAM T
            DATA:
              password : TEXT SENSITIVE
            PROCEDURE Main:
              STOP RUN
            END-PROCEDURE
        """.trimIndent())
        val field = p.dataSection!!.items[0]
        val typeSpec = field.type
        assertIs<TypeSpec.TextType>(typeSpec)
        assertTrue(typeSpec.sensitive)
    }

    @Test fun `TEXT without SENSITIVE is not sensitive`() {
        val p = parse("""
            PROGRAM T
            DATA:
              name : TEXT
            PROCEDURE Main:
              STOP RUN
            END-PROCEDURE
        """.trimIndent())
        val field = p.dataSection!!.items[0]
        val typeSpec = field.type as TypeSpec.TextType
        assertTrue(!typeSpec.sensitive)
    }

    // -------------------------------------------------------------------------
    // CONFIG section
    // -------------------------------------------------------------------------

    @Test fun `CONFIG section with ENV bindings`() {
        val p = parse("""
            PROGRAM T
            CONFIG:
              db-url  : TEXT    FROM ENV "DATABASE_URL" REQUIRED
              timeout : INTEGER FROM ENV "TIMEOUT"      DEFAULT 30
            END-CONFIG
            PROCEDURE Main:
              STOP RUN
            END-PROCEDURE
        """.trimIndent())
        assertNotNull(p.configSection)
        val items = p.configSection!!.items
        assertEquals(2, items.size)
        assertEquals("DB-URL",  items[0].name)
        assertEquals("TIMEOUT", items[1].name)
        assertTrue(items[0].required)
        // default is an Expression
        val defaultExpr = items[1].default
        assertNotNull(defaultExpr)
    }

    @Test fun `CONFIG item with DEFAULT`() {
        val p = parse("""
            PROGRAM T
            CONFIG:
              port : INTEGER FROM ENV "PORT" DEFAULT 8080
            END-CONFIG
            PROCEDURE Main:
              STOP RUN
            END-PROCEDURE
        """.trimIndent())
        val item = p.configSection!!.items[0]
        assertEquals("PORT",   item.name)
        assertNotNull(item.default)
        assertEquals("PORT",   item.envVar)
        assertTrue(!item.required)
    }

    // -------------------------------------------------------------------------
    // VALIDATE statement
    // -------------------------------------------------------------------------

    @Test fun `VALIDATE with MUST NOT BE EMPTY`() {
        val p = parse("""
            PROGRAM T
            DATA:
              name : TEXT
            PROCEDURE Main:
              VALIDATE name:
                MUST NOT BE EMPTY
              END-VALIDATE
            END-PROCEDURE
        """.trimIndent())
        val stmt = p.procedures[0].body[0]
        assertIs<ValidateStatement>(stmt)
        assertEquals(1, stmt.constraints.size)
        assertIs<ValidationConstraint.MustNotBe>(stmt.constraints[0])
    }

    @Test fun `VALIDATE with MUST MATCH regex`() {
        val p = parse("""
            PROGRAM T
            DATA:
              email : TEXT
            PROCEDURE Main:
              VALIDATE email:
                MUST MATCH "[a-z]+@[a-z]+\.[a-z]+"
              END-VALIDATE
            END-PROCEDURE
        """.trimIndent())
        val stmt = p.procedures[0].body[0] as ValidateStatement
        val constraint = stmt.constraints[0]
        assertIs<ValidationConstraint.MustMatch>(constraint)
        assertEquals("[a-z]+@[a-z]+\\.[a-z]+", constraint.pattern)
    }

    @Test fun `VALIDATE with MUST BE and comparison`() {
        val p = parse("""
            PROGRAM T
            DATA:
              age : INTEGER
            PROCEDURE Main:
              VALIDATE age:
                MUST BE >= 18 FAIL-MSG "Must be adult"
              END-VALIDATE
            END-PROCEDURE
        """.trimIndent())
        val stmt = p.procedures[0].body[0] as ValidateStatement
        val constraint = stmt.constraints[0]
        assertIs<ValidationConstraint.MustBe>(constraint)
        assertEquals(">=", constraint.op)
        assertNotNull(constraint.failMsg)
    }

    @Test fun `VALIDATE with multiple constraints`() {
        val p = parse("""
            PROGRAM T
            DATA:
              username : TEXT
            PROCEDURE Main:
              VALIDATE username:
                MUST NOT BE EMPTY
                MUST LENGTH >= 3
                MUST MATCH "[a-zA-Z0-9_]+"
              END-VALIDATE
            END-PROCEDURE
        """.trimIndent())
        val stmt = p.procedures[0].body[0] as ValidateStatement
        assertEquals(3, stmt.constraints.size)
        assertIs<ValidationConstraint.MustNotBe>(stmt.constraints[0])
        assertIs<ValidationConstraint.MustLength>(stmt.constraints[1])
        assertIs<ValidationConstraint.MustMatch>(stmt.constraints[2])
    }

    // -------------------------------------------------------------------------
    // VARIANT declaration and MATCH statement
    // -------------------------------------------------------------------------

    @Test fun `VARIANT with simple cases`() {
        val p = parse("""
            PROGRAM T
            VARIANT OrderStatus IS
              Pending
              | Processing
              | Shipped
              | Delivered
            END-VARIANT
            PROCEDURE Main:
              STOP RUN
            END-PROCEDURE
        """.trimIndent())
        assertEquals(1, p.variants.size)
        val v = p.variants[0]
        assertEquals("ORDERSTATUS", v.name)
        assertEquals(4, v.cases.size)
        assertEquals("PENDING",    v.cases[0].name)
        assertEquals("DELIVERED",  v.cases[3].name)
    }

    @Test fun `VARIANT with fields on cases`() {
        val p = parse("""
            PROGRAM T
            VARIANT Shape IS
              Circle WITH radius: DECIMAL(10,2)
              | Rect WITH width: DECIMAL(10,2)
            END-VARIANT
            PROCEDURE Main:
              STOP RUN
            END-PROCEDURE
        """.trimIndent())
        val v = p.variants[0]
        assertEquals("SHAPE", v.name)
        val circle = v.cases[0]
        assertEquals("CIRCLE", circle.name)
        assertEquals(1, circle.fields.size)
        assertEquals("RADIUS", circle.fields[0].name)
        assertEquals(1, v.cases[1].fields.size)
    }

    @Test fun `MATCH with WHEN and OTHERWISE`() {
        val p = parse("""
            PROGRAM T
            VARIANT Color IS Red | Green | Blue END-VARIANT
            DATA:
              c : Color
            PROCEDURE Main:
              MATCH c:
                WHEN Red:
                  DISPLAY "red"
                WHEN Green:
                  DISPLAY "green"
                OTHERWISE:
                  DISPLAY "blue"
              END-MATCH
            END-PROCEDURE
        """.trimIndent())
        val stmt = p.procedures[0].body[0]
        assertIs<MatchStatement>(stmt)
        assertEquals(2, stmt.whenClauses.size)
        assertNotNull(stmt.otherwise)
    }
}
