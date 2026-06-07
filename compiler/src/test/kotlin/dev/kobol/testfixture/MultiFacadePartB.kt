@file:JvmName("MultiFacade")
@file:JvmMultifileClass

package dev.kobol.testfixture

/**
 * Second part of the **F28** multifile-class facade `MultiFacade` (see
 * [MultiFacadePartA.kt]). `multiAlwaysPresent()` returns a non-null `String`,
 * so the resolver must NOT warn W237 on it — the union of part-class functions
 * has to carry per-function nullability, not a blanket flag.
 */
fun multiAlwaysPresent(): String = "present"
