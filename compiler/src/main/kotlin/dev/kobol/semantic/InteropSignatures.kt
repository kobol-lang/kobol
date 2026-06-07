package dev.kobol.semantic

/**
 * Shared interop-signature resolution (challenge enabler **E2**, feature **F14**).
 *
 * Single source of truth — consumed by BOTH the [TypeChecker] (to infer the static type of a
 * `CALL` expression) and the codegen AsmEmitter (to emit the call and coerce its result). The two
 * sides MUST agree on the owner class and the return type; if owner resolution forked between them
 * a call could type-check against class X and link against class Y, reintroducing exactly the
 * type-check-clean→runtime-crash landmine F14 exists to kill. Keeping the maps and the resolver
 * here — not in `codegen` — also keeps `semantic` free of any dependency on `codegen` (P1).
 */

/**
 * Stdlib module path → JVM owner. Single source of truth; codegen re-exports these (BytecodeConst)
 * so the alias table never forks. Keyed by lowercase slash path.
 */
val STDLIB_OWNERS: Map<String, String> = mapOf(
    "kobol/math"     to "dev/kobol/stdlib/KobolMath",
    "kobol/text"     to "dev/kobol/stdlib/KobolText",
    "kobol/date"     to "dev/kobol/stdlib/KobolDate",
    "kobol/sort"     to "dev/kobol/stdlib/KobolSort",
    "kobol/convert"  to "dev/kobol/stdlib/KobolConvert",
    "kobol/security" to "dev/kobol/stdlib/KobolSecurity",
    "kobol/uuid"     to "dev/kobol/stdlib/KobolUuid",
    "http"           to "dev/kobol/stdlib/KobolHttp",
    "jdbc"           to "dev/kobol/stdlib/KobolJdbc",
)

/**
 * `java.lang.*` classes auto-imported for a bare single-part `CALL`/`NEW` owner (spec §8.11).
 * Keyed by UPPERCASE owner alias → JVM binary name. Excludes names that collide with Kobol type
 * keywords (`String`, `Integer`, …) — those lex as type tokens, so a bare owner cannot reach here.
 */
val JAVA_LANG_OWNERS: Map<String, String> = mapOf(
    "SYSTEM"        to "java/lang/System",
    "MATH"          to "java/lang/Math",
    "THREAD"        to "java/lang/Thread",
    "RUNTIME"       to "java/lang/Runtime",
    "OBJECT"        to "java/lang/Object",
    "STRINGBUILDER" to "java/lang/StringBuilder",
    "CHARACTER"     to "java/lang/Character",
)

/**
 * KobolType → JVM type descriptor. Single source of truth; `AsmEmitter.jvmDescriptor` delegates
 * here so the descriptor a `CALL` argument is scored against (type checker) matches the descriptor
 * actually emitted (codegen). `RecordRefType`/`VariantRefType` erase to `Object` (the caller uses
 * the concrete inner-class name where it has it).
 */
fun kobolDescriptor(type: KobolType): String = when (type) {
    is KobolType.IntegerType    -> "J"
    is KobolType.SmallIntType   -> "I"
    is KobolType.BooleanType    -> "Z"
    is KobolType.TextType       -> "Ljava/lang/String;"
    is KobolType.DecimalType,
    is KobolType.MoneyType      -> "Ljava/math/BigDecimal;"
    is KobolType.DateType       -> "Ljava/time/LocalDate;"
    is KobolType.TimeType       -> "Ljava/time/LocalTime;"
    is KobolType.DateTimeType   -> "Ljava/time/LocalDateTime;"
    is KobolType.ListType       -> "Ljava/util/List;"
    is KobolType.MapType        -> "Ljava/util/Map;"
    is KobolType.FutureType     -> "Ljava/util/concurrent/CompletableFuture;"
    is KobolType.RecordRefType  -> "Ljava/lang/Object;"
    is KobolType.VariantRefType -> "Ljava/lang/Object;"
    is KobolType.UuidType       -> "Ljava/util/UUID;"
    is KobolType.JavaObjectType,
    is KobolType.UnknownType    -> "Ljava/lang/Object;"
    is KobolType.VoidType       -> "V"
}

