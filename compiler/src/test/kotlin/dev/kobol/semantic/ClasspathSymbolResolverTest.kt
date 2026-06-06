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
}
