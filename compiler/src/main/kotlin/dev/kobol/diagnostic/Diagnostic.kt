package dev.kobol.diagnostic

import dev.kobol.lexer.SourcePosition

/** Severity of a compiler diagnostic. */
enum class Severity { ERROR, WARNING, INFO }

/**
 * A single compiler diagnostic (ER-1 compliant).
 * Rendered with source-line pointer and optional "did you mean?" suggestion.
 */
data class Diagnostic(
    val severity:   Severity,
    val code:       String,        // e.g. "E001"
    val message:    String,
    val pos:        SourcePosition,
    val sourceLine: String  = "",
    val suggestion: String? = null,
) {
    /**
     * Render to a human-readable string matching the format:
     *
     * ```
     * error[E001]: Undefined variable 'inovice-total'
     *   --> billing.kbl:42:10
     *    |
     * 42 |   ADD tax TO inovice-total
     *    |              ^^^^^^^^^^^^^ not found in this scope
     *    |
     *    = did you mean: invoice-total (declared at line 8)?
     * ```
     */
    fun render(): String {
        val sb = StringBuilder()
        val label = severity.name.lowercase()
        sb.appendLine("$label[$code]: $message")
        sb.appendLine("  --> ${pos.file}:${pos.line}:${pos.column}")

        if (sourceLine.isNotEmpty()) {
            val lineNumStr = pos.line.toString()
            val pad        = " ".repeat(lineNumStr.length)
            val pointer    = " ".repeat((pos.column - 1).coerceAtLeast(0)) + "^".repeat(1)

            sb.appendLine("$pad |")
            sb.appendLine("$lineNumStr | $sourceLine")
            sb.appendLine("$pad | $pointer")
            sb.appendLine("$pad |")
        }

        if (suggestion != null) {
            sb.append("   = $suggestion")
        }

        return sb.toString().trimEnd()
    }

    override fun toString() = render()
}

/** Collects diagnostics during compilation; throws on first error when sealed. */
class DiagnosticBag {
    private val items = mutableListOf<Diagnostic>()
    private var errorCount = 0   // O(1) error presence check

    val errors:   List<Diagnostic> get() = items.filter { it.severity == Severity.ERROR }
    val warnings: List<Diagnostic> get() = items.filter { it.severity == Severity.WARNING }
    val infos:    List<Diagnostic> get() = items.filter { it.severity == Severity.INFO }
    /** O(1) — backed by a counter, never re-scans the list. */
    val hasErrors: Boolean get() = errorCount > 0

    fun error(code: String, message: String, pos: SourcePosition,
              sourceLine: String = "", suggestion: String? = null) {
        items += Diagnostic(Severity.ERROR, code, message, pos, sourceLine, suggestion)
        errorCount++
    }

    fun warning(code: String, message: String, pos: SourcePosition,
                sourceLine: String = "", suggestion: String? = null) {
        items += Diagnostic(Severity.WARNING, code, message, pos, sourceLine, suggestion)
    }

    fun info(code: String, message: String, pos: SourcePosition,
             sourceLine: String = "", suggestion: String? = null) {
        items += Diagnostic(Severity.INFO, code, message, pos, sourceLine, suggestion)
    }

    fun printAll() = items.forEach { println(it.render()); println() }

    fun throwIfErrors() {
        if (hasErrors) throw CompilationException(errors)
    }
}

class CompilationException(val diagnostics: List<Diagnostic>) :
    RuntimeException("Compilation failed with ${diagnostics.size} error(s)") {
    override fun toString() = diagnostics.joinToString("\n\n") { it.render() }
}

/**
 * Levenshtein-distance "did you mean?" helper.
 *
 * Single-pass: computes distance for each candidate once and returns the
 * best match (distance 1 or 2). Breaks early on a perfect edit-distance-1
 * match since no candidate can be closer.
 *
 * Complexity: O(candidates × |name| × |candidate|) worst-case, but typically
 * exits at the first distance-1 hit.
 */
fun didYouMean(name: String, candidates: Collection<String>): String? {
    val nameLower = name.lowercase()
    var bestName: String? = null
    var bestDist = 3            // anything ≥ 3 is ignored
    for (candidate in candidates) {
        val d = levenshtein(nameLower, candidate.lowercase())
        if (d < bestDist) {
            bestDist = d
            bestName = candidate
            if (d == 1) break   // can't improve further — exit early
        }
    }
    return bestName?.let { "did you mean: $it?" }
}

private fun levenshtein(a: String, b: String): Int {
    val dp = Array(a.length + 1) { IntArray(b.length + 1) }
    for (i in 0..a.length) dp[i][0] = i
    for (j in 0..b.length) dp[0][j] = j
    for (i in 1..a.length) for (j in 1..b.length) {
        dp[i][j] = if (a[i - 1] == b[j - 1]) dp[i - 1][j - 1]
                   else minOf(dp[i - 1][j], dp[i][j - 1], dp[i - 1][j - 1]) + 1
    }
    return dp[a.length][b.length]
}