/**
 * A method's JVM return descriptor → the [KobolType] a `CALL` expression yields, or null when the
 * descriptor maps to no Kobol type (the caller raises a diagnostic rather than guess). Integral
 * `int`/`short`/`byte`/`char` collapse to SMALLINT and `long` to INTEGER (Kobol's 64-bit integer);
 * `float`/`double` and `BigDecimal` become DECIMAL (codegen widens the primitive into BigDecimal,
 * P4). Any other reference type is an opaque JAVA-OBJECT. `void` → null (a void call has no value;
 * the caller must report it).
 */
fun kobolTypeFromDescriptor(desc: String): KobolType? = when (desc) {
    "J"                            -> KobolType.IntegerType
    "I", "S", "B", "C"             -> KobolType.SmallIntType
    "Z"                            -> KobolType.BooleanType
    "D", "F"                       -> KobolType.DecimalType(10, 2)
    "Ljava/lang/String;"           -> KobolType.TextType(null)
    "Ljava/math/BigDecimal;"       -> KobolType.DecimalType(10, 2)
    "Ljava/time/LocalDate;"        -> KobolType.DateType
    "Ljava/time/LocalTime;"        -> KobolType.TimeType
    "Ljava/time/LocalDateTime;"    -> KobolType.DateTimeType
    "Ljava/util/UUID;"             -> KobolType.UuidType
    "V"                            -> null
    else -> if (desc.startsWith("L") || desc.startsWith("[")) KobolType.JavaObjectType() else null
}

/**
 * JVM binary class name for a [KobolType] receiver of an instance `CALL`, or null if the type
 * cannot be an instance-call receiver. `JavaObjectType` erases to `java/lang/Object` here; a
 * known NEW owner is resolved separately by [resolveInteropTarget] before this is consulted.
 */
fun instanceCallClassOf(type: KobolType): String? = when (type) {
    is KobolType.TextType       -> "java/lang/String"
    is KobolType.DateType       -> "java/time/LocalDate"
    is KobolType.TimeType       -> "java/time/LocalTime"
    is KobolType.DateTimeType   -> "java/time/LocalDateTime"
    is KobolType.MoneyType,
    is KobolType.DecimalType    -> "java/math/BigDecimal"
    is KobolType.ListType       -> "java/util/List"
    is KobolType.MapType        -> "java/util/Map"
    is KobolType.UuidType       -> "java/util/UUID"
    is KobolType.JavaObjectType -> "java/lang/Object"
    else -> null
}

/**
 * Resolve a `NEW`/`CALL` owner's dotted parts to a JVM internal class name, consulting (in order)
 * import aliases, the stdlib path map, and `java.lang.*`. Single-part falls back to the original
 * case so a missing IMPORT surfaces as a `NoClassDefFoundError` naming the real class. Used by both
 * the NEW constructor-owner path and [resolveInteropTarget].
 */
fun resolveInteropOwner(ownerParts: List<String>, importAliasMap: Map<String, String>): String {
    if (ownerParts.size == 1) {
        val alias = ownerParts[0].uppercase()
        return importAliasMap[alias]
            ?: STDLIB_OWNERS[ownerParts[0].lowercase()]
            ?: JAVA_LANG_OWNERS[alias]
            ?: ownerParts[0]
    }
    val lowPath = ownerParts.joinToString("/") { it.lowercase() }
    return STDLIB_OWNERS[lowPath] ?: ownerParts.joinToString("/")
}

/** Outcome of resolving a `CALL` owner to a dispatch target. */
sealed class InteropTarget {
    /** INVOKESTATIC against [owner]. */
    data class Static(val owner: String) : InteropTarget()
    /** Instance dispatch (INVOKEVIRTUAL/INVOKEINTERFACE) against [owner]; receiver loaded by caller. */
    data class Instance(val owner: String) : InteropTarget()
    /** Multi-part `alias.STATIC_FIELD.method` — reflective dispatch (codegen-only statement path). */
    object Reflective : InteropTarget()
    /** No owner parts (malformed). */
    object Unresolvable : InteropTarget()
}

