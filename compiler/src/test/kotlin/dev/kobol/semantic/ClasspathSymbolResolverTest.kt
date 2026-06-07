package dev.kobol.semantic

import dev.kobol.semantic.ClasspathSymbolResolver.CallResolution
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for the E2 classpath-aware symbol resolver: it must read a real class off
 * the classpath and report a method's true descriptor (vs. the Kobol-side guess), and
 * distinguish unresolved class / unknown method / arity-ambiguous overload so the caller
 * can fall back safely.
 */
class ClasspathSymbolResolverTest {

    private val resolver = ClasspathSymbolResolver(
        System.getProperty("java.class.path").split(File.pathSeparator),
    )

    @Test fun `resolves a unique no-arg method to its real descriptor`() {
        val r = resolver.resolveCall("java/lang/System", "lineSeparator", 0)
        assertTrue(r is CallResolution.Resolved, "expected Resolved, got $r")
        assertEquals("()Ljava/lang/String;", r.sig.descriptor)
        assertTrue(r.sig.isStatic && r.sig.isPublic)
    }

    @Test fun `picks the overload by arity`() {
        // System.getProperty(String) and getProperty(String,String) both exist.
        val one = resolver.resolveCall("java/lang/System", "getProperty", 1)
        assertTrue(one is CallResolution.Resolved, "expected Resolved at arity 1, got $one")
        assertEquals("(Ljava/lang/String;)Ljava/lang/String;", one.sig.descriptor)
    }

    @Test fun `arity-ambiguous overload set is reported, not picked`() {
        // Math.max has int/long/float/double pairs — 4 methods at arity 2. Coercion ranking
        // is F13; increment-1 reports Ambiguous so the caller falls back rather than guess.
        val r = resolver.resolveCall("java/lang/Math", "max", 2)
        assertTrue(r is CallResolution.Ambiguous, "expected Ambiguous, got $r")
        assertTrue(r.candidates.size >= 2)
    }

    @Test fun `unknown method on a real class is NoSuchMethod`() {
        val r = resolver.resolveCall("java/lang/System", "totallyBogusXyz", 0)
        assertEquals(CallResolution.NoSuchMethod, r)
    }

    @Test fun `unreadable class is ClassNotFound (caller falls back to its guess)`() {
        val r = resolver.resolveCall("com/no/such/Class", "whatever", 0)
        assertEquals(CallResolution.ClassNotFound, r)
    }

    // ─── E2 increment-2: coercion-ranked overload resolution (F13) ───────────────────

    @Test fun `resolveByArgs picks the exact-match overload over a widening one`() {
        // Math.max(long,long) is an exact match for (J,J) — cost 0 — and must beat the
        // (float,float)/(double,double) widenings.
        val r = resolver.resolveByArgs("java/lang/Math", "max", listOf("J", "J"))
        assertTrue(r is CallResolution.Resolved, "expected Resolved, got $r")
        assertEquals("(JJ)J", r.sig.descriptor)
    }

    @Test fun `resolveByArgs widens a mismatched arg width to the cheapest viable overload`() {
        // (I,J): Math.max(int,long) does NOT exist. (long,long) costs I→J=1; (float,float)/
        // (double,double) cost more. Cheapest viable = (long,long).
        val r = resolver.resolveByArgs("java/lang/Math", "max", listOf("I", "J"))
        assertTrue(r is CallResolution.Resolved, "expected Resolved, got $r")
        assertEquals("(JJ)J", r.sig.descriptor)
    }

    @Test fun `resolveByArgs widens an int arg to a double-only overload`() {
        // Math.sqrt has only (D)D; an integer arg (J) widens J→D.
        val r = resolver.resolveByArgs("java/lang/Math", "sqrt", listOf("J"))
        assertTrue(r is CallResolution.Resolved, "expected Resolved, got $r")
        assertEquals("(D)D", r.sig.descriptor)
    }

    @Test fun `resolveByArgs excludes overloads needing a narrowing arg`() {
        // A double arg (D) cannot narrow to any int/long/float Math.max param; only (double,double)
        // is viable, so it resolves uniquely rather than reporting the int/long/float set.
        val r = resolver.resolveByArgs("java/lang/Math", "max", listOf("D", "D"))
        assertTrue(r is CallResolution.Resolved, "expected Resolved, got $r")
        assertEquals("(DD)D", r.sig.descriptor)
    }

    @Test fun `resolveByArgs reports NoSuchMethod when no overload is callable with these args`() {
        // System.lineSeparator has no 1-arg form, and a String arg cannot reach a no-arg method.
        val r = resolver.resolveByArgs("java/lang/System", "lineSeparator", listOf("Ljava/lang/String;"))
        assertEquals(CallResolution.NoSuchMethod, r)
    }

