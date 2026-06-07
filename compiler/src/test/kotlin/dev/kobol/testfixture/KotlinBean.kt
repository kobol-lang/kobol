package dev.kobol.testfixture

/**
 * Test fixture for challenge **#5** (Kotlin property accessors via `obj.field`).
 *
 * A Kotlin property `val name: String` compiles to a getter `getName()Ljava/lang/String;`; a
 * nullable `val nickname: String?` is `getNickname()Ljava/lang/String;` with the nullability
 * recorded only in `@Metadata` (the erased descriptor is identical to a non-null `String`). The
 * `obj.field` surface must resolve the getter, infer the real property type, and warn `W237` on a
 * nullable one — instead of the old blind `TEXT` hardcode that emitted nothing.
 */
class KotlinBean(val name: String) {
    /** Int property → getter `getScore()I`; reading it widens int→long for Kobol INTEGER (P4/F14). */
    val score: Int = 42
    /** Nullable property → must warn W237 (`getNickname():String?`, erased to plain `String`). */
    val nickname: String? = null
    /** Boolean property `active` (no `is` prefix) → getter `getActive()Z`. */
    val active: Boolean = true
}
