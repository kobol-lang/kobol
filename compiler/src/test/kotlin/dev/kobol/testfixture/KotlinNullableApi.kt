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