    @Test fun `JvmCoercion cost ranks exact below widening below boxing, and rejects narrowing`() {
        assertEquals(0, JvmCoercion.cost("J", "J"))
        assertTrue(JvmCoercion.cost("I", "J")!! < JvmCoercion.cost("I", "D")!!) // closer widening cheaper
        assertEquals(null, JvmCoercion.cost("D", "I"))                          // fractional narrowing unsupported
        assertEquals(null, JvmCoercion.cost("J", "S"))                          // J→short narrowing unsupported (only J→I)
        assertTrue(JvmCoercion.cost("D", "Ljava/math/BigDecimal;") != null)     // double → BigDecimal
        assertTrue(JvmCoercion.cost("Ljava/lang/String;", "Ljava/lang/Object;")!! <
            JvmCoercion.cost("Ljava/lang/Object;", "Ljava/lang/String;")!!)     // upcast cheaper than checkcast
    }

    // ─── E2: F22 — guarded long→int narrowing (Kobol INTEGER is a 64-bit long) ───────

    @Test fun `JvmCoercion permits the guarded J to I narrowing but ranks it below every widening`() {
        val narrow = JvmCoercion.cost("J", "I")
        assertTrue(narrow != null, "J→I must be a supported (guarded) narrowing for F22")
        // It must lose to every non-narrowing coercion so a real long/wider overload always wins.
        assertTrue(narrow > JvmCoercion.cost("I", "D")!!)                       // beaten by widening
        assertTrue(narrow > JvmCoercion.cost("D", "Ljava/math/BigDecimal;")!!)  // beaten by prim→BigDecimal
        assertTrue(narrow > JvmCoercion.cost("Ljava/lang/Object;", "Ljava/lang/String;")!!) // beaten by checkcast
    }

    @Test fun `resolveByArgs narrows an INTEGER arg into an int-only overload`() {
        // String.substring(int) is the only arity-1 overload — (I)Ljava/lang/String;. A Kobol
        // INTEGER arg is a long (J); F22's guarded J→I narrowing lets it link instead of falling
        // through to the substring(long) guess.
        val r = resolver.resolveByArgs("java/lang/String", "substring", listOf("J"))
        assertTrue(r is CallResolution.Resolved, "expected Resolved, got $r")
        assertEquals("(I)Ljava/lang/String;", r.sig.descriptor)
    }

    @Test fun `resolveByArgs collapses a covariant-return bridge so a fluent overload resolves`() {
        // StringBuilder.append(String) returns StringBuilder, but the covariant-return override
        // leaves a synthetic bridge append(String) returning AbstractStringBuilder. Both are public
        // with identical params; without skipping bridges this is a false tie → Ambiguous → the
        // caller guesses → NoSuchMethodError. The resolver must skip bridges and resolve the real one.
        val r = resolver.resolveByArgs("java/lang/StringBuilder", "append", listOf("Ljava/lang/String;"))
        assertTrue(r is CallResolution.Resolved, "expected Resolved (bridge skipped), got $r")
        assertEquals("(Ljava/lang/String;)Ljava/lang/StringBuilder;", r.sig.descriptor)
    }

    @Test fun `resolveByArgs matches a varargs overload and reports the fixed-arg count`() {
        // String.format(String, Object...) — 3 args: arg0 → the fixed String param, args 1-2 packed
        // into the Object[] vararg. The fixed-arity-3 format(Locale,String,Object[]) is excluded
        // (arg0 String is not assignable to Locale — F25), so the varargs match resolves.
        val r = resolver.resolveByArgs(
            "java/lang/String", "format",
            listOf("Ljava/lang/String;", "Ljava/lang/String;", "Ljava/lang/String;"),
        )
        assertTrue(r is CallResolution.Resolved, "expected Resolved, got $r")
        assertEquals("(Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/String;", r.sig.descriptor)
        assertEquals(1, r.varargsFixed, "one fixed arg, the rest packed")
    }

    // ─── F25 — ref→ref arg coercion admissible only as an upcast (subtype) ────────────

    @Test fun `JvmCoercion cost rejects an unrelated ref cross-cast when a subtype oracle is supplied (F25)`() {
        val sub: (String, String) -> Boolean = resolver::isSubtype
        // Java overload resolution NEVER inserts an argument cross-cast. String is not assignable
        // to Locale → not a viable coercion (pre-F25 it scored as a CHECKCAST → false match → CCE).
        assertEquals(null, JvmCoercion.cost("Ljava/lang/String;", "Ljava/util/Locale;", sub))
        // A genuine upcast stays viable: ArrayList <: List.
        assertTrue(JvmCoercion.cost("Ljava/util/ArrayList;", "Ljava/util/List;", sub) != null,
            "a real upcast must remain admissible")
        // → Object is always the safe upcast.
        assertTrue(JvmCoercion.cost("Ljava/lang/String;", "Ljava/lang/Object;", sub) != null)
    }

