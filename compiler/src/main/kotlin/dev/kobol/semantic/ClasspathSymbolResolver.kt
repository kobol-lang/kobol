package dev.kobol.semantic

import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import java.io.File
import java.net.URLClassLoader
import kotlin.metadata.KmFunction
import kotlin.metadata.KmProperty
import kotlin.metadata.isNullable
import kotlin.metadata.isSuspend
import kotlin.metadata.jvm.KotlinClassMetadata
import kotlin.metadata.jvm.getterSignature
import kotlin.metadata.jvm.signature

/** One method signature read from a real `.class` on the compile classpath. */
data class JvmMethodSig(
    val name: String,
    /** JVM method descriptor, e.g. `()Ljava/lang/String;`. */
    val descriptor: String,
    val isStatic: Boolean,
    val isPublic: Boolean,
    /** True if declared varargs (`ACC_VARARGS`); the last parameter is the variadic array. */
    val isVarargs: Boolean = false,
    /**
     * True when the declaring class is Kotlin and this method's return type is nullable (`T?`)
     * per its `@Metadata` (challenge **F15**). Always false for a Java/JDK class (no metadata) —
     * the erased JVM descriptor cannot express nullability. Kobol has no null, so a nullable
     * return is a silent-NPE landmine the type checker warns on.
     */
    val returnNullable: Boolean = false,
    /**
     * True when the declaring class is Kotlin and this method is `suspend` per its `@Metadata`
     * (challenge **F15 #6**). Its real JVM shape is `(<params…>, Lkotlin/coroutines/Continuation;)
     * Ljava/lang/Object;` — the synthetic trailing [Continuation] and erased `Object` return are why
     * a plain arity-matched [resolveByArgs] never finds it (the user supplies one fewer arg). Always
     * false for a Java/JDK method (no metadata). Resolved for a call by [resolveSuspend].
     */
    val isSuspend: Boolean = false,
    /**
     * Per-USER-parameter nullability from `@Metadata` (challenge **F15** — parameter nullability).
     * `paramNullable[i]` is true when the Kotlin declared value parameter `i` has a nullable type
     * (`T?`). Empty for a Java/JDK method (no metadata) or a no-arg method. These are the
     * Kotlin-declared parameters only — the synthetic trailing `Continuation` of a `suspend`
     * method is NOT a declared parameter and does not appear here, so this list is one shorter
     * than the erased [descriptor]'s parameter count for a suspend method (it matches the user
     * call's arity). A Kotlin NON-null parameter fed a possibly-null value (an uninitialized
     * `JAVA-OBJECT`, or a nullable Kotlin return) fails `Intrinsics.checkNotNullParameter` at the
     * callee's entry — surfaced here so that null-safety analysis can tell a tolerant nullable
     * sink from a strict non-null one. Kobol warns the possibly-null SOURCE (W019 / W237), so this
     * data is currently consumed only by tests; it is the foundation for future flow-based checks.
     */
    val paramNullable: List<Boolean> = emptyList(),
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

    // owner internal name → (superName?, interfaceNames), or null when unreadable. Cached (P5).
    private val hierarchy = HashMap<String, Pair<String?, List<String>>?>()

    /** Raw `.class` bytes for [owner] (slashed), or null if unreadable. Shared byte fetch (P1). */
    private fun classBytes(owner: String): ByteArray? =
        (loader.getResourceAsStream("$owner.class")
            ?: ClassLoader.getSystemResourceAsStream("$owner.class"))
            ?.use { it.readBytes() }

    /** All methods of [ownerInternalName] (slashed, e.g. `java/lang/System`), or null if unreadable. */
    fun methodsOf(ownerInternalName: String): List<JvmMethodSig>? =
        cache.getOrPut(ownerInternalName) { readMethods(ownerInternalName) }

    private fun readMethods(owner: String): List<JvmMethodSig>? {
        val raw = readRaw(owner) ?: return null
        val parsed = raw.metadata?.let { runCatching { KotlinClassMetadata.readLenient(it) }.getOrNull() }

        // F28: a MULTIFILE-CLASS FACADE (`k=4`, every published library's top-level fns, e.g.
        // kotlin-stdlib `StringsKt`) declares NO public API methods of its own — its `<clinit>` is
        // all that's here. The real static API lives in the part classes (`k=5`), reached either by
        // a forwarder (default) or by inheritance (stdlib's `-Xmultifile-parts-inherit`); the facade
        // metadata names only the parts. So the facade's effective methods = the union of the part
        // classes' methods, EACH carrying its own part `@Metadata` nullability (F15). Without this,
        // a `CALL StringsKt.toIntOrNull` never resolves here → falls back to a guess → no W237 →
        // silent NPE on the nullable `Int?` return (P3). Parts are `k=5`, never facades, so the
        // recursion is one level.
        if (parsed is KotlinClassMetadata.MultiFileClassFacade) {
            val unioned = LinkedHashMap<String, JvmMethodSig>()
            for (part in parsed.partClassNames) {
                // partClassNames are JVM internal (slashed) names; normalize defensively.
                val partMethods = methodsOf(part.replace('.', '/')) ?: continue
                for (m in partMethods) unioned.putIfAbsent(m.name + m.descriptor, m)
            }
            return unioned.values.toList()
        }

        // F15: a Kotlin method's nullable return (`T?`) erases to the same JVM descriptor as a
        // non-null one, and a `suspend` modifier is invisible in the descriptor too — both live only
        // in @Metadata. Decode them and mark the matching sigs (keyed by name + JVM descriptor).
        val nullableByKey = nullableKeysFromParsed(parsed)
        val suspendByKey  = suspendKeysFromParsed(parsed)
        val paramNullByKey = paramNullableFromParsed(parsed)
        return if (nullableByKey.isEmpty() && suspendByKey.isEmpty() && paramNullByKey.isEmpty()) raw.methods
        else raw.methods.map { sig ->
            val key = sig.name + sig.descriptor
            sig.copy(
                returnNullable = sig.returnNullable || nullableByKey.contains(key),
                isSuspend = suspendByKey.contains(key),
                paramNullable = paramNullByKey[key] ?: sig.paramNullable,
            )
        }
    }

    /** A class's declared methods + raw `@Metadata` from one ASM byte pass, or null if unreadable. */
    private class RawClass(val methods: List<JvmMethodSig>, val metadata: Metadata?)

    private fun readRaw(owner: String): RawClass? {
        val bytes = classBytes(owner) ?: return null
        val sigs = ArrayList<JvmMethodSig>()
        val metadata = MetadataCollector()
        ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9) {
            // Collect the class's @Metadata (F15) in the SAME byte-read pass — no class load, no
            // extra fetch. Non-Kotlin classes have no such annotation, so the collector stays empty.
            override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor? =
                if (descriptor == "Lkotlin/Metadata;") metadata else super.visitAnnotation(descriptor, visible)

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
        return RawClass(sigs, metadata.build())
    }

    /**
     * Collects the raw fields of a `@kotlin/Metadata` annotation during an ASM class read and
     * rebuilds a real [Metadata] (challenge **F15**) — no class loading, the same bytes-only path
     * E2 established (P1). Decoding (→ nullable-return keys) lives in [nullableReturnKeys] at the
     * resolver level, where it can recurse into multifile-facade part classes (**F28**).
     */
    private class MetadataCollector : AnnotationVisitor(Opcodes.ASM9) {
        private var kind = 1
        private var extraInt = 0
        private var extraString = ""
        private var packageName = ""
        private val metadataVersion = ArrayList<Int>()
        private val data1 = ArrayList<String>()
        private val data2 = ArrayList<String>()

        override fun visit(name: String?, value: Any?) {
            when (name) {
                "k"  -> kind = value as? Int ?: kind
                "xi" -> extraInt = value as? Int ?: extraInt
                "xs" -> extraString = value as? String ?: extraString
                "pn" -> packageName = value as? String ?: packageName
                // ASM delivers a primitive int[] annotation array (the metadata version `mv`) as a
                // whole array through visit(), not element-by-element via visitArray (that path is
                // only taken for the String arrays d1/d2). Without mv, readLenient rejects the
                // metadata as malformed.
                "mv" -> (value as? IntArray)?.let { metadataVersion.addAll(it.toList()) }
            }
        }

        override fun visitArray(name: String?): AnnotationVisitor = object : AnnotationVisitor(Opcodes.ASM9) {
            override fun visit(n: String?, value: Any?) {
                when (name) {
                    "mv" -> (value as? Int)?.let { metadataVersion.add(it) }
                    "d1" -> (value as? String)?.let { data1.add(it) }
                    "d2" -> (value as? String)?.let { data2.add(it) }
                }
            }
        }

        /** The collected annotation as a real [Metadata], or null when no payload was seen. */
        fun build(): Metadata? {
            if (data1.isEmpty() && data2.isEmpty()) return null
            // Annotation constructors are callable since Kotlin 1.3.
            return Metadata(
                kind = kind,
                metadataVersion = metadataVersion.toIntArray(),
                data1 = data1.toTypedArray(),
                data2 = data2.toTypedArray(),
                extraString = extraString,
                packageName = packageName,
                extraInt = extraInt,
            )
        }
    }

    /**
     * The set of `name + JVM descriptor` keys whose Kotlin return type is nullable (`T?`) for an
     * already-parsed metadata, via `kotlin-metadata-jvm` — empty for a non-Kotlin / unsupported
     * metadata (fail safe — never invents a nullability claim). The `k=4` MULTIFILE-CLASS FACADE
     * is NOT handled here: it declares no functions of its own (see [readMethods] — its methods +
     * nullability are unioned from the part classes).
     */
    private fun nullableKeysFromParsed(parsed: KotlinClassMetadata?): Set<String> = when (parsed) {
        is KotlinClassMetadata.Class              -> nullableKeysOf(parsed.kmClass.functions, parsed.kmClass.properties)
        is KotlinClassMetadata.FileFacade         -> nullableKeysOf(parsed.kmPackage.functions, parsed.kmPackage.properties)
        is KotlinClassMetadata.MultiFileClassPart -> nullableKeysOf(parsed.kmPackage.functions, parsed.kmPackage.properties)
        else                                      -> emptySet()
    }

    /**
     * `name + JVM descriptor` keys of every nullable-returning function AND nullable property
     * GETTER. A Kotlin property `val foo: T?` is invisible in [KmFunction]s — it lives in
     * [KmProperty] with a [getterSignature] — so the `obj.field` accessor path (#5) needs the
     * getter keyed here too, else its nullability is lost.
     */
    private fun nullableKeysOf(functions: List<KmFunction>, properties: List<KmProperty>): Set<String> {
        val keys = HashSet<String>()
        for (fn in functions) {
            if (!fn.returnType.isNullable) continue
            val sig = fn.signature ?: continue   // JvmMethodSignature: name + descriptor
            keys.add(sig.name + sig.descriptor)
        }
        for (prop in properties) {
            if (!prop.returnType.isNullable) continue
            val getter = prop.getterSignature ?: continue
            keys.add(getter.name + getter.descriptor)
        }
        return keys
    }

    /**
     * `name + JVM descriptor` keys of every `suspend` function (challenge **F15 #6**). The descriptor
     * from [KmFunction.signature] is the REAL JVM signature — i.e. it already carries the synthetic
     * trailing `Continuation` parameter and the erased `Object` return — so it keys the same
     * [JvmMethodSig] the byte read produced. A multifile-class FACADE has none of its own (handled
     * by the part-class union in [readMethods], same as nullability).
     */
    private fun suspendKeysFromParsed(parsed: KotlinClassMetadata?): Set<String> = when (parsed) {
        is KotlinClassMetadata.Class              -> suspendKeysOf(parsed.kmClass.functions)
        is KotlinClassMetadata.FileFacade         -> suspendKeysOf(parsed.kmPackage.functions)
        is KotlinClassMetadata.MultiFileClassPart -> suspendKeysOf(parsed.kmPackage.functions)
        else                                      -> emptySet()
    }

    private fun suspendKeysOf(functions: List<KmFunction>): Set<String> {
        val keys = HashSet<String>()
        for (fn in functions) {
            if (!fn.isSuspend) continue
            val sig = fn.signature ?: continue
            keys.add(sig.name + sig.descriptor)
        }
        return keys
    }

    /**
     * `name + JVM descriptor` → per-declared-parameter nullability (challenge **F15** — parameter
     * nullability). Keyed by the SAME real JVM signature the byte read produces, so it lands on the
     * matching [JvmMethodSig]. A multifile-class FACADE has no functions of its own (handled by the
     * part-class union in [readMethods]); empty for a non-Kotlin metadata.
     */
    private fun paramNullableFromParsed(parsed: KotlinClassMetadata?): Map<String, List<Boolean>> = when (parsed) {
        is KotlinClassMetadata.Class              -> paramNullableOf(parsed.kmClass.functions)
        is KotlinClassMetadata.FileFacade         -> paramNullableOf(parsed.kmPackage.functions)
        is KotlinClassMetadata.MultiFileClassPart -> paramNullableOf(parsed.kmPackage.functions)
        else                                      -> emptyMap()
    }

    private fun paramNullableOf(functions: List<KmFunction>): Map<String, List<Boolean>> {
        val map = HashMap<String, List<Boolean>>()
        for (fn in functions) {
            val sig = fn.signature ?: continue
            map[sig.name + sig.descriptor] = fn.valueParameters.map { it.type.isNullable }
        }
        return map
    }

    /** [owner]'s direct superclass + interfaces (slashed names), or null when unreadable. */
    private fun superAndInterfaces(owner: String): Pair<String?, List<String>>? =
        hierarchy.getOrPut(owner) {
            val bytes = classBytes(owner) ?: return@getOrPut null
            var result: Pair<String?, List<String>> = null to emptyList()
            ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9) {
                override fun visit(
                    version: Int, access: Int, name: String, signature: String?,
                    superName: String?, interfaces: Array<out String>?,
                ) {
                    result = superName to (interfaces?.toList() ?: emptyList())
                }
            }, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
            result
        }

    /**
     * Is [sub] assignable to [sup] by Java reference widening (an upcast)? Walks the class hierarchy
     * (superclass + interfaces, transitively) off the same cached classpath bytes — no class loading.
     * `java/lang/Object` is every reference's supertype. Used by [JvmCoercion.cost] to reject an
     * unrelated argument cross-cast (**F25**). Both names are internal (slashed); a class whose bytes
     * are unreadable contributes no further edges (conservative → not a subtype).
     */
    fun isSubtype(sub: String, sup: String): Boolean {
        if (sub == sup || sup == "java/lang/Object") return true
        val seen = HashSet<String>()
        val queue = ArrayDeque<String>().apply { add(sub) }
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            if (cur == sup) return true
            if (!seen.add(cur)) continue
            val (superName, interfaces) = superAndInterfaces(cur) ?: continue
            superName?.let { queue.add(it) }
            queue.addAll(interfaces)
        }
        return false
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
                val c = JvmCoercion.cost(argDescs[i], params[i].descriptor, ::isSubtype)
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
                val c = JvmCoercion.cost(argDescs[i], params[i].descriptor, ::isSubtype)
                if (c == null) { ok = false; break }
                total += c
            }
            if (ok) for (j in fixedCount until arity) {
                val c = JvmCoercion.cost(argDescs[j], elemDesc, ::isSubtype)
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

    /**
     * Resolve a Kotlin `suspend` [methodName] on [ownerInternalName] callable with the user-supplied
     * argument descriptors [argDescs] — which do NOT include the hidden trailing `Continuation`
     * (challenge **F15 #6**). A suspend method's JVM shape is `(<params…>, Continuation)Object`, so a
     * call of `n` user args matches a suspend overload of `n + 1` JVM params whose last is a
     * `Continuation`; the leading `n` params are scored by [JvmCoercion.cost] exactly like
     * [resolveByArgs]. Returns:
     *  - [CallResolution.Resolved] — unique cheapest suspend overload (its `sig.descriptor` is the
     *    REAL suspend descriptor, incl. the Continuation, for the codegen bridge to emit);
     *  - [CallResolution.Ambiguous] — a tie at minimum cost;
     *  - [CallResolution.NoSuchMethod] — no suspend overload by that name is callable with these args;
     *  - [CallResolution.ClassNotFound] — class unreadable.
     *
     * A non-Kotlin (Java/JDK) class has no suspend methods, so this always reports
     * [CallResolution.NoSuchMethod] for it — the caller then proceeds down the ordinary path.
     */
    fun resolveSuspend(ownerInternalName: String, methodName: String, argDescs: List<String>): CallResolution {
        val methods = methodsOf(ownerInternalName) ?: return CallResolution.ClassNotFound
        val named = methods.filter { it.name == methodName && it.isPublic && it.isSuspend }
        if (named.isEmpty()) return CallResolution.NoSuchMethod

        data class Scored(val sig: JvmMethodSig, val cost: Int)
        val scored = ArrayList<Scored>()
        for (sig in named) {
            val params = Type.getArgumentTypes(sig.descriptor)
            if (params.isEmpty() || params.last().descriptor != "L$CONTINUATION;") continue
            val userParams = params.dropLast(1)
            if (userParams.size != argDescs.size) continue
            var total = 0
            var ok = true
            for (i in argDescs.indices) {
                val c = JvmCoercion.cost(argDescs[i], userParams[i].descriptor, ::isSubtype)
                if (c == null) { ok = false; break }
                total += c
            }
            if (ok) scored.add(Scored(sig, total))
        }
        if (scored.isEmpty()) return CallResolution.NoSuchMethod
        val min = scored.minOf { it.cost }
        val best = scored.filter { it.cost == min }
        return if (best.size == 1) CallResolution.Resolved(best[0].sig)
               else CallResolution.Ambiguous(best.map { it.sig })
    }

    private companion object {
        /** Added to a varargs candidate so a viable fixed-arity overload of equal arg cost wins. */
        const val VARARGS_PENALTY = 1

        /** Internal name of the synthetic trailing parameter every `suspend` function carries. */
        const val CONTINUATION = "kotlin/coroutines/Continuation"
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
