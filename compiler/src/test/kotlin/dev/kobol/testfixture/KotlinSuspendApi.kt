package dev.kobol.testfixture

/**
 * Test fixture for challenge **#6** (Kotlin `suspend` functions).
 *
 * A `suspend fun` does NOT compile to the plain JVM signature it reads as in source: the Kotlin
 * compiler appends a hidden `kotlin.coroutines.Continuation` parameter and erases the return to
 * `Object` (the real result arrives through the continuation, or directly if the body never
 * suspends). So `suspendValue()` is really `suspendValue(Continuation)Ljava/lang/Object;` on the
 * JVM. A Kobol `CALL` that passes no continuation links a non-existent zero-arg method →
 * `NoSuchMethodError` at run, type-checks clean = P3 landmine. The compiler must recognise the
 * `@Metadata` `isSuspend` flag and refuse the call with a clear diagnostic until a real
 * Continuation↔FUTURE bridge exists.
 */
@Suppress("RedundantSuspendModifier")
suspend fun suspendValue(): String = "suspended"

/**
 * An INSTANCE suspend method with a real argument — exercises the bridge's receiver + arg-coercion
 * path (INVOKEVIRTUAL `greet(String, Continuation)Object`) alongside the static top-level case above.
 * Neither body actually suspends, so the coroutine returns its value directly (not COROUTINE_SUSPENDED)
 * and [dev.kobol.runtime.KobolContinuation.settle] completes the future synchronously.
 */
class SuspendService {
    @Suppress("RedundantSuspendModifier")
    suspend fun greet(name: String): String = "hi $name"
}