    @Test fun `resolveByArgs excludes an unrelated ref cross-cast arg (F25)`() {
        // Locale.forLanguageTag(String) is the only arity-1 overload. A Date arg is NOT a String;
        // pre-F25 the ref→ref CHECKCAST cost made it a false match → ClassCastException at run.
        // Now excluded → NoSuchMethod (the caller falls back to its guess, no silent mis-link).
        val r = resolver.resolveByArgs("java/util/Locale", "forLanguageTag", listOf("Ljava/util/Date;"))
        assertEquals(CallResolution.NoSuchMethod, r)
    }

    @Test fun `resolveByArgs admits a genuine ref upcast arg (F25)`() {
        // Collections.max(Collection) called with an ArrayList — ArrayList <: Collection, a real
        // implicit upcast. Must stay viable (excluding all ref→ref would regress legit upcasts).
        val r = resolver.resolveByArgs("java/util/Collections", "max", listOf("Ljava/util/ArrayList;"))
        assertTrue(r is CallResolution.Resolved, "a real upcast arg must resolve, got $r")
    }

    // ─── F30 — constructors are `<init>` methods; resolveByArgs ranks them like any call ──

    @Test fun `resolveByArgs resolves a constructor by its int param, narrowing the INTEGER arg (F30)`() {
        // StringBuilder has ctors ()/(int)/(String)/(CharSequence). A Kobol INTEGER arg is a long (J):
        // (String)/(CharSequence) are not coercible from J; only (int) is viable via F22's guarded
        // J→I narrowing. The old NEW path guessed `<init>(J)V` → NoSuchMethodError. resolveByArgs must
        // pick the real (I)V ctor so NEW emits the right descriptor.
        val r = resolver.resolveByArgs("java/lang/StringBuilder", "<init>", listOf("J"))
        assertTrue(r is CallResolution.Resolved, "expected Resolved, got $r")
        assertEquals("(I)V", r.sig.descriptor)
    }

    // ─── F15 #6 — Kotlin `suspend` functions resolve past their hidden Continuation ──────

    @Test fun `resolveSuspend resolves a static Kotlin suspend function past its Continuation (F15)`() {
        // Top-level `suspend fun suspendValue(): String` → static `suspendValue(Continuation)Object`.
        // A 0-user-arg call matches it via resolveSuspend (the trailing Continuation is implicit).
        val r = resolver.resolveSuspend("dev/kobol/testfixture/KotlinSuspendApiKt", "suspendValue", emptyList())
        assertTrue(r is CallResolution.Resolved, "expected Resolved, got $r")
        assertTrue(r.sig.isSuspend, "the resolved sig must be flagged suspend")
        assertEquals("(Lkotlin/coroutines/Continuation;)Ljava/lang/Object;", r.sig.descriptor)
    }

    @Test fun `resolveSuspend matches an instance suspend method by its user args (F15)`() {
        // `suspend fun greet(name: String): String` → `greet(String, Continuation)Object`; the user
        // supplies only the String, the Continuation is appended by the bridge.
        val r = resolver.resolveSuspend("dev/kobol/testfixture/SuspendService", "greet", listOf("Ljava/lang/String;"))
        assertTrue(r is CallResolution.Resolved, "expected Resolved, got $r")
        assertEquals("(Ljava/lang/String;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;", r.sig.descriptor)
    }

    @Test fun `resolveByArgs does not match a suspend function whose arity is off by the Continuation (F15)`() {
        // The 0-user-arg suspend call has no plain arity-0 method — only (Continuation)Object exists.
        // This is exactly why suspend needs its own resolver entry rather than riding resolveByArgs.
        val r = resolver.resolveByArgs("dev/kobol/testfixture/KotlinSuspendApiKt", "suspendValue", emptyList())
        assertEquals(CallResolution.NoSuchMethod, r)
    }

    @Test fun `resolveSuspend reports NoSuchMethod for a non-suspend Java method (F15)`() {
        // A plain JDK method is never suspend → resolveSuspend never claims it (caller proceeds normally).
        val r = resolver.resolveSuspend("java/lang/System", "lineSeparator", emptyList())
        assertEquals(CallResolution.NoSuchMethod, r)
    }

    @Test fun `resolveByArgs prefers the long overload over narrowing when both exist`() {
        // Math.abs has abs(int)/abs(long)/abs(float)/abs(double). A long arg matches abs(long)
        // exactly (cost 0) and must NOT narrow to abs(int) — narrowing is the last resort.
        val r = resolver.resolveByArgs("java/lang/Math", "abs", listOf("J"))
        assertTrue(r is CallResolution.Resolved, "expected Resolved, got $r")
        assertEquals("(J)J", r.sig.descriptor)
    }
}
