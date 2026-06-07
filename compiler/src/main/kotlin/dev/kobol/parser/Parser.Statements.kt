package dev.kobol.parser

import dev.kobol.parser.ast.*
import dev.kobol.lexer.Token
import dev.kobol.lexer.TokenType
import dev.kobol.lexer.TokenType.*

internal fun Parser.parseAwaitStatement(): AwaitStatement {
        val p = currentPos()
        expect(AWAIT, "Expected AWAIT")
        val future = parseReference()
        expect(INTO, "Expected INTO after future variable in AWAIT")
        val into = parseReference()
        return AwaitStatement(future, into, p)
    }

    // -------------------------------------------------------------------------
    // SLEEP amount MILLISECONDS | SECONDS | MINUTES
    // -------------------------------------------------------------------------

internal fun Parser.parseSleepStatement(): SleepStatement {
        val p = currentPos()
        expect(SLEEP, "Expected SLEEP")
        val amount = parseExpression()
        val unit = when {
            match(MILLISECONDS) -> SleepUnit.MILLISECONDS
            match(SECONDS)      -> SleepUnit.SECONDS
            match(MINUTES)      -> SleepUnit.MINUTES
            else -> throw error("Expected MILLISECONDS, SECONDS, or MINUTES after SLEEP")
        }
        return SleepStatement(amount, unit, p)
    }

    // -------------------------------------------------------------------------
    // TEST "name": ... END-TEST
    // -------------------------------------------------------------------------

internal fun Parser.parseAssertStatement(): AssertStatement {
        val p = currentPos()
        expect(ASSERT, "Expected ASSERT")
        val condition = parseExpression()
        val msg = if (match(WITH)) parseExpression() else null
        return AssertStatement(condition, msg, p)
    }

    // MOVE source TO target
internal fun Parser.parseMoveStatement(): Statement {
        val p = currentPos(); expect(MOVE, "Expected MOVE")
        val from = parseExpression()
        expect(TO, "Expected TO in MOVE statement")
        val to = parseReference()
        return MoveStatement(from, to, p)
    }

    // PUT value TO map WITH KEY key   (#12 — MAP insert/update)
    internal fun Parser.parseMapPutStatement(): MapPutStatement {
        val p = currentPos()
        expect(PUT, "Expected PUT")
        val value = parseExpression()
        expect(TO, "Expected TO after value in PUT")
        val map = parseReference()
        expect(WITH, "Expected WITH in PUT … WITH KEY")
        expect(KEY, "Expected KEY in PUT … WITH KEY")
        val key = parseExpression()
        return MapPutStatement(value, map, key, p)
    }

    // GET map KEY key INTO dest        (#12 — MAP lookup)
    internal fun Parser.parseMapGetStatement(): MapGetStatement {
        val p = currentPos()
        expect(GET, "Expected GET")
        val map = parseReference()
        expect(KEY, "Expected KEY in GET map KEY … INTO")
        val key = parseExpression()
        expect(INTO, "Expected INTO in GET map KEY … INTO")
        val into = parseReference()
        return MapGetStatement(map, key, into, p)
    }

    // COMPUTE/LET target = expr  |  LET name : type = expr  (local var decl)
internal fun Parser.parseComputeOrLet(): Statement {
        val p       = currentPos()
        val isLet   = check(LET)
        advance()  // consume COMPUTE or LET
        val name    = expectIdent("Expected variable name")

        // LET name : type = expr  → LocalVarDecl
        if (isLet && check(COLON)) {
            advance()
            val type = parseTypeSpec()
            expect(EQ, "Expected '=' in LET declaration")
            val expr = parseExpression()
            return LocalVarDecl(name, type, expr, p)
        }
        // LET/COMPUTE name [. field]* = expr  → ComputeStatement
        val parts = mutableListOf(name)
        while (check(DOT)) { advance(); parts.add(expectIdent("Expected field name")) }
        expect(EQ, "Expected '=' in COMPUTE/LET")
        val expr = parseExpression()
        return ComputeStatement(Reference(parts, p), expr, p)
    }

    // ADD operand TO target [GIVING result]
