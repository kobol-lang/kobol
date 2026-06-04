package dev.kobol.parser

import dev.kobol.parser.ast.*
import dev.kobol.lexer.Token
import dev.kobol.lexer.TokenType
import dev.kobol.lexer.TokenType.*

internal fun Parser.parseHttpCallStatement(): HttpCallStatement {
        val p = currentPos()
        expect(CALL, "Expected CALL")
        advance() // HTTP
        expect(DOT, "Expected '.' after HTTP")
        val method = advance().value.uppercase()  // GET | POST | PUT | DELETE | PATCH
        val url = if (match(USING)) parseExpression() else throw error("Expected USING after http.$method")
        var headers: Expression? = null
        var body: Expression? = null
        var timeout: Expression? = null
        // Parse optional named clauses in any order
        while (true) {
            when {
                check(HEADERS) -> { advance(); headers = parseExpression() }
                check(BODY)    -> { advance(); body    = parseExpression() }
                check(TIMEOUT) -> { advance(); timeout = parseExpression() }
                else -> break
            }
        }
        val giving = if (match(GIVING)) parseReference() else null
        return HttpCallStatement(method, url, headers, body, timeout, giving, p)
    }

    // -------------------------------------------------------------------------
    // JDBC bridge: CALL jdbc.connect/query/execute/disconnect USING ...
    // -------------------------------------------------------------------------

internal fun Parser.parseJdbcCallStatement(): Statement {
        val p = currentPos()
        expect(CALL, "Expected CALL")
        advance() // JDBC
        expect(DOT, "Expected '.' after JDBC")
        return when (val op = advance().value.uppercase()) {
            "CONNECT"    -> {
                if (!match(USING)) throw error("Expected USING after jdbc.connect")
                val url  = parseExpression()
                val user = if (check(IDENTIFIER) && peek().value.uppercase() == "USER") {
                    advance(); parseExpression()
                } else null
                val pw   = if (check(IDENTIFIER) && peek().value.uppercase() == "PASSWORD") {
                    advance(); parseExpression()
                } else null
                JdbcConnectStatement(url, user, pw, p)
            }
            "QUERY"     -> {
                if (!match(USING)) throw error("Expected USING after jdbc.query")
                val sql    = parseExpression()
                val params = if (check(PARAMS)) { advance(); parseCallArgs() } else emptyList()
                val into   = if (match(INTO)) parseReference() else null
                // AS LIST OF TypeName
                val asType = if (match(AS)) {
                    if (!match(LIST)) throw error("Expected LIST after AS")
                    if (!match(OF))   throw error("Expected OF after LIST")
                    expectIdent("Expected type name after AS LIST OF")
                } else null
                JdbcQueryStatement(sql, params, into, asType, p)
            }
            "EXECUTE"   -> {
                if (!match(USING)) throw error("Expected USING after jdbc.execute")
                val sql    = parseExpression()
                val params = if (check(PARAMS)) { advance(); parseCallArgs() } else emptyList()
                JdbcExecuteStatement(sql, params, p)
            }
            "DISCONNECT" -> JdbcDisconnectStatement(p)
            else -> throw error("Unknown jdbc operation: $op (expected connect/query/execute/disconnect)")
        }
    }

    // -------------------------------------------------------------------------
    // REST server: SERVER AT PORT n: ENDPOINT ... END-SERVER
    // -------------------------------------------------------------------------

internal fun Parser.parseServerStatement(): ServerStatement {
        val p = currentPos()
        expect(SERVER, "Expected SERVER")
        expect(AT, "Expected AT after SERVER")
        expect(PORT, "Expected PORT after AT")
        val port = parseExpression()
        expect(COLON, "Expected ':' after port number")
        val endpoints = mutableListOf<EndpointHandler>()
        while (true) {
            while (check(INDENT) || check(DEDENT) || check(NEWLINE)) advance()
            if (check(END_SERVER) || check(EOF)) break
            if (check(ENDPOINT)) endpoints.add(parseEndpointHandler())
            else break
        }
        if (check(END_SERVER)) advance()
        return ServerStatement(port, endpoints, p)
    }

internal fun Parser.parseEndpointHandler(): EndpointHandler {
        val p = currentPos()
        expect(ENDPOINT, "Expected ENDPOINT")
        val method = advance().value.uppercase()  // GET | POST | etc.
        val path   = advance().value.trim('"')     // "/path" or identifier
        expect(COLON, "Expected ':' after endpoint path")
        // Extract {paramName} placeholders from path string
        val pathParams = Regex("\\{([A-Za-z_][A-Za-z0-9_]*)}")
            .findAll(path).map { it.groupValues[1] }.toList()
        val body = parseBlock()
        return EndpointHandler(method, path, pathParams, body, p)
    }

    // -------------------------------------------------------------------------
    // RESPOND WITH expr [AS JSON]
    // -------------------------------------------------------------------------

internal fun Parser.parseRespondStatement(): RespondStatement {
        val p = currentPos()
        expect(RESPOND, "Expected RESPOND")
        if (!match(WITH)) throw error("Expected WITH after RESPOND")
        val value  = parseExpression()
        val asJson = check(AS) && run { advance(); advance().value.uppercase() == "JSON" }
        val statusCode = if (check(STATUS)) { advance(); parseExpression() } else null
        return RespondStatement(value, asJson, statusCode, p)
    }

    // -------------------------------------------------------------------------
    // ASSERT condition [WITH "message"]
    // -------------------------------------------------------------------------

