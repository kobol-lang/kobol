package dev.kobol.lexer

/**
 * Every meaningful token type in the Kobol language.
 * Keywords are case-insensitive; they are normalized to uppercase during scanning.
 */
enum class TokenType {
    // ---- Keywords ----
    ADD, ALL, AND, AS, ASYNC, AUTHOR,
    AWAIT, BOOLEAN,
    CALL, CLOSE, KOBOL, COMBINE, COMPUTE, CONCURRENT, CONDITION, CONFIG,
    DATA, DATE, DATETIME, DECIMAL, DEFINE, DEPRECATED, DISPLAY, DIVIDE, DO,
    EACH, ELSE, END_CONCURRENT, END_CONFIG, END_FOR, END_IF, END_MATCH,
    END_PERFORM, END_PRECISION, END_PROCEDURE, END_PROGRAM, END_RECORD, END_TRY,
    END_VALIDATE, END_VARIANT, END_WHILE, ENSURE, EXCEPT, EXTEND,
    FALSE, FILES, FILTER, FOR, FROM, FUTURE,
    GIVING,
    IF, IMPORT, IN, INPUT, INTEGER, INTO, IS,
    KEY,
    LABEL, LET, LIST, LOG,
    MAP, MATCH, MONEY, MOVE, MULTIPLY, MUST,
    NOT,
    OF, ON, OPEN, OR, OTHERWISE, OUTPUT,
    PARALLEL, PARSE, PERFORM, PRECISION, PROCEDURE, PROGRAM,
    RAISE, READ, RECORD, REPEAT, RETURN, RETURNING, ROUND,
    RUN,
    SENSITIVE, SET, SLEEP, SMALLINT, SORT, STOP, SUBTRACT, SUM,
    TAKE, TEST, TEXT, TIME, TIMES, TO, TRANSFORM, TRUE, TRY,
    USING, UUID,
    MILLISECONDS, SECONDS, MINUTES,
    ASSERT, END_TEST,
    EXPORT, MODULE, END_MODULE, MOCK,
    VALIDATE, VARIANT, VERSION,
    WAIT, WHERE, WHILE, WITH, WRITE,
    // HTTP client + JDBC + REST server (Groups 11-12)
    AT, BODY, ENDPOINT, END_SERVER, HEADERS, PARAMS, PORT,
    RESPOND, SERVER, STATUS, TIMEOUT,

    // NoSQL document store + cache key-value (Groups 13-14)
    CACHE, COUNT, DATABASE, DELETE, EXPIRES, FIND, NOSQL, SAVE,

    // ---- Log levels ----
    TRACE, DEBUG, INFO, WARN, ERROR,

    // ---- Literals ----
    INTEGER_LIT,       // 42
    DECIMAL_LIT,       // 3.14
    STRING_LIT,        // "hello world"
    BOOLEAN_LIT,       // TRUE or FALSE  (also matched as keyword, resolved in lexer)

    // ---- Identifiers ----
    IDENTIFIER,        // customer-name, BALANCE, etc.

    // ---- Punctuation ----
    COLON,             // :
    DOT,               // .
    DOTDOT,            // .. (range operator in MATCH patterns)
    COMMA,             // ,
    LPAREN,            // (
    RPAREN,            // )
    LBRACE,            // {
    RBRACE,            // }
    LBRACKET,          // [
    RBRACKET,          // ]
    PIPE,              // |  (variant case separator)

    // ---- Arithmetic operators ----
    PLUS,              // +
    MINUS,             // -
    STAR,              // *
    SLASH,             // /
    POWER,             // **

    // ---- Comparison operators ----
    EQ,                // =
    NEQ,               // <>
    LT,                // <
    GT,                // >
    LEQ,               // <=
    GEQ,               // >=

    // ---- String interpolation (synthetic) ----
    INTERP_START,      // { inside a string literal
    INTERP_END,        // } closing interpolation

    // ---- Structural ----
    NEWLINE,
    INDENT,
    DEDENT,
    EOF,
}
