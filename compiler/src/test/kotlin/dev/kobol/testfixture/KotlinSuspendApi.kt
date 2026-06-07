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
