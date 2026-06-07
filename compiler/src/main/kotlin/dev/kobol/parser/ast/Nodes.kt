package dev.kobol.parser.ast

import dev.kobol.lexer.SourcePosition

// =============================================================================
//  Root
// =============================================================================

data class Program(
    val name: String,
    val version: String?,
    val author: String?,
    val imports: List<ImportDecl>,
    val records: List<RecordDecl>,
    val constants: List<ConstantDecl>,
    val dataSection: DataSection?,
    val fileSection: FileSection?,
    val procedures: List<ProcedureDecl>,
    val pos: SourcePosition,
    val configSection: ConfigSection? = null,
    val variants: List<VariantDecl> = emptyList(),
    val typeAliases: List<TypeAliasDecl> = emptyList(),
    val tests: List<TestDecl> = emptyList(),
    val namedConditions: List<NamedConditionDecl> = emptyList(),
    val moduleDecl: ModuleDecl? = null,
    val tableTests: List<TableTestDecl> = emptyList(),
    /** PROGRAM name in original source case (e.g. "DataTypes"); [name] is UPPERCASE. */
    val rawName: String = name,
)

// =============================================================================
//  Imports
// =============================================================================

data class ImportDecl(
    val qualifiedName: String,   // "java.time.LocalDate"
    val alias: String?,          // "LD" in "AS LD"
    val versionConstraint: String? = null,  // e.g. "2.x" or "2.1" from VERSION clause
    val pos: SourcePosition,
)

// =============================================================================
//  Module declaration (MODULE name VERSION "x.y": EXPORT ... END-MODULE)
// =============================================================================

/** MODULE kobol.billing VERSION "2.1": ... END-MODULE */
data class ModuleDecl(
    val name: String,           // "kobol.billing"
    val version: String?,       // "2.1"
    val exports: List<ExportDecl>,
    val pos: SourcePosition,
)

/** EXPORT PROCEDURE name / EXPORT RECORD name / EXPORT TYPE name / EXPORT VARIANT name */
data class ExportDecl(
    val kind: String,   // "PROCEDURE", "RECORD", "VARIANT", "TYPE"
    val name: String,
    val pos: SourcePosition,
)

// =============================================================================
//  Type Specifications
// =============================================================================

sealed class TypeSpec {
    data class IntegerType(val pos: SourcePosition) : TypeSpec()
    data class SmallIntType(val pos: SourcePosition) : TypeSpec()
    data class DecimalType(val precision: Int, val scale: Int, val pos: SourcePosition) : TypeSpec()
    data class MoneyType(val precision: Int, val scale: Int, val pos: SourcePosition) : TypeSpec()
    data class TextType(val maxLength: Int?, val sensitive: Boolean = false, val pos: SourcePosition) : TypeSpec()
    data class BooleanType(val pos: SourcePosition) : TypeSpec()
    data class DateType(val pos: SourcePosition) : TypeSpec()
    data class TimeType(val pos: SourcePosition) : TypeSpec()
    data class DateTimeType(val pos: SourcePosition) : TypeSpec()
    data class ListOf(val elementType: TypeSpec, val pos: SourcePosition) : TypeSpec()
    data class MapOf(val keyType: TypeSpec, val valueType: TypeSpec, val pos: SourcePosition) : TypeSpec()
    data class FutureOf(val elementType: TypeSpec, val pos: SourcePosition) : TypeSpec()
    data class NamedType(val name: String, val pos: SourcePosition) : TypeSpec()  // record ref
    data class UuidType(val pos: SourcePosition) : TypeSpec()
}

// =============================================================================
//  Test declarations (TEST "name": ... END-TEST)
// =============================================================================

data class TestDecl(
    val name: String,
    val body: List<Statement>,
    val pos: SourcePosition,
)

/**
 * Table-driven test:
 * TEST TABLE "name":
 *   COLUMNS: col1, col2, ...
 *   ROW: v1, v2, ...
 *   WHEN: body
 *   THEN: body
 * END-TEST
 */
data class TableTestDecl(
    val name: String,
    val columns: List<String>,
    val rows: List<List<Expression>>,
    val whenBlock: List<Statement>,
    val thenBlock: List<Statement>,
    val pos: SourcePosition,
)

// =============================================================================
//  Type aliases (DEFINE TYPE Name IS typespec)
// =============================================================================

