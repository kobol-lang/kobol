package dev.kobol

import dev.kobol.parser.ast.*

// =============================================================================
//  Feature detection — walks the fully-parsed AST and determines which runtime
//  capabilities a Kobol program actually needs.
//
//  This feeds three outputs:
//    1. A human-readable dependency manifest written alongside .class files.
//    2. The JDK module list required for `jlink` custom-JRE packaging.
//    3. The third-party Maven coordinates to include in the deployment fat-JAR.
// =============================================================================

/** Capabilities detected in a compiled Kobol program. */
data class FeatureSet(
    /** At least one LOG statement present (SLF4J + java.logging). */
    val usesLog: Boolean,
    /** At least one HTTP CALL statement (outbound HTTP client). */
    val usesHttpClient: Boolean,
    /** At least one SERVER … END-SERVER block (embedded Javalin/Jetty server). */
    val usesHttpServer: Boolean,
    /** At least one CONNECT TO DATABASE / EXECUTE SQL statement. */
    val usesJdbc: Boolean,
    /** At least one NOSQL CONNECT statement (MongoDB driver). */
    val usesNoSql: Boolean,
    /** At least one CACHE CONNECT statement (Jedis/Redis client). */
    val usesCache: Boolean,
    /** CONCURRENT … END-CONCURRENT or FOR … PARALLEL blocks present. */
    val usesConcurrency: Boolean,
    /** VALIDATE statement present (throws KobolValidationError from runtime). */
    val usesValidation: Boolean,
    /** Any WRITE/PARSE JSON or XML statement (Jackson in stdlib). */
    val usesJsonXml: Boolean,
    /** Any DATE, TIME, or DATETIME typed variable in the data section. */
    val usesDateTime: Boolean,
    /** Any MONEY or DECIMAL typed variable (BigDecimal — part of java.base). */
    val usesBigDecimal: Boolean,
) {
    /** True if any stdlib feature is used (i.e. kobol-stdlib.jar is needed). */
    val stdlibRequired: Boolean get() =
        usesLog || usesHttpClient || usesHttpServer || usesJdbc ||
        usesNoSql || usesCache || usesJsonXml

    /** Minimum JDK modules required for `jlink` packaging. */
    val jdkModules: List<String> get() = buildList {
        add("java.base")
        // Kotlin stdlib uses sun.misc.Unsafe internally
        add("jdk.unsupported")
        // SLF4J-simple binds to java.util.logging when present
        if (usesLog) add("java.logging")
        if (usesJdbc) add("java.sql")
        // java.net.http module backing the HTTP client stdlib wrapper
        if (usesHttpClient) add("java.net.http")
        // Required by Netty (inside Jetty 11 embedded in Javalin)
        if (usesHttpServer) addAll(listOf("java.net.http", "jdk.crypto.ec"))
        // MongoDB driver uses the management MXBeans API
        if (usesNoSql) add("java.management")
    }.distinct().sorted()

    /** Kobol JARs required at runtime (coordinates without version for brevity). */
    val kobolJars: List<String> get() = buildList {
        add("dev.kobol:kobol-runtime")
        if (stdlibRequired) add("dev.kobol:kobol-stdlib")
    }

    /** Third-party Maven coordinates required at runtime. */
    val thirdPartyDeps: List<String> get() = buildList {
        if (usesHttpClient || usesHttpServer) add("io.javalin:javalin:${DepVersions.JAVALIN}")
        if (usesNoSql) add("org.mongodb:mongodb-driver-sync:${DepVersions.MONGODB}")
        if (usesCache) add("redis.clients:jedis:${DepVersions.JEDIS}")
    }
}

// =============================================================================
//  Detector
// =============================================================================

object FeatureDetector {

    fun detect(program: Program): FeatureSet {
        val stmts = mutableListOf<Statement>()
        for (proc in program.procedures) collectStatements(proc.body, stmts)
        for (test in program.tests)       collectStatements(test.body, stmts)
        for (tt   in program.tableTests) {
            collectStatements(tt.whenBlock, stmts)
            collectStatements(tt.thenBlock, stmts)
        }

        // Data-section type inspection (DataItem has a flat type: TypeSpec? field)
        val dataTypes = program.dataSection?.items?.mapNotNull { it.type } ?: emptyList()

        return FeatureSet(
            usesLog         = stmts.any { it is LogStatement },
            usesHttpClient  = stmts.any { it is HttpCallStatement },
            usesHttpServer  = stmts.any { it is ServerStatement },
            usesJdbc        = stmts.any { it is JdbcConnectStatement },
            usesNoSql       = stmts.any { it is NoSqlConnectStatement },
            usesCache       = stmts.any { it is CacheConnectStatement },
            usesConcurrency = stmts.any { it is ConcurrentBlock || it is ParallelForEachStatement },
            usesValidation  = stmts.any { it is ValidateStatement },
            usesJsonXml     = stmts.any {
                it is WriteJsonStatement || it is ParseJsonStatement ||
                it is WriteXmlStatement  || it is ParseXmlStatement
            },
            usesDateTime    = dataTypes.any {
                it is TypeSpec.DateType || it is TypeSpec.TimeType || it is TypeSpec.DateTimeType
            },
            usesBigDecimal  = dataTypes.any {
                it is TypeSpec.MoneyType || it is TypeSpec.DecimalType
            },
        )
    }

