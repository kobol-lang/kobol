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
internal const val COMPLETABLE_FUTURE    = "java/util/concurrent/CompletableFuture"

// Logging
internal const val SLF4J_LOGGER  = "org/slf4j/Logger"
internal const val SLF4J_FACTORY = "org/slf4j/LoggerFactory"
internal const val LOGGER_FIELD  = "__LOGGER"

// Precision / math
internal const val MATH_CTX  = "dev/kobol/stdlib/KobolMathContext"
internal const val MATH_OWN  = "dev/kobol/stdlib/KobolMath"
internal const val JMATH_CTX = "java/math/MathContext"

// Stdlib module owners (used by emitBuiltin)
internal val STDLIB_OWNERS = mapOf(
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
 * `java.lang.*` classes auto-imported for a bare single-part `CALL` (spec §8.11),
 * e.g. `CALL System.currentTimeMillis GIVING t` without an explicit IMPORT.
 * Keyed by UPPERCASE owner alias → JVM binary name (original case).
 *
 * Deliberately excludes classes whose names collide with Kobol type keywords
 * (`String`, `Integer`, `Long`, `Double`, `Boolean`) — those lex as type tokens,
 * not identifiers, so a bare `CALL Integer.parseInt` cannot reach here anyway.
 * Use an explicit `IMPORT java.lang.Integer AS …` for those.
 */
internal val JAVA_LANG_OWNERS = mapOf(
    "SYSTEM"        to "java/lang/System",
    "MATH"          to "java/lang/Math",
    "THREAD"        to "java/lang/Thread",
    "RUNTIME"       to "java/lang/Runtime",
    "OBJECT"        to "java/lang/Object",
    "STRINGBUILDER" to "java/lang/StringBuilder",
    "CHARACTER"     to "java/lang/Character",
)

// NoSQL + Cache
internal const val KOBOL_MONGO = "dev/kobol/stdlib/KobolMongo"
internal const val KOBOL_REDIS = "dev/kobol/stdlib/KobolRedis"
internal const val BSON_DESC   = "Lorg/bson/conversions/Bson;"
internal const val FILTERS     = "com/mongodb/client/model/Filters"

/**
 * JVM binary class name for a KobolType receiver (used by emitCall instance dispatch).
 * Returns null if the type cannot be used as an instance call receiver.
 */
internal fun jvmClassForInstanceCall(type: dev.kobol.semantic.KobolType): String? = when (type) {
    is dev.kobol.semantic.KobolType.TextType     -> "java/lang/String"
    is dev.kobol.semantic.KobolType.DateType     -> LOCALDATE
    is dev.kobol.semantic.KobolType.TimeType     -> LOCALTIME
    is dev.kobol.semantic.KobolType.DateTimeType -> LOCALDATETIME
    is dev.kobol.semantic.KobolType.MoneyType,
    is dev.kobol.semantic.KobolType.DecimalType  -> BIGDECIMAL
    is dev.kobol.semantic.KobolType.ListType     -> "java/util/List"
    is dev.kobol.semantic.KobolType.MapType      -> "java/util/Map"
    is dev.kobol.semantic.KobolType.UuidType     -> "java/util/UUID"
    is dev.kobol.semantic.KobolType.JavaObjectType -> "java/lang/Object"
    else -> null
}

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