data class TypeAliasDecl(
    val name: String,
    val target: TypeSpec,
    val pos: SourcePosition,
)

// =============================================================================
//  Record declarations
// =============================================================================

data class RecordDecl(
    val name: String,
    val fields: List<FieldDecl>,
    val pos: SourcePosition,
)

data class FieldDecl(
    val name: String,
    val type: TypeSpec,
    val conditions: List<ConditionDecl>,
    val pos: SourcePosition,
)

data class ConditionDecl(
    val name: String,       // e.g. "Active"
    val expr: Expression,   // the WHEN expression
    val pos: SourcePosition,
)

/** Top-level named boolean expression: CONDITION name WHEN expr */
data class NamedConditionDecl(
    val name: String,
    val expr: Expression,
    val pos: SourcePosition,
)

// =============================================================================
//  Constants
// =============================================================================

data class ConstantDecl(
    val name: String,
    val type: TypeSpec,
    val value: Expression,     // must be a literal at the call site
    val pos: SourcePosition,
)

// =============================================================================
//  Data section (working storage)
// =============================================================================

data class DataSection(
    val items: List<DataItem>,
    val pos: SourcePosition,
)

data class DataItem(
    val name: String,
    val type: TypeSpec?,          // null = infer from initializer
    val initializer: Expression?,
    val pos: SourcePosition,
)

// =============================================================================
//  File section
// =============================================================================

data class FileSection(
    val files: List<FileDecl>,
    val pos: SourcePosition,
)

enum class FileOrg { SEQUENTIAL, INDEXED, RELATIVE }
enum class FileFormat { TEXT, CSV, FIXED, BINARY }
enum class FileMode { INPUT, OUTPUT, EXTEND }

data class FileDecl(
    val name: String,
    val org: FileOrg,
    val format: FileFormat,
    val recordType: String,
    val defaultMode: FileMode?,
    val keyField: String?,
    val pos: SourcePosition,
    /** Original-case file name as written in source; used for the on-disk path (case-sensitive FS). */
    val rawName: String = name,
)

// =============================================================================
//  Procedures
// =============================================================================

data class ProcedureDecl(
    val name: String,
    val params: List<ProcParam>,
    val returnType: TypeSpec?,
    val body: List<Statement>,
    val pos: SourcePosition,
    /** True when declared with `EXPORT PROCEDURE` — makes the method public static in generated bytecode. */
    val exported: Boolean = false,
    /** True when declared with `ASYNC PROCEDURE` — body runs on a virtual thread, returns CompletableFuture. */
    val isAsync: Boolean = false,
    /** Deprecation message when declared `PROCEDURE name DEPRECATED "msg"`; null if not deprecated. */
    val deprecated: String? = null,
)

data class ProcParam(
    val name: String,
    val type: TypeSpec,
    val pos: SourcePosition,
)

// =============================================================================
//  Statements (sealed hierarchy)
// =============================================================================

sealed class Statement {
    abstract val pos: SourcePosition
}

data class MoveStatement(
    val from: Expression,
    val to: Reference,
    override val pos: SourcePosition,
) : Statement()

data class ComputeStatement(
    val target: Reference,
    val expr: Expression,
    override val pos: SourcePosition,
) : Statement()

// PUT value TO map WITH KEY key   (#12 — MAP insert/update)
data class MapPutStatement(
    val value: Expression,
    val map: Reference,
    val key: Expression,
    override val pos: SourcePosition,
) : Statement()

// GET map KEY key INTO dest        (#12 — MAP lookup)
data class MapGetStatement(
    val map: Reference,
    val key: Expression,
    val into: Reference,
    override val pos: SourcePosition,
) : Statement()

data class AddStatement(
    val operand: Expression,
    val target: Reference,
    val giving: Reference?,
    override val pos: SourcePosition,
) : Statement()

data class SubtractStatement(
    val operand: Expression,
    val from: Reference,
    val giving: Reference?,
    override val pos: SourcePosition,
) : Statement()

data class MultiplyStatement(
    val left: Expression,
    val right: Reference,
    val giving: Reference?,
    override val pos: SourcePosition,
) : Statement()