internal fun Parser.parseAddStatement(): Statement {
        val p = currentPos(); expect(ADD, "Expected ADD")
        val operand = parseExpression()
        expect(TO, "Expected TO in ADD statement")
        val target  = parseReference()
        val giving  = if (match(GIVING)) parseReference() else null
        return AddStatement(operand, target, giving, p)
    }

    // SUBTRACT operand FROM target [GIVING result]
internal fun Parser.parseSubtractStatement(): Statement {
        val p = currentPos(); expect(SUBTRACT, "Expected SUBTRACT")
        val operand = parseExpression()
        expect(FROM, "Expected FROM in SUBTRACT")
        val target  = parseReference()
        val giving  = if (match(GIVING)) parseReference() else null
        return SubtractStatement(operand, target, giving, p)
    }

    // MULTIPLY left BY right [GIVING result]
internal fun Parser.parseMultiplyStatement(): Statement {
        val p = currentPos(); expect(MULTIPLY, "Expected MULTIPLY")
        val left   = parseExpression()
        if (!matchKeywordValue("BY")) throw error("Expected BY in MULTIPLY")
        val right  = parseReference()
        val giving = if (match(GIVING)) parseReference() else null
        return MultiplyStatement(left, right, giving, p)
    }

    // Two readable directions, both → result = target / divisor:
    //   COBOL form : DIVIDE <divisor> INTO <target>  [GIVING result] [USING mode]
    //   English    : DIVIDE <dividend> BY <divisor>  [GIVING result] [USING mode]
internal fun Parser.parseDivideStatement(): Statement {
        val p = currentPos(); expect(DIVIDE, "Expected DIVIDE")
        val first = parseExpression()
        val divisor: Expression
        val target: Reference
        when {
            match(INTO) -> { divisor = first; target = parseReference() }
            matchKeywordValue("BY") -> {
                // English direction: `first` is the dividend (becomes the arith target),
                // the operand after BY is the divisor.
                target  = first as? Reference
                    ?: throw error("DIVIDE ... BY requires a variable name before BY")
                divisor = parseExpression()
            }
            else -> throw error("Expected INTO or BY in DIVIDE (got '${peek().value}')")
        }
        val giving  = if (match(GIVING)) parseReference() else null
        val mode    = if (match(USING)) {
            when {
                matchKeywordValue("HALF-EVEN")  -> "HALF_EVEN"
                matchKeywordValue("HALF-UP")    -> "HALF_UP"
                matchKeywordValue("HALF-DOWN")  -> "HALF_DOWN"
                matchKeywordValue("UP")         -> "UP"
                matchKeywordValue("DOWN")       -> "DOWN"
                matchKeywordValue("CEILING")    -> "CEILING"
                matchKeywordValue("FLOOR")      -> "FLOOR"
                else -> expectIdent("Expected rounding mode after USING")
            }
        } else null
        return DivideStatement(divisor, target, giving, mode, p)
    }

    // DISPLAY expr (expr | ',' expr)*  — space-separated or comma-separated args
