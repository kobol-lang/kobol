package dev.kobol.parser

import dev.kobol.parser.ast.*
import dev.kobol.lexer.Token
import dev.kobol.lexer.TokenType
import dev.kobol.lexer.TokenType.*

internal fun Parser.parseLogStatement(): Statement {
        val p = currentPos()
        expect(LOG, "Expected LOG")
        val level = when (peek().type) {
            TRACE -> { advance(); LogLevel.TRACE }
            DEBUG -> { advance(); LogLevel.DEBUG }
            INFO  -> { advance(); LogLevel.INFO  }
            WARN  -> { advance(); LogLevel.WARN  }
            ERROR -> { advance(); LogLevel.ERROR }
            else  -> {
                // Also accept as identifier value (case-insensitive)
                val v = peek().value.uppercase()
                if (v in setOf("TRACE", "DEBUG", "INFO", "WARN", "ERROR")) {
                    advance()
                    LogLevel.valueOf(v)
                } else {
                    throw error("Expected log level (TRACE/DEBUG/INFO/WARN/ERROR), got '${peek().value}'")
                }
            }
        }
        val message = parseExpression()
        val kvPairs = mutableListOf<LogKvPair>()
        if (match(WITH)) {
            skipWs()
            if (check(INDENT)) {
                advance()
                while (!check(DEDENT) && !isBlockEnd() && !isAtEnd()) {
                    skipWs()
                    if (check(DEDENT) || isBlockEnd() || isAtEnd()) break
                    val kp = currentPos()
                    val key = expectIdent("Expected key in LOG WITH")
                    expect(COLON, "Expected ':' after key")
                    val value = parseExpression()
                    kvPairs.add(LogKvPair(key, value, kp))
                    skipWs()
                }
                if (check(DEDENT)) advance()
            } else {
                // Inline form: WITH key: val [, key: val ...]
                do {
                    val kp = currentPos()
                    val key = expectIdent("Expected key in LOG WITH")
                    expect(COLON, "Expected ':'")
                    val value = parseExpression()
                    kvPairs.add(LogKvPair(key, value, kp))
                } while (match(COMMA))
            }
        }
        return LogStatement(level, message, kvPairs, p)
    }

    // CONCURRENT [SCOPE "name"]: DO a, DO b ... WAIT ALL [OR FAIL] END-CONCURRENT
internal fun Parser.parseConcurrentBlock(): Statement {
        val p = currentPos()
        expect(CONCURRENT, "Expected CONCURRENT")
        val scope: String? = if (peek().value == "SCOPE") {
            advance()  // consume SCOPE
            if (check(STRING_LIT)) advance().value else null
        } else null
        expect(COLON, "Expected ':' after CONCURRENT")
        skipWs()
        val branches = mutableListOf<List<Statement>>()
        // Each branch is a single statement (usually DO/PERFORM) or a block
        if (check(INDENT)) {
            advance()
            while (!check(DEDENT) && !check(WAIT) && !isBlockEnd() && !isAtEnd()) {
                skipWs()
                if (check(DEDENT) || check(WAIT) || isBlockEnd() || isAtEnd()) break
                val stmt = parseStatement() ?: break
                branches.add(listOf(stmt))
                skipWs()
            }
            if (check(DEDENT)) advance()
        }
        // WAIT ALL [OR FAIL]
        val failMode = if (match(WAIT)) {
            match(ALL)  // consume ALL
            if (peek().value == "OR") {
                advance()  // OR
                if (peek().value == "FAIL") { advance(); ConcurrentFailMode.FAIL_FAST }
                else ConcurrentFailMode.WAIT_ALL
            } else ConcurrentFailMode.WAIT_ALL
        } else ConcurrentFailMode.WAIT_ALL
        if (check(END_CONCURRENT)) advance()
        return ConcurrentBlock(scope, branches, failMode, p)
    }

    // VALIDATE target: MUST ... [FAIL-MSG "..."] ... END-VALIDATE
