package dev.kobol.lexer

/**
 * Source location for diagnostics. Line and column are 1-based.
 */
data class SourcePosition(
    val file: String,
    val line: Int,
    val column: Int,
) {
    override fun toString() = "$file:$line:$column"
}

/**
 * A single lexical token produced by the [Lexer].
 *
 * @param type     The token's classification.
 * @param value    Normalized to UPPERCASE — used for keyword matching and symbol lookup.
 * @param rawValue Original source text with original case — used for Java class/method names
 *                 in IMPORT and CALL statements to preserve JVM binary names.
 * @param pos      Position in the source file.
 */
data class Token(
    val type: TokenType,
    val value: String,
    val pos: SourcePosition,
    val rawValue: String = value,   // defaults to value; Lexer sets original case for identifiers
) {
    override fun toString() = "Token($type, \"$value\", $pos)"
}