data class DivideStatement(
    val divisor: Expression,
    val into: Reference,
    val giving: Reference?,
    /** Optional per-expression rounding mode: HALF-EVEN, HALF-UP, HALF-DOWN, UP, DOWN, CEILING, FLOOR */
    val dividingMode: String? = null,
    override val pos: SourcePosition,
) : Statement()

/**
 * ROUND target TO scale [USING mode]
 *
 * In-place rounding of a DECIMAL or MONEY variable.
 *
 * @param target   the variable to round in place
 * @param scale    expression evaluating to the number of decimal places
 * @param mode     optional rounding mode string (e.g. "HALF-UP"); defaults to HALF-EVEN when null
 */
data class RoundStatement(
    val target: Reference,
    val scale: Expression,
    val mode: String? = null,
    override val pos: SourcePosition,
) : Statement()

data class DisplayStatement(
    val values: List<Expression>,
    override val pos: SourcePosition,
) : Statement()

data class PerformStatement(
    val procedureName: String,
    val args: List<Expression>,
    override val pos: SourcePosition,
    val moduleAlias: String? = null,   // set when "PERFORM Alias.ProcName" syntax is used
    val giving: Reference? = null,     // set when "PERFORM AsyncProc USING args GIVING future"
) : Statement()

/**
 * AWAIT future-var INTO result-var
 * Blocks until the CompletableFuture completes and stores the unwrapped value.
 */
data class AwaitStatement(
    val future: Reference,
    val into: Reference,
    override val pos: SourcePosition,
) : Statement()

data class IfStatement(
    val condition: Expression,
    val thenBranch: List<Statement>,
    val elseIfClauses: List<ElseIfClause>,
    val elseBranch: List<Statement>?,
    override val pos: SourcePosition,
) : Statement()

data class ElseIfClause(
    val condition: Expression,
    val body: List<Statement>,
    val pos: SourcePosition,
)

data class WhileStatement(
    val condition: Expression,
    val body: List<Statement>,
    override val pos: SourcePosition,
) : Statement()

data class ForEachStatement(
    val variable: String,
    val iterable: Expression,
    val body: List<Statement>,
    override val pos: SourcePosition,
) : Statement()

data class RepeatStatement(
    val count: Expression,
    val body: List<Statement>,
    override val pos: SourcePosition,
) : Statement()

data class OpenStatement(
    val fileName: String,
    val mode: FileMode,
    override val pos: SourcePosition,
) : Statement()

data class ReadStatement(
    val fileName: String,
    val into: Reference,
    val atEnd: List<Statement>?,
    override val pos: SourcePosition,
) : Statement()

data class WriteStatement(
    val fileName: String,
    val from: Reference,
    override val pos: SourcePosition,
) : Statement()

/** WRITE JSON expr TO filepath [PRETTY] */
data class WriteJsonStatement(
    val value: Expression,
    val filepath: Expression,
    val pretty: Boolean = false,
    override val pos: SourcePosition,
) : Statement()

/** WRITE XML expr TO filepath [PRETTY] */
data class WriteXmlStatement(
    val value: Expression,
    val filepath: Expression,
    val pretty: Boolean = false,
    override val pos: SourcePosition,
) : Statement()

/** PARSE JSON [FILE] source INTO ref [AS TypeName | AS LIST [OF TypeName]] */
data class ParseJsonStatement(
    val source: Expression,
    val fromFile: Boolean,
    val into: Reference,
    val asTypeName: String? = null,   // AS TypeName — compile-time type assertion
    val asList: Boolean = false,       // AS LIST — deserialise a JSON array
    override val pos: SourcePosition,
) : Statement()

/** PARSE XML [FILE] source INTO ref [AS TypeName | AS LIST [OF TypeName]] */
data class ParseXmlStatement(
    val source: Expression,
    val fromFile: Boolean,
    val into: Reference,
    val asTypeName: String? = null,
    val asList: Boolean = false,
    override val pos: SourcePosition,
) : Statement()

/** WITH PRECISION precisionName [ROUNDING mode]: body END-PRECISION */
data class WithPrecisionStatement(
    val precisionName: String,
    val roundingMode: String? = null,   // e.g. "HALF-UP"; null means use profile default
    val body: List<Statement>,
    override val pos: SourcePosition,
) : Statement()

data class CloseStatement(
    val fileName: String,
    override val pos: SourcePosition,
) : Statement()