    // -------------------------------------------------------------------------
    //  Recursive statement collector
    // -------------------------------------------------------------------------

    private fun collectStatements(block: List<Statement>, out: MutableList<Statement>) {
        for (stmt in block) {
            out.add(stmt)
            when (stmt) {
                is IfStatement -> {
                    collectStatements(stmt.thenBranch, out)
                    stmt.elseIfClauses.forEach { collectStatements(it.body, out) }
                    stmt.elseBranch?.let { collectStatements(it, out) }
                }
                is WhileStatement          -> collectStatements(stmt.body, out)
                is ForEachStatement        -> collectStatements(stmt.body, out)
                is RepeatStatement         -> collectStatements(stmt.body, out)
                is WithPrecisionStatement  -> collectStatements(stmt.body, out)
                is TryStatement -> {
                    collectStatements(stmt.body, out)
                    stmt.handlers.forEach { collectStatements(it.body, out) }
                    stmt.ensure?.let { collectStatements(it, out) }
                }
                is ConcurrentBlock         -> stmt.branches.forEach { collectStatements(it, out) }
                is ParallelForEachStatement -> collectStatements(stmt.body, out)
                is MatchStatement -> {
                    stmt.whenClauses.forEach { collectStatements(it.body, out) }
                    stmt.otherwise?.let { collectStatements(it, out) }
                }
                is ReadStatement           -> stmt.atEnd?.let { collectStatements(it, out) }
                else -> { /* leaf statement — already added above */ }
            }
        }
    }

}

// =============================================================================
//  Manifest serialisation
// =============================================================================

/** Serialise a [FeatureSet] to a human-readable JSON manifest. */
fun FeatureSet.toManifestJson(programName: String): String {
    fun Boolean.jv() = if (this) "true" else "false"
    fun List<String>.jArr() = if (isEmpty()) "[]"
        else joinToString(",\n      ", prefix = "[\n      ", postfix = "\n    ]") { "\"$it\"" }

    return buildString {
        appendLine("{")
        appendLine("  \"program\": \"$programName\",")
        appendLine("  \"features\": {")
        appendLine("    \"log\": ${usesLog.jv()},")
        appendLine("    \"httpClient\": ${usesHttpClient.jv()},")
        appendLine("    \"httpServer\": ${usesHttpServer.jv()},")
        appendLine("    \"jdbc\": ${usesJdbc.jv()},")
        appendLine("    \"nosql\": ${usesNoSql.jv()},")
        appendLine("    \"cache\": ${usesCache.jv()},")
        appendLine("    \"concurrency\": ${usesConcurrency.jv()},")
        appendLine("    \"validation\": ${usesValidation.jv()},")
        appendLine("    \"jsonXml\": ${usesJsonXml.jv()},")
        appendLine("    \"dateTime\": ${usesDateTime.jv()},")
        appendLine("    \"bigDecimal\": ${usesBigDecimal.jv()}")
        appendLine("  },")
        appendLine("  \"jdkModules\": ${jdkModules.jArr()},")
        appendLine("  \"kobolJars\": ${kobolJars.jArr()},")
        appendLine("  \"thirdPartyDeps\": ${thirdPartyDeps.jArr()}")
        append("}")
    }
}

/** Print a compact summary of the detected feature set to stdout. */
fun FeatureSet.printSummary(programName: String) {
    println("kobolc: dependency analysis for '$programName'")
    println()
    println("  Features used:")
    if (usesLog)         println("    log")
    if (usesHttpClient)  println("    http-client")
    if (usesHttpServer)  println("    http-server")
    if (usesJdbc)        println("    jdbc")
    if (usesNoSql)       println("    nosql (MongoDB)")
    if (usesCache)       println("    cache (Redis)")
    if (usesConcurrency) println("    concurrency (virtual threads)")
    if (usesValidation)  println("    validation")
    if (usesJsonXml)     println("    json/xml")
    if (usesDateTime)    println("    date-time")
    if (usesBigDecimal)  println("    big-decimal")
    if (!usesLog && !usesHttpClient && !usesHttpServer && !usesJdbc &&
        !usesNoSql && !usesCache && !usesConcurrency && !usesValidation &&
        !usesJsonXml && !usesDateTime && !usesBigDecimal)
        println("    (none beyond java.base)")
    println()
    println("  JDK modules (for jlink):  ${jdkModules.joinToString(", ")}")
    println("  Kobol JARs:               ${kobolJars.joinToString(", ")}")
    if (thirdPartyDeps.isNotEmpty())
        println("  Third-party deps:         ${thirdPartyDeps.joinToString(", ")}")
    println()
    println("  jlink command hint:")
    println("    jlink --add-modules ${jdkModules.joinToString(",")} \\")
    println("          --output custom-jre")
    println()
    if (!stdlibRequired) {
        println("  This program needs only kobol-runtime.jar — no stdlib or third-party JARs.")
    }
}
