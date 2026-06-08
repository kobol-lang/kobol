package dev.kobol.parser

import dev.kobol.lexer.Token
import dev.kobol.lexer.TokenType
import dev.kobol.lexer.TokenType.*
import dev.kobol.lexer.SourcePosition
import dev.kobol.parser.ast.*

/**
 * Kobol recursive-descent parser.
 *
 * Consumes a [List<Token>] produced by the Lexer and returns a [Program] AST.
 * NEWLINE tokens are used only for block structure detection; they are skipped
 * everywhere a newline is not semantically meaningful.
 *
 * Ergonomics desugaring (spec §17):
 *   LET  → ComputeStatement (or LocalVarDecl when a type annotation is present)
 *   DO   → PerformStatement
 */
class Parser(
    rawTokens: List<Token>,
    private val fileName: String = "<unknown>",
) {
    // Strip NEWLINE tokens that appear inside certain positions; keep INDENT/DEDENT
    internal val tokens: List<Token> = rawTokens.filter { it.type != NEWLINE }
    internal var pos = 0

    /**
     * When true, `parsePipelineOrPrimary` does NOT consume trailing pipeline stages
     * (FILTER/TRANSFORM/SUM/SORT/TAKE). Set while parsing a FILTER predicate so a condition
     * like `NOT paid` does not greedily absorb the enclosing pipeline's `TRANSFORM TO amount
     * SUM` stages into the predicate.
     */
    internal var suppressPipelineStages = false

    companion object {
        /**
         * Keyword token types that can also appear as inline builtin function calls
         * when followed by a left parenthesis — e.g. COMBINE(a, b).
         */
        internal val KEYWORD_BUILTINS = setOf(TokenType.COMBINE, TokenType.ROUND)

        /**
         * "Soft" keywords introduced for Groups 11-12 (HTTP/JDBC/REST). They have
         * dedicated TokenTypes to avoid ambiguity inside their own syntax, but they
         * are contextually valid as identifiers (variable names, config items, etc.)
         * everywhere else.
         */
        internal val SOFT_KEYWORDS = setOf(
            TokenType.AT, TokenType.BODY, TokenType.ENDPOINT, TokenType.END_SERVER,
            TokenType.HEADERS, TokenType.PARAMS, TokenType.PORT,
            TokenType.RESPOND, TokenType.SERVER, TokenType.STATUS, TokenType.TIMEOUT,
            // NoSQL/Cache — can also appear as stdlib function names (e.g. kobol.text.FIND)
            TokenType.FIND, TokenType.SAVE, TokenType.DELETE, TokenType.COUNT,
            TokenType.CACHE, TokenType.DATABASE, TokenType.EXPIRES,
        )
    }

    // -------------------------------------------------------------------------
    // Public entry point
    // -------------------------------------------------------------------------

    fun parseProgram(): Program {
        skipWs()
        // Detect script mode: no PROGRAM keyword at the start
        return if (check(PROGRAM)) parseFullProgram() else parseScriptMode()
    }

    // -------------------------------------------------------------------------
    // Script mode (§17.5): wrap file body in implicit Program + Main procedure
    // -------------------------------------------------------------------------

    internal fun parseScriptMode(): Program {
        val startPos = currentPos()
        val stmts    = parseStatements()
        val mainProc = ProcedureDecl("MAIN", emptyList(), null, stmts, startPos)
        return Program(
            name        = fileName.substringBefore('.').uppercase(),
            version     = null,
            author      = null,
            imports     = emptyList(),
            records     = emptyList(),
            constants   = emptyList(),
            dataSection = null,
            fileSection = null,
            procedures  = listOf(mainProc),
            pos         = startPos,
        )
    }

    // -------------------------------------------------------------------------
    // Full program
    // -------------------------------------------------------------------------

    internal fun parseFullProgram(): Program {
        val startPos = currentPos()
        expect(PROGRAM, "Expected PROGRAM")
        val nameRaw = peek().rawValue   // original source case, e.g. "DataTypes"
        val name    = expectIdent("Expected program name")
        // VERSION/AUTHOR may appear on the next line (indented) — consume leading INDENT
        while (check(INDENT) || check(DEDENT)) advance()
        val version = if (match(VERSION)) expectString("Expected version string") else null
        while (check(INDENT) || check(DEDENT)) advance()
        val author  = if (match(AUTHOR))  expectString("Expected author string")  else null
        while (check(INDENT) || check(DEDENT)) advance()

        val imports    = mutableListOf<ImportDecl>()
        val records    = mutableListOf<RecordDecl>()
        val constants  = mutableListOf<ConstantDecl>()
        val typeAliases = mutableListOf<TypeAliasDecl>()
        var dataSection: DataSection? = null
        var fileSection: FileSection? = null
        var configSection: ConfigSection? = null
        val variantDecls = mutableListOf<VariantDecl>()
        val procedures = mutableListOf<ProcedureDecl>()
        val testDecls  = mutableListOf<TestDecl>()
        val tableTests = mutableListOf<TableTestDecl>()
        val namedConditions = mutableListOf<NamedConditionDecl>()
        var moduleDecl: ModuleDecl? = null

        while (!isAtEnd()) {
            while (check(INDENT) || check(DEDENT)) advance()  // skip between top-level decls
            when {
                check(MODULE)    -> moduleDecl = parseModuleDecl()
                check(IMPORT)    -> imports.add(parseImport())
                check(RECORD)    -> records.add(parseRecord())
                check(DEFINE)    -> {
                    if (check(DEFINE) && peek(1).type == IDENTIFIER && peek(1).value.uppercase() == "TYPE")
                        typeAliases.add(parseTypeAlias())
                    else
                        constants.add(parseConstant())
                }
                check(DATA)      -> dataSection = parseDataSection()
                check(FILES)     -> fileSection = parseFileSection()
                check(CONFIG)    -> configSection = parseConfigSection()
                check(VARIANT)   -> variantDecls.add(parseVariantDecl())
                check(CONDITION) -> namedConditions.add(parseNamedConditionDecl())
                check(PROCEDURE) -> procedures.add(parseProcedure())
                // EXPORT PROCEDURE — inline export decorator (alternative to MODULE block)
                check(EXPORT) && peek(1).type == PROCEDURE -> {
                    advance() // consume EXPORT
                    procedures.add(parseProcedure(exported = true))
                }
                // ASYNC PROCEDURE — virtual-thread procedure returning CompletableFuture
                check(ASYNC) && peek(1).type == PROCEDURE -> {
                    advance() // consume ASYNC
                    procedures.add(parseProcedure(isAsync = true))
                }
                // EXPORT ASYNC PROCEDURE
                check(EXPORT) && peek(1).type == ASYNC -> {
                    advance() // consume EXPORT
                    advance() // consume ASYNC
                    procedures.add(parseProcedure(exported = true, isAsync = true))
                }
                check(TEST)      -> {
                    if (peek(1).type == IDENTIFIER && peek(1).value.uppercase() == "TABLE")
                        tableTests.add(parseTableTestDecl())
                    else
                        testDecls.add(parseTestDecl())
                }
                check(END_PROGRAM) -> { advance(); break }
                check(EOF)       -> break
                else -> throw error("Unexpected token '${peek().value}' at top level")
            }
        }

        return Program(name, version, author, imports, records, constants, dataSection, fileSection, procedures, startPos,
            configSection = configSection, variants = variantDecls, typeAliases = typeAliases, tests = testDecls,
            namedConditions = namedConditions, moduleDecl = moduleDecl, tableTests = tableTests, rawName = nameRaw)
    }

    // -------------------------------------------------------------------------
    // Imports
    // -------------------------------------------------------------------------

    internal fun parseBlock(): List<Statement> {
        val stmts = mutableListOf<Statement>()
        if (!match(INDENT)) return stmts
        while (!check(DEDENT) && !isBlockEnd() && !isAtEnd()) {
            skipWs()
            if (check(DEDENT) || isBlockEnd() || isAtEnd()) break
            val s = parseStatement() ?: break
            stmts.add(s)
            skipWs()
        }
        if (check(DEDENT)) advance()
        return stmts
    }

    internal fun isBlockEnd() = peek().type in setOf(
        END_IF, END_FOR, END_WHILE, END_PROCEDURE, END_PROGRAM,
        END_TRY, END_PERFORM, END_CONCURRENT, END_CONFIG, END_VALIDATE,
        END_MATCH, END_VARIANT, END_TEST, ELSE, ENSURE, ON, EOF
    )

    /** Parse statements at current indent level (no INDENT/DEDENT wrapping). */
    internal fun parseStatements(): List<Statement> {
        val stmts = mutableListOf<Statement>()
        while (!isAtEnd() && !isBlockEnd()) {
            skipWs()
            if (isAtEnd() || isBlockEnd()) break
            val s = parseStatement() ?: break
            stmts.add(s)
        }
        return stmts
    }

    // -------------------------------------------------------------------------
    // Statement dispatch
    // -------------------------------------------------------------------------

    internal fun parseStatement(): Statement? {
        skipWs()
        return when (peek().type) {
            MOVE       -> parseMoveStatement()
            COMPUTE, LET -> parseComputeOrLet()
            ADD        -> parseAddStatement()
            SUBTRACT   -> parseSubtractStatement()
            MULTIPLY   -> parseMultiplyStatement()
            DIVIDE     -> parseDivideStatement()
            ACCEPT     -> parseAcceptStatement()
            DISPLAY    -> parseDisplayStatement()
            PERFORM, DO -> parsePerformStatement()
            IF         -> parseIfStatement()
            WHILE      -> parseWhileStatement()
            FOR        -> parseForStatement()
            REPEAT     -> parseRepeatStatement()
            OPEN       -> parseOpenStatement()
            READ       -> parseReadStatement()
            WRITE      -> parseWriteStatement()
            CLOSE      -> parseCloseStatement()
            TRY        -> parseTryStatement()
            RAISE      -> parseRaiseStatement()
            RETURN     -> parseReturnStatement()
            STOP       -> parseStopStatement()
            CALL       -> {
                val t1 = peek(1).value.uppercase()
                when {
                    t1 == "HTTP"  -> parseHttpCallStatement()
                    t1 == "JDBC"  -> parseJdbcCallStatement()
                    else          -> parseCallStatement()
                }
            }
            LOG        -> parseLogStatement()
            CONCURRENT -> parseConcurrentBlock()
            VALIDATE   -> parseValidateStatement()
            MATCH      -> parseMatchStatement()
            SLEEP      -> parseSleepStatement()
            ASSERT     -> parseAssertStatement()
            PARSE      -> parseParseStatement()
            WITH       -> parseWithStatement()
            ROUND      -> parseRoundStatement()
            MOCK       -> parseMockStatement()
            SERVER     -> parseServerStatement()
            RESPOND    -> parseRespondStatement()
            AWAIT      -> parseAwaitStatement()
            NOSQL      -> parseNoSqlStatement()
            FIND       -> parseNoSqlFindStatement()
            SAVE       -> parseNoSqlSaveStatement()
            DELETE     -> parseNoSqlDeleteStatement()
            COUNT      -> parseNoSqlCountStatement()
            CACHE      -> parseCacheStatement()
            PUT        -> parseMapPutStatement()   // #12 — MAP insert/update
            GET        -> parseMapGetStatement()   // #12 — MAP lookup
            else -> null
        }
    }

    // AWAIT future-var INTO result-var
    internal fun parseCallArgs(): List<Expression> {
        val args = mutableListOf<Expression>()
        args.add(parseCallArg())
        while (check(COMMA)) { advance(); args.add(parseCallArg()) }
        return args
    }

    internal fun parseCallArg(): Expression {
        // Named argument: `name : expr`
        if (check(IDENTIFIER) && peek(1).type == COLON) {
            val p        = currentPos()
            val argName  = advance().value
            advance() // ':'
            val value    = parseExpression()
            return NamedArgument(argName, value, p)
        }
        return parseExpression()
    }

    // -------------------------------------------------------------------------
    // Expression parsing (Pratt / recursive-descent with precedence)
    // -------------------------------------------------------------------------

    internal fun peek(offset: Int = 0) = if (pos + offset < tokens.size) tokens[pos + offset] else tokens.last()
    internal fun advance() = tokens[pos++]
    internal fun isAtEnd() = pos >= tokens.size || tokens[pos].type == EOF
    internal fun currentPos() = peek().pos
    internal fun check(type: TokenType) = !isAtEnd() && peek().type == type
    internal fun match(type: TokenType): Boolean { if (check(type)) { advance(); return true }; return false }
    internal fun skipWs() { /* NEWLINEs are already filtered; INDENT/DEDENT are structural — do not consume them here */ }
    internal fun isStatementEnd() = isAtEnd() || check(DEDENT) || isBlockEnd()

    internal fun expect(type: TokenType, msg: String): Token {
        if (!check(type)) throw error("$msg (got '${peek().value}')")
        return advance()
    }

    internal fun expectIdent(msg: String): String {
        if (!check(IDENTIFIER) && !isSoftKeyword(peek().type)) throw error("$msg (got '${peek().value}')")
        return advance().value
    }


    /**
     * Returns true for token types that are "soft" keywords — introduced for specific syntactic
     * positions (Groups 11-12 HTTP/JDBC/REST) but valid as identifiers elsewhere (e.g. as variable
     * or config-item names).
     */
    internal fun isSoftKeyword(t: TokenType): Boolean = t in SOFT_KEYWORDS

    /** Like [expectIdent] but also accepts keyword tokens (for dotted qualified names). */
    internal fun expectAnyIdentifier(msg: String): String {
        if (isAtEnd()) throw error("$msg (got EOF)")
        return advance().value
    }

    internal fun expectString(msg: String): String {
        if (!check(STRING_LIT)) throw error("$msg (got '${peek().value}')")
        return advance().value
    }

    internal fun expectInt(): Int {
        if (!check(INTEGER_LIT)) throw error("Expected integer (got '${peek().value}')")
        return advance().value.toInt()
    }

    internal fun expectKeyword(value: String, msg: String): Token {
        if (peek().value != value.uppercase()) throw error(msg)
        return advance()
    }

    internal fun error(msg: String) = ParseException(msg, currentPos())

    /** Match an identifier token whose value equals [keyword] (e.g. "BY", "AT"). */
    internal fun matchKeywordValue(keyword: String): Boolean {
        if (check(IDENTIFIER) && peek().value == keyword.uppercase()) { advance(); return true }
        return false
    }

    // =========================================================================
}