data class TryStatement(
    val body: List<Statement>,
    val handlers: List<ExceptionHandler>,
    val ensure: List<Statement>?,
    override val pos: SourcePosition,
) : Statement()

data class ExceptionHandler(
    val exceptionType: String,
    val binding: String?,
    val body: List<Statement>,
    val pos: SourcePosition,
)

data class RaiseStatement(
    val exceptionType: String,
    val message: Expression?,
    override val pos: SourcePosition,
) : Statement()

data class ReturnStatement(
    val value: Expression?,
    override val pos: SourcePosition,
) : Statement()

data class StopRunStatement(
    val exitCode: Expression?,
    override val pos: SourcePosition,
) : Statement()

enum class SleepUnit { MILLISECONDS, SECONDS, MINUTES }

/** SLEEP amount MILLISECONDS|SECONDS|MINUTES */
data class SleepStatement(
    val amount: Expression,
    val unit: SleepUnit,
    override val pos: SourcePosition,
) : Statement()

/** ASSERT condition [WITH "message"] */
data class AssertStatement(
    val condition: Expression,
    val message: Expression?,
    override val pos: SourcePosition,
) : Statement()

/** MOCK ProcedureName RETURNS expr  (inside TEST / TEST TABLE blocks) */
data class MockStatement(
    val procedureName: String,
    val returns: Expression,
    override val pos: SourcePosition,
) : Statement()

data class CallStatement(
    val method: String,
    val args: List<Expression>,
    val giving: Reference?,
    override val pos: SourcePosition,
) : Statement()

// =============================================================================
//  Group 11 — HTTP client
// =============================================================================

/**
 * CALL http.GET  USING url [HEADERS "K: V"] [TIMEOUT n] GIVING response
 * CALL http.POST USING url [HEADERS "K: V"] [BODY payload] [TIMEOUT n] GIVING response
 * (also PUT, DELETE, PATCH)
 */
data class HttpCallStatement(
    val httpMethod: String,        // "GET" | "POST" | "PUT" | "DELETE" | "PATCH"
    val url: Expression,
    val headers: Expression?,
    val body: Expression?,
    val timeout: Expression?,
    val giving: Reference?,
    override val pos: SourcePosition,
) : Statement()

// =============================================================================
//  Group 11 — JDBC bridge
// =============================================================================

/** CALL jdbc.connect USING url [USER u] [PASSWORD p] */
data class JdbcConnectStatement(
    val url: Expression,
    val user: Expression?,
    val password: Expression?,
    override val pos: SourcePosition,
) : Statement()

/**
 * CALL jdbc.query USING sql [PARAMS p1, p2, ...] [INTO var] [AS LIST OF TypeName]
 * Returns a list of row maps (or typed records if AS LIST OF is specified).
 */
data class JdbcQueryStatement(
    val sql: Expression,
    val params: List<Expression>,
    val into: Reference?,
    val asTypeName: String?,
    override val pos: SourcePosition,
) : Statement()

/** CALL jdbc.execute USING sql [PARAMS p1, p2, ...] */
data class JdbcExecuteStatement(
    val sql: Expression,
    val params: List<Expression>,
    override val pos: SourcePosition,
) : Statement()

/** CALL jdbc.disconnect */
data class JdbcDisconnectStatement(
    override val pos: SourcePosition,
) : Statement()

// =============================================================================
//  Group 12 — REST server
// =============================================================================

/**
 * SERVER AT PORT n:
 *   ENDPOINT GET "/path":
 *     ... statements ...
 *   ENDPOINT POST "/path":
 *     ... statements ...
 * END-SERVER
 */
data class ServerStatement(
    val port: Expression,
    val endpoints: List<EndpointHandler>,
    override val pos: SourcePosition,
) : Statement()

data class EndpointHandler(
    val method: String,       // "GET" | "POST" | "PUT" | "DELETE" | "PATCH"
    val path: String,
    val pathParams: List<String>, // names extracted from {param} placeholders in path
    val body: List<Statement>,
    val pos: SourcePosition,
)

/**
 * RESPOND WITH expr [AS JSON]
 * Returns the value as the HTTP response body.  Terminates the current endpoint handler.
 */
data class RespondStatement(
    val value: Expression,
    val asJson: Boolean,
    val statusCode: Expression?, // optional STATUS n clause (default 200)
    override val pos: SourcePosition,
) : Statement()


