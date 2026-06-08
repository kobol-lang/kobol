package dev.kobol.lexer

import dev.kobol.diagnostic.DiagnosticBag

/**
 * Kobol lexical error — includes source line text for ER-1 pointer diagnostics.
 */
class LexException(
    message: String,
    val pos: SourcePosition,
    val sourceLine: String = "",
) : Exception("$pos: $message")

/**
 * Kobol Lexer — converts raw source text into a flat list of [Token]s.
 *
 * Key rules:
 * - Free-format UTF-8; no column significance.
 * - Keywords and identifiers case-insensitive, normalised to UPPERCASE.
 * - Identifier hyphens: `customer-name` is ONE token; `balance-1` splits at
 *   the hyphen because the next char is a digit, not a letter.
 * - Tabs are forbidden (error + continue).
 * - INDENT/DEDENT synthetic tokens emitted on indentation changes.
 * - String interpolation: `"Hello, {name}!"` emits STRING_LIT / INTERP_START /
 *   expr tokens / INTERP_END / STRING_LIT.
 */
class Lexer(
    private val source: String,
    private val fileName: String = "<unknown>",
) {
    private var pos             = 0
    private var line            = 1
    private var column          = 1
    private var lineStartOffset = 0

    private val errors      = mutableListOf<LexException>()
    private val tokens      = mutableListOf<Token>()

    /** Non-fatal lexer diagnostics: comment tags (TODO/FIXME/HACK/XXX). Drained by the compile pipeline. */
    val diagnostics = DiagnosticBag()
    private val indentStack = ArrayDeque<Int>().also { it.addLast(0) }

    private var atLineStart    = true
    private var inInterpolation = false

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    fun tokenize(): List<Token> {
        while (!isAtEnd()) scanToken()
        emitPendingDedents(0)
        emit(TokenType.EOF, "")
        if (errors.isNotEmpty()) throw errors.first()
        return tokens.toList()
    }

    // -------------------------------------------------------------------------
    // Scanner dispatch
    // -------------------------------------------------------------------------

    private fun scanToken() {
        val lineStart = atLineStart && !inInterpolation
        if (lineStart) {
            handleIndentation()
            if (isAtEnd()) return
        }

        val startPos = currentPos()
        val c = peek()

        when {
            // NOTE: … END-NOTE block comment — free text, only at the start of a line.
            lineStart && matchesNoteBlockStart() -> skipNoteBlock()
            c == ' '  -> advance()
            c == '\r' -> advance()
            c == '\t' -> { addError("Tabs are not allowed; use spaces", startPos); advance() }
            c == '\n' -> {
                advance(); line++; column = 1; lineStartOffset = pos
                if (tokens.isNotEmpty() && tokens.last().type !in setOf(TokenType.NEWLINE, TokenType.INDENT)) {
                    tokens.add(Token(TokenType.NEWLINE, "\\n", startPos))
                }
                atLineStart = true
            }
            c == '-' && peek(1) == '-'  -> skipLineComment()
            c == '"'  -> scanString()
            c == '\'' -> scanRawString()
            c == '*' && peek(1) == '*'  -> { advance(); advance(); emit(TokenType.POWER,    "**") }
            c == '<' && peek(1) == '>'  -> { advance(); advance(); emit(TokenType.NEQ,      "<>") }
            c == '<' && peek(1) == '='  -> { advance(); advance(); emit(TokenType.LEQ,      "<=") }
            c == '>' && peek(1) == '='  -> { advance(); advance(); emit(TokenType.GEQ,      ">=") }
            c == '+'  -> { advance(); emit(TokenType.PLUS,     "+") }
            c == '-'  -> { advance(); emit(TokenType.MINUS,    "-") }
            c == '*'  -> { advance(); emit(TokenType.STAR,     "*") }
            c == '/'  -> { advance(); emit(TokenType.SLASH,    "/") }
            c == '='  -> { advance(); emit(TokenType.EQ,       "=") }
            c == '<'  -> { advance(); emit(TokenType.LT,       "<") }
            c == '>'  -> { advance(); emit(TokenType.GT,       ">") }
            c == ':'  -> { advance(); emit(TokenType.COLON,    ":") }
            c == '.'  -> { advance(); if (!isAtEnd() && peek() == '.') { advance(); emit(TokenType.DOTDOT, "..") } else emit(TokenType.DOT, ".") }
            c == ','  -> { advance(); emit(TokenType.COMMA,    ",") }
            c == '('  -> { advance(); emit(TokenType.LPAREN,   "(") }
            c == ')'  -> { advance(); emit(TokenType.RPAREN,   ")") }
            c == '{'  -> { advance(); emit(TokenType.LBRACE,   "{") }
            c == '}'  -> { advance(); emit(TokenType.RBRACE,   "}") }
            c == '['  -> { advance(); emit(TokenType.LBRACKET, "[") }
            c == ']'  -> { advance(); emit(TokenType.RBRACKET, "]") }
            c == '|'  -> { advance(); emit(TokenType.PIPE,     "|") }
            c.isDigit()            -> scanNumber()
            c.isLetter() || c == '_' -> scanWord()
            else -> { addError("Unexpected character: '$c'", startPos); advance() }
        }
    }

    // -------------------------------------------------------------------------
    // Indentation
    // -------------------------------------------------------------------------

    private fun handleIndentation() {
        atLineStart = false
        var spaces = 0
        while (!isAtEnd() && peek() == ' ') { spaces++; advance() }

        // Blank / comment-only lines: ignore for indentation purposes
        if (isAtEnd() || peek() == '\n' || peek() == '\r'
            || (peek() == '-' && peek(1) == '-')
            || matchesNoteBlockStart()) return

        val current = indentStack.last()
        when {
            spaces > current -> { indentStack.addLast(spaces); emit(TokenType.INDENT, "") }
            spaces < current -> emitPendingDedents(spaces)
        }
    }

    private fun emitPendingDedents(target: Int) {
        while (indentStack.last() > target) { indentStack.removeLast(); emit(TokenType.DEDENT, "") }
        if (indentStack.last() != target)
            addError("Inconsistent indentation: expected ${indentStack.last()} spaces, got $target", currentPos())
    }

    // -------------------------------------------------------------------------
    // String literals with {…} interpolation
    // -------------------------------------------------------------------------

    private fun scanString() {
        val startPos = currentPos()
        advance() // consume "
        var seg = StringBuilder()

        fun flush() { tokens.add(Token(TokenType.STRING_LIT, seg.toString(), startPos)); seg = StringBuilder() }

        while (!isAtEnd() && peek() != '\n') {
            when (val ch = peek()) {
                '"'  -> {
                    advance()
                    if (!isAtEnd() && peek() == '"') { seg.append('"'); advance() }
                    else { flush(); return }
                }
                '{'  -> {
                    flush(); advance()
                    tokens.add(Token(TokenType.INTERP_START, "{", currentPos()))
                    scanInterpolationExpr()
                }
                '\\' -> {
                    advance()
                    when (peek()) {
                        'n'  -> { seg.append('\n'); advance() }
                        't'  -> { seg.append('\t'); advance() }
                        '\\' -> { seg.append('\\'); advance() }
                        // F4: inside an interpolation body, `\"` is the closing delimiter of a
                        // nested string (the user escaped the quotes because the whole literal is
                        // already inside "…"). Elsewhere `\"` is an escaped literal quote.
                        '"'  -> { if (inInterpolation) { advance(); flush(); return } else { seg.append('"'); advance() } }
                        // \{ and \} = literal braces, the escape hatch for JSON literals
                        // and regex {n,m} quantifiers in an interpolating string (#17).
                        '{'  -> { seg.append('{');  advance() }
                        '}'  -> { seg.append('}');  advance() }
                        else ->   seg.append('\\')
                    }
                }
                else -> { seg.append(ch); advance() }
            }
        }
        flush()
        addError("Unterminated string literal", startPos)
    }

    /**
     * Single-quoted raw string: '...'
     * No interpolation — {curly braces} and all other characters are literal.
     * Useful for URL path templates like '/hello/{name}' that contain literal braces.
     * Emits a single STRING_LIT token.
     */
    private fun scanRawString() {
        val startPos = currentPos()
        advance() // consume '
        val sb = StringBuilder()
        while (!isAtEnd() && peek() != '\n') {
            val ch = peek()
            if (ch == '\'') {
                advance()  // consume closing '
                tokens.add(Token(TokenType.STRING_LIT, sb.toString(), startPos))
                return
            }
            sb.append(ch); advance()
        }
        tokens.add(Token(TokenType.STRING_LIT, sb.toString(), startPos))
        addError("Unterminated raw string literal", startPos)
    }

    private fun scanInterpolationExpr() {
        var depth = 1
        val savedAtLineStart = atLineStart
        inInterpolation = true

        while (!isAtEnd() && depth > 0) {
            when {
                peek() == '\n' -> { addError("Newline inside interpolation", currentPos()); break }
                peek() == '{' -> { depth++; advance(); emit(TokenType.LBRACE, "{") }
                peek() == '}' -> {
                    depth--; advance()
                    if (depth == 0) emit(TokenType.INTERP_END, "}") else emit(TokenType.RBRACE, "}")
                }
                peek() == ' '  -> advance()
                peek() == '"'  -> scanString()
                // F4: `\"` opens a nested string written with escaped quotes — drop the leading
                // backslash and let scanString read the body (its `\"` close path handles the end).
                peek() == '\\' && peek(1) == '"' -> { advance(); scanString() }
                peek().isDigit()              -> scanNumber()
                peek().isLetter() || peek() == '_' -> scanWord()
                else -> { atLineStart = false; scanToken() }
            }
        }

        inInterpolation = false
        atLineStart = savedAtLineStart
    }

    // -------------------------------------------------------------------------
    // Numbers and words
    // -------------------------------------------------------------------------

    private fun scanNumber() {
        val startPos = currentPos()
        val sb = StringBuilder()
        while (!isAtEnd() && peek().isDigit()) { sb.append(peek()); advance() }
        if (!isAtEnd() && peek() == '.' && peek(1).isDigit()) {
            sb.append('.'); advance()
            while (!isAtEnd() && peek().isDigit()) { sb.append(peek()); advance() }
            tokens.add(Token(TokenType.DECIMAL_LIT, sb.toString(), startPos))
        } else {
            tokens.add(Token(TokenType.INTEGER_LIT, sb.toString(), startPos))
        }
    }

    private fun scanWord() {
        val startPos = currentPos()
        val upper = StringBuilder()
        val raw   = StringBuilder()
        while (!isAtEnd()) {
            val c = peek()
            when {
                c.isLetterOrDigit() || c == '_' -> {
                    upper.append(c.uppercaseChar()); raw.append(c); advance()
                }
                // Hyphen only continues identifier when followed by a LETTER (not digit)
                c == '-' && !isAtEnd(1) && peek(1).isLetter() -> {
                    upper.append(c); raw.append(c); advance()
                }
                else -> break
            }
        }
        val upperStr = upper.toString()
        val rawStr   = raw.toString()
        tokens.add(Token(KEYWORDS[upperStr] ?: TokenType.IDENTIFIER, upperStr, startPos, rawStr))
    }

    // -------------------------------------------------------------------------
    // Comments
    // -------------------------------------------------------------------------

    /** `-- comment` to end of line. Surfaces TODO/FIXME/HACK/XXX tags as diagnostics. */
    private fun skipLineComment() {
        val startPos = currentPos()
        advance(); advance() // consume --
        val sb = StringBuilder()
        while (!isAtEnd() && peek() != '\n') { sb.append(peek()); advance() }
        reportCommentTag(sb.toString(), startPos)
    }

    /**
     * Comment-tag → diagnostic mapping. TODO/HACK are informational; FIXME/XXX are warnings.
     * A tag fires only when it is the first word of the comment and ends at a non-alphanumeric
     * boundary (so `TODONT` does not match `TODO`).
     */
    private fun reportCommentTag(text: String, pos: SourcePosition) {
        val body = text.trimStart()
        if (body.isEmpty()) return
        val word = body.takeWhile { it.isLetter() }
        val rest = body.substring(word.length)
        if (rest.firstOrNull()?.let { it.isLetterOrDigit() || it == '_' } == true) return
        val message = "${word.uppercase()}:${rest.removePrefix(":").trimEnd()}".trimEnd(':', ' ')
        val src = currentSourceLine()
        when (word.uppercase()) {
            "TODO" -> diagnostics.info("W004", message, pos, src)
            "HACK" -> diagnostics.info("W006", message, pos, src)
            "FIXME" -> diagnostics.warning("W005", message, pos, src)
            "XXX" -> diagnostics.warning("W007", message, pos, src)
        }
    }

    /** True if the cursor sits at `NOTE` (whole word, case-insensitive) followed by optional spaces then `:`. */
    private fun matchesNoteBlockStart(): Boolean {
        if (!matchesWordCIAt(0, "NOTE")) return false
        var i = 4
        while (peek(i) == ' ') i++
        return peek(i) == ':'
    }

    /** Whole-word case-insensitive match of [word] at [offset], with a non-identifier boundary after it. */
    private fun matchesWordCIAt(offset: Int, word: String): Boolean {
        for (j in word.indices) {
            val c = peek(offset + j)
            if (c == ' ' || c.uppercaseChar() != word[j]) return false
        }
        val after = peek(offset + word.length)
        return !(after.isLetterOrDigit() || after == '_' || after == '-')
    }

    /**
     * `NOTE: … END-NOTE` block comment — free text, lexer-skipped (never tokenized).
     * Spans from the opening `NOTE:` line through the line whose first word is `END-NOTE`.
     */
    private fun skipNoteBlock() {
        val startPos = currentPos()
        while (!isAtEnd() && peek() != '\n') advance()   // rest of the opening "NOTE:" line
        while (!isAtEnd()) {
            advance(); line++; column = 1; lineStartOffset = pos   // step onto next line
            var i = 0
            while (peek(i) == ' ' || peek(i) == '\t') i++
            if (matchesWordCIAt(i, "END-NOTE")) {
                while (!isAtEnd() && peek() != '\n') advance()      // consume to end of END-NOTE line
                return
            }
            while (!isAtEnd() && peek() != '\n') advance()          // skip this line's content
        }
        addError("Unterminated NOTE block (missing END-NOTE)", startPos)
    }

    // -------------------------------------------------------------------------
    // Utilities
    // -------------------------------------------------------------------------

    private fun emit(type: TokenType, value: String) = tokens.add(Token(type, value, currentPos()))
    private fun peek(offset: Int = 0) = if (pos + offset < source.length) source[pos + offset] else '\u0000'
    private fun advance(): Char { val c = source[pos++]; column++; return c }
    private fun isAtEnd(offset: Int = 0) = pos + offset >= source.length
    private fun currentPos() = SourcePosition(fileName, line, column)
    private fun currentSourceLine(): String {
        val end = source.indexOf('\n', lineStartOffset).let { if (it < 0) source.length else it }
        return source.substring(lineStartOffset, minOf(end, source.length))
    }
    private fun addError(msg: String, pos: SourcePosition) =
        errors.add(LexException(msg, pos, currentSourceLine()))

    // -------------------------------------------------------------------------
    // Keyword table
    // -------------------------------------------------------------------------

    companion object {
        val KEYWORDS: Map<String, TokenType> = mapOf(
            "ACCEPT" to TokenType.ACCEPT,
            "ADD" to TokenType.ADD, "ALL" to TokenType.ALL, "AND" to TokenType.AND,
            "AS" to TokenType.AS, "AUTHOR" to TokenType.AUTHOR,
            "BOOLEAN" to TokenType.BOOLEAN,
            "CALL" to TokenType.CALL, "CLOSE" to TokenType.CLOSE,
            "COMBINE" to TokenType.COMBINE, "COMPUTE" to TokenType.COMPUTE,
            "CONDITION" to TokenType.CONDITION,
            "DATA" to TokenType.DATA, "DATE" to TokenType.DATE,
            "DATETIME" to TokenType.DATETIME, "DECIMAL" to TokenType.DECIMAL,
            "DEFINE" to TokenType.DEFINE, "DEPRECATED" to TokenType.DEPRECATED,
            "DISPLAY" to TokenType.DISPLAY,
            "DIVIDE" to TokenType.DIVIDE, "DO" to TokenType.DO,
            "EACH" to TokenType.EACH, "ELSE" to TokenType.ELSE,
            "END-FOR" to TokenType.END_FOR, "END-IF" to TokenType.END_IF,
            "END-PERFORM" to TokenType.END_PERFORM, "END-PROCEDURE" to TokenType.END_PROCEDURE,
            "END-PROGRAM" to TokenType.END_PROGRAM, "END-RECORD" to TokenType.END_RECORD,
            "END-TRY" to TokenType.END_TRY, "END-WHILE" to TokenType.END_WHILE,
            "ENSURE" to TokenType.ENSURE, "EXCEPT" to TokenType.EXCEPT,
            "EXTEND" to TokenType.EXTEND,
            "FALSE" to TokenType.FALSE, "FILES" to TokenType.FILES,
            "FILTER" to TokenType.FILTER, "FOR" to TokenType.FOR, "FROM" to TokenType.FROM,
            "GIVING" to TokenType.GIVING,
            "IF" to TokenType.IF, "IMPORT" to TokenType.IMPORT, "IN" to TokenType.IN,
            "INPUT" to TokenType.INPUT, "INTEGER" to TokenType.INTEGER,
            "INTO" to TokenType.INTO, "IS" to TokenType.IS,
            "KEY" to TokenType.KEY, "PUT" to TokenType.PUT, "GET" to TokenType.GET,
            "LABEL" to TokenType.LABEL, "LET" to TokenType.LET,
            "LIST" to TokenType.LIST,
            "MAP" to TokenType.MAP, "MONEY" to TokenType.MONEY,
            "MOVE" to TokenType.MOVE, "MULTIPLY" to TokenType.MULTIPLY,
            "NEW" to TokenType.NEW,
            "NOSQL" to TokenType.NOSQL,
            "NOT" to TokenType.NOT,
            "OF" to TokenType.OF, "ON" to TokenType.ON, "OPEN" to TokenType.OPEN,
            "OR" to TokenType.OR, "OUTPUT" to TokenType.OUTPUT,
            "PERFORM" to TokenType.PERFORM, "PROCEDURE" to TokenType.PROCEDURE,
            "PROGRAM" to TokenType.PROGRAM,
            "RAISE" to TokenType.RAISE, "READ" to TokenType.READ, "RECORD" to TokenType.RECORD,
            "REPEAT" to TokenType.REPEAT, "RETURN" to TokenType.RETURN,
            "RETURNING" to TokenType.RETURNING, "ROUND" to TokenType.ROUND, "RUN" to TokenType.RUN,
            "SET" to TokenType.SET, "SLEEP" to TokenType.SLEEP, "SMALLINT" to TokenType.SMALLINT,
            "SORT" to TokenType.SORT, "STOP" to TokenType.STOP,
            "SUBTRACT" to TokenType.SUBTRACT, "SUM" to TokenType.SUM,
            "TAKE" to TokenType.TAKE, "TEXT" to TokenType.TEXT,
            "TIME" to TokenType.TIME, "TIMES" to TokenType.TIMES,
            "TO" to TokenType.TO, "TRANSFORM" to TokenType.TRANSFORM,
            "TRUE" to TokenType.TRUE, "TRY" to TokenType.TRY,
            "USING" to TokenType.USING, "VERSION" to TokenType.VERSION,
            "MILLISECONDS" to TokenType.MILLISECONDS,
            "SECONDS" to TokenType.SECONDS,
            "MINUTES" to TokenType.MINUTES,
            "UUID" to TokenType.UUID,
            "TEST" to TokenType.TEST, "END-TEST" to TokenType.END_TEST,
            "ASSERT" to TokenType.ASSERT,
            "MODULE" to TokenType.MODULE, "EXPORT" to TokenType.EXPORT,
            "END-MODULE" to TokenType.END_MODULE, "MOCK" to TokenType.MOCK,
            "WHEN" to TokenType.WHERE, "WHERE" to TokenType.WHERE, "WHILE" to TokenType.WHILE,
            "WITH" to TokenType.WITH, "WRITE" to TokenType.WRITE,
            // Concurrency & Observability keywords
            "LOG" to TokenType.LOG,
            "TRACE" to TokenType.TRACE, "DEBUG" to TokenType.DEBUG,
            "INFO" to TokenType.INFO, "WARN" to TokenType.WARN, "ERROR" to TokenType.ERROR,
            "CONCURRENT" to TokenType.CONCURRENT, "PARALLEL" to TokenType.PARALLEL,
            "WAIT" to TokenType.WAIT, "ASYNC" to TokenType.ASYNC, "AWAIT" to TokenType.AWAIT,
            "FUTURE" to TokenType.FUTURE,
            "END-CONCURRENT" to TokenType.END_CONCURRENT,
            // Security, Validation & Configuration keywords
            "CONFIG" to TokenType.CONFIG,
            "VALIDATE" to TokenType.VALIDATE, "MUST" to TokenType.MUST,
            "MATCH" to TokenType.MATCH, "OTHERWISE" to TokenType.OTHERWISE,
            "PARSE" to TokenType.PARSE, "PRECISION" to TokenType.PRECISION,
            "END-PRECISION" to TokenType.END_PRECISION,
            "END-WITH" to TokenType.END_PRECISION,   // spec §12.4 terminator; alias of END-PRECISION
            "VARIANT" to TokenType.VARIANT, "SENSITIVE" to TokenType.SENSITIVE,
            "END-CONFIG" to TokenType.END_CONFIG, "END-VALIDATE" to TokenType.END_VALIDATE,
            "END-MATCH" to TokenType.END_MATCH, "END-VARIANT" to TokenType.END_VARIANT,
            // HTTP / JDBC / REST server
            "AT" to TokenType.AT, "BODY" to TokenType.BODY,
            "ENDPOINT" to TokenType.ENDPOINT, "END-SERVER" to TokenType.END_SERVER,
            "HEADERS" to TokenType.HEADERS, "PARAMS" to TokenType.PARAMS,
            "PORT" to TokenType.PORT,
            "RESPOND" to TokenType.RESPOND, "SERVER" to TokenType.SERVER,
            "STATUS" to TokenType.STATUS, "TIMEOUT" to TokenType.TIMEOUT,
            // NoSQL document store + cache key-value
            "CACHE" to TokenType.CACHE, "COUNT" to TokenType.COUNT,
            "DATABASE" to TokenType.DATABASE, "DELETE" to TokenType.DELETE,
            "EXPIRES" to TokenType.EXPIRES, "FIND" to TokenType.FIND,
            "NOSQL" to TokenType.NOSQL, "SAVE" to TokenType.SAVE,
        )
    }
}
