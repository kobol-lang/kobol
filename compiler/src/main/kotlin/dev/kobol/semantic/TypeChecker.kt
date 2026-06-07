package dev.kobol.semantic

import dev.kobol.diagnostic.DiagnosticBag
import dev.kobol.diagnostic.didYouMean
import dev.kobol.lexer.SourcePosition
import dev.kobol.parser.ast.*
import java.util.IdentityHashMap

/**
 * Semantic analyser.
 *
 * Walks the AST in a single pass and:
 *   1. Populates the [SymbolTable] with all program-level symbols
 *      (records, constants, data items, procedures).
 *   2. Resolves all name references against the symbol table.
 *   3. Type-checks all expressions and statements.
 *   4. Infers types for `LET x = expr` (ER-5).
 *   5. Emits ER-1-style diagnostics via [DiagnosticBag] (ER-2: "did you mean?").
 *
 * Returns a [SemanticResult] containing the annotated AST and diagnostics.
 * Does **not** throw; callers inspect [DiagnosticBag.hasErrors].
 */
class TypeChecker(
    private val sourceLines: List<String> = emptyList(),
    val moduleRegistry: ModuleRegistry = ModuleRegistry(),
) {

    val symbols = SymbolTable()
    val diagnostics = DiagnosticBag()

    /** Type aliases from DEFINE TYPE declarations: alias name → resolved KobolType. */
    private val typeAliases = mutableMapOf<String, KobolType>()

    /** Variant case construction index: UPPERCASE case name → (variant name, case info). #18 */
    private val variantCaseIndex = mutableMapOf<String, Pair<String, Symbol.VariantSymbol.CaseInfo>>()

    /** Type annotations attached to every Expression node (keyed by identity). */
    private val exprTypes = IdentityHashMap<Expression, KobolType>()

    /** Import alias (UPPERCASE) → JVM binary class path; built in [analyze] from program imports. */
    private var importAliasMap: Map<String, String> = emptyMap()

    /**
     * Classpath-aware interop resolver (E2), shared design with codegen. Lazily built from
     * [dev.kobol.KobolHome.compileClasspath] (runtime libs + the project's resolved user deps, F27),
     * so a `CALL` expression's inferred return type (here) is computed against the exact methods
     * codegen will link (F14). Lazy → programs with no interop CALL expression pay nothing.
     */
    private val interopResolver: ClasspathSymbolResolver? by lazy {
        runCatching { ClasspathSymbolResolver(dev.kobol.KobolHome.compileClasspath()) }.getOrNull()
    }

    fun analyze(program: Program) {
        // Build the import alias map up front (UPPERCASE alias → slashed JVM path) — same construction
        // codegen uses — so CALL-expression owner resolution agrees with codegen (F14).
        importAliasMap = program.imports.associate { imp ->
            val alias = (imp.alias ?: imp.qualifiedName.substringAfterLast('.')).uppercase()
            alias to imp.qualifiedName.replace('.', '/')
        }
        // Pass 0: register type aliases so they are available during type resolution
        for (alias in program.typeAliases) {
            typeAliases[alias.name] = toKobolType(alias.target)
        }
        // Pass 1: register all top-level symbols (forward declarations)
        registerTopLevel(program)
        // Pass 1.5: bind module aliases before body-checking so cross-module PERFORMs resolve
        registerModuleImports(program)
        // Pass 1.5: type-check named condition expressions
        for (cond in program.namedConditions) {
            val t = inferType(cond.expr)
            if (t !is KobolType.BooleanType) {
                error("E105", "CONDITION '${cond.name}' expression must be BOOLEAN, got $t", cond.pos)
            }
        }
        // Pass 2: check bodies
        for (proc in program.procedures) checkProcedure(proc)
        // Pass 2b: check test blocks
        for (test in program.tests) {
            symbols.enterScope("test:${test.name}")
            for (stmt in test.body) checkStatement(stmt, null)
            symbols.exitScope()
        }
        // Pass 2c: check table test blocks
        for (tt in program.tableTests) checkTableTest(tt)
        // Pass 3: cross-program checks
        checkProgramInvariants(program)
        // Pass 4: module/import lint
        checkModuleDecl(program)
        checkUnusedImports(program)
    }

    /** Bind any IMPORT declarations that match known Kobol modules in the registry. */
    private fun registerModuleImports(program: Program) {
        if (moduleRegistry.isEmpty()) return
        for (imp in program.imports) {
            val alias = imp.alias ?: imp.qualifiedName.substringAfterLast('.')
            val found = moduleRegistry.registerAlias(alias, imp.qualifiedName)
            if (found && imp.versionConstraint != null) {
                val info = moduleRegistry.resolveByAlias(alias) ?: continue
                if (!satisfiesVersionConstraint(info.version, imp.versionConstraint)) {
                    error(
                        // E219 (was E215): IMPORT version-constraint mismatch. E215 is reserved for
                        // the async-GIVING family (E215 sync-GIVING misuse, E216/E217/E218) — F5.
                        "E219",
                        "IMPORT '${imp.qualifiedName}' VERSION \"${imp.versionConstraint}\" not satisfied" +
                            " — registered version is \"${info.version ?: "unknown"}\"",
                        imp.pos,
                    )
                }
            }
        }
    }

    /**
     * Checks whether [registeredVersion] satisfies [constraint].
     * Rules (COBOL-friendly, simple):
     *  - "2.x" or "2.*" → registered must start with "2."
     *  - "2.1"           → registered must equal "2.1"
     *  - null constraint  → always satisfied
     * O(V) where V = version string length — negligible.
     */
    private fun satisfiesVersionConstraint(registeredVersion: String?, constraint: String): Boolean {
        if (registeredVersion == null) return true   // unversioned module: no check
        return if (constraint.endsWith(".x") || constraint.endsWith(".*")) {
            val major = constraint.dropLast(2)
            registeredVersion.startsWith("$major.")
        } else {
            registeredVersion == constraint
        }
    }

    private fun checkModuleDecl(program: Program) {
        val mod = program.moduleDecl ?: return
        val knownNames = (program.procedures.map { it.name } +
                          program.records.map { it.name } +
                          program.variants.map { it.name } +
                          program.typeAliases.map { it.name }).toSet()
        for (exp in mod.exports) {
            if (exp.name !in knownNames) {
                error("E210", "MODULE EXPORT references unknown name '${exp.name}'", exp.pos)
            }
        }
    }

    private fun checkUnusedImports(program: Program) {
        // Simple heuristic: if the alias (or last name segment) never appears in
        // the source text of any procedure, warn. We check against symbol table
        // resolution — any referenced Java type would have been resolved.
        for (imp in program.imports) {
            val refName = imp.alias ?: imp.qualifiedName.substringAfterLast('.')
            // Skip stdlib-style qualified names that have no alias (java.* etc)
            if (imp.alias == null && imp.qualifiedName.contains('.')) continue
            // Skip imports that resolved as Kobol module aliases
            if (moduleRegistry.isModuleAlias(refName)) continue
            if (symbols.resolve(refName) == null) {
                warning("W010", "Unused import '${imp.qualifiedName}'${if (imp.alias != null) " AS ${imp.alias}" else ""}", imp.pos)
            }
        }
    }

    private fun checkTableTest(tt: TableTestDecl) {
        symbols.enterScope("table-test:${tt.name}")
        // Infer column types from the first row if available
        tt.columns.forEachIndexed { i, col ->
            val colType = tt.rows.firstOrNull()?.getOrNull(i)?.let { inferType(it) }
                ?: KobolType.UnknownType
            symbols.define(Symbol.Variable(col, colType, mutable = false, pos = tt.pos))
        }
        // Validate each row has the right number of values; skip type-checking
        // malformed rows so we don't cascade false type errors on top of E211.
        val expectedCols = tt.columns.size
        for ((ri, row) in tt.rows.withIndex()) {
            if (row.size != expectedCols) {
                error("E211", "TABLE test '${tt.name}' row $ri has ${row.size} values but $expectedCols columns expected", tt.pos)
            } else {
                row.forEach { checkExpr(it) }
            }
        }
        for (stmt in tt.whenBlock) checkStatement(stmt, null)
        for (stmt in tt.thenBlock) checkStatement(stmt, null)
        symbols.exitScope()
    }

    private fun checkProgramInvariants(program: Program) {
        // W001: missing MAIN procedure (not required for script mode or library modules)
        // Suppress for programs that declare a MODULE block — they are library modules, not apps.
        val hasMain = program.procedures.any { it.name == "MAIN" }
        if (!hasMain && program.procedures.isNotEmpty() && program.moduleDecl == null) {
            warning("W001", "Program '${program.name}' has no MAIN procedure — entry point is the first procedure '${program.procedures[0].name}'", program.pos)
        }
    }

    fun typeOf(expr: Expression): KobolType = exprTypes[expr] ?: KobolType.UnknownType

    // -------------------------------------------------------------------------
    // Top-level registration
    // -------------------------------------------------------------------------

    private fun registerTopLevel(program: Program) {
        // VARIANT declarations first — DATA section fields may reference variant types
        for (variantDecl in program.variants) {
            if (symbols.resolveInCurrentScope(variantDecl.name) != null) {
                error("E012", "Duplicate name '${variantDecl.name}': VARIANT already declared", variantDecl.pos); continue
            }
            val cases = variantDecl.cases.map { case ->
                val fields = LinkedHashMap<String, KobolType>()
                for (f in case.fields) fields[f.name] = toKobolType(f.type)
                Symbol.VariantSymbol.CaseInfo(case.name, fields)
            }
            symbols.define(Symbol.VariantSymbol(variantDecl.name, cases, variantDecl.pos))
            for (c in cases) variantCaseIndex[c.name.uppercase()] = variantDecl.name to c   // #18
        }

        // Records
        for (rec in program.records) {
            if (symbols.resolveInCurrentScope(rec.name) != null) {
                error("E012", "Duplicate name '${rec.name}': RECORD already declared", rec.pos); continue
            }
            val fields = LinkedHashMap<String, KobolType>()
            val conditions = mutableMapOf<String, String>()
            for (f in rec.fields) {
                if (fields.containsKey(f.name)) {
                    error("E013", "Duplicate field '${f.name}' in RECORD '${rec.name}'", f.pos); continue
                }
                fields[f.name] = toKobolType(f.type)
                for (cond in f.conditions) conditions[cond.name] = f.name
            }
            symbols.define(Symbol.RecordSymbol(rec.name, fields, conditions, rec.pos))
        }

        // Constants
        for (c in program.constants) {
            if (symbols.resolveInCurrentScope(c.name) != null) {
                error("E012", "Duplicate name '${c.name}': DEFINE already declared", c.pos); continue
            }
            val kType = toKobolType(c.type)
            symbols.define(Symbol.Constant(c.name, kType, "<const>", c.pos))
        }

        // Named conditions
        for (cond in program.namedConditions) {
            if (symbols.resolveInCurrentScope(cond.name) != null) {
                error("E012", "Duplicate name '${cond.name}': CONDITION already declared", cond.pos); continue
            }
            symbols.define(Symbol.NamedCondition(cond.name, cond.expr, cond.pos))
        }

        // Data section
        program.dataSection?.items?.forEach { item ->
            if (symbols.resolveInCurrentScope(item.name) != null) {
                error("E012", "Duplicate name '${item.name}' in DATA section", item.pos); return@forEach
            }
            val kType = if (item.type != null) toKobolType(item.type)
                        else item.initializer?.let { inferType(it) } ?: KobolType.UnknownType
            // #19: temporal types have NO implicit default (the spec no longer
            // promises "current date" — that was COBOL hidden state, §1.7). An
            // uninitialized DATE/TIME/DATETIME is null and NPEs on first use, so
            // flag it at the declaration site instead of leaving a runtime landmine.
            if (item.initializer == null &&
                (kType is KobolType.DateType || kType is KobolType.TimeType || kType is KobolType.DateTimeType)) {
                val tn = when (kType) {
                    is KobolType.DateType     -> "DATE"
                    is KobolType.TimeType     -> "TIME"
                    is KobolType.DateTimeType -> "DATETIME"
                }
                warning("W019", "'${item.name}' ($tn) has no initializer and no implicit default; it is null " +
                    "until assigned. Initialize it explicitly (e.g. `${item.name} : DATE = TODAY`) to avoid a " +
                    "NullPointerException on first use.", item.pos)
            }
            symbols.define(Symbol.Variable(item.name, kType, mutable = true, pos = item.pos))
        }

        // File declarations
        program.fileSection?.files?.forEach { fd ->
            if (symbols.resolveInCurrentScope(fd.name) != null) {
                error("E012", "Duplicate name '${fd.name}': FILE already declared", fd.pos); return@forEach
            }
            val recType = if (fd.recordType.isNotEmpty()) KobolType.RecordRefType(fd.recordType)
                          else KobolType.UnknownType
            symbols.define(Symbol.Variable(fd.name, KobolType.ListType(recType), mutable = false, pos = fd.pos))
        }

        // CONFIG section — register items as read-only variables resolved at startup
        program.configSection?.items?.forEach { item ->
            if (symbols.resolveInCurrentScope(item.name) != null) {
                error("E012", "Duplicate name '${item.name}' in CONFIG section", item.pos); return@forEach
            }
            val kType = toKobolType(item.type)
            symbols.define(Symbol.Variable(item.name, kType, mutable = false, pos = item.pos))
        }

        // Procedures (signatures only — bodies checked later)
        for (proc in program.procedures) {
            if (symbols.resolveInCurrentScope(proc.name) != null) {
                error("E012", "Duplicate PROCEDURE name '${proc.name}'", proc.pos); continue
            }
            val params = proc.params.map { Symbol.ProcedureSymbol.Param(it.name, toKobolType(it.type)) }
            val ret    = proc.returnType?.let { toKobolType(it) }
            symbols.define(Symbol.ProcedureSymbol(proc.name, params, ret, proc.pos, isAsync = proc.isAsync, deprecated = proc.deprecated))
        }
    }

    // -------------------------------------------------------------------------
    // Procedure body
    // -------------------------------------------------------------------------

    private fun checkProcedure(proc: ProcedureDecl) {
        symbols.enterScope(proc.name)
        // Register parameters in procedure scope
        for (p in proc.params) {
            symbols.define(Symbol.Variable(p.name, toKobolType(p.type), mutable = false, pos = p.pos))
        }
        val expectedReturn = proc.returnType?.let { toKobolType(it) }
        checkBlock(proc.body, expectedReturn)
        symbols.exitScope()
    }

    private fun checkBlock(stmts: List<Statement>, returnType: KobolType?) {
        for (stmt in stmts) checkStatement(stmt, returnType)
    }

    // -------------------------------------------------------------------------
    // Statement checking
    // -------------------------------------------------------------------------

    private fun checkStatement(stmt: Statement, returnType: KobolType?) {
        when (stmt) {
            is MoveStatement     -> checkMove(stmt)
            is ComputeStatement  -> checkCompute(stmt)
            is MapPutStatement   -> checkMapPut(stmt)
            is MapGetStatement   -> checkMapGet(stmt)
            is LocalVarDecl      -> checkLocalVarDecl(stmt)
            is AddStatement      -> checkArithAssign(stmt.operand, stmt.target, stmt.giving, stmt.pos)
            is SubtractStatement -> checkArithAssign(stmt.operand, stmt.from, stmt.giving, stmt.pos)
            is MultiplyStatement -> checkArithAssign(stmt.left, stmt.right, stmt.giving, stmt.pos)
            is DivideStatement   -> checkArithAssign(stmt.divisor, stmt.into, stmt.giving, stmt.pos)
            is DisplayStatement  -> stmt.values.forEach { checkExpr(it) }
            is PerformStatement  -> checkPerform(stmt)
            is CallStatement     -> checkCall(stmt)
            is AwaitStatement    -> checkAwait(stmt)
            is IfStatement       -> {
                val condType = checkExpr(stmt.condition)
                if (condType != KobolType.BooleanType && condType != KobolType.UnknownType) {
                    warning("W002", "IF condition should be BOOLEAN, got $condType", stmt.pos)
                }
                checkBlock(stmt.thenBranch, returnType)
                for (ei in stmt.elseIfClauses) {
                    checkExpr(ei.condition)
                    checkBlock(ei.body, returnType)
                }
                stmt.elseBranch?.let { checkBlock(it, returnType) }
            }
            is WhileStatement    -> { checkExpr(stmt.condition); checkBlock(stmt.body, returnType) }
            is ForEachStatement  -> {
                val iterType = checkExpr(stmt.iterable)
                val elemType = when (iterType) {
                    is KobolType.ListType -> iterType.elementType
                    KobolType.UnknownType -> KobolType.UnknownType
                    else -> { warning("W003", "FOR EACH expects LIST, got $iterType", stmt.pos); KobolType.UnknownType }
                }
                symbols.enterScope("FOR-${stmt.variable}")
                symbols.define(Symbol.Variable(stmt.variable, elemType, mutable = false, stmt.pos))
                checkBlock(stmt.body, returnType)
                symbols.exitScope()
            }
            is RepeatStatement   -> { checkExpr(stmt.count); checkBlock(stmt.body, returnType) }
            is TryStatement      -> {
                checkBlock(stmt.body, returnType)
                for (h in stmt.handlers) {
                    symbols.enterScope("ON-${h.exceptionType}")
                    if (h.binding != null) {
                        symbols.define(Symbol.Variable(h.binding, KobolType.JavaObjectType(), mutable = false, h.pos))
                    }
                    checkBlock(h.body, returnType)
                    symbols.exitScope()
                }
                stmt.ensure?.let { checkBlock(it, returnType) }
            }
            is RaiseStatement    -> stmt.message?.let { checkExpr(it) }
            is ReturnStatement   -> {
                if (returnType == null) {
                    error("E010", "RETURN used in procedure with no RETURNING clause", stmt.pos)
                } else {
                    stmt.value?.let { v ->
                        val vt = checkExpr(v)
                        if (!vt.isAssignableTo(returnType)) {
                            error("E011", "RETURN type $vt does not match declared RETURNING $returnType", stmt.pos)
                        }
                    }
                }
            }
            is StopRunStatement  -> stmt.exitCode?.let { checkExpr(it) }
            is OpenStatement     -> resolveOrError(stmt.fileName, stmt.pos)
            is ReadStatement     -> { resolveOrError(stmt.fileName, stmt.pos); stmt.atEnd?.let { checkBlock(it, returnType) } }
            is WriteStatement    -> resolveOrError(stmt.fileName, stmt.pos)
            is WriteJsonStatement -> {
                checkExpr(stmt.value)
                checkExpr(stmt.filepath)
            }
            is CloseStatement    -> resolveOrError(stmt.fileName, stmt.pos)

            is LogStatement -> {
                checkExpr(stmt.message)
                stmt.kvPairs.forEach { checkExpr(it.value) }
            }

            is ConcurrentBlock -> {
                stmt.branches.forEach { branch -> checkBlock(branch, returnType) }
            }

            is ParallelForEachStatement -> {
                val iterType = checkExpr(stmt.iterable)
                val elemType = (iterType as? KobolType.ListType)?.elementType ?: KobolType.UnknownType
                symbols.enterScope("parallel-for-${stmt.variable}")
                symbols.define(Symbol.Variable(stmt.variable, elemType, mutable = false, pos = stmt.pos))
                stmt.maxThreads?.let { checkExpr(it) }
                checkBlock(stmt.body, returnType)
                symbols.exitScope()
            }

            is ValidateStatement -> {
                val targetType = resolveRefType(stmt.target)
                for (constraint in stmt.constraints) checkValidationConstraint(constraint, targetType, stmt.pos)
            }

            is MatchStatement -> checkMatch(stmt, returnType)

            is SleepStatement -> {
                val amountType = checkExpr(stmt.amount)
                if (amountType !is KobolType.IntegerType) {
                    error("E101", "SLEEP amount must be INTEGER, got $amountType", stmt.pos)
                }
            }

            is AssertStatement -> {
                val condType = checkExpr(stmt.condition)
                if (condType !is KobolType.BooleanType && condType !is KobolType.UnknownType) {
                    error("E102", "ASSERT condition must be BOOLEAN, got $condType", stmt.pos)
                }
                stmt.message?.let { checkExpr(it) }
            }

            is MockStatement -> {
                if (symbols.resolve(stmt.procedureName) == null) {
                    error("E212", "MOCK references unknown procedure '${stmt.procedureName}'", stmt.pos)
                }
                checkExpr(stmt.returns)
            }

            is WriteXmlStatement -> {
                checkExpr(stmt.value)
                checkExpr(stmt.filepath)
            }

            is ParseJsonStatement -> {
                val srcType = checkExpr(stmt.source)
                if (srcType !is KobolType.TextType && srcType !is KobolType.UnknownType) {
                    error("E106", "PARSE JSON source must be TEXT, got $srcType", stmt.pos)
                }
                if (stmt.asTypeName != null) {
                    val sym = symbols.resolve(stmt.asTypeName)
                    if (sym == null) error("E108", "Unknown type '${stmt.asTypeName}' in PARSE JSON AS clause", stmt.pos)
                }
            }

            is ParseXmlStatement -> {
                val srcType = checkExpr(stmt.source)
                if (srcType !is KobolType.TextType && srcType !is KobolType.UnknownType) {
                    error("E107", "PARSE XML source must be TEXT, got $srcType", stmt.pos)
                }
                if (stmt.asTypeName != null) {
                    val sym = symbols.resolve(stmt.asTypeName)
                    if (sym == null) error("E109", "Unknown type '${stmt.asTypeName}' in PARSE XML AS clause", stmt.pos)
                }
            }

            is WithPrecisionStatement -> checkBlock(stmt.body, returnType)
            is RoundStatement -> {
                val targetType = resolveRefType(stmt.target)
                if (targetType !is KobolType.DecimalType && targetType !is KobolType.MoneyType &&
                    targetType !is KobolType.UnknownType) {
                    error("E301", "ROUND target must be DECIMAL or MONEY, got $targetType", stmt.pos)
                }
                checkExpr(stmt.scale)
            }

            // ── Group 11: HTTP client ─────────────────────────────────────────
            is HttpCallStatement -> {
                checkExpr(stmt.url)
                stmt.headers?.let { checkExpr(it) }
                stmt.body?.let    { checkExpr(it) }
                stmt.timeout?.let { checkExpr(it) }
                if (stmt.giving != null) {
                    val existing = symbols.resolve(stmt.giving.parts[0])
                    if (existing == null)
                        symbols.define(Symbol.Variable(stmt.giving.parts[0], KobolType.TextType(null), mutable = true, pos = stmt.pos))
                }
            }

            // ── Group 11: JDBC bridge ─────────────────────────────────────────
            is JdbcConnectStatement -> {
                checkExpr(stmt.url)
                stmt.user?.let { checkExpr(it) }
                stmt.password?.let { checkExpr(it) }
            }
            is JdbcQueryStatement -> {
                checkExpr(stmt.sql)
                stmt.params.forEach { checkExpr(it) }
                if (stmt.asTypeName != null && symbols.resolve(stmt.asTypeName) == null) {
                    error("E220", "Unknown type '${stmt.asTypeName}' in JDBC query AS LIST OF clause", stmt.pos)
                }
                if (stmt.into != null) {
                    val existing = symbols.resolve(stmt.into.parts[0])
                    if (existing == null) {
                        val elemType = stmt.asTypeName?.let { KobolType.RecordRefType(it) }
                            ?: KobolType.JavaObjectType()
                        symbols.define(Symbol.Variable(stmt.into.parts[0], KobolType.ListType(elemType), mutable = true, pos = stmt.pos))
                    }
                }
            }
            is JdbcExecuteStatement -> {
                checkExpr(stmt.sql)
                stmt.params.forEach { checkExpr(it) }
            }
            is JdbcDisconnectStatement -> {}

            // ── Group 12: REST server ─────────────────────────────────────────
            is ServerStatement -> {
                checkExpr(stmt.port)
                for (ep in stmt.endpoints) {
                    symbols.enterScope("endpoint:${ep.method}:${ep.path}")
                    // Inject "BODY" (request body) as TEXT into endpoint scope — uppercase to match Lexer normalization
                    symbols.define(Symbol.Variable("BODY", KobolType.TextType(null), mutable = false, pos = ep.pos))
                    // Auto-declare path parameters (from {param} placeholders) as TEXT
                    // Names uppercased to match Lexer normalization (e.g. "name" → "NAME")
                    for (param in ep.pathParams) {
                        symbols.define(Symbol.Variable(param.uppercase(), KobolType.TextType(null), mutable = false, pos = ep.pos))
                    }
                    for (s in ep.body) checkStatement(s, KobolType.TextType(null))
                    symbols.exitScope()
                }
            }
            is RespondStatement -> {
                checkExpr(stmt.value)
                stmt.statusCode?.let { checkExpr(it) }
            }

            // ── Group 13: NoSQL document store ────────────────────────────────
            is NoSqlConnectStatement -> { checkExpr(stmt.url); checkExpr(stmt.database) }
            is NoSqlDisconnectStatement -> {}
            is NoSqlFindStatement -> {
                checkExpr(stmt.collection)
                stmt.filter?.let { f ->
                    val ft = checkExpr(f)
                    if (ft !is KobolType.BooleanType && ft !is KobolType.UnknownType)
                        error("E230", "FIND WHERE filter must be BOOLEAN, got $ft", stmt.pos)
                }
                val existing = symbols.resolve(stmt.giving.parts[0])
                if (existing == null) {
                    val resultType = if (stmt.findOne) KobolType.JavaObjectType()
                                     else KobolType.ListType(KobolType.JavaObjectType())
                    symbols.define(Symbol.Variable(stmt.giving.parts[0], resultType, mutable = true, pos = stmt.pos))
                }
            }
            is NoSqlSaveStatement -> {
                checkExpr(stmt.collection)
                resolveOrError(stmt.document.parts[0], stmt.pos)
            }
            is NoSqlDeleteStatement -> {
                checkExpr(stmt.collection)
                val ft = checkExpr(stmt.filter)
                if (ft !is KobolType.BooleanType && ft !is KobolType.UnknownType)
                    error("E231", "DELETE WHERE filter must be BOOLEAN, got $ft", stmt.pos)
            }
            is NoSqlCountStatement -> {
                checkExpr(stmt.collection)
                stmt.filter?.let { f ->
                    val ft = checkExpr(f)
                    if (ft !is KobolType.BooleanType && ft !is KobolType.UnknownType)
                        error("E232", "COUNT WHERE filter must be BOOLEAN, got $ft", stmt.pos)
                }
                val existing = symbols.resolve(stmt.giving.parts[0])
                if (existing == null)
                    symbols.define(Symbol.Variable(stmt.giving.parts[0], KobolType.IntegerType, mutable = true, pos = stmt.pos))
            }

            // ── Group 14: Cache / key-value store ────────────────────────────
            is CacheConnectStatement    -> checkExpr(stmt.url)
            is CacheDisconnectStatement -> {}
            is CacheGetStatement -> {
                checkExpr(stmt.key)
                val existing = symbols.resolve(stmt.giving.parts[0])
                if (existing == null)
                    symbols.define(Symbol.Variable(stmt.giving.parts[0], KobolType.TextType(null), mutable = true, pos = stmt.pos))
            }
            is CacheSetStatement -> {
                checkExpr(stmt.key)
                checkExpr(stmt.value)
                stmt.ttlSeconds?.let { checkExpr(it) }
            }
            is CacheDeleteStatement -> checkExpr(stmt.key)
            is CacheExistsStatement -> {
                checkExpr(stmt.key)
                val existing = symbols.resolve(stmt.giving.parts[0])
                if (existing == null)
                    symbols.define(Symbol.Variable(stmt.giving.parts[0], KobolType.BooleanType, mutable = true, pos = stmt.pos))
            }
        }
    }

    private fun checkMove(stmt: MoveStatement) {
        val fromType = checkExpr(stmt.from)
        val toType   = resolveRefType(stmt.to)
        if (!fromType.isAssignableTo(toType)) {
            error("E003", "Cannot MOVE $fromType to $toType", stmt.pos)
        }
    }

    private fun checkCompute(stmt: ComputeStatement) {
        val exprType = checkExpr(stmt.expr)
        val targetName = stmt.target.parts[0]
        val existing = symbols.resolve(targetName)
        if (existing == null && stmt.target.parts.size == 1) {
            // ER-5: LET x = expr (no type annotation) — infer type and define as local var
            symbols.define(Symbol.Variable(targetName, exprType, mutable = true, pos = stmt.pos))
        } else {
            resolveRefType(stmt.target) // ensure target exists
        }
    }

    private fun checkLocalVarDecl(stmt: LocalVarDecl) {
        val initType = checkExpr(stmt.initializer)
        val declType = stmt.type?.let { toKobolType(it) } ?: initType  // ER-5: type inference
        if (stmt.type != null && !initType.isAssignableTo(declType)) {
            error("E004", "Initializer type $initType does not match declared type $declType", stmt.pos)
        }
        symbols.define(Symbol.Variable(stmt.name, declType, mutable = true, pos = stmt.pos))
    }

    private fun checkArithAssign(
        operand: Expression, target: Reference, giving: Reference?, pos: SourcePosition
    ) {
        val operandType = checkExpr(operand)
        val targetType  = resolveRefType(target)
        // ADD item TO list is a list-append operation — skip numeric check
        if (targetType is KobolType.ListType) {
            giving?.let { resolveRefType(it) }
            return
        }
        if (!operandType.isNumeric() && operandType != KobolType.UnknownType) {
            error("E005", "Arithmetic operand must be numeric, got $operandType", pos)
        }
        giving?.let { resolveRefType(it) }
    }

    private fun checkValidationConstraint(c: ValidationConstraint, targetType: KobolType, pos: SourcePosition) {
        when (c) {
            is ValidationConstraint.MustBe     -> checkExpr(c.value)
            is ValidationConstraint.MustMatch  -> { /* regex validated at runtime */ }
            is ValidationConstraint.MustNotBe  -> { /* always valid */ }
            is ValidationConstraint.MustLength -> {
                if (targetType !is KobolType.TextType)
                    warning("W010", "MUST LENGTH constraint on non-TEXT type $targetType", pos)
            }
            is ValidationConstraint.MustSatisfy -> {
                val sym = symbols.resolve(c.procName)
                if (sym == null) error("E020", "VALIDATE SATISFY: undefined procedure '${c.procName}'", pos)
            }
        }
    }

    private fun checkMatch(stmt: MatchStatement, returnType: KobolType?) {
        val subjectType = checkExpr(stmt.subject)
        // Exhaustiveness check for VARIANT types
        val variantSym = when (subjectType) {
            is KobolType.VariantRefType ->
                symbols.resolve(subjectType.name) as? Symbol.VariantSymbol
            else -> null
        }
        if (variantSym != null && stmt.otherwise == null) {
            val coveredCases = stmt.whenClauses.mapNotNull {
                (it.pattern as? MatchPattern.VariantPattern)?.caseName
            }.toSet()
            val missingCases = variantSym.cases.map { it.name }.filter { it !in coveredCases }
            if (missingCases.isNotEmpty()) {
                error("E021", "Non-exhaustive MATCH: missing cases ${missingCases.joinToString()}", stmt.pos)
            }
        }
        for (clause in stmt.whenClauses) {
            when (val pattern = clause.pattern) {
                is MatchPattern.VariantPattern -> {
                    if (variantSym != null) {
                        val caseInfo = variantSym.cases.find { it.name == pattern.caseName }
                        if (caseInfo == null) {
                            error("E022", "MATCH: unknown variant case '${pattern.caseName}'", clause.pos)
                        } else {
                            symbols.enterScope("match-case-${pattern.caseName}")
                            // Bind WITH fields into scope
                            for (binding in pattern.bindings) {
                                val fieldType = caseInfo.fields[binding] ?: KobolType.UnknownType
                                symbols.define(Symbol.Variable(binding, fieldType, mutable = false, pos = clause.pos))
                            }
                            checkBlock(clause.body, returnType)
                            symbols.exitScope()
                        }
                    } else {
                        checkBlock(clause.body, returnType)
                    }
                }
                is MatchPattern.LiteralPattern -> {
                    checkExpr(pattern.value)
                    checkBlock(clause.body, returnType)
                }
                is MatchPattern.RangePattern -> {
                    checkExpr(pattern.from)
                    checkExpr(pattern.to)
                    checkBlock(clause.body, returnType)
                }
                is MatchPattern.TypePattern -> {
                    if (pattern.binding != null) {
                        symbols.enterScope("match-type-${pattern.typeName}")
                        symbols.define(Symbol.Variable(
                            pattern.binding, subjectType, mutable = false, pos = clause.pos
                        ))
                        checkBlock(clause.body, returnType)
                        symbols.exitScope()
                    } else {
                        checkBlock(clause.body, returnType)
                    }
                }
                is MatchPattern.GuardPattern -> {
                    checkMatchGuardClause(clause, pattern, subjectType, variantSym, returnType)
                }
            }
        }
        stmt.otherwise?.let { checkBlock(it, returnType) }
    }

    /**
     * Type-checks a GuardPattern clause.
     * Sets up scope for bindings from the inner pattern (VariantPattern / TypePattern),
     * verifies the guard expression is BOOLEAN, then checks the body in that scope.
     * O(1) per clause — inner pattern scope setup is bounded by binding count.
     */
    private fun checkMatchGuardClause(
        clause: WhenClause,
        guard: MatchPattern.GuardPattern,
        subjectType: KobolType,
        variantSym: Symbol.VariantSymbol?,
        returnType: KobolType?,
    ) {
        fun inScope(block: () -> Unit) {
            when (val inner = guard.inner) {
                is MatchPattern.VariantPattern -> {
                    if (variantSym != null) {
                        val caseInfo = variantSym.cases.find { it.name == inner.caseName }
                        if (caseInfo == null) {
                            error("E022", "MATCH: unknown variant case '${inner.caseName}'", clause.pos)
                            block(); return
                        }
                        symbols.enterScope("match-guard-${inner.caseName}")
                        for (binding in inner.bindings) {
                            val fieldType = caseInfo.fields[binding] ?: KobolType.UnknownType
                            symbols.define(Symbol.Variable(binding, fieldType, mutable = false, pos = clause.pos))
                        }
                        block()
                        symbols.exitScope()
                    } else block()
                }
                is MatchPattern.TypePattern -> {
                    if (inner.binding != null) {
                        symbols.enterScope("match-guard-type-${inner.typeName}")
                        symbols.define(Symbol.Variable(inner.binding, subjectType, mutable = false, pos = clause.pos))
                        block()
                        symbols.exitScope()
                    } else block()
                }
                is MatchPattern.LiteralPattern -> { checkExpr(inner.value); block() }
                is MatchPattern.RangePattern   -> { checkExpr(inner.from); checkExpr(inner.to); block() }
                is MatchPattern.GuardPattern   -> block() // nested guard: not supported, skip
            }
        }
        inScope {
            val guardType = checkExpr(guard.guard)
            if (guardType !is KobolType.BooleanType && guardType !is KobolType.UnknownType) {
                error("E024", "MATCH guard IF must be BOOLEAN, got $guardType", guard.pos)
            }
            checkBlock(clause.body, returnType)
        }
    }

    // PUT value TO map WITH KEY key  (#12)
    private fun checkMapPut(stmt: MapPutStatement) {
        val mapType = resolveRefType(stmt.map)
        if (mapType !is KobolType.MapType) {
            if (mapType != KobolType.UnknownType)
                error("E027", "PUT … TO requires a MAP target, got $mapType", stmt.pos)
            checkExpr(stmt.key); checkExpr(stmt.value); return
        }
        val keyType = checkExpr(stmt.key)
        if (keyType != KobolType.UnknownType && !keyType.isAssignableTo(mapType.keyType))
            error("E028", "MAP key type mismatch: expected ${mapType.keyType}, got $keyType", stmt.pos)
        val valType = checkExpr(stmt.value)
        if (valType != KobolType.UnknownType && !valType.isAssignableTo(mapType.valueType))
            error("E029", "MAP value type mismatch: expected ${mapType.valueType}, got $valType", stmt.pos)
    }

    // GET map KEY key INTO dest  (#12)
    private fun checkMapGet(stmt: MapGetStatement) {
        val mapType  = resolveRefType(stmt.map)
        val intoType = resolveRefType(stmt.into)
        if (mapType !is KobolType.MapType) {
            if (mapType != KobolType.UnknownType)
                error("E027", "GET … requires a MAP source, got $mapType", stmt.pos)
            checkExpr(stmt.key); return
        }
        val keyType = checkExpr(stmt.key)
        if (keyType != KobolType.UnknownType && !keyType.isAssignableTo(mapType.keyType))
            error("E028", "MAP key type mismatch: expected ${mapType.keyType}, got $keyType", stmt.pos)
        if (intoType != KobolType.UnknownType && !mapType.valueType.isAssignableTo(intoType))
            error("E030", "GET target of type $intoType cannot hold MAP value ${mapType.valueType}", stmt.pos)
    }

    private fun checkPerform(stmt: PerformStatement) {
        // Cross-module call: PERFORM Alias.ProcedureName USING ...
        if (stmt.moduleAlias != null) {
            val mod = moduleRegistry.resolveByAlias(stmt.moduleAlias)
            if (mod == null) {
                error("E211", "Unknown module alias '${stmt.moduleAlias}' — is it imported?", stmt.pos)
                return
            }
            val proc = mod.procedures[stmt.procedureName.uppercase()]
            if (proc == null) {
                error("E212", "Module '${mod.moduleName}' does not export procedure '${stmt.procedureName}'", stmt.pos)
                return
            }
            if (stmt.args.size != proc.params.size) {
                error("E213", "Procedure '${stmt.moduleAlias}.${stmt.procedureName}' expects ${proc.params.size} argument(s), got ${stmt.args.size}", stmt.pos)
            }
            stmt.args.forEachIndexed { i, arg ->
                val argType = checkExpr(arg)
                if (i < proc.params.size && !argType.isAssignableTo(proc.params[i].type)) {
                    error("E214", "Argument ${i+1} to '${stmt.moduleAlias}.${stmt.procedureName}': expected ${proc.params[i].type}, got $argType", stmt.pos)
                }
            }
            // GIVING on a cross-module call — same rules as the local path below (F10).
            // Cross-module async capture is now supported: the call links to the exported
            // proc's CompletableFuture entry, stores it into the FUTURE OF T target, and is
            // consumed by AWAIT — no asymmetry vs a local ASYNC PERFORM. (E218 retired.)
            if (stmt.giving != null) {
                val qualified = "${stmt.moduleAlias}.${stmt.procedureName}"
                if (!proc.isAsync) {
                    error("E215", "GIVING is only valid for ASYNC PROCEDURE calls; '$qualified' is not async", stmt.pos)
                } else {
                    val futureType = proc.returnType?.let { KobolType.FutureType(it) }
                    val givingType = resolveRefType(stmt.giving)
                    if (futureType != null && givingType !is KobolType.FutureType && givingType != KobolType.UnknownType) {
                        error("E216", "GIVING target must be FUTURE OF ${proc.returnType}, got $givingType", stmt.pos)
                    }
                }
            }
            return
        }
        val sym = symbols.resolve(stmt.procedureName)
        if (sym == null) {
            val suggestion = didYouMean(stmt.procedureName, symbols.allVisibleNames())
            error("E006", "Undefined procedure '${stmt.procedureName}'${if (suggestion != null) " — $suggestion" else ""}", stmt.pos)
            return
        }
        if (sym !is Symbol.ProcedureSymbol) {
            error("E007", "'${stmt.procedureName}' is not a procedure", stmt.pos)
            return
        }
        if (sym.deprecated != null) {
            warning("W008", "PERFORM '${stmt.procedureName}' is deprecated: ${sym.deprecated}", stmt.pos)
        }
        if (stmt.args.size != sym.params.size) {
            error("E008", "Procedure '${stmt.procedureName}' expects ${sym.params.size} argument(s), got ${stmt.args.size}", stmt.pos)
        }
        stmt.args.forEachIndexed { i, arg ->
            val argType = checkExpr(arg)
            if (i < sym.params.size) {
                val expected = sym.params[i].type
                if (!argType.isAssignableTo(expected)) {
                    error("E009", "Argument ${i+1} to '${stmt.procedureName}': expected $expected, got $argType", stmt.pos)
                }
            }
        }
        // GIVING clause — only valid for ASYNC procedures; result is FUTURE OF ReturnType
        if (stmt.giving != null) {
            if (!sym.isAsync) {
                error("E215", "GIVING is only valid for ASYNC PROCEDURE calls; '${stmt.procedureName}' is not async", stmt.pos)
            } else {
                val futureType = sym.returnType?.let { KobolType.FutureType(it) }
                val givingType = resolveRefType(stmt.giving)
                if (futureType != null && givingType !is KobolType.FutureType && givingType != KobolType.UnknownType) {
                    error("E216", "GIVING target must be FUTURE OF ${sym.returnType}, got $givingType", stmt.pos)
                }
            }
        }
    }

    private fun checkAwait(stmt: AwaitStatement) {
        val futureType = resolveRefType(stmt.future)
        if (futureType !is KobolType.FutureType && futureType != KobolType.UnknownType) {
            error("E217", "AWAIT requires a FUTURE OF T variable, got $futureType", stmt.pos)
        }
        resolveRefType(stmt.into)  // just validate the target exists
    }

    private fun checkCall(stmt: CallStatement) {
        stmt.args.forEach { checkExpr(it) }
        stmt.giving?.let { resolveRefType(it) }
        // F15: the GIVING form captures the return into a variable, so a nullable Kotlin return is
        // the same NPE landmine as the expression form — warn W237 on every call form (P6), from
        // the shared resolver (P1). Fire-and-forget (no GIVING) discards the value → no warning.
        // Resolution is quiet here: a non-interop / reflective / unresolved CALL simply doesn't
        // warn (the statement path tolerates those forms; codegen handles their dispatch).
        if (stmt.giving != null) {
            val resolver = interopResolver ?: return
            val site = interopCallSite(stmt.method, stmt.args) ?: return
            (resolver.resolveByArgs(site.owner, site.methodName, site.argDescs)
                as? ClasspathSymbolResolver.CallResolution.Resolved)
                ?.let { warnNullableKotlinReturn(stmt.method, it.sig, stmt.pos) }
        }
    }

    // -------------------------------------------------------------------------
    // Expression type inference  (returns the inferred type and annotates the node)
    // -------------------------------------------------------------------------

    private fun checkExpr(expr: Expression): KobolType {
        val t = inferType(expr)
        exprTypes[expr] = t
        return t
    }

    private fun inferType(expr: Expression): KobolType = when (expr) {
        is Literal -> when (expr.kind) {
            LiteralKind.INTEGER -> KobolType.IntegerType
            LiteralKind.DECIMAL -> KobolType.DecimalType(10, 2)
            LiteralKind.STRING  -> KobolType.TextType(null)
            LiteralKind.BOOLEAN -> KobolType.BooleanType
        }

        is Reference -> resolveRefType(expr)

        is BinaryExpr -> {
            val l = checkExpr(expr.left)
            val r = checkExpr(expr.right)
            when (expr.op) {
                BinaryOp.EQ, BinaryOp.NEQ,
                BinaryOp.LT, BinaryOp.GT,
                BinaryOp.LEQ, BinaryOp.GEQ,
                BinaryOp.AND, BinaryOp.OR -> KobolType.BooleanType

                else -> when {
                    l is KobolType.MoneyType || r is KobolType.MoneyType ->
                        KobolType.MoneyType(maxOf((l as? KobolType.MoneyType)?.precision ?: 10,
                                                   (r as? KobolType.MoneyType)?.precision ?: 10),
                                             maxOf((l as? KobolType.MoneyType)?.scale ?: 2,
                                                   (r as? KobolType.MoneyType)?.scale ?: 2))
                    l is KobolType.DecimalType || r is KobolType.DecimalType ->
                        KobolType.DecimalType(10, 2)
                    l is KobolType.TextType && expr.op == BinaryOp.ADD -> KobolType.TextType(null)
                    else -> l
                }
            }
        }

        is UnaryExpr -> when (expr.op) {
            UnaryOp.NOT    -> KobolType.BooleanType
            UnaryOp.NEGATE -> checkExpr(expr.operand)
        }

        is BuiltinCall -> inferBuiltinType(expr)

        is StringTemplateExpr -> {
            expr.parts.filterIsInstance<StringTemplatePart.Interpolated>().forEach { checkExpr(it.expr) }
            KobolType.TextType(null)
        }

        is RecordLiteralExpr -> {
            val recSym = symbols.resolve(expr.typeName)
            if (recSym == null) {
                val s = didYouMean(expr.typeName, symbols.allVisibleNames())
                error("E001", "Undefined record type '${expr.typeName}'${if (s != null) " — $s" else ""}", expr.pos)
            } else if (recSym is Symbol.RecordSymbol) {
                for (field in expr.fields) {
                    val fieldType = recSym.fields[field.name]
                    if (fieldType == null) {
                        error("E002", "Record '${expr.typeName}' has no field '${field.name}'", field.pos)
                    } else {
                        val vt = checkExpr(field.value)
                        if (!vt.isAssignableTo(fieldType)) {
                            error("E004", "Field '${field.name}': expected $fieldType, got $vt", field.pos)
                        }
                    }
                }
            }
            KobolType.RecordRefType(expr.typeName)
        }

        is NewExpr -> {
            // F12: construct an arbitrary classpath object. We cannot validate the constructor
            // against the real class without the E2 classpath-reading phase, so we only check
            // the argument expressions. The result is a JAVA-OBJECT that CARRIES its source owner
            // (F21): a later instance CALL on this value resolves the real method against the
            // concrete class instead of the erased java/lang/Object. Named arguments need declared
            // fields to reorder → reject them.
            expr.args.forEach {
                if (it is NamedArgument)
                    error("E031", "NEW ${expr.owner}: named arguments need a known constructor signature; use positional arguments", it.pos)
                checkExpr(it)
            }
            KobolType.JavaObjectType(ownerName = expr.owner)
        }

        is CallExpr -> inferCallExprType(expr)

        is NamedArgument -> checkExpr(expr.value)

        is PipelineExpr -> inferPipelineType(expr)
    }

    /**
     * Infer the static type of a `CALL` in expression position (F14) from the method's REAL return
     * descriptor, read off the compile classpath (E2). The owner is resolved with the SAME shared
     * logic codegen uses ([resolveInteropTarget]) and the arguments are scored with the SAME
     * descriptor mapping ([kobolDescriptor]), so the type computed here is exactly the type codegen
     * emits — no type-check-clean→runtime-crash gap.
     *
     * Unresolvable (class not on the classpath, no matching/ambiguous overload), a `void` method, or
     * a return descriptor with no Kobol type → a diagnostic + [KobolType.UnknownType] (rejected at
     * compile time, never a silent Object guess).
     */
    private fun inferCallExprType(expr: CallExpr): KobolType {
        expr.args.forEach {
            if (it is NamedArgument)
                error("E031", "CALL expression: named arguments need a known signature; use positional arguments", it.pos)
            checkExpr(it)
        }
        val resolver = interopResolver
        if (resolver == null) {
            error("E230", "CALL expression '${expr.method}': interop classpath unavailable, cannot resolve return type", expr.pos)
            return KobolType.UnknownType
        }

        val site = interopCallSite(expr.method, expr.args)
        if (site == null) {
            error("E231", "CALL expression '${expr.method}' has no resolvable interop owner; the reflective `alias.FIELD.method` form is only valid as a statement", expr.pos)
            return KobolType.UnknownType
        }

        return when (val res = resolver.resolveByArgs(site.owner, site.methodName, site.argDescs)) {
            is ClasspathSymbolResolver.CallResolution.Resolved -> {
                val retDesc = org.objectweb.asm.Type.getReturnType(res.sig.descriptor).descriptor
                val kt = kobolTypeFromDescriptor(retDesc)
                when {
                    retDesc == "V" -> {
                        error("E232", "CALL expression '${expr.method}' calls a void method — it has no value; use a CALL statement instead", expr.pos)
                        KobolType.UnknownType
                    }
                    kt == null -> {
                        error("E233", "CALL expression '${expr.method}' returns '$retDesc', which has no Kobol type", expr.pos)
                        KobolType.UnknownType
                    }
                    else -> {
                        warnNullableKotlinReturn(expr.method, res.sig, expr.pos)
                        kt
                    }
                }
            }
            is ClasspathSymbolResolver.CallResolution.Ambiguous -> {
                error("E234", "CALL expression '${expr.method}': ambiguous overload for these argument types", expr.pos)
                KobolType.UnknownType
            }
            ClasspathSymbolResolver.CallResolution.NoSuchMethod -> {
                error("E235", "CALL expression '${expr.method}': no method matches these argument types on '${site.owner}'", expr.pos)
                KobolType.UnknownType
            }
            ClasspathSymbolResolver.CallResolution.ClassNotFound -> {
                error("E236", "CALL expression '${expr.method}': class '${site.owner}' not found on the classpath (missing IMPORT?)", expr.pos)
                KobolType.UnknownType
            }
        }
    }

    /**
     * Resolves a CALL's dotted target string into the JVM owner, method name, and argument
     * descriptors — the shared front half of interop resolution used by BOTH the CALL-expression
     * and CALL-statement paths (no fork, P1). Mirrors codegen's receiver detection so static vs
     * instance dispatch is decided identically. Returns null when the dotted form names no
     * resolvable interop owner (e.g. the reflective `alias.FIELD.method` statement form).
     */
    private fun interopCallSite(method: String, args: List<Expression>): InteropCallSite? {
        val parts      = method.split(".")
        val ownerParts = parts.dropLast(1)
        // Receiver type for a single-part owner that names a known variable (else null). Symbols are
        // keyed by the UPPERCASE-normalised name (the owner part keeps original source case).
        val receiverType: KobolType? = if (ownerParts.size == 1) {
            when (val sym = symbols.resolve(ownerParts[0].uppercase())) {
                is Symbol.Variable -> sym.type
                is Symbol.Constant -> sym.type
                else -> null
            }
        } else null
        val owner = when (val target = resolveInteropTarget(ownerParts, importAliasMap, receiverType)) {
            is InteropTarget.Static   -> target.owner
            is InteropTarget.Instance -> target.owner
            else -> return null
        }
        return InteropCallSite(owner, parts.last(), args.map { kobolDescriptor(exprTypes[it] ?: inferType(it)) })
    }

    /** (jvm owner, method, arg descriptors) for a resolved interop CALL site. */
    private data class InteropCallSite(val owner: String, val methodName: String, val argDescs: List<String>)

    /**
     * F15: a Kotlin method declared to return `T?` can hand back null, but Kobol has no null — the
     * value silently NPEs when later used (type-checks clean otherwise = P3 landmine). Warns W237
     * on a resolved nullable Kotlin return. Shared by every call form (P6 — expression and
     * statement-GIVING) from one source (P1). Java/JDK methods carry no @Metadata so never trip.
     */
    private fun warnNullableKotlinReturn(method: String, sig: JvmMethodSig, pos: SourcePosition) {
        if (sig.returnNullable)
            warning("W237", "CALL '$method' targets a Kotlin method whose return type is nullable, but Kobol has no null — the result may be null and cause a NullPointerException when used; guard the source or call a non-null alternative", pos)
    }

    private fun inferBuiltinType(expr: BuiltinCall): KobolType {
        expr.args.forEach { checkExpr(it) }
        return when (expr.name.uppercase()) {
            // ── KobolText ─────────────────────────────────────────────────
            "UPPERCASE", "LOWERCASE", "TRIM", "TRIM-LEFT", "TRIM-START",
            "TRIM-RIGHT", "TRIM-END", "SUBSTRING", "REPLACE",
            "PAD-LEFT", "PAD-RIGHT", "REPEAT", "COMBINE",
            "REVERSE"                                      -> KobolType.TextType(null)
            "SPLIT"                                        -> KobolType.ListType(KobolType.TextType(null))
            "LENGTH", "FIND", "SCALE-OF", "PRECISION-OF"        -> KobolType.IntegerType
            "CONTAINS", "STARTS-WITH", "ENDS-WITH",
            "IS-BLANK", "IS-EMPTY"                         -> KobolType.BooleanType
            "IS-POSITIVE", "IS-NEGATIVE", "IS-ZERO"         -> KobolType.BooleanType

            // ── KobolMath ─────────────────────────────────────────────────
            "ROUND", "TRUNCATE", "FLOOR", "CEIL", "SQRT",
            "POWER", "ROUND-WITH-MODE"                     -> KobolType.DecimalType(20, 6)
            "ABS", "MAX", "MIN", "MOD" -> {
                val firstArg = expr.args.firstOrNull()
                val argType  = firstArg?.let { checkExpr(it) } ?: KobolType.IntegerType
                if (argType is KobolType.IntegerType) KobolType.IntegerType else KobolType.DecimalType(20, 6)
            }
            "SIGN"                                         -> KobolType.IntegerType

            // ── KobolDate ─────────────────────────────────────────────────
            "TODAY", "DATE", "ADD-DAYS", "ADD-MONTHS", "ADD-YEARS",
            "PARSE-DATE"                                   -> KobolType.DateType
            "NOW"                                          -> KobolType.DateTimeType
            "YEAR", "MONTH", "DAY", "DATE-DIFF"            -> KobolType.IntegerType
            "FORMAT-DATE", "ISO-DATE"                      -> KobolType.TextType(null)
            "IS-BEFORE", "IS-AFTER", "IS-EQUAL"            -> KobolType.BooleanType

            // ── KobolUuid ────────────────────────────────────────────────
            "UUID-GENERATE", "UUID-NIL"                    -> KobolType.UuidType
            "UUID-FROM-TEXT"                               -> KobolType.UuidType
            "UUID-TO-TEXT"                                 -> KobolType.TextType(null)

            // ── KobolConvert ──────────────────────────────────────────────
            "TO-TEXT"                                      -> KobolType.TextType(null)
            "TO-INTEGER"                                   -> KobolType.IntegerType
            "TO-DECIMAL"                                   -> KobolType.DecimalType(20, 6)
            "TO-DATE"                                      -> KobolType.DateType
            "TO-BOOLEAN"                                   -> KobolType.BooleanType

            "DISPLAY_TABLE", "DISPLAY_LABEL", "DISPLAY_FORMAT",
            "DISPLAY_JSON", "DISPLAY_JSON_PRETTY",
            "DISPLAY_STYLED",
            "DISPLAY_XML", "DISPLAY_XML_PRETTY"             -> KobolType.VoidType
            else -> {
                // Variant case construction: Shipped("X") / Shipped(tracking: "X") — #18
                val caseEntry = variantCaseIndex[expr.name.uppercase()]
                if (caseEntry != null) {
                    checkVariantConstruction(expr, caseEntry.first, caseEntry.second)
                    return KobolType.VariantRefType(caseEntry.first)
                }
                // Check if this is a user-defined procedure call in expression context
                val procSym = symbols.resolve(expr.name) as? Symbol.ProcedureSymbol
                    ?: symbols.resolve(expr.name.uppercase()) as? Symbol.ProcedureSymbol
                procSym?.returnType ?: KobolType.UnknownType
            }
        }
    }

    /** Validate a VARIANT case construction call — arity (positional) or field names (named). #18 */
    private fun checkVariantConstruction(expr: BuiltinCall, variantName: String, case: Symbol.VariantSymbol.CaseInfo) {
        val fieldNames = case.fields.keys
        val named = expr.args.filterIsInstance<NamedArgument>()
        when {
            named.isEmpty() -> {
                if (expr.args.size != fieldNames.size)
                    error("E023", "Variant case '${case.name}' takes ${fieldNames.size} field(s), got ${expr.args.size}", expr.pos)
            }
            named.size != expr.args.size ->
                error("E023", "Variant case '${case.name}' construction cannot mix positional and named arguments", expr.pos)
            else -> {
                val upperFields = fieldNames.map { it.uppercase() }.toSet()
                for (a in named) if (a.paramName.uppercase() !in upperFields)
                    error("E025", "Variant case '${case.name}' has no field '${a.paramName}'", a.pos)
                for (fn in fieldNames) if (named.none { it.paramName.equals(fn, ignoreCase = true) })
                    error("E026", "Variant case '${case.name}' construction missing field '$fn'", expr.pos)
            }
        }
    }

    private fun inferPipelineType(expr: PipelineExpr): KobolType {
        var current = checkExpr(expr.source)
        for (stage in expr.stages) {
            current = when (stage) {
                is PipelineStage.FilterStage    -> {
                    // Inject element record fields so bare field names resolve inside FILTER WHERE
                    val elemType = (current as? KobolType.ListType)?.elementType ?: KobolType.UnknownType
                    val recSym = (elemType as? KobolType.RecordRefType)
                        ?.let { symbols.resolve(it.name) as? Symbol.RecordSymbol }
                    if (recSym != null) {
                        symbols.enterScope("pipeline-filter")
                        val syntheticPos = SourcePosition("<pipeline>", 0, 0)
                        recSym.fields.forEach { (n, t) ->
                            symbols.define(Symbol.Variable(n, t, mutable = false, pos = syntheticPos))
                        }
                        recSym.conditions.keys.forEach { condName ->
                            symbols.define(Symbol.Variable(condName, KobolType.BooleanType, mutable = false, pos = syntheticPos))
                        }
                        checkExpr(stage.condition)
                        symbols.exitScope()
                    } else {
                        checkExpr(stage.condition)
                    }
                    current
                }
                is PipelineStage.TransformStage -> {
                    val elemType = (current as? KobolType.ListType)?.elementType ?: KobolType.UnknownType
                    when (val rec = elemType) {
                        is KobolType.RecordRefType -> {
                            val recSym = symbols.resolve(rec.name) as? Symbol.RecordSymbol
                            KobolType.ListType(recSym?.fields?.get(stage.field) ?: KobolType.UnknownType)
                        }
                        else -> KobolType.ListType(KobolType.UnknownType)
                    }
                }
                is PipelineStage.SumStage       -> {
                    // The sum preserves element type: INTEGER list → INTEGER, DECIMAL/MONEY list → DECIMAL
                    val elemType = (current as? KobolType.ListType)?.elementType ?: KobolType.UnknownType
                    when (elemType) {
                        is KobolType.IntegerType -> KobolType.IntegerType
                        is KobolType.MoneyType   -> elemType
                        else                     -> KobolType.DecimalType(18, 6)
                    }
                }
                is PipelineStage.SortStage      -> current
                is PipelineStage.TakeStage      -> { checkExpr(stage.count); current }
            }
        }
        return current
    }

    // -------------------------------------------------------------------------
    // Reference resolution helpers
    // -------------------------------------------------------------------------

    /**
     * Type-check a reference, emitting E001/E002 diagnostics for unknown names or
     * bad field access. Used during the checking phase.
     */
    private fun resolveRefType(ref: Reference): KobolType {
        val rootName = ref.parts[0]
        val sym = symbols.resolve(rootName)
        if (sym == null) {
            // F1: a bare single-part name matching a field-less VARIANT case constructs that case
            // (`MOVE Pending TO st`). Mirrors the call-form path (variantCaseIndex) used for
            // `Pending()`; a real symbol of the same name shadows it (checked above).
            if (ref.parts.size == 1) {
                val caseEntry = variantCaseIndex[rootName.uppercase()]
                if (caseEntry != null && caseEntry.second.fields.isEmpty())
                    return KobolType.VariantRefType(caseEntry.first)
            }
            val suggestion = didYouMean(rootName, symbols.allVisibleNames())
            error("E001", "Undefined name '${rootName}'${if (suggestion != null) " — $suggestion" else ""}", ref.pos)
            return KobolType.UnknownType
        }
        return walkFieldChain(
            rootSymbolType(sym), ref.parts.drop(1),
            onError = { msg -> error("E002", msg, ref.pos) },
            onNullableProperty = { field ->
                warning("W237", "property '$field' is a Kotlin property whose type is nullable, but Kobol has no null — the value may be null and cause a NullPointerException when used; guard the source", ref.pos)
            },
        )
    }

    /**
     * Resolve a reference's type by walking its field chain — pure, no diagnostics.
     * Codegen needs the *field* type of a target like `invoice.amount` (e.g. to know
     * it is DECIMAL), but must not re-emit errors or depend on checking-phase
     * memoization. Returns [KobolType.UnknownType] if any step can't be resolved.
     * O(depth) in the number of `.`-separated parts (always tiny).
     */
    fun typeOfReference(ref: Reference): KobolType {
        val sym = symbols.resolve(ref.parts[0]) ?: return KobolType.UnknownType
        return walkFieldChain(rootSymbolType(sym), ref.parts.drop(1), onError = { /* silent */ })
    }

    /** Static type a root symbol contributes before any field access. */
    private fun rootSymbolType(sym: Symbol): KobolType = when (sym) {
        is Symbol.Variable        -> sym.type
        is Symbol.Constant        -> sym.type
        is Symbol.ProcedureSymbol -> sym.returnType ?: KobolType.VoidType
        is Symbol.RecordSymbol    -> KobolType.RecordRefType(sym.name)
        is Symbol.VariantSymbol   -> KobolType.VariantRefType(sym.name)
        is Symbol.NamedCondition  -> KobolType.BooleanType
    }

    /**
     * Walk a `a.b.c` field chain from [start], invoking [onError] (with the same
     * message text [resolveRefType] historically produced) when a field can't be
     * resolved. Shared by the diagnostic ([resolveRefType]) and pure
     * ([typeOfReference]) entry points so the resolution logic lives in one place.
     */
    private inline fun walkFieldChain(
        start: KobolType,
        fields: List<String>,
        onError: (String) -> Unit,
        onNullableProperty: (String) -> Unit = {},
    ): KobolType {
        var type = start
        for (field in fields) {
            type = when (type) {
                is KobolType.RecordRefType -> {
                    val recSym = symbols.resolve(type.name) as? Symbol.RecordSymbol
                    recSym?.fields?.get(field)
                        ?: if (recSym?.conditions?.containsKey(field) == true) KobolType.BooleanType
                           else { onError("Record '${type.name}' has no field '$field'"); KobolType.UnknownType }
                }
                is KobolType.ListType ->
                    if (field == "LENGTH" || field == "SIZE" || field == "COUNT") KobolType.IntegerType
                    else { onError("LIST has no field '$field'"); KobolType.UnknownType }
                // #5 (F29): `obj.field` on a JAVA-OBJECT with a known owner resolves to its property
                // getter (off the compile classpath, via the SAME helper codegen emits — P1) so the
                // real property type flows into the type system and a nullable property warns W237.
                // An opaque owner (null) keeps the historical permissive TEXT fallback (e.g. a field
                // on a JAVA-OBJECT whose concrete class was erased across a boundary).
                is KobolType.JavaObjectType -> {
                    val getter = type.ownerName?.let { owner ->
                        interopResolver?.let { r ->
                            resolvePropertyGetter(r, resolveInteropOwner(owner.split("."), importAliasMap), field)
                        }
                    }
                    if (getter != null) {
                        if (getter.returnNullable) onNullableProperty(field)
                        kobolTypeFromDescriptor(getter.descriptor.substringAfterLast(')')) ?: KobolType.JavaObjectType()
                    } else KobolType.TextType(null)
                }
                else -> { onError("Cannot access field '$field' on type $type"); KobolType.UnknownType }
            }
        }
        return type
    }

    private fun resolveOrError(name: String, pos: SourcePosition): Symbol? {
        val sym = symbols.resolve(name)
        if (sym == null) {
            val suggestion = didYouMean(name, symbols.allVisibleNames())
            error("E001", "Undefined name '$name'${if (suggestion != null) " — $suggestion" else ""}", pos)
        }
        return sym
    }

    // -------------------------------------------------------------------------
    // TypeSpec → KobolType conversion
    // -------------------------------------------------------------------------

    fun toKobolType(spec: TypeSpec): KobolType = when (spec) {
        is TypeSpec.IntegerType  -> KobolType.IntegerType
        is TypeSpec.SmallIntType -> KobolType.SmallIntType
        is TypeSpec.BooleanType  -> KobolType.BooleanType
        is TypeSpec.DateType     -> KobolType.DateType
        is TypeSpec.TimeType     -> KobolType.TimeType
        is TypeSpec.DateTimeType -> KobolType.DateTimeType
        is TypeSpec.DecimalType  -> KobolType.DecimalType(spec.precision, spec.scale)
        is TypeSpec.MoneyType    -> KobolType.MoneyType(spec.precision, spec.scale)
        is TypeSpec.TextType     -> KobolType.TextType(spec.maxLength)
        is TypeSpec.ListOf       -> KobolType.ListType(toKobolType(spec.elementType))
        is TypeSpec.MapOf        -> KobolType.MapType(toKobolType(spec.keyType), toKobolType(spec.valueType))
        is TypeSpec.FutureOf     -> KobolType.FutureType(toKobolType(spec.elementType))
        is TypeSpec.UuidType     -> KobolType.UuidType
        is TypeSpec.NamedType    -> {
            // Resolution order: type alias → known VARIANT/RECORD symbol → IMPORT alias (an imported
            // 3rd-party class used as a declared type, F26) → RecordRefType (forward/unknown record ref).
            // A real record/variant wins over a same-named import alias; the import case lets a DATA
            // field, USING param, or RETURNING type hold a concrete JAVA-OBJECT class so its owner
            // survives across a procedure boundary (owner = the source name, re-resolved via the
            // import alias map exactly like a NEW value — no fork).
            typeAliases[spec.name]
                ?: when (symbols.resolve(spec.name)) {
                    is Symbol.VariantSymbol -> KobolType.VariantRefType(spec.name)
                    is Symbol.RecordSymbol  -> KobolType.RecordRefType(spec.name)
                    else ->
                        if (importAliasMap.containsKey(spec.name.uppercase()))
                            KobolType.JavaObjectType(ownerName = spec.name)
                        else KobolType.RecordRefType(spec.name)
                }
        }
    }

    // -------------------------------------------------------------------------
    // Diagnostic helpers
    // -------------------------------------------------------------------------

    private fun sourceLine(pos: SourcePosition): String =
        if (pos.line >= 1 && pos.line <= sourceLines.size) sourceLines[pos.line - 1] else ""

    private fun error(code: String, message: String, pos: SourcePosition) {
        diagnostics.error(code, message, pos, sourceLine(pos))
    }

    private fun warning(code: String, message: String, pos: SourcePosition) {
        diagnostics.warning(code, message, pos, sourceLine(pos))
    }
}