internal fun Parser.parseDisplayStatement(): Statement {
        val p = currentPos(); expect(DISPLAY, "Expected DISPLAY")
        val values = mutableListOf<Expression>()
        // Handle special DISPLAY TABLE / DISPLAY LABEL / DISPLAY FORMAT
        if (peek().value == "TABLE" || peek().value == "LABEL" || peek().value == "FORMAT" || peek().value == "JSON" || peek().value == "STYLED" || peek().value == "XML") {
            val func = advance().value
            if (func == "JSON") {
                // PRETTY accepted on either side of the expression:
                //   DISPLAY JSON PRETTY x   (legacy)   and   DISPLAY JSON x PRETTY (spec §26)
                val pretty = matchKeywordValue("PRETTY")
                val expr = parseExpression()
                val prettyAfter = matchKeywordValue("PRETTY")
                values.add(BuiltinCall(if (pretty || prettyAfter) "DISPLAY_JSON_PRETTY" else "DISPLAY_JSON", listOf(expr), p))
            } else if (func == "XML") {
                val pretty = matchKeywordValue("PRETTY")
                val expr = parseExpression()
                val prettyAfter = matchKeywordValue("PRETTY")
                values.add(BuiltinCall(if (pretty || prettyAfter) "DISPLAY_XML_PRETTY" else "DISPLAY_XML", listOf(expr), p))
            } else if (func == "STYLED") {
                val text = parseExpression()
                var bold = false
                var underline = false
                var color: Expression = Literal("NONE", LiteralKind.STRING, p)
                while (true) {
                    when {
                        matchKeywordValue("BOLD")      -> bold = true
                        matchKeywordValue("UNDERLINE") -> underline = true
                        matchKeywordValue("COLOR")     -> color = parseExpression()
                        else                           -> break
                    }
                }
                values.add(BuiltinCall("DISPLAY_STYLED", listOf(
                    text,
                    Literal(bold, LiteralKind.BOOLEAN, p),
                    Literal(underline, LiteralKind.BOOLEAN, p),
                    color
                ), p))
            } else {
                val args = mutableListOf<Expression>(parseExpression())
                if (func == "LABEL" || func == "FORMAT") args.add(parseExpression())
                values.add(BuiltinCall("DISPLAY_$func", args, p))
            }
        } else {
            values.add(parseExpression())
            // Continue on comma OR if the next token can begin an expression
            // (string literal, identifier, number, paren) but is NOT a statement keyword.
            while (true) {
                if (check(COMMA)) { advance() } else if (!isDisplayExpressionStart()) break
                if (isStatementEnd() || isAtEnd()) break
                values.add(parseExpression())
            }
        }
        return DisplayStatement(values, p)
    }

    /** True when the current token can start a DISPLAY argument but is NOT a statement keyword. */
internal fun Parser.isDisplayExpressionStart(): Boolean {
        val t = peek().type
        return t == IDENTIFIER || t == STRING_LIT || t == INTEGER_LIT ||
               t == DECIMAL_LIT || t == TRUE || t == FALSE || t == LPAREN || t == MINUS
    }

    // PERFORM / DO [Alias.]ProcName [USING args] [GIVING future]
internal fun Parser.parsePerformStatement(): Statement {
        val p = currentPos()
        advance() // PERFORM or DO
        val firstName = expectIdent("Expected procedure name")
        // Support dotted cross-module call: PERFORM ModuleAlias.ProcedureName
        val (moduleAlias, procName) = if (check(DOT)) {
            advance() // consume '.'
            firstName to expectIdent("Expected procedure name after '.'")
        } else {
            null to firstName
        }
        val args = if (match(USING)) parseCallArgs() else emptyList()
        val giving = if (match(GIVING)) parseReference() else null
        return PerformStatement(procName, args, p, moduleAlias = moduleAlias, giving = giving)
    }

    // IF condition: body [ELSE IF ...] [ELSE ...] [END-IF]
internal fun Parser.parseIfStatement(): Statement {
        val p = currentPos(); expect(IF, "Expected IF")
        val cond = parseExpression()
        expect(COLON, "Expected ':' after IF condition")
        skipWs()
        val thenBranch = parseBlock()
        val elseIfs    = mutableListOf<ElseIfClause>()
        var elseBranch: List<Statement>? = null
        while (check(ELSE)) {
            val ep = currentPos(); advance()
            if (check(IF)) {
                advance()
                val ec   = parseExpression()
                expect(COLON, "Expected ':' after ELSE IF")
                val body = parseBlock()
                elseIfs.add(ElseIfClause(ec, body, ep))
            } else {
                if (check(COLON)) advance()
                elseBranch = parseBlock()
                break
            }
        }
        if (check(END_IF)) advance()
        return IfStatement(cond, thenBranch, elseIfs, elseBranch, p)
    }

    // WHILE condition: body [END-WHILE]
