package dev.kobol.stdlib

import java.math.MathContext
import java.math.RoundingMode

/**
 * Thread-local stack of [MathContext] values for WITH PRECISION blocks.
 *
 * Each `WITH PRECISION` block pushes its context on entry and pops it on exit
 * (including on exception), restoring the enclosing context — matching COBOL's
 * lexically-scoped INTERMEDIATE ROUNDING semantics.
 *
 * When no WITH PRECISION block is active, [current] returns null and callers
 * fall back to their default behaviour (HALF_EVEN for divide, exact for others).
 */
object KobolMathContext {

    private val stack: ThreadLocal<ArrayDeque<MathContext>> =
        ThreadLocal.withInitial { ArrayDeque() }

    @JvmStatic fun push(name: String) {
        stack.get().addLast(nameToContext(name))
    }

    /**
     * Push a custom [MathContext] built from a named precision profile and an
     * explicit rounding mode.  Accepted modes (case-insensitive, hyphens allowed):
     * HALF-UP, HALF-DOWN, HALF-EVEN, UP, DOWN, CEILING, FLOOR, UNNECESSARY.
     */
    @JvmStatic fun pushWithRounding(name: String, mode: String) {
        stack.get().addLast(nameToContextWithRounding(name, mode))
    }

    @JvmStatic fun pop() {
        stack.get().removeLastOrNull()
    }

    @JvmStatic fun current(): MathContext? = stack.get().lastOrNull()

    /** Convenience: push, run [block], pop even on exception. */
    inline fun <T> withContext(name: String, block: () -> T): T {
        push(name)
        try {
            return block()
        } finally {
            pop()
        }
    }

    private fun nameToContext(name: String): MathContext {
        // Spec §12.4: an integer-literal precision (e.g. WITH PRECISION 34) maps to
        // MathContext(n) with the default HALF_EVEN rounding; 0 means UNLIMITED.
        name.toIntOrNull()?.let { return if (it == 0) MathContext.UNLIMITED else MathContext(it) }
        return when (name.uppercase()) {
            "DECIMAL32"  -> MathContext.DECIMAL32
            "DECIMAL64"  -> MathContext.DECIMAL64
            "DECIMAL128" -> MathContext.DECIMAL128
            "UNLIMITED"  -> MathContext.UNLIMITED
            else         -> MathContext.DECIMAL128   // safe default for unknown names
        }
    }

    private fun nameToContextWithRounding(name: String, mode: String): MathContext {
        val rm = when (mode.trim().uppercase().replace('_', '-')) {
            "HALF-UP"     -> RoundingMode.HALF_UP
            "HALF-DOWN"   -> RoundingMode.HALF_DOWN
            "HALF-EVEN"   -> RoundingMode.HALF_EVEN
            "UP"          -> RoundingMode.UP
            "DOWN"        -> RoundingMode.DOWN
            "CEILING"     -> RoundingMode.CEILING
            "FLOOR"       -> RoundingMode.FLOOR
            "UNNECESSARY" -> RoundingMode.UNNECESSARY
            else          -> throw IllegalArgumentException("Unknown rounding mode: $mode")
        }
        val precision = name.toIntOrNull() ?: when (name.uppercase()) {
            "DECIMAL32"  -> MathContext.DECIMAL32.precision
            "DECIMAL64"  -> MathContext.DECIMAL64.precision
            "DECIMAL128" -> MathContext.DECIMAL128.precision
            "UNLIMITED"  -> 0
            else         -> MathContext.DECIMAL128.precision
        }
        return MathContext(precision, rm)
    }
}
