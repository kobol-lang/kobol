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
                sigs.add(
                    JvmMethodSig(
                        name = name,
                        descriptor = descriptor,
                        isStatic = access and Opcodes.ACC_STATIC != 0,
                        isPublic = access and Opcodes.ACC_PUBLIC != 0,
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

    sealed class CallResolution {
        data class Resolved(val sig: JvmMethodSig) : CallResolution()
        data class Ambiguous(val candidates: List<JvmMethodSig>) : CallResolution()
        object NoSuchMethod : CallResolution()
        object ClassNotFound : CallResolution()
    }
}
