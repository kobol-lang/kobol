package dev.kobol.codegen

import dev.kobol.parser.ast.*
import dev.kobol.semantic.KobolType
import dev.kobol.semantic.Symbol
import dev.kobol.semantic.TypeChecker
import org.objectweb.asm.*
import org.objectweb.asm.Opcodes.*
import java.io.File

/**
 * Kobol AST → JVM bytecode using ObjectWeb ASM 9.7.
 *
 * Emits .class files directly — no javac dependency at runtime.
 *
 * Strategy (mirrors JavaTranspiler structure):
 *   - One outer class per PROGRAM  (public final)
 *   - One static inner class per RECORD
 *   - DATA items → private static fields on outer class
 *   - PROCEDURE → private static method on outer class
 *   - public static void main(String[]) → calls MAIN procedure
 *
 * JVM type mapping:
 *   INTEGER    → long      (J)
 *   SMALLINT   → int       (I)
 *   BOOLEAN    → boolean   (Z)
 *   TEXT       → String    (Ljava/lang/String;)
 *   DECIMAL/MONEY → BigDecimal  (Ljava/math/BigDecimal;)
 *   DATE       → LocalDate (Ljava/time/LocalDate;)
 *   TIME       → LocalTime (Ljava/time/LocalTime;)
 *   DATETIME   → LocalDateTime
 *   LIST OF T  → ArrayList (Ljava/util/ArrayList;)
 *   Record     → inner class
 */
