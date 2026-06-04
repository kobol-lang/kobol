package dev.kobol.parser

import dev.kobol.parser.ast.*
import dev.kobol.lexer.Token
import dev.kobol.lexer.TokenType
import dev.kobol.lexer.TokenType.*

internal fun Parser.parseExpression(): Expression = parseOrExpr()

internal fun Parser.parseOrExpr(): Expression {
        var left = parseAndExpr()
        while (match(OR)) left = BinaryExpr(BinaryOp.OR, left, parseAndExpr(), left.pos)
        return left
    }

internal fun Parser.parseAndExpr(): Expression {
        var left = parseNotExpr()
        while (match(AND)) left = BinaryExpr(BinaryOp.AND, left, parseNotExpr(), left.pos)
        return left
    }

internal fun Parser.parseNotExpr(): Expression {
        if (check(NOT)) { val p = currentPos(); advance(); return UnaryExpr(UnaryOp.NOT, parseNotExpr(), p) }
        return parseCompareExpr()
    }

internal fun Parser.parseCompareExpr(): Expression {
        var left = parseAddExpr()
        while (true) {
            // IS [NOT] POSITIVE / NEGATIVE / ZERO / EMPTY / BLANK
            if (check(IS)) {
                advance()
                val negated = match(NOT)
                val cond = when {
                    matchKeywordValue("POSITIVE") -> "IS-POSITIVE"
                    matchKeywordValue("NEGATIVE") -> "IS-NEGATIVE"
                    matchKeywordValue("ZERO")     -> "IS-ZERO"
                    matchKeywordValue("EMPTY")    -> "IS-EMPTY"
                    matchKeywordValue("BLANK")    -> "IS-BLANK"
                    else -> throw error("Expected POSITIVE/NEGATIVE/ZERO/EMPTY/BLANK after IS [NOT]")
                }
                val call: Expression = BuiltinCall(cond, listOf(left), left.pos)
                left = if (negated) UnaryExpr(UnaryOp.NOT, call, left.pos) else call
                continue
            }
            val op = when {
                match(EQ)  -> BinaryOp.EQ;  match(NEQ) -> BinaryOp.NEQ
                match(LT)  -> BinaryOp.LT;  match(GT)  -> BinaryOp.GT
                match(LEQ) -> BinaryOp.LEQ; match(GEQ) -> BinaryOp.GEQ
                else -> break
            }
            left = BinaryExpr(op, left, parseAddExpr(), left.pos)
        }
        return left
    }

internal fun Parser.parseAddExpr(): Expression {
        var left = parseMulExpr()
        while (true) {
            val op = when { match(PLUS) -> BinaryOp.ADD; match(MINUS) -> BinaryOp.SUBTRACT; else -> break }
            left = BinaryExpr(op, left, parseMulExpr(), left.pos)
        }
        return left
    }

internal fun Parser.parseMulExpr(): Expression {
        var left = parsePowerExpr()
        while (true) {
            val op = when { match(STAR) -> BinaryOp.MULTIPLY; match(SLASH) -> BinaryOp.DIVIDE; else -> break }
            left = BinaryExpr(op, left, parsePowerExpr(), left.pos)
        }
        return left
    }

internal fun Parser.parsePowerExpr(): Expression {
        var left = parseUnaryExpr()
        if (match(POWER)) left = BinaryExpr(BinaryOp.POWER, left, parseUnaryExpr(), left.pos)
        return left
    }

internal fun Parser.parseUnaryExpr(): Expression {
        if (check(MINUS)) { val p = currentPos(); advance(); return UnaryExpr(UnaryOp.NEGATE, parseUnaryExpr(), p) }
        return parsePipelineOrPrimary()
    }

internal fun Parser.parsePipelineOrPrimary(): Expression {
        var expr = parsePrimary()
        // Pipeline stages follow the collection expression
        while (check(FILTER) || check(TRANSFORM) || check(SUM) || check(SORT) || check(TAKE)) {
            expr = parsePipelineStages(expr)
        }
        return expr
    }

internal fun Parser.parsePipelineStages(source: Expression): Expression {
        val stages = mutableListOf<PipelineStage>()
        val p = currentPos()
        while (check(FILTER) || check(TRANSFORM) || check(SUM) || check(SORT) || check(TAKE)) {
            when {
                check(FILTER) -> {
                    val sp = currentPos(); advance()
                    if (check(WHERE)) advance()
                    // Parse condition without letting it consume subsequent pipeline keywords.
                    // If the recursive parse returns a PipelineExpr, lift those stages up.
                    val condOrPipeline = parseExpression()
                    if (condOrPipeline is PipelineExpr) {
                        stages.add(PipelineStage.FilterStage(condOrPipeline.source, sp))
                        stages.addAll(condOrPipeline.stages)
                    } else {
                        stages.add(PipelineStage.FilterStage(condOrPipeline, sp))
                    }
                }
                check(TRANSFORM) -> {
                    val sp = currentPos(); advance()
                    if (check(TO)) advance()
                    stages.add(PipelineStage.TransformStage(expectIdent("Expected field"), sp))
                }
                check(SUM) -> {
                    val sp = currentPos(); advance()
                    stages.add(PipelineStage.SumStage(sp))
                }
                check(SORT) -> {
                    val sp = currentPos(); advance()
                    if (matchKeywordValue("BY")) { /* consumed */ }
                    val field = expectIdent("Expected sort field")
                    val desc  = peek().value == "DESCENDING"; if (desc) advance()
                    stages.add(PipelineStage.SortStage(field, desc, sp))
                }
                check(TAKE) -> {
                    val sp = currentPos(); advance()
                    stages.add(PipelineStage.TakeStage(parsePrimary(), sp))
                }
                else -> break
            }
        }
        return if (stages.isEmpty()) source else PipelineExpr(source, stages, p)
    }