internal fun Parser.parseValidateStatement(): Statement {
        val p = currentPos()
        expect(VALIDATE, "Expected VALIDATE")
        val target = parseReference()
        expect(COLON, "Expected ':' after VALIDATE target")
        skipWs()
        val constraints = mutableListOf<ValidationConstraint>()
        if (check(INDENT)) {
            advance()
            while (!check(DEDENT) && !check(END_VALIDATE) && !isBlockEnd() && !isAtEnd()) {
                skipWs()
                if (check(DEDENT) || check(END_VALIDATE) || isBlockEnd() || isAtEnd()) break
                constraints.add(parseValidationConstraint())
                skipWs()
            }
            if (check(DEDENT)) advance()
        } else {
            // Single-line form
            constraints.add(parseValidationConstraint())
        }
        if (check(END_VALIDATE)) advance()
        return ValidateStatement(target, constraints, p)
    }

internal fun Parser.parseValidationConstraint(): ValidationConstraint {
        val p = currentPos()
        expect(MUST, "Expected MUST")
        // MUST NOT BE EMPTY/BLANK/NULL | MUST NOT BE ...value...
        if (match(NOT)) {
            val kind = when {
                matchKeywordValue("BE") -> when {
                    peek().value == "EMPTY" -> { advance(); "EMPTY" }
                    peek().value == "BLANK" -> { advance(); "BLANK" }
                    peek().value == "NULL"  -> { advance(); "NULL"  }
                    else -> "EMPTY"
                }
                else -> "EMPTY"
            }
            val failMsg = parseOptionalFailMsg()
            return ValidationConstraint.MustNotBe(kind, failMsg, p)
        }
        // MUST LENGTH op n
        if (peek().value == "LENGTH") {
            advance()
            val op = parseCompOp()
            val n  = expectInt()
            val failMsg = parseOptionalFailMsg()
            return ValidationConstraint.MustLength(op, n, failMsg, p)
        }
        // MUST MATCH "regex"
        if (peek().value == "MATCH") {
            advance()
            val pattern = expectString("Expected regex pattern string")
            val failMsg = parseOptionalFailMsg()
            return ValidationConstraint.MustMatch(pattern, failMsg, p)
        }
        // MUST SATISFY ProcName
        if (peek().value == "SATISFY") {
            advance()
            val procName = expectIdent("Expected procedure name")
            val failMsg  = parseOptionalFailMsg()
            return ValidationConstraint.MustSatisfy(procName, failMsg, p)
        }
        // MUST BE op value
        matchKeywordValue("BE") // optional BE
        val op    = parseCompOp()
        val value = parsePrimary()
        val failMsg = parseOptionalFailMsg()
        return ValidationConstraint.MustBe(op, value, failMsg, p)
    }

internal fun Parser.parseCompOp(): String = when {
        match(GEQ) -> ">=";  match(LEQ) -> "<="
        match(GT)  -> ">";   match(LT)  -> "<"
        match(EQ)  -> "=";   match(NEQ) -> "<>"
        else -> throw error("Expected comparison operator")
    }

internal fun Parser.parseOptionalFailMsg(): String? {
        if (peek().value == "FAIL-MSG") {
            advance()  // FAIL-MSG token
            return if (check(STRING_LIT)) advance().value else null
        }
        return null
    }

    // VARIANT TypeName IS Case1 | Case2 WITH field : Type ... END-VARIANT
internal fun Parser.parseMatchStatement(): Statement {
        val p = currentPos()
        expect(MATCH, "Expected MATCH")
        val subject = parseExpression()
        expect(COLON, "Expected ':' after MATCH subject")
        skipWs()
        val whenClauses = mutableListOf<WhenClause>()
        var otherwise: List<Statement>? = null
        if (check(INDENT)) {
            advance()
            while (!check(DEDENT) && !check(END_MATCH) && !isAtEnd()) {
                skipWs()
                if (check(DEDENT) || check(END_MATCH) || isAtEnd()) break
                when {
                    peek().value == "WHEN" || check(WHERE) -> {
                        advance()  // WHEN / WHERE
                        val wp = currentPos()
                        val pattern = parseMatchPattern()
                        expect(COLON, "Expected ':' after WHEN pattern")
                        val body = parseBlock()
                        whenClauses.add(WhenClause(pattern, body, wp))
                    }
                    check(OTHERWISE) -> {
                        advance()  // OTHERWISE
                        if (check(COLON)) advance()
                        otherwise = parseBlock()
                    }
                    else -> break
                }
                skipWs()
            }
            if (check(DEDENT)) advance()
        }
        if (check(END_MATCH)) advance()
        return MatchStatement(subject, whenClauses, otherwise, p)
    }