internal fun Parser.parseWhileStatement(): Statement {
        val p = currentPos(); expect(WHILE, "Expected WHILE")
        val cond = parseExpression()
        expect(COLON, "Expected ':' after WHILE")
        val body = parseBlock()
        if (check(END_WHILE)) advance()
        return WhileStatement(cond, body, p)
    }

    // FOR EACH var IN iterable [PARALLEL [MAX-THREADS n]]: body [END-FOR]
internal fun Parser.parseForStatement(): Statement {
        val p = currentPos()
        expect(FOR, "Expected FOR")
        expect(EACH, "Expected EACH after FOR")
        val varName  = expectAnyIdentifier("Expected loop variable")
        expect(IN, "Expected IN")
        val iterable = parseExpression()
        // Check for PARALLEL modifier
        if (check(PARALLEL)) {
            advance()
            val maxThreads: Expression? = if (peek().value == "MAX-THREADS") {
                advance()  // consume MAX-THREADS
                parsePrimary()
            } else null
            expect(COLON, "Expected ':' after FOR EACH ... PARALLEL")
            val body = parseBlock()
            if (check(END_FOR)) advance()
            return ParallelForEachStatement(varName, iterable, maxThreads, body, p)
        }
        expect(COLON, "Expected ':' after FOR EACH")
        val body = parseBlock()
        if (check(END_FOR)) advance()
        return ForEachStatement(varName, iterable, body, p)
    }

    // REPEAT n TIMES: body [END-REPEAT]
internal fun Parser.parseRepeatStatement(): Statement {
        val p = currentPos(); expect(REPEAT, "Expected REPEAT")
        val count = parseExpression()
        expectKeyword("TIMES", "Expected TIMES after repeat count")
        expect(COLON, "Expected ':' after REPEAT n TIMES")
        val body = parseBlock()
        if (peek().value == "END-REPEAT") advance()
        return RepeatStatement(count, body, p)
    }

    // OPEN FileName FOR INPUT|OUTPUT|EXTEND
internal fun Parser.parseOpenStatement(): Statement {
        val p = currentPos(); expect(OPEN, "Expected OPEN")
        val name = expectIdent("Expected file name")
        expect(FOR, "Expected FOR in OPEN statement")
        val mode = when {
            match(INPUT)  -> FileMode.INPUT
            match(OUTPUT) -> FileMode.OUTPUT
            match(EXTEND) -> FileMode.EXTEND
            else -> throw error("Expected INPUT, OUTPUT, or EXTEND")
        }
        return OpenStatement(name, mode, p)
    }

    // READ FileName INTO target [AT END: stmts END-READ]
internal fun Parser.parseReadStatement(): Statement {
        val p = currentPos()
        expect(READ, "Expected READ")
        val name = expectIdent("Expected file name")
        expect(INTO, "Expected INTO in READ")
        val into  = parseReference()
        var atEnd: List<Statement>? = null
        if (matchKeywordValue("AT")) {
            if (!matchKeywordValue("END")) throw error("Expected END after AT")
            expect(COLON, "Expected ':' after AT END"); atEnd = parseBlock()
            if (peek().value == "END-READ") advance()
        }
        return ReadStatement(name, into, atEnd, p)
    }

    // WRITE FileName FROM source