/**
 * Decide how a `CALL owner.method` dispatches, identically for the type checker and codegen.
 *
 * Single-part owner order: instance receiver (a known local/field whose type maps to a JVM class,
 * or a NEW-constructed value whose carried owner resolves) → import alias / stdlib → `java.lang.*` →
 * original-case static fallback. The receiver is checked FIRST so a genuine variable wins over any
 * class interpretation of the same name — a more-specific scope beats an import alias / stdlib /
 * java.lang owner (**F23**: pre-fix the alias was checked first, so `LET sb = NEW SB` + `CALL
 * sb.append` mis-routed to an INVOKESTATIC on the imported class → wrong descriptor, type-checks
 * clean = P3 landmine). A genuine static `CALL Alias.method` is unaffected — `Alias` names no
 * variable, so `receiverType` is null and resolution falls through to the alias. Multi-part: an
 * import alias as the first part is the reflective `alias.FIELD.method` form; otherwise a stdlib/FQN
 * static owner.
 *
 * @param receiverType the type of a single-part owner when it names a known variable, else null.
 */
fun resolveInteropTarget(
    ownerParts: List<String>,
    importAliasMap: Map<String, String>,
    receiverType: KobolType?,
): InteropTarget {
    if (ownerParts.isEmpty()) return InteropTarget.Unresolvable
    if (ownerParts.size == 1) {
        val alias = ownerParts[0].uppercase()
        // A real variable (local/DATA) is the most specific scope → it wins over a same-named class.
        if (receiverType != null && receiverType !is KobolType.UnknownType) {
            val cls = if (receiverType is KobolType.JavaObjectType && receiverType.ownerName != null)
                resolveInteropOwner(receiverType.ownerName.split("."), importAliasMap)
            else instanceCallClassOf(receiverType)
            if (cls != null) return InteropTarget.Instance(cls)
        }
        importAliasMap[alias]?.let { return InteropTarget.Static(it) }
        STDLIB_OWNERS[ownerParts[0].lowercase()]?.let { return InteropTarget.Static(it) }
        JAVA_LANG_OWNERS[alias]?.let { return InteropTarget.Static(it) }
        return InteropTarget.Static(ownerParts[0])
    }
    if (importAliasMap[ownerParts[0].uppercase()] != null) return InteropTarget.Reflective
    val lowPath = ownerParts.joinToString("/") { it.lowercase() }
    return InteropTarget.Static(STDLIB_OWNERS[lowPath] ?: ownerParts.joinToString("/"))
}

/**
 * Resolve a property read `obj.field` on [ownerInternalName] (slashed) to its no-arg getter method,
 * via the classpath [resolver] (challenge **#5 / F29**). A Kotlin property `val/var foo: T` compiles
 * to a getter `getFoo()T`; a property already named `isX` (typically `Boolean`) keeps its name
 * (`isX()`); a Java bean exposes the same `getX()/isX()` shape. Candidates, in order: `get<Field>`,
 * `is<Field>`, then the field verbatim (covers an already-accessor-named or `@JvmName`-renamed
 * getter). Returns the resolved public, non-static, no-arg getter [JvmMethodSig] — carrying its
 * `@Metadata` return nullability for W237 — or null when none resolves (caller keeps its opaque
 * fallback). Reads bytes only, no class load (P1); shared by the type checker and codegen so the
 * inferred property type can never disagree with the emitted getter call (P1, kills the F14-class
 * landmine for `obj.field` too).
 *
 * Match is **case-insensitive**: a Kobol [field] arrives upper-cased (the lexer normalises
 * identifiers, so `obj.nickname` reaches here as `NICKNAME`) while a JVM getter is camelCase
 * (`getNickname`). The resolved sig carries the getter's REAL name for the caller to invoke.
 */
fun resolvePropertyGetter(
    resolver: ClasspathSymbolResolver,
    ownerInternalName: String,
    field: String,
): JvmMethodSig? {
    val methods = resolver.methodsOf(ownerInternalName) ?: return null
    for (candidate in listOf("get$field", "is$field", field)) {
        val getter = methods.firstOrNull {
            it.isPublic && !it.isStatic &&
                it.descriptor.startsWith("()") &&          // no-arg getter
                it.name.equals(candidate, ignoreCase = true)
        }
        if (getter != null) return getter
    }
    return null
}
