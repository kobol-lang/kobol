@file:JvmName("MultiFacade")
@file:JvmMultifileClass

package dev.kobol.testfixture

/**
 * Test fixture for challenge **F28** (multifile-class-facade nullability).
 *
 * `@JvmMultifileClass` + a shared `@JvmName` make the Kotlin compiler emit a
 * MULTIFILE-CLASS FACADE (`@Metadata k=4`) `dev/kobol/testfixture/MultiFacade`
 * whose metadata holds only the part-class NAMES, not the function signatures —
 * the functions live in the part classes (`k=5`, e.g.
 * `MultiFacade__MultiFacadePartAKt`). This mirrors how every published Kotlin
 * library (e.g. kotlin-stdlib `StringsKt`) ships its top-level functions.
 *
 * `multiMaybeNull()` returns `String?` (nullable). The resolver must follow the
 * facade's part-class names, read each part's `@Metadata`, and surface the
 * nullability — otherwise W237 silently misses the COMMON library case.
 */
fun multiMaybeNull(): String? = null