internal fun Parser.parseMatchPattern(): MatchPattern {
        val p = currentPos()
        // A guard expression can attach in two documented ways: the explicit
        // `WHEN <pattern> IF <cond>` form, or the §22.4 `WHEN Type WITH <cond>` form
        // (where the WITH clause is a boolean expression, not a binding list).
        var withGuard: Expression? = null
        val base: MatchPattern = when {
            // Type pattern: WHEN TEXT AS x:  |  WHEN INTEGER AS n:
            peek().type in setOf(TEXT, INTEGER, DECIMAL, MONEY, BOOLEAN, DATE, DATETIME, UUID) -> {
                val typeName = advance().value
                val binding  = if (check(AS)) { advance(); expectIdent("Expected binding name after AS") } else null
                MatchPattern.TypePattern(typeName, binding, p)
            }
            // Variant/record case pattern: CapitalisedName [WITH binding, ... | WITH guard-expr]
            check(IDENTIFIER) && peek().value[0].isUpperCase() -> {
                val caseName = advance().value
                val bindings = mutableListOf<String>()
                if (match(WITH)) {
                    // A deconstruction binding list is `ident (',' ident)*` terminated by ':'
                    // (or 'IF' before an explicit guard). Anything else after WITH — a comparison,
                    // a NOT/AND, a literal — is a §22.4 boolean guard over the subject's fields.
                    if (check(IDENTIFIER) && peek(1).type in setOf(COMMA, COLON, IF)) {
                        bindings.add(expectIdent("Expected binding name"))
                        while (check(COMMA)) { advance(); bindings.add(expectIdent("Expected binding name")) }
                    } else {
                        withGuard = parseExpression()
                    }
                }
                MatchPattern.VariantPattern(caseName, bindings, p)
            }
            // Literal or range pattern: parse first expression, check for ..
            else -> {
                val first = parseExpression()
                if (check(DOTDOT)) {
                    advance()
                    MatchPattern.RangePattern(first, parseExpression(), p)
                } else {
                    MatchPattern.LiteralPattern(first, p)
                }
            }
        }
        // Guard: WHEN <pattern> WITH <cond>  (§22.4)  or  WHEN <pattern> IF <cond>.
        val guard = withGuard ?: if (check(IF)) { advance(); parseExpression() } else null
        return if (guard != null) MatchPattern.GuardPattern(base, guard, p) else base
    }

    // CONFIG: item : TYPE FROM ENV "VAR" [REQUIRED | DEFAULT expr] [MUST ...]
internal fun Parser.parseConfigSection(): ConfigSection {
        val p = currentPos()
        expect(CONFIG, "Expected CONFIG")
        expect(COLON, "Expected ':' after CONFIG")
        skipWs()
        val items = mutableListOf<ConfigItem>()
        if (match(INDENT)) {
            while (!check(DEDENT) && !check(END_CONFIG) && !isAtEnd()) {
                skipWs()
                if (check(DEDENT) || check(END_CONFIG) || isAtEnd()) break
                items.add(parseConfigItem())
                skipWs()
            }
            if (check(DEDENT)) advance()
        }
        if (check(END_CONFIG)) advance()
        return ConfigSection(items, p)
    }

internal fun Parser.parseConfigItem(): ConfigItem {
        val p = currentPos()
        val name = expectIdent("Expected config item name")
        expect(COLON, "Expected ':' after config item name")
        val type = parseTypeSpec()
        if (peek().value != "FROM") throw error("Expected FROM ENV \"VAR\"")
        advance()  // FROM
        if (peek().value != "ENV") throw error("Expected ENV after FROM")
        advance()  // ENV
        val envVar = expectString("Expected environment variable name string")
        var required = false
        var default: Expression? = null
        var constraint: ValidationConstraint? = null
        while (!isStatementEnd()) {
            when {
                peek().value == "REQUIRED" -> { advance(); required = true }
                peek().value == "DEFAULT"  -> { advance(); default = parseExpression() }
                check(MUST)               -> constraint = parseValidationConstraint()
                else                      -> break
            }
        }
        return ConfigItem(name, type, envVar, required, default, constraint, p)
    }

    // TRY: body [ON ExType [AS binding]: handler]* [ENSURE: block] END-TRY