class AsmEmitter(
    internal val checker: TypeChecker,
    internal val moduleRegistry: dev.kobol.semantic.ModuleRegistry = dev.kobol.semantic.ModuleRegistry(),
) {

    // Synthetic concurrent-branch methods accumulated during procedure emission.
    // Maps synthetic method name → (branch statements, owner class name).
    internal val pendingSyntheticMethods = mutableListOf<Triple<String, String, List<Statement>>>()
    // Synthetic PARALLEL FOR EACH body methods: (methodName, owner, (variable, stmt))
    internal val pendingSyntheticParallelMethods = mutableListOf<Triple<String, String, Pair<String, ParallelForEachStatement>>>()
    internal var concBlockCounter = 0
    /** Set to true when any LOG statement is encountered during emission. */
    internal var emitsLog = false
    internal val namedConditions = mutableMapOf<String, Expression>()
    /**
     * Variant case construction index: UPPERCASE case name → (variant type name, the case decl).
     * Lets [emitBuiltin] recognise `Shipped("X")` / `Shipped(tracking: "X")` as a variant
     * construction (NEW + ctor) rather than an unknown stdlib call. Populated in [emit].
     */
    internal var variantCaseIndex: Map<String, Pair<String, VariantCase>> = emptyMap()
    /**
     * Import alias → JVM binary class name (slash-separated, original case).
     * Built from `program.imports` each time [emit] is called.
     * Keys are UPPERCASE to match Kobol's identifier normalisation.
     * Example: "LOCALDATE" → "java/time/LocalDate"
     */
    internal var importAliasMap: Map<String, String> = emptyMap()
    /** Names of DATA section items (static fields on the outer class). */
    internal var dataSectionNames: Set<String> = emptySet()
    /** DEFINE constants: name → literal expression, populated in [emit]. */
    internal val constantExprs: MutableMap<String, Expression> = mutableMapOf()
    /** FILES section declarations keyed by UPPERCASE file name, populated in [emit]. */
    internal var fileDecls: Map<String, FileDecl> = emptyMap()
    /**
     * Set to a record name while emitting that record's CONDITION predicate methods.
     * Lets [inferExprType] type `__self.field` references (which the type checker never
     * saw, since they are synthesised during codegen). Null during all other emission.
     */
    internal var conditionSelfRecord: String? = null
    /** Endpoint handler methods deferred until emitProgramClass (Triple: owner, proc, handler). */
    internal val pendingEndpoints = mutableListOf<Triple<String, ProcedureDecl, EndpointHandler>>()
    internal val pendingEndpointMethods = mutableMapOf<String, MutableList<Any>>() // placeholder — unused, see pendingEndpoints

    /**
     * Compile the program to bytecode.
     * Returns a map from class binary name (slash-separated) → bytecode bytes.
     * The outer class appears first; inner classes follow.
     */
    fun emit(program: Program, className: String? = null): Map<String, ByteArray> {
        val result = LinkedHashMap<String, ByteArray>()
        val outerName = className ?: javaClass(program.name)
        // Reset per-program state
        pendingSyntheticMethods.clear()
        pendingSyntheticParallelMethods.clear()
        concBlockCounter = 0
        emitsLog = false
        namedConditions.clear()
        pendingEndpoints.clear()
        pendingEndpointMethods.clear()
        for (cond in program.namedConditions) namedConditions[cond.name] = cond.expr
        variantCaseIndex = buildMap {
            for (v in program.variants) for (c in v.cases) put(c.name.uppercase(), v.name to c)
        }
        dataSectionNames = program.dataSection?.items?.map { it.name }?.toSet() ?: emptySet()
        constantExprs.clear()
        for (c in program.constants) constantExprs[c.name] = c.value
        fileDecls = program.fileSection?.files?.associateBy { it.name.uppercase() } ?: emptyMap()

        // Build import alias map: UPPERCASE_ALIAS → "java/time/LocalDate" (original-case JVM path)
        // imp.qualifiedName now has original case from Token.rawValue (e.g. "java.time.LocalDate")
        // imp.alias is UPPERCASE from the lexer (e.g. "LD" from "AS LD")
        importAliasMap = program.imports.associate { imp ->
            val alias = (imp.alias ?: imp.qualifiedName.substringAfterLast('.')).uppercase()
            alias to imp.qualifiedName.replace('.', '/')
        }

        // Pre-scan for LOG usage to decide whether to emit SLF4J field
        emitsLog = hasLogStatements(program)

        // Emit VARIANT sealed class hierarchies
        for (variantDecl in program.variants) {
            val variantResult = emitVariantClasses(variantDecl, outerName)
            result.putAll(variantResult)
        }

        // Emit inner RECORD classes
        for (rec in program.records) {
            val innerName = "$outerName\$${javaClass(rec.name)}"
            result[innerName] = emitRecordClass(rec, outerName)
        }

        // Emit the outer program class
        result[outerName] = emitProgramClass(program, outerName)

        // Emit any synthetic concurrent-branch methods as standalone lambdas
        // (they were already written into the program ClassWriter; nothing extra here)

        return result
    }

    /**
     * Write all class files to [outputDir].
     */
    fun emitToDir(program: Program, outputDir: File, className: String? = null): List<File> {
        val files = mutableListOf<File>()
        for ((name, bytes) in emit(program, className)) {
            val f = File(outputDir, "${name.replace('/', File.separatorChar)}.class")
            f.parentFile?.mkdirs()
            f.writeBytes(bytes)
            files.add(f)
        }
        return files
    }

    // -------------------------------------------------------------------------
    // RECORD inner class
    // -------------------------------------------------------------------------

    private fun emitRecordClass(rec: RecordDecl, outerName: String): ByteArray {
        val cw   = ClassWriter(ClassWriter.COMPUTE_FRAMES)
        val name = "$outerName\$${javaClass(rec.name)}"
        cw.visit(JVM_VERSION, ACC_PUBLIC or ACC_STATIC or ACC_FINAL,
            name, null, "java/lang/Object", null)

        // Fields
        for (f in rec.fields) {
            val jvmType = jvmDescriptor(checker.toKobolType(f.type))
            cw.visitField(ACC_PUBLIC, javaIdent(f.name), jvmType, null, null).visitEnd()
        }

        // Default constructor
        val init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null)
        init.visitCode()
        init.visitVarInsn(ALOAD, 0)
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
        // Initialize BigDecimal fields to ZERO
        for (f in rec.fields) {
            val kt = checker.toKobolType(f.type)
            if (kt is KobolType.MoneyType || kt is KobolType.DecimalType) {
                init.visitVarInsn(ALOAD, 0)
                init.visitFieldInsn(GETSTATIC, BIGDECIMAL, "ZERO", "L$BIGDECIMAL;")
                init.visitFieldInsn(PUTFIELD, name, javaIdent(f.name), "L$BIGDECIMAL;")
            } else if (kt is KobolType.TextType) {
                init.visitVarInsn(ALOAD, 0)
                init.visitLdcInsn("")
                init.visitFieldInsn(PUTFIELD, name, javaIdent(f.name), "L$STRING;")
            } else if (kt is KobolType.UuidType) {
                // Spec §6: UUID defaults to the Nil UUID (else field is null → NPE on use).
                init.visitVarInsn(ALOAD, 0)
                init.visitMethodInsn(INVOKESTATIC, "dev/kobol/stdlib/KobolUuid", "nil", "()Ljava/util/UUID;", false)
                init.visitFieldInsn(PUTFIELD, name, javaIdent(f.name), "Ljava/util/UUID;")
            }
        }
        init.visitInsn(RETURN)
        init.visitMaxs(0, 0)
        init.visitEnd()

        // toString
        emitRecordToString(cw, name, rec)

        // Synthetic shallow-copy method for value semantics (#23): `ADD buf TO list`
        // must snapshot the buffer, not alias it.
        emitRecordCopy(cw, name, rec)

        // Synthetic boolean predicate methods for each CONDITION (e.g. HighValue WHEN balance > 50000)
        emitRecordConditionMethods(cw, rec, outerName)

        cw.visitEnd()
        return cw.toByteArray()
    }

    /**
     * Emit `public <Record> copy()` — a shallow clone (new instance, each field copied
     * across by value/reference). Records are mutable buffers, so appending one to a LIST
     * or assigning it must snapshot it; otherwise later mutation of the buffer is visible
     * through every stored reference (#23). Shallow is correct: nested records are
     * themselves copied at their own copy sites; scalars are immutable.
     */
    private fun emitRecordCopy(cw: ClassWriter, name: String, rec: RecordDecl) {
        val m = cw.visitMethod(ACC_PUBLIC, "copy", "()L$name;", null, null)
        m.visitCode()
        m.visitTypeInsn(NEW, name)
        m.visitInsn(DUP)
        m.visitMethodInsn(INVOKESPECIAL, name, "<init>", "()V", false)
        m.visitVarInsn(ASTORE, 1)                       // local 1 = fresh instance
        for (f in rec.fields) {
            val desc = jvmDescriptor(checker.toKobolType(f.type))
            m.visitVarInsn(ALOAD, 1)                    // target
            m.visitVarInsn(ALOAD, 0)                    // this
            m.visitFieldInsn(GETFIELD, name, javaIdent(f.name), desc)
            m.visitFieldInsn(PUTFIELD, name, javaIdent(f.name), desc)
        }
        m.visitVarInsn(ALOAD, 1)
        m.visitInsn(ARETURN)
        m.visitMaxs(0, 0)
        m.visitEnd()
    }

    private fun emitRecordToString(cw: ClassWriter, className: String, rec: RecordDecl) {
        val mv = cw.visitMethod(ACC_PUBLIC, "toString", "()Ljava/lang/String;", null, null)
        mv.visitCode()

        // Build: "RecordName{field1=" + field1 + ", field2=" + field2 + "}"
        mv.visitTypeInsn(NEW, "java/lang/StringBuilder")
        mv.visitInsn(DUP)
        mv.visitMethodInsn(INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false)

        val prefix = "${rec.name}{"
        mv.visitLdcInsn(prefix)
        appendString(mv)

        rec.fields.forEachIndexed { i, f ->
            if (i > 0) { mv.visitLdcInsn(", "); appendString(mv) }
            mv.visitLdcInsn("${f.name}="); appendString(mv)
            mv.visitVarInsn(ALOAD, 0)
            mv.visitFieldInsn(GETFIELD, className, javaIdent(f.name), jvmDescriptor(checker.toKobolType(f.type)))
            appendValue(mv, checker.toKobolType(f.type))
        }
        mv.visitLdcInsn("}")
        appendString(mv)
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false)
        mv.visitInsn(ARETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    // -------------------------------------------------------------------------
    // Main program class
    // -------------------------------------------------------------------------

    private fun emitProgramClass(program: Program, outerName: String): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES)
        cw.visit(JVM_VERSION, ACC_PUBLIC or ACC_FINAL,
            outerName, null, "java/lang/Object", null)

        // SLF4J static logger field (if any LOG statements are present)
        if (emitsLog) {
            cw.visitField(ACC_PRIVATE or ACC_STATIC or ACC_FINAL,
                LOGGER_FIELD, "L$SLF4J_LOGGER;", null, null).visitEnd()
        }

        // Static fields for DATA section
        program.dataSection?.items?.forEach { item ->
            emitStaticField(cw, item, outerName)
        }

        // Single <clinit> for logger init + DATA field defaults.
        // Also initializes reference-type fields (LIST, MAP, TEXT) that have no explicit initializer.
        val dataItems = program.dataSection?.items ?: emptyList()
        val needsDefaultInit = { item: dev.kobol.parser.ast.DataItem ->
            val kt = if (item.type != null) checker.toKobolType(item.type) else null
            item.initializer == null && (kt is KobolType.ListType || kt is KobolType.MapType || kt is KobolType.TextType || kt is KobolType.RecordRefType || kt is KobolType.MoneyType || kt is KobolType.DecimalType || kt is KobolType.UuidType)
        }
        val needsClinit = emitsLog || dataItems.any { it.initializer != null } || dataItems.any(needsDefaultInit)
        if (needsClinit) {
            val clinit = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null)
            clinit.visitCode()
            if (emitsLog) {
                clinit.visitLdcInsn(Type.getType("L$outerName;"))
                clinit.visitMethodInsn(INVOKESTATIC, SLF4J_FACTORY, "getLogger",
                    "(Ljava/lang/Class;)L$SLF4J_LOGGER;", false)
                clinit.visitFieldInsn(PUTSTATIC, outerName, LOGGER_FIELD, "L$SLF4J_LOGGER;")
            }
            val dummyPos = dev.kobol.lexer.SourcePosition("<clinit>", 0, 0)
            val dummyProc = dev.kobol.parser.ast.ProcedureDecl("<clinit>", emptyList(), null, emptyList(), dummyPos)
            val ctx = MethodContext(clinit, outerName, dummyProc)
            dataItems.forEach { item ->
                val kType = if (item.type != null) checker.toKobolType(item.type)
                            else item.initializer?.let { inferExprType(it) } ?: KobolType.TextType(null)
                if (item.initializer != null) {
                    emitDataInitializer(ctx, item)   // F7: shared with the main() prologue
                } else {
                    // Spec defaults: TEXT → "", LIST → new ArrayList<>(), MAP → new LinkedHashMap<>()
                    when (kType) {
                        is KobolType.TextType -> {
                            clinit.visitLdcInsn("")
                            clinit.visitFieldInsn(PUTSTATIC, outerName, javaIdent(item.name), "Ljava/lang/String;")
                        }
                        is KobolType.ListType -> {
                            clinit.visitTypeInsn(NEW, ARRAYLIST)
                            clinit.visitInsn(DUP)
                            clinit.visitMethodInsn(INVOKESPECIAL, ARRAYLIST, "<init>", "()V", false)
                            clinit.visitFieldInsn(PUTSTATIC, outerName, javaIdent(item.name), "Ljava/util/List;")
                        }
                        is KobolType.MapType -> {
                            clinit.visitTypeInsn(NEW, LINKEDHASHMAP)
                            clinit.visitInsn(DUP)
                            clinit.visitMethodInsn(INVOKESPECIAL, LINKEDHASHMAP, "<init>", "()V", false)
                            clinit.visitFieldInsn(PUTSTATIC, outerName, javaIdent(item.name), "Ljava/util/Map;")
                        }
                        is KobolType.RecordRefType -> {
                            // Record-typed DATA item without initializer → default-construct it
                            // (its no-arg ctor zero-inits MONEY/DECIMAL→ZERO, TEXT→"") so field
                            // access like MOVE 0 TO summary.total-count does not NPE.
                            val recClass = "$outerName\$${javaClass(kType.name)}"
                            clinit.visitTypeInsn(NEW, recClass)
                            clinit.visitInsn(DUP)
                            clinit.visitMethodInsn(INVOKESPECIAL, recClass, "<init>", "()V", false)
                            clinit.visitFieldInsn(PUTSTATIC, outerName, javaIdent(item.name), recDesc(outerName, kType))
                        }
                        is KobolType.MoneyType, is KobolType.DecimalType -> {
                            // Spec §6: MONEY/DECIMAL default to 0. A top-level field of these
                            // types with no initializer would otherwise fall to the JVM `null`
                            // below and NPE on first use. Match the record no-arg ctor exactly
                            // (see emitRecordClass: BigDecimal.ZERO) so top-level and record-field
                            // zeros are identical.
                            clinit.visitFieldInsn(GETSTATIC, BIGDECIMAL, "ZERO", "L$BIGDECIMAL;")
                            clinit.visitFieldInsn(PUTSTATIC, outerName, javaIdent(item.name), "L$BIGDECIMAL;")
                        }
                        is KobolType.UuidType -> {
                            // Spec §6: UUID defaults to the Nil UUID. Same null-landmine class
                            // as MONEY/DECIMAL above; match the record ctor (KobolUuid.nil()).
                            clinit.visitMethodInsn(INVOKESTATIC, "dev/kobol/stdlib/KobolUuid", "nil", "()Ljava/util/UUID;", false)
                            clinit.visitFieldInsn(PUTSTATIC, outerName, javaIdent(item.name), "Ljava/util/UUID;")
                        }
                        else -> { /* JVM default (0, false, null) matches Kobol defaults */ }
                    }
                }
            }
            clinit.visitInsn(RETURN)
            clinit.visitMaxs(0, 0)
            clinit.visitEnd()
        }

        // Static fields for CONFIG section (env-var-backed, loaded in main())
        program.configSection?.items?.forEach { item ->
            emitConfigField(cw, item, outerName)
        }

        // Static methods for each PROCEDURE
        // A procedure is exported (public static) if:
        //   a) it is listed in the MODULE block's EXPORT declarations, OR
        //   b) it was declared directly with the EXPORT PROCEDURE prefix.
        val exportedNames: Set<String> = program.moduleDecl?.exports?.map { it.name }?.toSet() ?: emptySet()
        for (proc in program.procedures) {
            val isExported = proc.exported || proc.name in exportedNames
            emitProcedure(cw, proc, outerName, isExported)
        }

        // Emit pending synthetic concurrent-branch methods
        for ((methodName, owner, stmts) in pendingSyntheticMethods) {
            emitSyntheticBranchMethod(cw, methodName, owner, stmts)
        }
        // Emit pending PARALLEL FOR EACH body methods
        for ((methodName, owner, pair) in pendingSyntheticParallelMethods) {
            emitSyntheticParallelBodyMethod(cw, methodName, owner, pair.first, pair.second)
        }
        // Emit pending endpoint handler methods (GROUP 12)
        for ((owner, proc, ep) in pendingEndpoints) {
            emitEndpointMethodNow(cw, owner, proc, ep)
        }

        // Emit test methods (regular TEST blocks + TABLE TEST rows)
        for (test in program.tests) emitTestMethod(cw, test, outerName)
        for (tt in program.tableTests) {
            for (rowIdx in tt.rows.indices) emitTableTestRowMethod(cw, tt, rowIdx, outerName)
        }
        // Emit runAll() to execute all tests and produce JUnit XML
        if (program.tests.isNotEmpty() || program.tableTests.isNotEmpty()) {
            emitRunAllTests(cw, program, outerName)
        }

        // main(String[]) entry point
        val mainProc = program.procedures.find { it.name == "MAIN" }
        emitMainEntry(cw, outerName, program, mainProc)

        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun emitStaticField(cw: ClassWriter, item: DataItem, owner: String) {
        val kType = if (item.type != null) checker.toKobolType(item.type)
                    else item.initializer?.let { inferExprType(it) } ?: KobolType.TextType(null)
        val desc  = recDesc(owner, kType)
        cw.visitField(ACC_PRIVATE or ACC_STATIC, javaIdent(item.name), desc, null, null).visitEnd()
    }

    /**
     * Emit a DATA item's explicit initializer as `<expr> → PUTSTATIC field` (F7). Shared by the
     * two phases that run initializers: the `<clinit>` (at class load) and the `main()` prologue
     * (re-run AFTER the CONFIG section is loaded, so a config-referencing initializer resolves).
     * The phases differ only in WHEN they run — not in HOW a field is initialized — so the emit is
     * identical and lives here once. `ctx.mv`/`ctx.owner` carry the phase's visitor + owner.
     * Caller guarantees `item.initializer != null`.
     */
    private fun emitDataInitializer(ctx: MethodContext, item: DataItem) {
        val init  = item.initializer!!
        val kType = if (item.type != null) checker.toKobolType(item.type) else inferExprType(init)
        emitExpr(ctx, init)
        if (kType is KobolType.MoneyType || kType is KobolType.DecimalType)
            coerceToDecimalIfNeeded(ctx.mv, inferExprType(init))
        else
            coerceIntToTarget(ctx.mv, inferExprType(init), kType)
        ctx.mv.visitFieldInsn(PUTSTATIC, ctx.owner, javaIdent(item.name), recDesc(ctx.owner, kType))
    }

    // -------------------------------------------------------------------------
    // Static initializer  (<clinit>) for DATA defaults
    // -------------------------------------------------------------------------
    // We emit field initialisation in a class-level MethodContext (clinit).
    // For simplicity, static field defaults are handled per-field in a separate
    // pass (we use a lazy approach: emit the clinit at the end after collecting all fields).

    // -------------------------------------------------------------------------
    // PROCEDURE → private static method
    // -------------------------------------------------------------------------

    private fun emitProcedure(cw: ClassWriter, proc: ProcedureDecl, owner: String, isExported: Boolean = false) {
        if (proc.isAsync) {
            emitAsyncProcedure(cw, proc, owner, isExported)
            return
        }
        val retDesc = if (proc.returnType != null) jvmDescriptor(checker.toKobolType(proc.returnType)) else "V"
        val paramDescs = proc.params.joinToString("") { jvmDescriptor(checker.toKobolType(it.type)) }
        val desc = "($paramDescs)$retDesc"

        val access = if (isExported) ACC_PUBLIC or ACC_STATIC else ACC_PRIVATE or ACC_STATIC
        val mv = cw.visitMethod(access, javaIdent(proc.name), desc, null, null)
        mv.visitCode()

        val ctx = MethodContext(mv, owner, proc)
        // Register params as locals — long (INTEGER) occupies 2 JVM slots
        var slot = 0
        for (p in proc.params) {
            val kobolType = checker.toKobolType(p.type)
            ctx.defineLocal(p.name, kobolType, slot)
            slot += if (kobolType is KobolType.IntegerType) 2 else 1
        }

        emitBlock(ctx, proc.body)

        if (proc.returnType == null) mv.visitInsn(RETURN)
        try {
            mv.visitMaxs(0, 0)
        } catch (e: Exception) {
            throw RuntimeException("ASM frame error in procedure '${proc.name}'", e)
        }
        mv.visitEnd()
    }

    /**
     * Emit an ASYNC PROCEDURE as three JVM methods:
     *  1. `__asyncBody_<name>(params)RetType`  — the actual body
     *  2. `__asyncRunnable_<name>(params, CompletableFuture)V`  — wraps body; completes/completeExceptionally
     *  3. `<name>(params)CompletableFuture`  — creates CF, starts virtual thread, returns CF
     */
    private fun emitAsyncProcedure(cw: ClassWriter, proc: ProcedureDecl, owner: String, isExported: Boolean) {
        val bodyRetDesc  = if (proc.returnType != null) jvmDescriptor(checker.toKobolType(proc.returnType)) else "V"
        val paramDescs   = proc.params.joinToString("") { jvmDescriptor(checker.toKobolType(it.type)) }

        // ---------- 1. Body method ----------
        val bodyName = "__asyncBody_${javaIdent(proc.name)}"
        run {
            val mv = cw.visitMethod(ACC_PRIVATE or ACC_STATIC or ACC_SYNTHETIC,
                bodyName, "($paramDescs)$bodyRetDesc", null, null)
            mv.visitCode()
            val ctx = MethodContext(mv, owner, proc)
            proc.params.forEachIndexed { i, p -> ctx.defineLocal(p.name, checker.toKobolType(p.type), i) }
            emitBlock(ctx, proc.body)
            if (proc.returnType == null) mv.visitInsn(RETURN)
            mv.visitMaxs(0, 0)
            mv.visitEnd()
        }

        // ---------- 2. Runnable wrapper method: (params, CompletableFuture)V ----------
        val runnableName = "__asyncRunnable_${javaIdent(proc.name)}"
        val cfDesc = "L$COMPLETABLE_FUTURE;"
        run {
            val wrapDesc = "($paramDescs${cfDesc})V"
            val mv = cw.visitMethod(ACC_PRIVATE or ACC_STATIC or ACC_SYNTHETIC,
                runnableName, wrapDesc, null, null)
            mv.visitCode()

            // Slots: 0..n-1 = params, n = CompletableFuture (wide params take 2 slots)
            val paramTypes = proc.params.map { checker.toKobolType(it.type) }
            var cfSlot = 0
            paramTypes.forEach { t -> cfSlot += if (isWide(t)) 2 else 1 }

            val lTry   = Label(); val lEnd  = Label()
            val lCatch = Label()
            mv.visitTryCatchBlock(lTry, lEnd, lCatch, "java/lang/Throwable")
            mv.visitLabel(lTry)

            // Call the body method
            paramTypes.forEachIndexed { i, t ->
                val slot = paramTypes.take(i).map { t2 -> if (isWide(t2)) 2 else 1 }.sum()
                loadLocal(mv, t, slot)
            }
            mv.visitMethodInsn(INVOKESTATIC, owner, bodyName, "($paramDescs)$bodyRetDesc", false)

            // Complete the future
            mv.visitVarInsn(ALOAD, cfSlot)
            if (proc.returnType != null) {
                // box if primitive
                val retKType = checker.toKobolType(proc.returnType)
                boxValue(mv, retKType)
                mv.visitMethodInsn(INVOKEVIRTUAL, COMPLETABLE_FUTURE, "complete",
                    "(Ljava/lang/Object;)Z", false)
                mv.visitInsn(POP)
            } else {
                mv.visitInsn(ACONST_NULL)
                mv.visitMethodInsn(INVOKEVIRTUAL, COMPLETABLE_FUTURE, "complete",
                    "(Ljava/lang/Object;)Z", false)
                mv.visitInsn(POP)
            }
            mv.visitLabel(lEnd)
            mv.visitInsn(RETURN)

            // Catch: completeExceptionally
            mv.visitLabel(lCatch)
            mv.visitVarInsn(ALOAD, cfSlot)
            mv.visitInsn(SWAP)
            mv.visitMethodInsn(INVOKEVIRTUAL, COMPLETABLE_FUTURE, "completeExceptionally",
                "(Ljava/lang/Throwable;)Z", false)
            mv.visitInsn(POP)
            mv.visitInsn(RETURN)
            mv.visitMaxs(0, 0)
            mv.visitEnd()
        }

        // ---------- 3. Public entry method: (params)CompletableFuture ----------
        val access = if (isExported) ACC_PUBLIC or ACC_STATIC else ACC_PRIVATE or ACC_STATIC
        run {
            val mv = cw.visitMethod(access, javaIdent(proc.name),
                "($paramDescs)L$COMPLETABLE_FUTURE;", null, null)
            mv.visitCode()

            // new CompletableFuture()
            mv.visitTypeInsn(NEW, COMPLETABLE_FUTURE)
            mv.visitInsn(DUP)
            mv.visitMethodInsn(INVOKESPECIAL, COMPLETABLE_FUTURE, "<init>", "()V", false)
            val paramTypes = proc.params.map { checker.toKobolType(it.type) }
            val cfLocalSlot = paramTypes.map { t -> if (isWide(t)) 2 else 1 }.sum()
            mv.visitVarInsn(ASTORE, cfLocalSlot)

            // Build capturing Runnable via LambdaMetafactory
            val bsm = Handle(H_INVOKESTATIC,
                "java/lang/invoke/LambdaMetafactory", "metafactory",
                "(Ljava/lang/invoke/MethodHandles\$Lookup;Ljava/lang/String;" +
                "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;" +
                "Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)" +
                "Ljava/lang/invoke/CallSite;", false)

            // Load all params + CF onto stack (captured args for invokedynamic)
            paramTypes.forEachIndexed { i, t ->
                val slot = paramTypes.take(i).map { t2 -> if (isWide(t2)) 2 else 1 }.sum()
                loadLocal(mv, t, slot)
            }
            mv.visitVarInsn(ALOAD, cfLocalSlot)

            val captureDesc = "($paramDescs${cfDesc})Ljava/lang/Runnable;"
            mv.visitInvokeDynamicInsn(
                "run", captureDesc, bsm,
                Type.getType("()V"),
                Handle(H_INVOKESTATIC, owner, runnableName, "($paramDescs${cfDesc})V", false),
                Type.getType("()V")
            )

            // Start virtual thread
            mv.visitMethodInsn(INVOKESTATIC, KOBOL_CONCURRENT, "startVirtual",
                "(Ljava/lang/Runnable;)V", false)

            mv.visitVarInsn(ALOAD, cfLocalSlot)
            mv.visitInsn(ARETURN)
            mv.visitMaxs(0, 0)
            mv.visitEnd()
        }
    }

    private fun emitMainEntry(cw: ClassWriter, owner: String, program: Program, mainProc: ProcedureDecl?) {
        val mv = cw.visitMethod(ACC_PUBLIC or ACC_STATIC, "main", "([Ljava/lang/String;)V", null, null)
        mv.visitCode()

        // Emit static field initialisers for DATA section items that have a default value.
        // Use a lightweight synthetic MethodContext so we can reuse emitExpr / emitLiteral.
        val syntheticProc = ProcedureDecl("__main__", emptyList(), null, emptyList(), program.pos)
        val ctx = MethodContext(mv, owner, syntheticProc)

        // Load CONFIG section values from env / .env file (before DATA defaults
        // so DATA initializers can reference config values if needed).
        program.configSection?.items?.forEach { item ->
            emitConfigLoad(ctx, item, owner)
        }

        program.dataSection?.items?.forEach { item ->
            if (item.initializer != null) emitDataInitializer(ctx, item)   // F7: shared with <clinit>
        }

        if (mainProc != null) {
            mv.visitMethodInsn(INVOKESTATIC, owner, javaIdent(mainProc.name),
                "()" + if (mainProc.returnType != null) jvmDescriptor(checker.toKobolType(mainProc.returnType)) else "V", false)
        }
        mv.visitInsn(RETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    // -------------------------------------------------------------------------
    // Statement emission
    // -------------------------------------------------------------------------

    internal fun emitBlock(ctx: MethodContext, stmts: List<Statement>) {
        stmts.forEach { emitStatement(ctx, it) }
    }

    internal fun emitStatement(ctx: MethodContext, stmt: Statement) {
        val mv = ctx.mv
        when (stmt) {
            is DisplayStatement  -> emitDisplay(ctx, stmt)
            is ComputeStatement  -> emitCompute(ctx, stmt)
            is LocalVarDecl      -> emitLocalVarDecl(ctx, stmt)
            is MoveStatement     -> emitMove(ctx, stmt)
            is MapPutStatement   -> emitMapPut(ctx, stmt)
            is MapGetStatement   -> emitMapGet(ctx, stmt)
            is AddStatement      -> emitArith(ctx, stmt.target, "+", stmt.operand, stmt.giving)
            is SubtractStatement -> emitArith(ctx, stmt.from, "-", stmt.operand, stmt.giving)
            is MultiplyStatement -> emitArith(ctx, stmt.right, "*", stmt.left, stmt.giving)
            is DivideStatement   -> emitArith(ctx, stmt.into, "/", stmt.divisor, stmt.giving, stmt.dividingMode)
            is PerformStatement  -> emitPerform(ctx, stmt)
            is CallStatement     -> emitCall(ctx, stmt)
            is IfStatement       -> emitIf(ctx, stmt)
            is WhileStatement    -> emitWhile(ctx, stmt)
            is ForEachStatement  -> emitForEach(ctx, stmt)
            is ParallelForEachStatement -> emitParallelForEach(ctx, stmt)
            is RepeatStatement   -> emitRepeat(ctx, stmt)
            is TryStatement      -> emitTry(ctx, stmt)
            is ReturnStatement   -> emitReturn(ctx, stmt)
            is StopRunStatement  -> emitStopRun(ctx, stmt)
            is RaiseStatement    -> emitRaise(ctx, stmt)
            is LogStatement      -> emitLog(ctx, stmt)
            is ConcurrentBlock   -> emitConcurrentBlock(ctx, stmt)
            is ValidateStatement -> emitValidate(ctx, stmt)
            is MatchStatement    -> emitMatch(ctx, stmt)
            is SleepStatement    -> emitSleep(ctx, stmt)
            is AssertStatement   -> emitAssert(ctx, stmt)
            is MockStatement     -> emitMock(ctx, stmt)
            is HttpCallStatement -> emitHttpCall(ctx, stmt)
            is JdbcConnectStatement    -> emitJdbcConnect(ctx, stmt)
            is JdbcQueryStatement      -> emitJdbcQuery(ctx, stmt)
            is JdbcExecuteStatement    -> emitJdbcExecute(ctx, stmt)
            is JdbcDisconnectStatement -> emitJdbcDisconnect(ctx.mv)
            is ServerStatement   -> emitServer(ctx, stmt)
            is RespondStatement  -> emitRespond(ctx, stmt)
            is OpenStatement  -> emitOpen(ctx, stmt)
            is WriteStatement -> emitWrite(ctx, stmt)
            is CloseStatement -> emitClose(ctx, stmt)
            is ReadStatement  -> {
                // READ ... INTO is not yet wired; FOR EACH over a file is the
                // supported sequential-read path. Left as a no-op for now.
            }
            is WriteJsonStatement -> {
                val JSON_OWN = "dev/kobol/stdlib/KobolJson"
                val mv = ctx.mv
                emitExpr(ctx, stmt.value)
                when (inferExprType(stmt.value)) {
                    is KobolType.IntegerType ->
                        mv.visitMethodInsn(INVOKESTATIC, "java/lang/Long",    "valueOf", "(J)Ljava/lang/Long;",     false)
                    is KobolType.BooleanType ->
                        mv.visitMethodInsn(INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false)
                    else -> {}
                }
                emitExpr(ctx, stmt.filepath)
                val methodName = if (stmt.pretty) "writePrettyToFile" else "writeToFile"
                mv.visitMethodInsn(INVOKESTATIC, JSON_OWN, methodName, "(Ljava/lang/Object;Ljava/lang/String;)V", false)
            }
            is WriteXmlStatement -> {
                val XML_OWN = "dev/kobol/stdlib/KobolXml"
                val mv = ctx.mv
                emitExpr(ctx, stmt.value)
                when (inferExprType(stmt.value)) {
                    is KobolType.IntegerType ->
                        mv.visitMethodInsn(INVOKESTATIC, "java/lang/Long",    "valueOf", "(J)Ljava/lang/Long;",     false)
                    is KobolType.BooleanType ->
                        mv.visitMethodInsn(INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false)
                    else -> {}
                }
                emitExpr(ctx, stmt.filepath)
                val methodName = if (stmt.pretty) "writePrettyToFile" else "writeToFile"
                mv.visitMethodInsn(INVOKESTATIC, XML_OWN, methodName, "(Ljava/lang/Object;Ljava/lang/String;)V", false)
            }
            is ParseJsonStatement -> {
                val JSON_OWN = "dev/kobol/stdlib/KobolJson"
                val mv = ctx.mv
                if (stmt.asList) {
                    // PARSE JSON ... AS LIST: call parseJsonArray(json) → ArrayList, then store
                    emitExpr(ctx, stmt.source)
                    val method = if (stmt.fromFile) "parseFileArray" else "parseJsonArray"
                    mv.visitMethodInsn(INVOKESTATIC, JSON_OWN, method, "(Ljava/lang/String;)Ljava/util/ArrayList;", false)
                    emitStore(ctx, stmt.into)
                } else {
                    loadRef(ctx, stmt.into)
                    emitExpr(ctx, stmt.source)
                    val method = if (stmt.fromFile) "parseFileInto" else "parseInto"
                    mv.visitMethodInsn(INVOKESTATIC, JSON_OWN, method, "(Ljava/lang/Object;Ljava/lang/String;)V", false)
                }
            }
            is ParseXmlStatement -> {
                val XML_OWN = "dev/kobol/stdlib/KobolXml"
                val mv = ctx.mv
                if (stmt.asList) {
                    emitExpr(ctx, stmt.source)
                    val method = if (stmt.fromFile) "parseFileElements" else "parseXmlElements"
                    mv.visitMethodInsn(INVOKESTATIC, XML_OWN, method, "(Ljava/lang/String;)Ljava/util/ArrayList;", false)
                    emitStore(ctx, stmt.into)
                } else {
                    loadRef(ctx, stmt.into)
                    emitExpr(ctx, stmt.source)
                    val method = if (stmt.fromFile) "parseFileInto" else "parseInto"
                    mv.visitMethodInsn(INVOKESTATIC, XML_OWN, method, "(Ljava/lang/Object;Ljava/lang/String;)V", false)
                }
            }
            is WithPrecisionStatement -> emitWithPrecision(ctx, stmt)
            is RoundStatement         -> emitRound(ctx, stmt)
            is AwaitStatement -> emitAwait(ctx, stmt)

            // ── Group 13: NoSQL document store ─────────────────────────────
            is NoSqlConnectStatement    -> emitNoSqlConnect(ctx, stmt)
            is NoSqlDisconnectStatement -> ctx.mv.visitMethodInsn(INVOKESTATIC, KOBOL_MONGO, "disconnect", "()V", false)
            is NoSqlFindStatement       -> emitNoSqlFind(ctx, stmt)
            is NoSqlSaveStatement       -> emitNoSqlSave(ctx, stmt)
            is NoSqlDeleteStatement     -> emitNoSqlDelete(ctx, stmt)
            is NoSqlCountStatement      -> emitNoSqlCount(ctx, stmt)

            // ── Group 14: Cache / key-value store ──────────────────────────
            is CacheConnectStatement    -> {
                emitExpr(ctx, stmt.url)
                ctx.mv.visitMethodInsn(INVOKESTATIC, KOBOL_REDIS, "connect", "(Ljava/lang/String;)V", false)
            }
            is CacheDisconnectStatement -> ctx.mv.visitMethodInsn(INVOKESTATIC, KOBOL_REDIS, "disconnect", "()V", false)
            is CacheGetStatement        -> emitCacheGet(ctx, stmt)
            is CacheSetStatement        -> emitCacheSet(ctx, stmt)
            is CacheDeleteStatement     -> {
                emitExpr(ctx, stmt.key)
                ctx.mv.visitMethodInsn(INVOKESTATIC, KOBOL_REDIS, "delete", "(Ljava/lang/String;)J", false)
                ctx.mv.visitInsn(POP2)
            }
            is CacheExistsStatement     -> emitCacheExists(ctx, stmt)
        }
    }

    // WITH PRECISION — push MathContext, run body, pop in finally
    internal fun jvmDescriptor(type: KobolType): String = when (type) {
        is KobolType.IntegerType    -> "J"
        is KobolType.SmallIntType   -> "I"
        is KobolType.BooleanType    -> "Z"
        is KobolType.TextType       -> "Ljava/lang/String;"
        is KobolType.DecimalType,
        is KobolType.MoneyType      -> "L$BIGDECIMAL;"
        is KobolType.DateType       -> "L$LOCALDATE;"
        is KobolType.TimeType       -> "L$LOCALTIME;"
        is KobolType.DateTimeType   -> "L$LOCALDATETIME;"
        is KobolType.ListType       -> "Ljava/util/List;"
        is KobolType.MapType        -> "Ljava/util/Map;"
        is KobolType.FutureType     -> "Ljava/util/concurrent/CompletableFuture;"
        is KobolType.RecordRefType  -> "Ljava/lang/Object;" // inner class—caller uses correct name
        is KobolType.VariantRefType -> "Ljava/lang/Object;" // sealed variant base class
        is KobolType.UuidType       -> "Ljava/util/UUID;"
        is KobolType.JavaObjectType,
        is KobolType.UnknownType    -> "Ljava/lang/Object;"
        is KobolType.VoidType       -> "V"
    }

    internal fun recDesc(owner: String, type: KobolType): String = when (type) {
        is KobolType.RecordRefType -> "L$owner\$${javaClass(type.name)};"
        else -> jvmDescriptor(type)
    }

    internal fun isWide(type: KobolType) = type is KobolType.IntegerType

    internal fun loadLocal(mv: MethodVisitor, type: KobolType, slot: Int) = when {
        type is KobolType.IntegerType -> mv.visitVarInsn(LLOAD, slot)
        type is KobolType.SmallIntType || type is KobolType.BooleanType -> mv.visitVarInsn(ILOAD, slot)
        else -> mv.visitVarInsn(ALOAD, slot)
    }

    internal fun storeLocal(mv: MethodVisitor, type: KobolType, slot: Int) = when {
        type is KobolType.IntegerType -> mv.visitVarInsn(LSTORE, slot)
        type is KobolType.SmallIntType || type is KobolType.BooleanType -> mv.visitVarInsn(ISTORE, slot)
        else -> mv.visitVarInsn(ASTORE, slot)
    }

    internal fun popValue(mv: MethodVisitor, type: KobolType) =
        if (isWide(type)) mv.visitInsn(POP2) else mv.visitInsn(POP)

    /** Box a JVM primitive (long → Long, int/boolean → Integer/Boolean) to Object. Object types are left as-is. */
    internal fun boxValue(mv: MethodVisitor, type: KobolType) {
        when (type) {
            is KobolType.IntegerType  -> mv.visitMethodInsn(INVOKESTATIC, "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", false)
            is KobolType.SmallIntType -> mv.visitMethodInsn(INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false)
            is KobolType.BooleanType  -> mv.visitMethodInsn(INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false)
            else -> {} // already reference type
        }
    }

    internal fun castFromObject(mv: MethodVisitor, type: KobolType, owner: String? = null) {
        val desc = when (type) {
            is KobolType.IntegerType -> "java/lang/Long"
            is KobolType.SmallIntType -> "java/lang/Integer"
            is KobolType.BooleanType -> "java/lang/Boolean"
            is KobolType.TextType -> STRING
            is KobolType.DecimalType, is KobolType.MoneyType -> BIGDECIMAL
            is KobolType.DateType -> LOCALDATE
            is KobolType.ListType -> ARRAYLIST
            is KobolType.RecordRefType -> {
                val innerClass = owner?.let { "$it\$${javaClass(type.name)}" }
                if (innerClass != null) { mv.visitTypeInsn(CHECKCAST, innerClass); return }
                else return
            }
            else -> return  // leave as Object
        }
        mv.visitTypeInsn(CHECKCAST, desc)
        when (type) {
            is KobolType.IntegerType  -> mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Long", "longValue", "()J", false)
            is KobolType.SmallIntType -> mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Integer", "intValue", "()I", false)
            is KobolType.BooleanType  -> mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Boolean", "booleanValue", "()Z", false)
            else -> {}
        }
    }

    /** Append the top-of-stack String to a StringBuilder below it. */
    internal fun appendString(mv: MethodVisitor) {
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
            "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false)
    }

    /** Convert TOS to String and append to StringBuilder below it. */
    internal fun appendValue(mv: MethodVisitor, type: KobolType) {
        boxAndToString(mv, type)
        appendString(mv)
    }

    internal fun boxAndToString(mv: MethodVisitor, type: KobolType) {
        when (type) {
            is KobolType.IntegerType -> mv.visitMethodInsn(INVOKESTATIC, "java/lang/Long", "toString", "(J)Ljava/lang/String;", false)
            is KobolType.SmallIntType -> mv.visitMethodInsn(INVOKESTATIC, "java/lang/Integer", "toString", "(I)Ljava/lang/String;", false)
            is KobolType.BooleanType -> mv.visitMethodInsn(INVOKESTATIC, "java/lang/Boolean", "toString", "(Z)Ljava/lang/String;", false)
            else -> mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Object", "toString", "()Ljava/lang/String;", false)
        }
    }

    internal fun resolveSymbolType(ctx: MethodContext, name: String): KobolType {
        val local = ctx.getLocal(name)
        if (local != null) return local.type
        return when (val sym = checker.symbols.resolve(name)) {
            is Symbol.Variable        -> sym.type
            is Symbol.Constant        -> sym.type
            is Symbol.RecordSymbol    -> KobolType.RecordRefType(sym.name)
            is Symbol.VariantSymbol   -> KobolType.VariantRefType(sym.name)
            is Symbol.ProcedureSymbol -> sym.returnType ?: KobolType.VoidType
            is Symbol.NamedCondition  -> KobolType.BooleanType
            null -> KobolType.UnknownType
        }
    }

    internal fun inferExprType(expr: Expression): KobolType {
        val t = checker.typeOf(expr)
        if (t != KobolType.UnknownType) return t
        return when (expr) {
            is Literal -> when (expr.kind) {
                LiteralKind.INTEGER -> KobolType.IntegerType
                LiteralKind.DECIMAL -> KobolType.DecimalType(10, 2)
                LiteralKind.STRING  -> KobolType.TextType(null)
                LiteralKind.BOOLEAN -> KobolType.BooleanType
            }
            is StringTemplateExpr -> KobolType.TextType(null)
            // `__self.field` inside a synthesised record CONDITION method: resolve the
            // field type from the record symbol (the checker never saw these nodes).
            is Reference -> {
                val rec = conditionSelfRecord
                if (rec != null && expr.parts.firstOrNull() == "__self" && expr.parts.size >= 2) {
                    val recSym = checker.symbols.resolve(rec) as? Symbol.RecordSymbol
                    val field  = expr.parts[1]
                    when {
                        recSym == null -> KobolType.UnknownType
                        recSym.conditions.containsKey(field) -> KobolType.BooleanType
                        else -> recSym.fields[field] ?: KobolType.UnknownType
                    }
                } else KobolType.UnknownType
            }
            else -> KobolType.UnknownType
        }
    }

    // -------------------------------------------------------------------------
    // Name helpers (mirrored from JavaTranspiler)
    // Cache results — both functions are pure and the same identifier can appear
    // dozens of times during codegen (field accesses, method calls, type descriptors).
    // -------------------------------------------------------------------------

    private val javaClassCache = HashMap<String, String>()
    private val javaIdentCache = HashMap<String, String>()

    fun javaClass(kobolName: String): String =
        javaClassCache.getOrPut(kobolName) {
            kobolName.split("-").joinToString("") { part ->
                part.lowercase().replaceFirstChar { c -> c.uppercase() }
            }
        }

    fun javaIdent(kobolName: String): String =
        javaIdentCache.getOrPut(kobolName) {
            val parts = kobolName.lowercase().split("-")
            parts[0] + parts.drop(1).joinToString("") { it.replaceFirstChar { c -> c.uppercase() } }
        }

    /** Synthetic method name for a record CONDITION predicate. `$`-prefixed to never collide with a field name. */
    internal fun condMethodName(conditionName: String): String = "cond\$${javaIdent(conditionName)}"

    // -------------------------------------------------------------------------
    // LOG statement
    // -------------------------------------------------------------------------

    inner class MethodContext(
        val mv: MethodVisitor,
        val owner: String,
        val proc: ProcedureDecl,
    ) {
        private val locals = LinkedHashMap<String, LocalVar>()
        // nextSlot tracks the next free JVM local variable slot.
        // INTEGER (long) occupies 2 slots; all other types occupy 1.
        private var nextSlot: Int = proc.params.sumOf { p ->
            if (checker.toKobolType(p.type) is KobolType.IntegerType) 2 else 1
        }

        init {
            var s = 0
            for (p in proc.params) {
                val kt = checker.toKobolType(p.type)
                locals[p.name] = LocalVar(p.name, kt, s)
                s += if (kt is KobolType.IntegerType) 2 else 1
            }
        }

        fun defineLocal(name: String, type: KobolType, slot: Int) {
            locals[name] = LocalVar(name, type, slot)
            // Ensure nextSlot is always past the highest defined slot
            val slotEnd = slot + if (type is KobolType.IntegerType) 2 else 1
            if (slotEnd > nextSlot) nextSlot = slotEnd
        }

        fun allocLocal(name: String, type: KobolType): Int {
            val slot = nextSlot
            locals[name] = LocalVar(name, type, slot)
            nextSlot += if (type is KobolType.IntegerType) 2 else 1
            return slot
        }

        fun getLocal(name: String): LocalVar? = locals[name]
    }
}
