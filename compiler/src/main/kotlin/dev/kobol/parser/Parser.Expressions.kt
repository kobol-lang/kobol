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
        // Pipeline stages follow the collection expression — but not while parsing a FILTER
        // predicate (else `NOT paid` would swallow the enclosing `TRANSFORM TO amount SUM`).
        while (!suppressPipelineStages && (check(FILTER) || check(TRANSFORM) || check(SUM) || check(SORT) || check(TAKE))) {
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
                    // Parse the predicate without letting it consume the enclosing pipeline's
                    // trailing stages (TRANSFORM/SUM/…) — those belong to this pipeline, not the
                    // condition. e.g. `FILTER WHERE NOT paid TRANSFORM TO amount SUM`.
                    val prev = suppressPipelineStages
                    suppressPipelineStages = true
                    val cond = try { parseExpression() } finally { suppressPipelineStages = prev }
                    stages.add(PipelineStage.FilterStage(cond, sp))
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

/** Tokens that can begin an expression atom — used to detect prose-builtin arguments. #5 */
internal fun Parser.canStartExprAtom(): Boolean = when (peek().type) {
    STRING_LIT, INTEGER_LIT, DECIMAL_LIT, INTERP_START, TRUE, FALSE,
    IDENTIFIER, LPAREN, MINUS, NOT, COMBINE, FIND -> true
    else -> false
}

/** Single-argument prose string builtins: `UPPERCASE name` etc. (spec §11). */
private val PROSE_UNARY_BUILTINS = setOf("UPPERCASE", "LOWERCASE", "TRIM", "REVERSE", "LENGTH")

/**
 * Parse the English-prose forms of the string builtins (spec §11) — the readability thesis.
 * The `FUNC(args)` call form still works (handled later in [parsePrimary]); these are additions:
 *   UPPERCASE x | LOWERCASE x | TRIM x | REVERSE x | LENGTH x
 *   SUBSTRING x FROM start FOR len
 *   FIND needle IN haystack          (lowers to FIND(haystack, needle))
 *   COMBINE a b c …                  (variadic, greedy until the atom run ends)
 * Returns null when the current tokens are not a prose builtin, so [parsePrimary] continues.
 * Arguments are parsed at unary precedence so binary operators bind to the enclosing expression
 * (e.g. `LENGTH name + 1` → `LENGTH(name) + 1`).
 */
internal fun Parser.parseProseStringOp(): Expression? {
    val p   = currentPos()
    val tok = peek()

    // COMBINE / FIND are keyword tokens; their call form `COMBINE(...)`/`FIND(...)` is left to
    // parsePrimary (guard on the following `(`). Lookahead is non-consuming — only commit (advance)
    // once a prose form is certain, so a non-match leaves the token stream untouched.
    if (tok.type == COMBINE && peek(1).type != LPAREN && canStartExprAtomAt(1)) {
        advance()  // COMBINE
        val args = mutableListOf(parseUnaryExpr())
        while (canStartExprAtom()) args.add(parseUnaryExpr())
        return BuiltinCall("COMBINE", args, p)
    }
    if (tok.type == FIND && peek(1).type != LPAREN && canStartExprAtomAt(1)) {
        advance()  // FIND
        val needle = parseUnaryExpr()
        expect(IN, "Expected IN in `FIND <needle> IN <text>`")
        val haystack = parseUnaryExpr()
        return BuiltinCall("FIND", listOf(haystack, needle), p)   // call form is FIND(haystack, needle)
    }
    if (tok.type != IDENTIFIER || peek(1).type == LPAREN) return null
    val name = tok.value
    if (name == "SUBSTRING" && canStartExprAtomAt(1)) {
        advance()  // SUBSTRING
        val s = parseUnaryExpr()
        expect(FROM, "Expected FROM in `SUBSTRING <text> FROM <start> FOR <len>`")
        val start = parseUnaryExpr()
        expect(FOR, "Expected FOR in `SUBSTRING <text> FROM <start> FOR <len>`")
        val len = parseUnaryExpr()
        return BuiltinCall("SUBSTRING", listOf(s, start, len), p)
    }
    if (name in PROSE_UNARY_BUILTINS && canStartExprAtomAt(1)) {
        advance()  // consume the builtin name
        return BuiltinCall(name, listOf(parseUnaryExpr()), p)
    }
    return null
}

/** Lookahead variant of [canStartExprAtom] for the token at [offset] without consuming. */
internal fun Parser.canStartExprAtomAt(offset: Int): Boolean = when (peek(offset).type) {
    STRING_LIT, INTEGER_LIT, DECIMAL_LIT, INTERP_START, TRUE, FALSE,
    IDENTIFIER, LPAREN, MINUS, NOT, COMBINE, FIND -> true
    else -> false
}

internal fun Parser.parsePrimary(): Expression {
        val p = currentPos()

        // Prose string builtins (spec §11) — UPPERCASE x, SUBSTRING x FROM..FOR.., FIND..IN.., COMBINE a b
        parseProseStringOp()?.let { return it }

        // Literals
        parseLiteral()?.let { return it }

        // Parenthesised
        if (check(LPAREN)) {
            advance()
            val e = parseExpression()
            expect(RPAREN, "Expected ')'")
            return e
        }

        // Interop call in expression position: CALL owner.method [WITH|USING args] (F14). Parsed
        // exactly like the CALL statement's head (dotted name in original case + arg list), but it
        // yields the method's real return value. A trailing `WITH a, b` is greedy to the arg list;
        // wrap in parens to use the result inside a larger expression — `(CALL Math.max WITH a, b) * 2`.
        if (check(CALL)) {
            advance() // CALL
            val parts = mutableListOf(advance().rawValue)
            while (check(DOT)) { advance(); parts.add(advance().rawValue) }
            val args = if (match(WITH) || match(USING)) parseCallArgs() else emptyList()
            return CallExpr(parts.joinToString("."), args, p)
        }

        // 3rd-party constructor: NEW Owner [WITH|USING args] (F12). Owner is a dotted name kept
        // in original case (advance().rawValue) so `java.util.ArrayList` and keyword-named parts
        // survive; resolved to a JVM class at codegen like a CALL static owner.
        if (check(NEW)) {
            advance() // NEW
            val ownerParts = mutableListOf(advance().rawValue)
            while (check(DOT)) { advance(); ownerParts.add(advance().rawValue) }
            val args = if (match(WITH) || match(USING)) parseCallArgs() else emptyList()
            return NewExpr(ownerParts.joinToString("."), args, p)
        }

        // Struct literal: TypeName { ... }
        if (check(IDENTIFIER) && peek(1).type == LBRACE) {
            val typeName = advance().value
            advance() // {
            val fields = mutableListOf<RecordLiteralField>()
            // #15 — allow multi-line struct literals. Indented field-per-line layout emits
            // INDENT/DEDENT (and NEWLINE, already filtered) tokens between fields; skip them
            // so a field name on its own line is not read as `got ''`. Commas stay optional.
            fun skipLayout() { while (check(INDENT) || check(DEDENT) || check(NEWLINE)) advance() }
            skipLayout()
            while (!check(RBRACE) && !isAtEnd()) {
                val fp   = currentPos()
                val name = expectIdent("Expected field name")
                expect(COLON, "Expected ':' in struct literal")
                val v    = parseExpression()
                fields.add(RecordLiteralField(name, v, fp))
                if (check(COMMA)) advance()
                skipLayout()
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
                    args.add(parseCallArg())                               // #18 — accept `field: value` named args
                    while (check(COMMA)) { advance(); args.add(parseCallArg()) }
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
                    args.add(parseCallArg())                               // #18 — accept named args here too
                    while (check(COMMA)) { advance(); args.add(parseCallArg()) }
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

