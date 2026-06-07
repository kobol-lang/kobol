package dev.kobol.testfixture

/**
 * Test fixture for challenge **F15** (Kotlin `@Metadata` read).
 *
 * Top-level functions compile to a Kotlin *file facade* class
 * `dev.kobol.testfixture.KotlinNullableApiKt` carrying a real `@Metadata`
 * annotation — the only way to get genuine Kotlin metadata onto the test
 * classpath (a hand-built `@Metadata` cannot be produced from ASM). One
 * function returns a nullable `String?`, its twin a non-null `String`, so a
 * test can assert the resolver reads the nullability difference off the
 * metadata rather than off the (identical) erased JVM descriptor.
 */

fun maybeNull(): String? = null

fun alwaysPresent(): String = "present"

/**
 * Parameter-nullability twins (challenge **F15**). Same erased JVM descriptor
 * `(Ljava/lang/String;)I` — the parameter's `T` vs `T?` lives only in `@Metadata`,
 * so the resolver must read it from there. A non-null parameter compiles an
 * `Intrinsics.checkNotNullParameter` at the callee's entry; a nullable one does not.
 */
@Suppress("unused")
fun needsNonNull(s: String): Int = s.length

@Suppress("unused")
fun acceptsNullable(s: String?): Int = s?.length ?: 0
