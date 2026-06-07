package dev.kobol.semantic

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import java.io.File
import java.net.URLClassLoader

/** One method signature read from a real `.class` on the compile classpath. */
data class JvmMethodSig(
    val name: String,
    /** JVM method descriptor, e.g. `()Ljava/lang/String;`. */
    val descriptor: String,
    val isStatic: Boolean,
    val isPublic: Boolean,
    /** True if declared varargs (`ACC_VARARGS`); the last parameter is the variadic array. */
    val isVarargs: Boolean = false,
)

/**
 * Classpath-aware compile-time symbol resolution (challenge enabler **E2**).
 *
 * Reads imported 3rd-party / JDK classes off the compile classpath via ASM
 * [ClassReader] — raw bytes only, no class loading and no static-init side effects —
 * so an interop `CALL` can link to a method's REAL JVM descriptor instead of a
 * Kobol-side guess (`emitCall` previously built the descriptor from inferred Kobol
 * types, e.g. a no-`GIVING` call defaulted the return to `Ljava/lang/Object;`, which
 * names a non-existent method → `NoSuchMethodError` at run while type-checking clean).
 *
 * Reading bytes (not `java.lang.reflect`) is deliberate and shared with the path
 * **F15** will use to read Kotlin `@Metadata` — no throwaway fork (P1).
 *
 * **Increment-1 scope:** locate the class, list its public methods, resolve a call by
 * name + arity. Overload *coercion* ranking (**F13**), real-return propagation into the
 * type system (**F14**) and Kotlin `@Metadata` (**F15**) build on this infra later.
 *
 * Each class is read at most once (cached) — O(classes), not per-call (P5).
 */
class ClasspathSymbolResolver(classpath: List<String>) {

    // Used only for getResourceAsStream("<owner>.class"); platform parent so JDK module
    // classes (java.lang.*) and user/stdlib jars both resolve their bytes.
    private val loader = URLClassLoader(
        classpath.mapNotNull { runCatching { File(it).toURI().toURL() }.getOrNull() }.toTypedArray(),
        ClassLoader.getPlatformClassLoader(),
    )

    // owner internal name (slashed) → its methods, or null when the class is unreadable.
    private val cache = HashMap<String, List<JvmMethodSig>?>()

    /** All methods of [ownerInternalName] (slashed, e.g. `java/lang/System`), or null if unreadable. */
    fun methodsOf(ownerInternalName: String): List<JvmMethodSig>? =
        cache.getOrPut(ownerInternalName) { readMethods(ownerInternalName) }