internal fun Parser.parsePrimary(): Expression {
        val p = currentPos()

        // Literals
        parseLiteral()?.let { return it }

        // Parenthesised
        if (check(LPAREN)) {
            advance()
            val e = parseExpression()
            expect(RPAREN, "Expected ')'")
            return e
        }

        // Struct literal: TypeName { ... }
        if (check(IDENTIFIER) && peek(1).type == LBRACE) {
            val typeName = advance().value
            advance() // {
            val fields = mutableListOf<RecordLiteralField>()
            while (!check(RBRACE) && !isAtEnd()) {
                skipWs()
                val fp   = currentPos()
                val name = expectIdent("Expected field name")
                expect(COLON, "Expected ':' in struct literal")
                val v    = parseExpression()
                fields.add(RecordLiteralField(name, v, fp))
                if (check(COMMA)) advance()
                skipWs()
            }
            expect(RBRACE, "Expected '}' to close struct literal")
            return RecordLiteralExpr(typeName, fields, p)
        }

        // Identifier, field access, or built-in function call
        if (check(IDENTIFIER) || isSoftKeyword(peek().type)) {
            val name = advance().value
            // Check for built-in functions: COMBINE, UPPERCASE, LOWERCASE, etc.
            if (check(LPAREN)) {
                advance()
                val args = mutableListOf<Expression>()
                if (!check(RPAREN)) {
                    args.add(parseExpression())
                    while (check(COMMA)) { advance(); args.add(parseExpression()) }
                }
                expect(RPAREN, "Expected ')'")
                return BuiltinCall(name, args, p)
            }
            // Field access chain: a.b.c
            val parts = mutableListOf(name)
            while (check(DOT)) { advance(); parts.add(expectIdent("Expected field name")) }
            return Reference(parts, p)
        }

        // Keyword-named builtins that can appear as inline expressions: COMBINE(a, b)
        // These tokens are keywords in the lexer but also serve as builtin function names.
        if (peek(1).type == LPAREN) {
            val tok = peek()
            if (tok.type in Parser.KEYWORD_BUILTINS) {
                val name = advance().value
                advance() // (
                val args = mutableListOf<Expression>()
                if (!check(RPAREN)) {
                    args.add(parseExpression())
                    while (check(COMMA)) { advance(); args.add(parseExpression()) }
                }
                expect(RPAREN, "Expected ')'")
                return BuiltinCall(name, args, p)
            }
        }

        throw error("Expected expression, got '${peek().value}'")
    }

internal fun Parser.parseLiteral(): Expression? {
        val p = currentPos()
        return when {
            check(INTEGER_LIT) -> {
                val v = advance().value
                Literal(v.toLong(), LiteralKind.INTEGER, p)
            }
            check(DECIMAL_LIT) -> {
                val v = advance().value
                Literal(v.toBigDecimal(), LiteralKind.DECIMAL, p)
            }
            check(TRUE)  -> { advance(); Literal(true,  LiteralKind.BOOLEAN, p) }
            check(FALSE) -> { advance(); Literal(false, LiteralKind.BOOLEAN, p) }
            check(STRING_LIT) && peek(1).type != INTERP_START -> {
                Literal(advance().value, LiteralKind.STRING, p)
            }
            check(STRING_LIT) || check(INTERP_START) -> parseStringTemplate()
            else -> null
        }
    }

internal fun Parser.parseStringTemplate(): Expression {
        val p     = currentPos()
        val parts = mutableListOf<StringTemplatePart>()

        while (check(STRING_LIT) || check(INTERP_START)) {
            when {
                check(STRING_LIT)   -> parts.add(StringTemplatePart.RawText(advance().value))
                check(INTERP_START) -> {
                    advance() // {
                    val expr = parseExpression()
                    expect(INTERP_END, "Expected '}' to close interpolation")
                    parts.add(StringTemplatePart.Interpolated(expr))
                }
                else -> break
            }
        }

        return when {
            parts.isEmpty() -> Literal("", LiteralKind.STRING, p)
            parts.size == 1 && parts[0] is StringTemplatePart.RawText ->
                Literal((parts[0] as StringTemplatePart.RawText).text, LiteralKind.STRING, p)
            else -> StringTemplateExpr(parts, p)
        }
    }

    // -------------------------------------------------------------------------
    // Reference (variable or field access)
    // -------------------------------------------------------------------------

internal fun Parser.parseReference(): Reference {
        val p    = currentPos()
        val name = expectIdent("Expected variable name")
        val parts = mutableListOf(name)
        while (check(DOT)) { advance(); parts.add(expectIdent("Expected field name")) }
        return Reference(parts, p)
    }

    // -------------------------------------------------------------------------
    // Utilities
    // -------------------------------------------------------------------------

