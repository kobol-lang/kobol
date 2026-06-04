package dev.kobol.parser

import dev.kobol.parser.ast.*
import dev.kobol.lexer.Token
import dev.kobol.lexer.TokenType
import dev.kobol.lexer.TokenType.*

internal fun Parser.parseProcedure(exported: Boolean = false, isAsync: Boolean = false): ProcedureDecl {
        val p = currentPos()
        expect(PROCEDURE, "Expected PROCEDURE")
        val name   = expectIdent("Expected procedure name")
        val deprecated = if (match(DEPRECATED)) expectString("Expected deprecation message after DEPRECATED") else null
        val params = if (match(USING)) parseParamList() else emptyList()
        val retType = if (match(RETURNING)) parseTypeSpec() else null
        expect(COLON, "Expected ':' after procedure header")
        skipWs()
        val body = parseBlock()
        if (check(END_PROCEDURE)) advance()
        return ProcedureDecl(name, params, retType, body, p, exported = exported, isAsync = isAsync, deprecated = deprecated)
    }

internal fun Parser.parseParamList(): List<ProcParam> {
        val params = mutableListOf<ProcParam>()
        params.add(parseProcParam())
        while (match(COMMA)) params.add(parseProcParam())
        return params
    }

internal fun Parser.parseProcParam(): ProcParam {
        val p    = currentPos()
        val name = expectIdent("Expected parameter name")
        expect(COLON, "Expected ':' after parameter name")
        val type = parseTypeSpec()
        return ProcParam(name, type, p)
    }

    // -------------------------------------------------------------------------
    // Block parsing (INDENT … DEDENT)
    // -------------------------------------------------------------------------

internal fun Parser.parseTestDecl(): TestDecl {
        val p = currentPos()
        expect(TEST, "Expected TEST")
        val name = if (check(STRING_LIT)) advance().value.trim('"') else expectIdent("Expected test name")
        expect(COLON, "Expected ':' after test name")
        skipWs()
        val body = parseBlock()
        if (check(END_TEST)) advance()
        return TestDecl(name, body, p)
    }

    // -------------------------------------------------------------------------
    // TEST TABLE "name": COLUMNS: col1, ... ROW: v1, ... WHEN: ... THEN: ...
    // -------------------------------------------------------------------------

internal fun Parser.parseTableTestDecl(): TableTestDecl {
        val p = currentPos()
        expect(TEST, "Expected TEST")
        advance() // consume TABLE identifier
        val name = if (check(STRING_LIT)) advance().value.trim('"') else expectIdent("Expected table test name")
        expect(COLON, "Expected ':' after table test name")
        // COLUMNS: col1, col2, ...
        while (check(INDENT) || check(DEDENT) || check(NEWLINE)) advance()
        if (peek().value.uppercase() != "COLUMNS") throw error("Expected COLUMNS in TABLE test")
        advance() // COLUMNS
        expect(COLON, "Expected ':' after COLUMNS")
        val columns = mutableListOf(expectIdent("Expected column name"))
        while (match(COMMA)) columns.add(expectIdent("Expected column name"))
        // ROW: v1, v2, ...
        val rows = mutableListOf<List<Expression>>()
        while (true) {
            while (check(INDENT) || check(DEDENT) || check(NEWLINE)) advance()
            if (peek().value.uppercase() != "ROW") break
            advance() // ROW
            expect(COLON, "Expected ':' after ROW")
            val row = mutableListOf(parseExpression())
            while (match(COMMA)) row.add(parseExpression())
            rows.add(row)
        }
        // WHEN: body
        while (check(INDENT) || check(DEDENT) || check(NEWLINE)) advance()
        if (peek().value.uppercase() != "WHEN") throw error("Expected WHEN in TABLE test")
        advance() // WHEN
        expect(COLON, "Expected ':' after WHEN")
        val whenBlock = parseBlock()
        // THEN: body
        while (check(INDENT) || check(DEDENT) || check(NEWLINE)) advance()
        if (peek().value.uppercase() != "THEN") throw error("Expected THEN in TABLE test")
        advance() // THEN
        expect(COLON, "Expected ':' after THEN")
        val thenBlock = parseBlock()
        while (check(INDENT) || check(DEDENT) || check(NEWLINE)) advance()
        if (check(END_TEST)) advance()
        return TableTestDecl(name, columns, rows, whenBlock, thenBlock, p)
    }

    // -------------------------------------------------------------------------
    // MOCK ProcedureName RETURNS expr
    // -------------------------------------------------------------------------

internal fun Parser.parseMockStatement(): MockStatement {
        val p = currentPos()
        expect(MOCK, "Expected MOCK")
        val procName = expectIdent("Expected procedure name after MOCK")
        if (!matchKeywordValue("RETURNS")) throw error("Expected RETURNS after procedure name in MOCK")
        val returns = parseExpression()
        return MockStatement(procName, returns, p)
    }

    // -------------------------------------------------------------------------
    // HTTP client: CALL http.GET/POST/PUT/DELETE/PATCH USING url ...
    // -------------------------------------------------------------------------