/**
 * Inline local variable declaration inside a procedure body (ergonomics §17.3).
 * `LET name [: type] = expr`
 * Desugared from [ComputeStatement] when a type annotation is present on the LHS,
 * indicating a new variable rather than an assignment to an existing one.
 */
data class LocalVarDecl(
    val name: String,
    val type: TypeSpec?,          // null = infer from initializer (§17.4)
    val initializer: Expression,
    override val pos: SourcePosition,
) : Statement()

// =============================================================================
//  Expressions (sealed hierarchy)
// =============================================================================

sealed class Expression {
    abstract val pos: SourcePosition
}

data class Literal(
    val value: Any,
    val kind: LiteralKind,
    override val pos: SourcePosition,
) : Expression()

enum class LiteralKind { INTEGER, DECIMAL, STRING, BOOLEAN }

/** A simple variable reference or a field access chain: `a`, `customer.name`, `a.b.c`. */
data class Reference(
    val parts: List<String>,
    override val pos: SourcePosition,
) : Expression() {
    val name: String get() = parts.first()
    val isFieldAccess: Boolean get() = parts.size > 1
}

data class BinaryExpr(
    val op: BinaryOp,
    val left: Expression,
    val right: Expression,
    override val pos: SourcePosition,
) : Expression()

data class UnaryExpr(
    val op: UnaryOp,
    val operand: Expression,
    override val pos: SourcePosition,
) : Expression()

data class BuiltinCall(
    val name: String,
    val args: List<Expression>,
    override val pos: SourcePosition,
) : Expression()

/**
 * String template expression (ergonomics §17.1).
 * "Hello, {name}! You owe ${amount}."
 * Parts alternate between raw string segments and embedded expressions.
 */
data class StringTemplateExpr(
    val parts: List<StringTemplatePart>,
    override val pos: SourcePosition,
) : Expression()

sealed class StringTemplatePart {
    data class RawText(val text: String) : StringTemplatePart()
    data class Interpolated(val expr: Expression) : StringTemplatePart()
}

/**
 * Record / struct literal (ergonomics §17.11).
 * `Invoice { invoice-id: 1, customer-name: "Acme", amount: 500.00 }`
 */
data class RecordLiteralExpr(
    val typeName: String,
    val fields: List<RecordLiteralField>,
    override val pos: SourcePosition,
) : Expression()

data class RecordLiteralField(
    val name: String,
    val value: Expression,
    val pos: SourcePosition,
)

/**
 * Construct an arbitrary classpath / 3rd-party object (interop, F12).
 * `NEW StringBuilder WITH "hi"` → JVM `NEW` + `<init>(...)`. The owner string keeps the
 * original source case ("StringBuilder", "java.util.ArrayList") and is resolved to a JVM
 * class the same way `CALL` resolves a static owner (import alias / stdlib / java.lang / FQN).
 * Result type is JAVA-OBJECT; arguments are positional, descriptors inferred Kobol-side
 * (no classpath read — overload truth waits on the E2 interop engine).
 */
data class NewExpr(
    val owner: String,
    val args: List<Expression>,
    override val pos: SourcePosition,
) : Expression()

/**
 * Interop method call in EXPRESSION position (F14): `COMPUTE x = CALL s.substring WITH 1`,
 * `LET n = CALL Math.max WITH a, b`. Mirrors [CallStatement] but yields a value: the method's
 * REAL return type, resolved at type-check time off the compile classpath (E2). [method] keeps
 * the dotted owner.method in original source case ("Math.max", "s.substring"); resolution of the
 * owner + return descriptor is shared with both the type checker and codegen so the inferred
 * static type and the emitted bytecode can never disagree (no type-check-clean→runtime-crash).
 */
data class CallExpr(
    val method: String,
    val args: List<Expression>,
    override val pos: SourcePosition,
) : Expression()

/**
 * A named argument in a PERFORM / DO call (ergonomics §17.12).
 * Wraps a regular expression with the declared parameter name.
 */
data class NamedArgument(
    val paramName: String,
    val value: Expression,
    override val pos: SourcePosition,
) : Expression()

/** Pipeline expression: `list FILTER WHERE ... TRANSFORM TO ... SUM` */
data class PipelineExpr(
    val source: Expression,
    val stages: List<PipelineStage>,
    override val pos: SourcePosition,
) : Expression()