    private fun readMethods(owner: String): List<JvmMethodSig>? {
        val bytes = (loader.getResourceAsStream("$owner.class")
            ?: ClassLoader.getSystemResourceAsStream("$owner.class"))
            ?.use { it.readBytes() } ?: return null
        val sigs = ArrayList<JvmMethodSig>()
        ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visitMethod(
                access: Int, name: String, descriptor: String,
                signature: String?, exceptions: Array<out String>?,
            ): MethodVisitor? {
                // Skip compiler-synthesized bridge/synthetic methods. A covariant-return override
                // (e.g. StringBuilder.append(String) returning StringBuilder) leaves a bridge
                // append(String) returning the SUPERtype — same params, different return. Counting
                // it as a separate overload makes every fluent/builder call falsely Ambiguous.
                if (access and (Opcodes.ACC_BRIDGE or Opcodes.ACC_SYNTHETIC) != 0) return null
                sigs.add(
                    JvmMethodSig(
                        name = name,
                        descriptor = descriptor,
                        isStatic = access and Opcodes.ACC_STATIC != 0,
                        isPublic = access and Opcodes.ACC_PUBLIC != 0,
                        isVarargs = access and Opcodes.ACC_VARARGS != 0,
                    )
                )
                return null
            }
        }, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        return sigs
    }

    /**
     * Resolve [methodName] on [ownerInternalName] for a call of [arity] arguments.
     *
     * - [CallResolution.ClassNotFound] — class unreadable; caller falls back to its guess.
     * - [CallResolution.NoSuchMethod]  — class read, but no public method by that name/arity.
     * - [CallResolution.Ambiguous]     — >1 public method at this arity; overload coercion
     *   ranking is **F13**, so the caller falls back rather than pick blindly.
     * - [CallResolution.Resolved]      — exactly one public method at this arity.
     */
    fun resolveCall(ownerInternalName: String, methodName: String, arity: Int): CallResolution {
        val methods = methodsOf(ownerInternalName) ?: return CallResolution.ClassNotFound
        val named = methods.filter { it.name == methodName && it.isPublic }
        if (named.isEmpty()) return CallResolution.NoSuchMethod
        val byArity = named.filter { Type.getArgumentTypes(it.descriptor).size == arity }
        return when (byArity.size) {
            0 -> CallResolution.NoSuchMethod
            1 -> CallResolution.Resolved(byArity[0])
            else -> CallResolution.Ambiguous(byArity)
        }
    }

    /**
     * Resolve [methodName] on [ownerInternalName] for a call whose arguments are already lowered
     * to the JVM descriptors [argDescs] — overload coercion ranking (**F13**).
     *
     * Unlike [resolveCall] (which buckets by arity only and reports any 2+ as [CallResolution.Ambiguous]),
     * this scores every same-arity public overload by the total [JvmCoercion.cost] of coercing each
     * emitted arg to that overload's parameter type, and picks the cheapest:
     *  - candidate with ANY un-coercible parameter is excluded (can't be called with these args);
     *  - exactly one cheapest → [CallResolution.Resolved] (caller emits the per-arg coercions);
     *  - a tie at the minimum cost → [CallResolution.Ambiguous] (caller falls back rather than guess);
     *  - no candidate callable → [CallResolution.NoSuchMethod]; class unreadable → [CallResolution.ClassNotFound].
     */
    fun resolveByArgs(ownerInternalName: String, methodName: String, argDescs: List<String>): CallResolution {
        val methods = methodsOf(ownerInternalName) ?: return CallResolution.ClassNotFound
        val named = methods.filter { it.name == methodName && it.isPublic }
        if (named.isEmpty()) return CallResolution.NoSuchMethod
        val arity = argDescs.size

        // Each viable candidate carries an emit plan: varargsFixed == null → a plain fixed-arity
        // call; varargsFixed == k → pack args[k until arity] into the trailing array parameter (F24).
        data class Scored(val sig: JvmMethodSig, val cost: Int, val varargsFixed: Int?)
        val scored = ArrayList<Scored>()

        // Phase A — fixed arity: every same-arity overload, scored by per-arg coercion cost.
        for (sig in named) {
            val params = Type.getArgumentTypes(sig.descriptor)
            if (params.size != arity) continue
            var total = 0
            var ok = true
            for (i in 0 until arity) {
                val c = JvmCoercion.cost(argDescs[i], params[i].descriptor)
                if (c == null) { ok = false; break }
                total += c
            }
            if (ok) scored.add(Scored(sig, total, null))
        }

        // Phase B — varargs: a method m(fixed…, T[]) accepts argCount >= fixedCount; the leading
        // args match the fixed params and each trailing arg coerces to the array's element type.
        // Reference element types only (ANEWARRAY-emittable); a primitive-element varargs is left
        // to the guess. A small penalty keeps a viable fixed-arity match preferred (JLS-like).
        for (sig in named) {
            if (!sig.isVarargs) continue
            val params = Type.getArgumentTypes(sig.descriptor)
            val pCount = params.size
            if (pCount == 0) continue
            val fixedCount = pCount - 1
            if (arity < fixedCount) continue
            val elem = params[pCount - 1].elementType
            val elemDesc = elem.descriptor
            if (elemDesc.length == 1) continue   // primitive element array — unsupported (see F24 note)
            var total = VARARGS_PENALTY
            var ok = true
            for (i in 0 until fixedCount) {
                val c = JvmCoercion.cost(argDescs[i], params[i].descriptor)
                if (c == null) { ok = false; break }
                total += c
            }
            if (ok) for (j in fixedCount until arity) {
                val c = JvmCoercion.cost(argDescs[j], elemDesc)
                if (c == null) { ok = false; break }
                total += c
            }
            if (ok) scored.add(Scored(sig, total, fixedCount))
        }

        if (scored.isEmpty()) return CallResolution.NoSuchMethod
        val min = scored.minOf { it.cost }
        val best = scored.filter { it.cost == min }
        // On a tie, prefer a fixed-arity match over a varargs one (a real overload beats a pack).
        val fixedBest = best.filter { it.varargsFixed == null }
        val winners = if (fixedBest.isNotEmpty()) fixedBest else best
        return if (winners.size == 1) CallResolution.Resolved(winners[0].sig, winners[0].varargsFixed)
               else CallResolution.Ambiguous(winners.map { it.sig })
    }

    private companion object {
        /** Added to a varargs candidate so a viable fixed-arity overload of equal arg cost wins. */
        const val VARARGS_PENALTY = 1
    }

    sealed class CallResolution {
        /**
         * @param varargsFixed when non-null, the call is a varargs invocation: emit args
         * `[0, varargsFixed)` against the fixed params, then pack `[varargsFixed, argCount)`
         * into the trailing array parameter (F24). null = a plain fixed-arity call.
         */
        data class Resolved(val sig: JvmMethodSig, val varargsFixed: Int? = null) : CallResolution()
        data class Ambiguous(val candidates: List<JvmMethodSig>) : CallResolution()
        object NoSuchMethod : CallResolution()
        object ClassNotFound : CallResolution()
    }
}