internal fun Parser.parseWriteStatement(): Statement {
        val p = currentPos()
        expect(WRITE, "Expected WRITE")
        // WRITE JSON expr TO filepath [PRETTY]
        if (matchKeywordValue("JSON")) {
            val expr   = parseExpression()
            expect(TO, "Expected TO in WRITE JSON")
            val path   = parseExpression()
            val pretty = matchKeywordValue("PRETTY")
            return WriteJsonStatement(expr, path, pretty, p)
        }
        // WRITE XML expr TO filepath [PRETTY]
        if (matchKeywordValue("XML")) {
            val expr   = parseExpression()
            expect(TO, "Expected TO in WRITE XML")
            val path   = parseExpression()
            val pretty = matchKeywordValue("PRETTY")
            return WriteXmlStatement(expr, path, pretty, p)
        }
        val name = expectIdent("Expected file name")
        expect(FROM, "Expected FROM in WRITE")
        val from = parseReference()
        return WriteStatement(name, from, p)
    }

    // PARSE JSON [FILE] source INTO ref [AS TypeName | AS LIST [OF TypeName]]
    // PARSE XML  [FILE] source INTO ref [AS TypeName | AS LIST [OF TypeName]]
internal fun Parser.parseParseStatement(): Statement {
        val p = currentPos()
        expect(PARSE, "Expected PARSE")
        val isXml = when {
            matchKeywordValue("JSON") -> false
            matchKeywordValue("XML")  -> true
            else -> throw error("Expected JSON or XML after PARSE")
        }
        val fromFile = matchKeywordValue("FILE")
        val source   = parseExpression()
        expect(INTO, "Expected INTO in PARSE statement")
        val into = parseReference()
        // Optional: AS TypeName  |  AS LIST [OF TypeName]
        var asTypeName: String? = null
        var asList = false
        if (check(AS)) {
            advance()
            if (check(LIST)) {
                advance()
                asList = true
                if (match(OF)) asTypeName = expectIdent("Expected type name after OF")
            } else {
                asTypeName = expectIdent("Expected type name after AS")
            }
        }
        return if (isXml) ParseXmlStatement(source, fromFile, into, asTypeName, asList, p)
               else        ParseJsonStatement(source, fromFile, into, asTypeName, asList, p)
    }

    // WITH PRECISION precisionName: body END-PRECISION
internal fun Parser.parseWithStatement(): Statement {
        val p = currentPos()
        expect(WITH, "Expected WITH")
        if (!matchKeywordValue("PRECISION") && peek().type != PRECISION) {
            throw error("Expected PRECISION after WITH")
        }
        if (peek().type == PRECISION) advance()
        val precisionName = when {
            check(INTEGER_LIT)              -> advance().value   // spec §12.4: WITH PRECISION 34 …
            matchKeywordValue("DECIMAL32")  -> "DECIMAL32"
            matchKeywordValue("DECIMAL64")  -> "DECIMAL64"
            matchKeywordValue("DECIMAL128") -> "DECIMAL128"
            matchKeywordValue("UNLIMITED")  -> "UNLIMITED"
            else -> expectIdent("Expected precision: an integer literal or DECIMAL32, DECIMAL64, DECIMAL128, UNLIMITED")
        }
        // Optional: ROUNDING <mode> before the colon
        val roundingMode = if (matchKeywordValue("ROUNDING")) parseRoundingMode("ROUNDING") else null
        expect(COLON, "Expected ':' after precision name")
        val body = parseBlock()
        if (check(END_PRECISION)) advance()
        return WithPrecisionStatement(precisionName, roundingMode, body, p)
    }

    /**
     * Parse a rounding-mode name (`HALF-EVEN`, `HALF-UP`, … `FLOOR`) into its canonical
     * hyphenated string. Shared by the precision block (`ROUNDING <mode>`), the ROUND
     * statement (`USING <mode>`), and the prose `ROUND x TO n USING <mode>` expression
     * (#v2) so the accepted mode set can never diverge between sites (priority 1).
     */
    internal fun Parser.parseRoundingMode(after: String): String = when {
        matchKeywordValue("HALF-EVEN") -> "HALF-EVEN"
        matchKeywordValue("HALF-UP")   -> "HALF-UP"
        matchKeywordValue("HALF-DOWN") -> "HALF-DOWN"
        matchKeywordValue("UP")        -> "UP"
        matchKeywordValue("DOWN")      -> "DOWN"
        matchKeywordValue("CEILING")   -> "CEILING"
        matchKeywordValue("FLOOR")     -> "FLOOR"
        else -> expectIdent("Expected rounding mode after $after")
    }

    // CLOSE FileName