sealed class PipelineStage {
    abstract val pos: SourcePosition
    data class FilterStage(val condition: Expression, override val pos: SourcePosition) : PipelineStage()
    data class TransformStage(val field: String, override val pos: SourcePosition) : PipelineStage()
    data class SumStage(override val pos: SourcePosition) : PipelineStage()
    data class SortStage(val field: String, val descending: Boolean, override val pos: SourcePosition) : PipelineStage()
    data class TakeStage(val count: Expression, override val pos: SourcePosition) : PipelineStage()
}

enum class BinaryOp {
    ADD, SUBTRACT, MULTIPLY, DIVIDE, POWER,
    EQ, NEQ, LT, GT, LEQ, GEQ,
    AND, OR,
}

enum class UnaryOp {
    NEGATE,   // unary minus
    NOT,
}

// =============================================================================
//  Concurrency & Observability
// =============================================================================

/** Structured log levels. */
enum class LogLevel { TRACE, DEBUG, INFO, WARN, ERROR }

data class LogKvPair(
    val key: String,
    val value: Expression,
    val pos: SourcePosition,
)

/**
 * LOG INFO "message" [WITH key: expr key: expr ...]
 * Emits SLF4J calls.
 */
data class LogStatement(
    val level: LogLevel,
    val message: Expression,
    val kvPairs: List<LogKvPair>,
    override val pos: SourcePosition,
) : Statement()

enum class ConcurrentFailMode { WAIT_ALL, FAIL_FAST }

/**
 * CONCURRENT: [SCOPE name:] DO x, DO y ... WAIT ALL [OR FAIL]
 */
data class ConcurrentBlock(
    val scope: String?,
    val branches: List<List<Statement>>,
    val failMode: ConcurrentFailMode,
    override val pos: SourcePosition,
) : Statement()

/**
 * FOR EACH var IN iterable PARALLEL [MAX-THREADS n]: body END-FOR
 */
data class ParallelForEachStatement(
    val variable: String,
    val iterable: Expression,
    val maxThreads: Expression?,
    val body: List<Statement>,
    override val pos: SourcePosition,
) : Statement()

/**
 * ASYNC PROCEDURE — decorator flag for ProcedureDecl.
 * Parsed identically to ProcedureDecl; the isAsync flag signals virtual-thread dispatch.
 */
data class AsyncProcedureDecl(
    val name: String,
    val params: List<ProcParam>,
    val returnType: TypeSpec?,
    val body: List<Statement>,
    val pos: SourcePosition,
)

// =============================================================================
//  Security, Validation & Configuration
// =============================================================================

/** CONFIG section — environment-variable-backed program configuration. */
data class ConfigSection(
    val items: List<ConfigItem>,
    val pos: SourcePosition,
)

data class ConfigItem(
    val name: String,
    val type: TypeSpec,
    val envVar: String,
    val required: Boolean,
    val default: Expression?,
    val constraint: ValidationConstraint?,
    val pos: SourcePosition,
)

/** VALIDATE target: constraints END-VALIDATE */
data class ValidateStatement(
    val target: Reference,
    val constraints: List<ValidationConstraint>,
    override val pos: SourcePosition,
) : Statement()

sealed class ValidationConstraint {
    data class MustBe(
        val op: String,           // ">", ">=", "<", "<=", "=", "<>"
        val value: Expression,
        val failMsg: String?,
        val pos: SourcePosition,
    ) : ValidationConstraint()
    data class MustMatch(
        val pattern: String,
        val failMsg: String?,
        val pos: SourcePosition,
    ) : ValidationConstraint()
    data class MustNotBe(
        val kind: String,         // "EMPTY", "BLANK", "NULL"
        val failMsg: String?,
        val pos: SourcePosition,
    ) : ValidationConstraint()
    data class MustLength(
        val op: String,           // ">=", "<=", ">", "<"
        val length: Int,
        val failMsg: String?,
        val pos: SourcePosition,
    ) : ValidationConstraint()
    data class MustSatisfy(
        val procName: String,
        val failMsg: String?,
        val pos: SourcePosition,
    ) : ValidationConstraint()
}

/** VARIANT TypeName IS Case1 | Case2 WITH field : Type ... END-VARIANT */
data class VariantDecl(
    val name: String,
    val cases: List<VariantCase>,
    val pos: SourcePosition,
)

