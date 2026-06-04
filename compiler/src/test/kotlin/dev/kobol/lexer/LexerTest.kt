package dev.kobol.lexer

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LexerTest {

    private fun lex(src: String) = Lexer(src, "test.kbl").tokenize()
    private fun types(src: String) = lex(src).map { it.type }
    private fun values(src: String) = lex(src).map { it.value }

    // ---- Keywords -----------------------------------------------------------

    @Test fun `keywords are case-insensitive`() {
        assertEquals(TokenType.PROGRAM, types("program")[0])
        assertEquals(TokenType.PROGRAM, types("PROGRAM")[0])
        assertEquals(TokenType.PROGRAM, types("Program")[0])
    }

    @Test fun `all core keywords recognised`() {
        val keywords = listOf("ADD","AND","COMPUTE","DATA","DEFINE","DISPLAY",
            "DO","EACH","ELSE","FOR","IF","IN","LET","MOVE","NOT","OF","OR",
            "PERFORM","PROCEDURE","RECORD","RETURN","STOP","TEXT","TO","WHILE","WITH")
        for (kw in keywords) {
            val t = types(kw)
            assertTrue(t[0] != TokenType.IDENTIFIER, "Expected $kw to be a keyword")
        }
    }

    @Test fun `hyphenated keywords tokenised as single token`() {
        assertEquals(TokenType.END_IF,        types("END-IF")[0])
        assertEquals(TokenType.END_PROCEDURE, types("END-PROCEDURE")[0])
        assertEquals(TokenType.END_FOR,       types("END-FOR")[0])
        assertEquals(TokenType.END_WHILE,     types("END-WHILE")[0])
        assertEquals(TokenType.END_TRY,       types("END-TRY")[0])
    }

    // ---- Identifiers --------------------------------------------------------

    @Test fun `simple identifier`() {
        val t = lex("balance")[0]
        assertEquals(TokenType.IDENTIFIER, t.type)
        assertEquals("BALANCE", t.value)
    }

    @Test fun `hyphenated identifier`() {
        val t = lex("customer-name")[0]
        assertEquals(TokenType.IDENTIFIER, t.type)
        assertEquals("CUSTOMER-NAME", t.value)
    }

    @Test fun `hyphen before digit splits identifier from minus`() {
        val ts = types("balance-1")
        assertEquals(TokenType.IDENTIFIER, ts[0])
        assertEquals(TokenType.MINUS,      ts[1])
        assertEquals(TokenType.INTEGER_LIT,ts[2])
    }

    @Test fun `multi-segment hyphenated identifier`() {
        val t = lex("invoice-line-item")[0]
        assertEquals(TokenType.IDENTIFIER, t.type)
        assertEquals("INVOICE-LINE-ITEM", t.value)
    }

    // ---- Literals -----------------------------------------------------------

    @Test fun `integer literal`() {
        val t = lex("42")[0]
        assertEquals(TokenType.INTEGER_LIT, t.type)
        assertEquals("42", t.value)
    }

    @Test fun `decimal literal`() {
        val t = lex("3.14")[0]
        assertEquals(TokenType.DECIMAL_LIT, t.type)
        assertEquals("3.14", t.value)
    }

    @Test fun `string literal`() {
        val t = lex("\"hello world\"")[0]
        assertEquals(TokenType.STRING_LIT, t.type)
        assertEquals("hello world", t.value)
    }

    @Test fun `escaped double quote in string`() {
        val t = lex("\"say \"\"hello\"\"\"")[0]
        assertEquals(TokenType.STRING_LIT, t.type)
        assertEquals("say \"hello\"", t.value)
    }

    @Test fun `boolean literals`() {
        assertEquals(TokenType.TRUE,  types("TRUE")[0])
        assertEquals(TokenType.FALSE, types("FALSE")[0])
        assertEquals(TokenType.TRUE,  types("true")[0])
    }

    // ---- String interpolation -----------------------------------------------

    @Test fun `plain string produces single STRING_LIT`() {
        val ts = lex("\"Hello, World!\"")
        assertEquals(1, ts.filter { it.type == TokenType.STRING_LIT }.size)
        assertEquals("Hello, World!", ts[0].value)
    }

    @Test fun `interpolated string emits correct token sequence`() {
        // "Hello, {name}!"
        val ts = lex("\"Hello, {name}!\"")
        val relevant = ts.filter { it.type !in setOf(TokenType.EOF, TokenType.NEWLINE) }
        assertEquals(TokenType.STRING_LIT,   relevant[0].type)  // "Hello, "
        assertEquals(TokenType.INTERP_START, relevant[1].type)  // {
        assertEquals(TokenType.IDENTIFIER,   relevant[2].type)  // name
        assertEquals(TokenType.INTERP_END,   relevant[3].type)  // }
        assertEquals(TokenType.STRING_LIT,   relevant[4].type)  // "!"
        assertEquals("Hello, ", relevant[0].value)
        assertEquals("NAME",    relevant[2].value)
        assertEquals("!",       relevant[4].value)
    }

    @Test fun `interpolated arithmetic expression`() {
        val ts = lex("\"Total: {a + b}\"")
        val types2 = ts.map { it.type }
        assertTrue(TokenType.INTERP_START in types2)
        assertTrue(TokenType.INTERP_END   in types2)
        assertTrue(TokenType.PLUS         in types2)
    }

    // ---- Operators ----------------------------------------------------------

    @Test fun `comparison operators`() {
        assertEquals(TokenType.EQ,  types("=")[0])
        assertEquals(TokenType.NEQ, types("<>")[0])
        assertEquals(TokenType.LT,  types("<")[0])
        assertEquals(TokenType.GT,  types(">")[0])
        assertEquals(TokenType.LEQ, types("<=")[0])
        assertEquals(TokenType.GEQ, types(">=")[0])
    }

    @Test fun `power operator`() {
        assertEquals(TokenType.POWER, types("**")[0])
    }

    @Test fun `arithmetic operators`() {
        assertEquals(TokenType.PLUS,  types("+")[0])
        assertEquals(TokenType.MINUS, types("-")[0])
        assertEquals(TokenType.STAR,  types("*")[0])
        assertEquals(TokenType.SLASH, types("/")[0])
    }

    // ---- Comments -----------------------------------------------------------

    @Test fun `line comment is ignored`() {
        val ts = types("-- this is a comment\nDATA")
        assertTrue(TokenType.PROGRAM !in ts)
        assertTrue(TokenType.DATA in ts)
    }

    @Test fun `NOTE block comment is skipped`() {
        val ts = types("NOTE:\n  free text /* not C */ anything\n  line two\nEND-NOTE\nDATA")
        assertTrue(TokenType.DATA in ts)
        assertTrue(ts.none { it == TokenType.IDENTIFIER })  // block body never tokenized
    }

    @Test fun `NOTE block opens only at line start`() {
        // NOTE mid-line is an ordinary identifier, not a block opener.
        val ts = types("MOVE NOTE TO x")
        assertEquals(TokenType.IDENTIFIER, ts[1])
    }

    @Test fun `unterminated NOTE block errors`() {
        assertThrows<LexException> { lex("NOTE:\n  text with no terminator\n") }
    }

    @Test fun `legacy block comment no longer skipped`() {
        // /* */ is no longer a comment — the chars now lex as operators/identifiers.
        val ts = types("/* x */ DATA")
        assertTrue(ts[0] != TokenType.DATA)
    }

    @Test fun `TODO and FIXME tags surface as diagnostics`() {
        val lexer = Lexer("-- TODO: rescale before store\n-- FIXME: O(n^2) here\nDATA", "test.kbl")
        lexer.tokenize()
        assertEquals(1, lexer.diagnostics.infos.size)
        assertEquals(1, lexer.diagnostics.warnings.size)
        assertTrue(lexer.diagnostics.infos[0].message.startsWith("TODO:"))
        assertTrue(lexer.diagnostics.warnings[0].message.startsWith("FIXME:"))
    }

    @Test fun `non-tag comment produces no diagnostic`() {
        val lexer = Lexer("-- TODONT is not a tag\n-- ordinary note\nDATA", "test.kbl")
        lexer.tokenize()
        assertTrue(lexer.diagnostics.infos.isEmpty())
        assertTrue(lexer.diagnostics.warnings.isEmpty())
    }

    // ---- Indentation --------------------------------------------------------

    @Test fun `indent and dedent emitted`() {
        val src = """
IF x:
  DISPLAY y
END-IF
""".trimIndent()
        val ts = types(src)
        assertTrue(TokenType.INDENT in ts)
        assertTrue(TokenType.DEDENT in ts)
    }

    @Test fun `blank lines do not affect indent level`() {
        val src = "PROCEDURE Main:\n\n  DISPLAY x\n"
        val ts = types(src)
        assertEquals(1, ts.count { it == TokenType.INDENT })
    }

    // ---- Error cases --------------------------------------------------------

    @Test fun `tab character throws LexException`() {
        assertThrows<LexException> { lex("\tDATA") }
    }

    @Test fun `unterminated string throws LexException`() {
        assertThrows<LexException> { lex("\"unclosed") }
    }

    @Test fun `unexpected character throws LexException`() {
        assertThrows<LexException> { lex("@bad") }
    }

    // ---- Source positions ---------------------------------------------------

    @Test fun `token position is correct`() {
        val t = lex("  balance")[0]
        assertEquals(3, t.pos.column)  // spaces skip, 'b' is col 3
    }

    @Test fun `multiline position tracking`() {
        val ts = lex("PROGRAM\nDATA")
        val dataToken = ts.first { it.type == TokenType.DATA }
        assertEquals(2, dataToken.pos.line)
    }
}