internal fun Parser.parseRoundStatement(): Statement {
        // ROUND target TO scale [USING mode]
        val p = currentPos()
        expect(ROUND, "Expected ROUND")
        val target = parseReference()
        if (!match(TO)) throw error("Expected TO after ROUND target")
        val scale = parseExpression()
        val mode = if (match(USING)) parseRoundingMode("USING") else null
        return RoundStatement(target, scale, mode, p)
    }

    // CLOSE FileName
internal fun Parser.parseCloseStatement(): Statement {
        val p = currentPos()
        expect(CLOSE, "Expected CLOSE")
        val name = expectIdent("Expected file name")
        return CloseStatement(name, p)
    }

    // LOG LEVEL "message" [WITH key: expr ...]
internal fun Parser.parseTryStatement(): Statement {
        val p = currentPos(); expect(TRY, "Expected TRY"); expect(COLON, "Expected ':'")
        val body     = parseBlock()
        val handlers = mutableListOf<ExceptionHandler>()
        var ensure: List<Statement>? = null
        while (check(ON)) {
            val hp = currentPos(); advance()
            val exType  = expectIdent("Expected exception type")
            val binding = if (match(AS)) expectIdent("Expected binding name") else null
            expect(COLON, "Expected ':' after ON clause")
            val hBody = parseBlock()
            handlers.add(ExceptionHandler(exType, binding, hBody, hp))
        }
        if (check(ENSURE)) {
            advance(); expect(COLON, "Expected ':' after ENSURE"); ensure = parseBlock()
        }
        if (check(END_TRY)) advance()
        return TryStatement(body, handlers, ensure, p)
    }

    // RAISE ExceptionType [message]
internal fun Parser.parseRaiseStatement(): Statement {
        val p = currentPos(); expect(RAISE, "Expected RAISE")
        val exType = expectIdent("Expected exception type name")
        val msg    = if (!isStatementEnd() && !isBlockEnd()) parseExpression() else null
        return RaiseStatement(exType, msg, p)
    }

    // RETURN [expr]
internal fun Parser.parseReturnStatement(): Statement {
        val p = currentPos(); expect(RETURN, "Expected RETURN")
        val value = if (!isStatementEnd() && !isBlockEnd()) parseExpression() else null
        return ReturnStatement(value, p)
    }

    // STOP RUN [WITH EXIT-CODE n]
internal fun Parser.parseStopStatement(): Statement {
        val p = currentPos(); expect(STOP, "Expected STOP"); expect(RUN, "Expected RUN")
        val code = if (peek().value == "WITH") {
            advance() // WITH
            if (peek().value == "EXIT-CODE") advance() // EXIT-CODE
            parseExpression()
        } else null
        return StopRunStatement(code, p)
    }

    // CALL qualified.method [WITH args] [GIVING target]
    // rawValue preserves original case: "LocalDate" not "LOCALDATE", "getInstance" not "GETINSTANCE"
    // advance().rawValue used so keywords in paths (DATE, TEXT, etc.) are accepted and case-preserved.
internal fun Parser.parseCallStatement(): Statement {
        val p = currentPos(); expect(CALL, "Expected CALL")
        val parts = mutableListOf(advance().rawValue)
        while (check(DOT)) { advance(); parts.add(advance().rawValue) }
        val method = parts.joinToString(".")
        val args   = if (match(WITH) || match(USING)) parseCallArgs() else emptyList()
        val giving = if (match(GIVING)) parseReference() else null
        return CallStatement(method, args, giving, p)
    }

    // -------------------------------------------------------------------------
    // Call arguments (positional or named)
    // -------------------------------------------------------------------------

