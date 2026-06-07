package dev.kobol.codegen

import org.objectweb.asm.Opcodes.V21

// ---------------------------------------------------------------------------
// JVM / stdlib binary-name constants shared across all AsmEmitter files.
// Internal visibility: accessible within the :compiler module only.
// ---------------------------------------------------------------------------

internal const val JVM_VERSION       = V21
internal const val BIGDECIMAL        = "java/math/BigDecimal"
internal const val ROUNDING          = "java/math/RoundingMode"
internal const val STRING            = "java/lang/String"
internal const val PRINTSTREAM       = "java/io/PrintStream"
internal const val SYSTEM            = "java/lang/System"
internal const val ARRAYLIST         = "java/util/ArrayList"
internal const val LINKEDHASHMAP     = "java/util/LinkedHashMap"
internal const val LOCALDATE         = "java/time/LocalDate"
internal const val LOCALTIME         = "java/time/LocalTime"
internal const val LOCALDATETIME     = "java/time/LocalDateTime"

// Runtime classes
internal const val KOBOL_EXCEPTION       = "dev/kobol/runtime/KobolException\$ApplicationException"
internal const val KOBOL_VALIDATION_ERROR = "dev/kobol/runtime/KobolValidationError"
internal const val KOBOL_CONCURRENT      = "dev/kobol/runtime/KobolConcurrent"
internal const val KOBOL_DECIMAL         = "dev/kobol/runtime/KobolDecimal"
internal const val COMPLETABLE_FUTURE    = "java/util/concurrent/CompletableFuture"

// Logging
internal const val SLF4J_LOGGER  = "org/slf4j/Logger"
internal const val SLF4J_FACTORY = "org/slf4j/LoggerFactory"
internal const val LOGGER_FIELD  = "__LOGGER"

// Precision / math
internal const val MATH_CTX  = "dev/kobol/stdlib/KobolMathContext"
internal const val MATH_OWN  = "dev/kobol/stdlib/KobolMath"
internal const val JMATH_CTX = "java/math/MathContext"

// Stdlib module owners + auto-imported java.lang.* owners. The tables live in `semantic`
// (InteropSignatures.kt) as the single source of truth — shared with the type checker so the
// CALL-expression owner resolution can never fork from codegen's (F14). Re-exported here under
// the names the emitter already uses so existing call sites stay unchanged (P1).
internal val STDLIB_OWNERS = dev.kobol.semantic.STDLIB_OWNERS
internal val JAVA_LANG_OWNERS = dev.kobol.semantic.JAVA_LANG_OWNERS

// NoSQL + Cache
internal const val KOBOL_MONGO = "dev/kobol/stdlib/KobolMongo"
internal const val KOBOL_REDIS = "dev/kobol/stdlib/KobolRedis"
internal const val BSON_DESC   = "Lorg/bson/conversions/Bson;"
internal const val FILTERS     = "com/mongodb/client/model/Filters"

/**
 * JVM binary class name for a KobolType receiver (used by emitCall instance dispatch).
 * Returns null if the type cannot be used as an instance call receiver. Delegates to the shared
 * `semantic` table so the type checker and codegen agree on instance-receiver classes (F14).
 */
internal fun jvmClassForInstanceCall(type: dev.kobol.semantic.KobolType): String? =
    dev.kobol.semantic.instanceCallClassOf(type)

/** JVM types that require INVOKEINTERFACE rather than INVOKEVIRTUAL. */
internal val JVM_INTERFACE_TYPES = setOf(
    "java/util/List", "java/util/Map", "java/util/Set", "java/util/Collection",
    "java/util/Iterator", "java/lang/Comparable", "java/lang/Iterable",
    "java/lang/Runnable", "java/util/function/Function",
)

internal val COMPARISON_OPS = setOf(
    dev.kobol.parser.ast.BinaryOp.EQ,  dev.kobol.parser.ast.BinaryOp.NEQ,
    dev.kobol.parser.ast.BinaryOp.LT,  dev.kobol.parser.ast.BinaryOp.GT,
    dev.kobol.parser.ast.BinaryOp.LEQ, dev.kobol.parser.ast.BinaryOp.GEQ,
)
