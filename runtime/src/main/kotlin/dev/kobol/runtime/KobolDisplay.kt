package dev.kobol.runtime

/**
 * Ergonomic display helpers (ER-9).
 * Called by transpiled Kobol programs for DISPLAY TABLE / DISPLAY LABEL / DISPLAY FORMAT.
 */
object KobolDisplay {

    /**
     * Renders a list of objects as a simple ASCII table using their toString()
     * and field names derived by reflection.
     */
    fun table(items: List<*>): String {
        if (items.isEmpty()) return "(empty table)"
        val rows = items.map { it.toString() }
        val width = rows.maxOf { it.length }.coerceAtLeast(10)
        val border = "+" + "-".repeat(width + 2) + "+"
        return buildString {
            appendLine(border)
            for (row in rows) appendLine("| ${row.padEnd(width)} |")
            append(border)
        }
    }

    /**
     * DISPLAY LABEL key value — prints a right-aligned label with the value.
     * Example: `  invoice-total : 1 234.56`
     */
    fun label(key: String, value: Any?, labelWidth: Int = 22): String {
        val keyStr = key.padStart(labelWidth)
        return "$keyStr : $value"
    }

    /**
     * DISPLAY FORMAT pattern value — delegates to Java's String.format.
     * Pattern uses printf-style directives.
     */
    fun format(pattern: String, value: Any?): String =
        try { String.format(pattern, value) }
        catch (e: Exception) { "$pattern($value)" }

    /**
     * DISPLAY STYLED text [BOLD] [UNDERLINE] [COLOR color] — wraps text in ANSI escape codes.
     * Falls back to plain text when no modifiers are specified.
     * Supported colors: BLACK, RED, GREEN, YELLOW, BLUE, MAGENTA, CYAN, WHITE.
     */
    @JvmStatic fun styled(text: String, bold: Boolean, underline: Boolean, color: String): String {
        val codes = mutableListOf<String>()
        if (bold)      codes.add("1")
        if (underline) codes.add("4")
        when (color.uppercase()) {
            "BLACK"   -> codes.add("30")
            "RED"     -> codes.add("31")
            "GREEN"   -> codes.add("32")
            "YELLOW"  -> codes.add("33")
            "BLUE"    -> codes.add("34")
            "MAGENTA" -> codes.add("35")
            "CYAN"    -> codes.add("36")
            "WHITE"   -> codes.add("37")
        }
        if (codes.isEmpty()) return text
        return "\u001B[${codes.joinToString(";")}m$text\u001B[0m"
    }
}
