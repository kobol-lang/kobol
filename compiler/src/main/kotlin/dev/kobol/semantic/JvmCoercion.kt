package dev.kobol.semantic

import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type

/**
 * JVM value coercion for classpath-aware interop (E2 increment-2: overload coercion ranking
 * **F13** + real-return propagation **F14**).
 *
 * ONE source of truth for two questions that MUST agree:
 *  - [cost]: can a value of descriptor `from` be coerced to `to`, and how expensive is it?
 *    Used to RANK overloads — the cheapest total wins (F13).
 *  - [emit]: produce the bytecode that performs `from → to` on the operand stack.
 *    Used at the call site for each argument (F13) and to convert a method's real return
 *    value into the declared GIVING target (F14).
 *
 * Keeping cost and emit in the same object means the ranker can NEVER pick a coercion the
 * emitter can't produce — every `from → to` with a non-null [cost] is handled by [emit] (P1).
 *
 * Descriptors are JVM field descriptors: primitives `I/J/F/D/Z/B/S/C`, references `L…;` / `[…`.
 * Supported (everything else → `null` = unsupported, so the candidate is excluded from ranking
 * and the caller falls back to its guess — no regression):
 *  - identity
 *  - numeric primitive widening (JLS 5.1.2 subset): `B/S/C/I → J/F/D`, `J → F/D`, `F → D`
 *  - numeric primitive → `BigDecimal` (DECIMAL/MONEY capture)
 *  - primitive → its exact wrapper, or primitive → `java/lang/Object` (boxing)
 *  - reference → `java/lang/Object` (no-op); reference → reference (`CHECKCAST`)
 *  - guarded integral narrowing `J → I` (F22): Kobol `INTEGER` is a 64-bit `long`, but many
 *    Java APIs take an `int` (indices/counts). Lowered via `Math.toIntExact` — it throws a clear
 *    `ArithmeticException` on a value outside `int` range, so narrowing is NEVER silent (P4).
 *    Ranked far above any widening/boxing ([NARROW_COST]) so a real `long`/wider overload always
 *    wins when one exists — narrowing is the last resort. Fractional narrowing (`D/F → I/J`) stays
 *    unsupported: a range check can't recover a lost fraction (`0.5 → 0`).
 */
object JvmCoercion {

    private const val BIG_DECIMAL = "Ljava/math/BigDecimal;"
    private const val OBJECT = "Ljava/lang/Object;"

    // Narrowing is lossy in range, so it must lose to every non-narrowing coercion (all ≤ 9).
    private const val NARROW_COST = 50

    // B/S/C/I all occupy a single int stack slot; J/F/D are distinct widths. Rank = widening order.
    private val numericRank = mapOf("B" to 0, "S" to 1, "C" to 1, "I" to 2, "J" to 3, "F" to 4, "D" to 5)
    private val intLike = setOf("B", "S", "C", "I")

    private val wrapperOf = mapOf(
        "I" to "Ljava/lang/Integer;", "J" to "Ljava/lang/Long;",
        "D" to "Ljava/lang/Double;", "F" to "Ljava/lang/Float;",
        "Z" to "Ljava/lang/Boolean;", "B" to "Ljava/lang/Byte;",
        "S" to "Ljava/lang/Short;", "C" to "Ljava/lang/Character;",
    )

    private fun isRef(d: String) = d.startsWith("L") || d.startsWith("[")

    /** Descriptor (`Lpkg/Cls;` / `[…`) → ASM internal name (`pkg/Cls` / `[…`). */
    private fun internalName(d: String): String =
        if (d.startsWith("L")) d.substring(1, d.length - 1) else Type.getType(d).internalName

    /**
     * Coercion cost (lower = closer match), or null if unsupported.
     *
     * @param isSubtype hierarchy oracle for the reference→reference case (**F25**). When supplied
     *   (overload-argument ranking), a `from → to` between unrelated reference types is REJECTED —
     *   Java overload resolution never inserts an argument cross-cast, so admitting it would let the
     *   ranker pick a method that then throws `ClassCastException` at run. Only a genuine upcast
     *   (`from <: to`) or `→ Object` is viable. When null (e.g. the explicit-`GIVING` return
     *   coercion, where the user instructed the cast) the legacy permissive `CHECKCAST` is kept.
     */
    fun cost(from: String, to: String, isSubtype: ((String, String) -> Boolean)? = null): Int? {
        if (from == to) return 0
        val rf = numericRank[from]
        val rt = numericRank[to]
        if (rf != null && rt != null) {
            if (rt > rf) return rt - rf                              // widening
            return if (from == "J" && to == "I") NARROW_COST else null // guarded J→I only (F22)
        }
        if (rf != null && to == BIG_DECIMAL) return 6
        if (rf != null && to == wrapperOf[from]) return 7                    // exact boxing
        if (rf != null && to == OBJECT) return 8                            // box → Object
        if (from == "Z" && (to == OBJECT || to == wrapperOf["Z"])) return 8
        if (isRef(from) && to == OBJECT) return 4                           // always-safe upcast
        if (isRef(from) && isRef(to)) {                                     // CHECKCAST
            if (isSubtype == null) return 9                                 // permissive (return coercion)
            return if (isSubtype(internalName(from), internalName(to))) 9 else null  // arg: upcast only (F25)
        }
        return null
    }

    /** Emit the bytecode that coerces the top-of-stack value from [from] to [to]. */
    fun emit(mv: MethodVisitor, from: String, to: String) {
        if (from == to) return
        val rf = numericRank[from]
        val rt = numericRank[to]
        when {
            from == "J" && to == "I" -> // guarded narrowing (F22): throws on out-of-int-range
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Math", "toIntExact", "(J)I", false)
            rf != null && rt != null -> emitWiden(mv, from, to)
            rf != null && to == BIG_DECIMAL -> {
                if (from == "F" || from == "D") {
                    if (from == "F") mv.visitInsn(Opcodes.F2D)
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/math/BigDecimal", "valueOf", "(D)Ljava/math/BigDecimal;", false)
                } else {
                    if (from != "J") mv.visitInsn(Opcodes.I2L) // B/S/C/I → long
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/math/BigDecimal", "valueOf", "(J)Ljava/math/BigDecimal;", false)
                }
            }
            from == "Z" && (to == OBJECT || to == wrapperOf["Z"]) ->
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false)
            rf != null && (to == OBJECT || to == wrapperOf[from]) -> {
                val w = wrapperOf[from]!!
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, w.substring(1, w.length - 1), "valueOf", "($from)$w", false)
            }
            isRef(from) && to == OBJECT -> { /* any reference is already Object-assignable */ }
            isRef(from) && isRef(to) -> {
                val internal = if (to.startsWith("L")) to.substring(1, to.length - 1) else Type.getType(to).internalName
                mv.visitTypeInsn(Opcodes.CHECKCAST, internal)
            }
            else -> error("unsupported JVM coercion $from -> $to")
        }
    }

    private fun emitWiden(mv: MethodVisitor, from: String, to: String) {
        val f = if (from in setOf("B", "S", "C")) "I" else from
        if (to in intLike) return // widening within the int slot (e.g. B→I, C→I) is a no-op
        val op = when (f to to) {
            "I" to "J" -> Opcodes.I2L; "I" to "F" -> Opcodes.I2F; "I" to "D" -> Opcodes.I2D
            "J" to "F" -> Opcodes.L2F; "J" to "D" -> Opcodes.L2D
            "F" to "D" -> Opcodes.F2D
            else -> error("unsupported widening $from -> $to")
        }
        mv.visitInsn(op)
    }
}
