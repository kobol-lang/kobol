package dev.kobol.parser

import dev.kobol.lexer.SourcePosition

class ParseException(
    message: String,
    val pos: SourcePosition,
    val sourceLine: String = "",
) : Exception("${pos}: $message")