data class VariantCase(
    val name: String,
    val fields: List<FieldDecl>,
    val pos: SourcePosition,
)

/** MATCH expr: WHEN pattern: body ... [OTHERWISE: body] END-MATCH */
data class MatchStatement(
    val subject: Expression,
    val whenClauses: List<WhenClause>,
    val otherwise: List<Statement>?,
    override val pos: SourcePosition,
) : Statement()

data class WhenClause(
    val pattern: MatchPattern,
    val body: List<Statement>,
    val pos: SourcePosition,
)

sealed class MatchPattern {
    /** Literal: WHEN "FOOD": or WHEN 1: */
    data class LiteralPattern(val value: Expression, val pos: SourcePosition) : MatchPattern()
    /** Variant case match: WHEN Pending: or WHEN Active WITH order-date: */
    data class VariantPattern(
        val caseName: String,
        val bindings: List<String>,   // names bound from WITH fields
        val pos: SourcePosition,
    ) : MatchPattern()
    /** Range: WHEN 1..10: or WHEN "A".."Z": */
    data class RangePattern(
        val from: Expression,
        val to: Expression,
        val pos: SourcePosition,
    ) : MatchPattern()
    /** Type: WHEN TEXT AS name: or WHEN INTEGER AS n: */
    data class TypePattern(
        val typeName: String,      // TEXT, INTEGER, DECIMAL, BOOLEAN, DATE, UUID
        val binding: String?,      // optional AS varName
        val pos: SourcePosition,
    ) : MatchPattern()
    /** Guard: WHEN pattern IF condition — inner must match AND guard must be true */
    data class GuardPattern(
        val inner: MatchPattern,
        val guard: Expression,
        val pos: SourcePosition,
    ) : MatchPattern()
}

// =============================================================================
//  Group 13 — NoSQL document store (MongoDB)
// =============================================================================

/** NOSQL CONNECT TO url DATABASE db-name */
data class NoSqlConnectStatement(
    val url: Expression,
    val database: Expression,
    override val pos: SourcePosition,
) : Statement()

data class NoSqlDisconnectStatement(override val pos: SourcePosition) : Statement()

/**
 * FIND [ONE] IN collection [WHERE expr] GIVING var
 *
 * findOne = false  →  result is List<Map>
 * findOne = true   →  result is Map (first match) or null
 */
data class NoSqlFindStatement(
    val collection: Expression,
    val filter: Expression?,
    val findOne: Boolean,
    val giving: Reference,
    override val pos: SourcePosition,
) : Statement()

/** SAVE TO collection USING document-map [UPSERT] */
data class NoSqlSaveStatement(
    val collection: Expression,
    val document: Reference,
    val upsert: Boolean,
    override val pos: SourcePosition,
) : Statement()

/** DELETE FROM collection WHERE expr */
data class NoSqlDeleteStatement(
    val collection: Expression,
    val filter: Expression,
    override val pos: SourcePosition,
) : Statement()

/** COUNT IN collection [WHERE expr] GIVING var */
data class NoSqlCountStatement(
    val collection: Expression,
    val filter: Expression?,
    val giving: Reference,
    override val pos: SourcePosition,
) : Statement()

// =============================================================================
//  Group 14 — Cache / key-value store (Redis)
// =============================================================================

/** CACHE CONNECT TO url */
data class CacheConnectStatement(
    val url: Expression,
    override val pos: SourcePosition,
) : Statement()

data class CacheDisconnectStatement(override val pos: SourcePosition) : Statement()

/** CACHE GET key GIVING value */
data class CacheGetStatement(
    val key: Expression,
    val giving: Reference,
    override val pos: SourcePosition,
) : Statement()

/**
 * CACHE SET key TO value [EXPIRES IN n SECONDS]
 * ttlSeconds = null means no expiry.
 */
data class CacheSetStatement(
    val key: Expression,
    val value: Expression,
    val ttlSeconds: Expression?,
    override val pos: SourcePosition,
) : Statement()

/** CACHE DELETE key */
data class CacheDeleteStatement(
    val key: Expression,
    override val pos: SourcePosition,
) : Statement()

/** CACHE EXISTS key GIVING found-flag */
data class CacheExistsStatement(
    val key: Expression,
    val giving: Reference,
    override val pos: SourcePosition,
) : Statement()
