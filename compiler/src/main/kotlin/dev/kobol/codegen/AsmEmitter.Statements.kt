package dev.kobol.codegen

import dev.kobol.codegen.AsmEmitter.MethodContext
import dev.kobol.parser.ast.*
import dev.kobol.semantic.KobolType
import dev.kobol.semantic.Symbol
import dev.kobol.semantic.TypeChecker
import org.objectweb.asm.*
import org.objectweb.asm.Opcodes.*
import java.io.File

internal fun AsmEmitter.emitMock(ctx: MethodContext, stmt: MockStatement) {
        val mv = ctx.mv
        mv.visitLdcInsn(stmt.procedureName)
        emitExpr(ctx, stmt.returns)
        // Box primitives for Object storage
        when (inferExprType(stmt.returns)) {
            is KobolType.IntegerType ->
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", false)
            is KobolType.BooleanType ->
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false)
            else -> {}
        }
        mv.visitMethodInsn(INVOKESTATIC, "dev/kobol/runtime/KobolMockRegistry",
            "register", "(Ljava/lang/String;Ljava/lang/Object;)V", false)
    }

    /** Emit a private static void __test_<sanitised name>() method for a TestDecl. */
internal fun AsmEmitter.emitDisplay(ctx: MethodContext, stmt: DisplayStatement) {
        val mv = ctx.mv
        mv.visitFieldInsn(GETSTATIC, SYSTEM, "out", "L$PRINTSTREAM;")
        if (stmt.values.size == 1) {
            emitExprAsString(ctx, stmt.values[0])
        } else {
            // Concatenate via StringBuilder
            mv.visitTypeInsn(NEW, "java/lang/StringBuilder")
            mv.visitInsn(DUP)
            mv.visitMethodInsn(INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false)
            stmt.values.forEachIndexed { i, v ->
                if (i > 0) { mv.visitLdcInsn(" "); appendString(mv) }
                emitExprAsString(ctx, v)
                appendString(mv)
            }
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false)
        }
        mv.visitMethodInsn(INVOKEVIRTUAL, PRINTSTREAM, "println", "(Ljava/lang/String;)V", false)
    }


internal fun AsmEmitter.emitCompute(ctx: MethodContext, stmt: ComputeStatement) {
        if (stmt.target.parts.size == 1) {
            val name = stmt.target.parts[0]
            val existingLocal = ctx.getLocal(name)
            if (existingLocal != null) {
                emitExpr(ctx, stmt.expr)
                storeLocal(ctx.mv, existingLocal.type, existingLocal.slot)
            } else if (name in dataSectionNames) {
                emitExpr(ctx, stmt.expr)
                emitStore(ctx, stmt.target)
            } else {
                val kType = inferExprType(stmt.expr)
                emitExpr(ctx, stmt.expr)
                val slot = ctx.allocLocal(name, kType)
                storeLocal(ctx.mv, kType, slot)
            }
        } else {
            emitExpr(ctx, stmt.expr)
            emitStore(ctx, stmt.target)
        }
    }

internal fun AsmEmitter.emitLocalVarDecl(ctx: MethodContext, stmt: LocalVarDecl) {
        val kType = stmt.type?.let { checker.toKobolType(it) } ?: inferExprType(stmt.initializer)
        emitExpr(ctx, stmt.initializer)
        val slot = ctx.allocLocal(stmt.name, kType)
        storeLocal(ctx.mv, kType, slot)
    }

internal fun AsmEmitter.emitMove(ctx: MethodContext, stmt: MoveStatement) {
        val mv = ctx.mv
        emitExpr(ctx, stmt.from)
        // Coerce INTEGER literal to BigDecimal when target field is MONEY/DECIMAL
        val fromType = inferExprType(stmt.from)
        if (fromType is KobolType.IntegerType) {
            val targetType = resolveRefFinalType(ctx, stmt.to)
            if (targetType is KobolType.MoneyType || targetType is KobolType.DecimalType) {
                coerceToDecimalIfNeeded(mv, fromType)
            }
        }
        emitStore(ctx, stmt.to)
    }

    /**
     * Resolve the final type of a reference, following field access chains
     * for record fields. Used to determine the target type before store.
     */
    internal fun AsmEmitter.resolveRefFinalType(ctx: MethodContext, ref: Reference): KobolType {
        val name = ref.parts[0]
        var type = ctx.getLocal(name)?.type ?: resolveSymbolType(ctx, name)
        if (ref.parts.size > 1 && type is KobolType.RecordRefType) {
            val recSym = checker.symbols.resolve(type.name) as? Symbol.RecordSymbol
            if (recSym != null) {
                type = recSym.fields[ref.parts[1]]
                    ?: if (recSym.conditions.containsKey(ref.parts[1])) KobolType.BooleanType else type
            }
        }
        return type
    }

internal fun AsmEmitter.emitArith(ctx: MethodContext, target: Reference, op: String, operand: Expression, giving: Reference?, dividingMode: String? = null) {
        val mv  = ctx.mv
        // Resolve the actual target field type. When the target is a record field (e.g. summary.amount),
        // resolveSymbolType returns RecordRefType — but arithmetic needs the field's own type (MONEY, INTEGER, etc).
        val kType = run {
            val baseType = resolveSymbolType(ctx, target.parts[0])
            if (baseType is KobolType.RecordRefType && target.parts.size > 1) {
                val recSym = checker.symbols.resolve(baseType.name) as? dev.kobol.semantic.Symbol.RecordSymbol
                recSym?.fields?.get(target.parts[1]) ?: baseType
            } else baseType
        }
        val dest  = giving ?: target

        // ADD item TO list — list append (O(1) amortized for ArrayList)
        if (op == "+" && kType is KobolType.ListType) {
            loadRef(ctx, target)                   // ArrayList on stack
            emitExpr(ctx, operand)                 // item on stack
            // Box primitives: ArrayList.add(Object)
            when (kType.elementType) {
                is KobolType.IntegerType  -> mv.visitMethodInsn(INVOKESTATIC, "java/lang/Long",    "valueOf", "(J)Ljava/lang/Long;",    false)
                is KobolType.SmallIntType -> mv.visitMethodInsn(INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false)
                is KobolType.BooleanType  -> mv.visitMethodInsn(INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false)
                else -> { /* reference types (String, BigDecimal, object) need no boxing */ }
            }
            // List.add is an interface method; field type is Ljava/util/List; not ArrayList
            mv.visitMethodInsn(INVOKEINTERFACE, "java/util/List", "add", "(Ljava/lang/Object;)Z", true)
            mv.visitInsn(POP)   // discard boolean return
            return              // no emitStore — list mutated in place
        }

        if (kType is KobolType.MoneyType || kType is KobolType.DecimalType) {
            loadRef(ctx, target)
            emitExpr(ctx, operand)
            val methodName = when (op) { "+" -> "add"; "-" -> "subtract"; "*" -> "multiply"; else -> "divide" }
            if (op == "/") {
                val roundingMode = dividingMode ?: "HALF_EVEN"
                // If a WITH PRECISION context is active, use the MathContext-aware overload
                mv.visitMethodInsn(INVOKESTATIC, MATH_CTX, "current", "()L$JMATH_CTX;", false)
                val lNoCtx = Label(); val lDone = Label()
                mv.visitInsn(DUP)
                mv.visitJumpInsn(IFNULL, lNoCtx)
                // non-null path: stack is lhs, rhs, mc — correct for divide(BigDecimal, MathContext)
                mv.visitMethodInsn(INVOKEVIRTUAL, BIGDECIMAL, "divide", "(L$BIGDECIMAL;L$JMATH_CTX;)L$BIGDECIMAL;", false)
                mv.visitJumpInsn(GOTO, lDone)
                mv.visitLabel(lNoCtx)
                mv.visitInsn(POP)   // pop null mc
                mv.visitFieldInsn(GETSTATIC, ROUNDING, roundingMode, "Ljava/math/RoundingMode;")
                mv.visitMethodInsn(INVOKEVIRTUAL, BIGDECIMAL, methodName, "(L$BIGDECIMAL;Ljava/math/RoundingMode;)L$BIGDECIMAL;", false)
                mv.visitLabel(lDone)
            } else {
                // For add/subtract/multiply: use MathContext overload when active
                mv.visitMethodInsn(INVOKESTATIC, MATH_CTX, "current", "()L$JMATH_CTX;", false)
                val lNoCtx = Label(); val lDone = Label()
                mv.visitInsn(DUP)
                mv.visitJumpInsn(IFNULL, lNoCtx)
                mv.visitMethodInsn(INVOKEVIRTUAL, BIGDECIMAL, methodName, "(L$BIGDECIMAL;L$JMATH_CTX;)L$BIGDECIMAL;", false)
                mv.visitJumpInsn(GOTO, lDone)
                mv.visitLabel(lNoCtx)
                mv.visitInsn(POP)
                mv.visitMethodInsn(INVOKEVIRTUAL, BIGDECIMAL, methodName, "(L$BIGDECIMAL;)L$BIGDECIMAL;", false)
                mv.visitLabel(lDone)
            }
        } else {
            loadRef(ctx, target)
            emitExpr(ctx, operand)
            when (op) {
                "+" -> mv.visitInsn(LADD)
                "-" -> mv.visitInsn(LSUB)
                "*" -> mv.visitInsn(LMUL)
                "/" -> mv.visitInsn(LDIV)
            }
        }
        emitStore(ctx, dest)
    }

/**
 * ROUND target TO scale [USING mode]
 * Rounds the DECIMAL/MONEY variable in place using KobolMath.round/roundWithMode.
 * Scale is a Kobol INTEGER (JVM long) — uses the Long overloads added in Phase 14.
 */
internal fun AsmEmitter.emitRound(ctx: MethodContext, stmt: RoundStatement) {
    val mv = ctx.mv
    val BD = "L$BIGDECIMAL;"
    loadRef(ctx, stmt.target)         // BigDecimal on stack
    emitExpr(ctx, stmt.scale)         // Long (Kobol INTEGER) on stack
    if (stmt.mode != null) {
        mv.visitLdcInsn(stmt.mode)    // String mode constant
        mv.visitMethodInsn(INVOKESTATIC, MATH_OWN, "roundWithMode", "(${BD}JLjava/lang/String;)$BD", false)
    } else {
        mv.visitMethodInsn(INVOKESTATIC, MATH_OWN, "round", "(${BD}J)$BD", false)
    }
    emitStore(ctx, stmt.target)
}

internal fun AsmEmitter.emitPerform(ctx: MethodContext, stmt: PerformStatement) {
        val mv = ctx.mv

        // Cross-module call: PERFORM Alias.ProcedureName USING ...
        if (stmt.moduleAlias != null) {
            val mod = moduleRegistry.resolveByAlias(stmt.moduleAlias)
                ?: error("Unknown module alias '${stmt.moduleAlias}' at emit time")
            val proc = mod.procedures[stmt.procedureName.uppercase()]
                ?: error("Module '${mod.moduleName}' has no exported procedure '${stmt.procedureName}'")
            val targetClass = mod.jvmClassName
            val methodName  = javaIdent(stmt.procedureName)
            val paramDescs  = proc.params.joinToString("") { jvmDescriptor(it.type) }
            val retDesc     = proc.returnType?.let { jvmDescriptor(it) } ?: "V"
            stmt.args.forEach { emitExpr(ctx, it) }
            mv.visitMethodInsn(INVOKESTATIC, targetClass, methodName, "($paramDescs)$retDesc", false)
            if (proc.returnType != null) popValue(mv, proc.returnType)
            return
        }

        val sym = checker.symbols.resolve(stmt.procedureName) as? Symbol.ProcedureSymbol ?: return
        val paramDescs = sym.params.joinToString("") { jvmDescriptor(it.type) }

        // ASYNC PROCEDURE: call returns CompletableFuture; store into GIVING if provided
        if (sym.isAsync) {
            stmt.args.forEach { emitExpr(ctx, it) }
            mv.visitMethodInsn(INVOKESTATIC, ctx.owner, javaIdent(stmt.procedureName),
                "($paramDescs)L$COMPLETABLE_FUTURE;", false)
            if (stmt.giving != null) emitStore(ctx, stmt.giving)
            else mv.visitInsn(POP)
            return
        }

        val retDesc    = sym.returnType?.let { jvmDescriptor(it) } ?: "V"
        // For RETURNING procedures: if mocked, skip the real call and discard mock value
        // (capture via COMPUTE Proc() in expression context — see emitUserProcCallExpr)
        if (sym.returnType != null) {
            val MOCK_REG   = "dev/kobol/runtime/KobolMockRegistry"
            val labelReal  = Label(); val labelAfter = Label()
            mv.visitLdcInsn(stmt.procedureName)
            mv.visitMethodInsn(INVOKESTATIC, MOCK_REG, "isMocked", "(Ljava/lang/String;)Z", false)
            mv.visitJumpInsn(IFEQ, labelReal)
            // Mock path: discard (result captured through COMPUTE Proc() expression, not PERFORM)
            mv.visitJumpInsn(GOTO, labelAfter)
            mv.visitLabel(labelReal)
            stmt.args.forEachIndexed { _, arg -> emitExpr(ctx, arg) }
            mv.visitMethodInsn(INVOKESTATIC, ctx.owner, javaIdent(stmt.procedureName), "($paramDescs)$retDesc", false)
            popValue(mv, sym.returnType)
            mv.visitLabel(labelAfter)
        } else {
            val MOCK_REG2 = "dev/kobol/runtime/KobolMockRegistry"
            val labelAfterVoid = Label()
            mv.visitLdcInsn(stmt.procedureName)
            mv.visitMethodInsn(INVOKESTATIC, MOCK_REG2, "isMocked", "(Ljava/lang/String;)Z", false)
            mv.visitJumpInsn(IFNE, labelAfterVoid)  // if mocked, skip the call entirely
            stmt.args.forEachIndexed { _, arg -> emitExpr(ctx, arg) }
            mv.visitMethodInsn(INVOKESTATIC, ctx.owner, javaIdent(stmt.procedureName), "($paramDescs)$retDesc", false)
            mv.visitLabel(labelAfterVoid)
        }
    }

    /**
     * AWAIT future-var INTO result-var
     * Calls CompletableFuture.join() and casts/stores the result.
     */
internal fun AsmEmitter.emitAwait(ctx: MethodContext, stmt: AwaitStatement) {
        val mv = ctx.mv
        loadRef(ctx, stmt.future)
        mv.visitMethodInsn(INVOKEVIRTUAL, COMPLETABLE_FUTURE, "join", "()Ljava/lang/Object;", false)
        val intoType = resolveSymbolType(ctx, stmt.into.parts[0])
        castFromObject(mv, intoType, ctx.owner)
        emitStore(ctx, stmt.into)
    }

internal fun AsmEmitter.emitCall(ctx: MethodContext, stmt: CallStatement) {
        val mv = ctx.mv
        // stmt.method has original case from Token.rawValue ("LocalDate.now", "kobol.security.sha256")
        // Owner resolution order:
        //   1. Single-part + import alias → INVOKESTATIC (e.g. CALL LocalDate.now)
        //   2. Single-part + stdlib path  → INVOKESTATIC (e.g. CALL kobol.security.sha256)
        //   3. Single-part + DATA/local variable → INVOKEVIRTUAL or INVOKEINTERFACE (instance call)
        //   4. Multi-part path → INVOKESTATIC via STDLIB_OWNERS or original-case FQN
        val parts      = stmt.method.split(".")
        val methodName = parts.last()
        val ownerParts = parts.dropLast(1)

        // Helper: emit static call after args are ready
        fun doStaticCall(owner: String) {
            stmt.args.forEach { emitExpr(ctx, it) }
            val argDesc = stmt.args.joinToString("") { jvmDescriptor(inferExprType(it)) }
            val retDesc = if (stmt.giving != null) jvmDescriptor(resolveSymbolType(ctx, stmt.giving.parts[0])) else "Ljava/lang/Object;"
            mv.visitMethodInsn(INVOKESTATIC, owner, methodName, "($argDesc)$retDesc", false)
            if (stmt.giving != null) emitStore(ctx, stmt.giving)
            else if (retDesc != "V") mv.visitInsn(POP)
        }

        if (ownerParts.size == 1) {
            val alias     = ownerParts[0].uppercase()
            val staticFqn = importAliasMap[alias] ?: STDLIB_OWNERS[ownerParts[0].lowercase()]

            if (staticFqn != null) {
                doStaticCall(staticFqn); return
            }

            // Detect instance call: alias resolves to a DATA field or local variable
            val receiverType: KobolType? = ctx.getLocal(alias)?.type
                ?: resolveSymbolType(ctx, alias).takeIf { it !is KobolType.UnknownType }
            val receiverClass = receiverType?.let { jvmClassForInstanceCall(it) }

            if (receiverClass != null) {
                // Load receiver, emit args, dispatch INVOKEVIRTUAL / INVOKEINTERFACE
                loadRef(ctx, dev.kobol.parser.ast.Reference(listOf(alias), stmt.pos))
                stmt.args.forEach { emitExpr(ctx, it) }
                val argDesc = stmt.args.joinToString("") { jvmDescriptor(inferExprType(it)) }
                val retDesc = if (stmt.giving != null) jvmDescriptor(resolveSymbolType(ctx, stmt.giving.parts[0])) else "V"
                val isIface = receiverClass in JVM_INTERFACE_TYPES
                mv.visitMethodInsn(if (isIface) INVOKEINTERFACE else INVOKEVIRTUAL,
                    receiverClass, methodName, "($argDesc)$retDesc", isIface)
                if (stmt.giving != null) emitStore(ctx, stmt.giving)
                else if (retDesc != "V") mv.visitInsn(POP)
                return
            }

            doStaticCall(alias.lowercase())
        } else {
            // Check if the first part is an import alias (e.g. DateFmt.ISO_LOCAL_DATE.format)
            val alias     = ownerParts[0].uppercase()
            val aliasFqn  = importAliasMap[alias]
            if (aliasFqn != null && ownerParts.size >= 2) {
                // Pattern: alias.STATIC_FIELD.method(args)
                // Emit: GETSTATIC to load the static field as receiver,
                // then use KobolRuntime.invokeInstanceMethod for reflective dispatch.
                val fieldParts = ownerParts.drop(1)  // [field1, ...]
                val staticFieldName = fieldParts[0].replace("-", "")
                val fieldDesc = "L$aliasFqn;"
                mv.visitFieldInsn(GETSTATIC, aliasFqn, staticFieldName, fieldDesc)
                // Save receiver in a temp local
                val receiverSlot = ctx.allocLocal("__call_rx", KobolType.JavaObjectType)
                storeLocal(mv, KobolType.JavaObjectType, receiverSlot)
                // Emit all args
                stmt.args.forEach { emitExpr(ctx, it) }
                // Save each arg in a temp local
                val argSlots = stmt.args.map { arg ->
                    val t = inferExprType(arg)
                    val s = ctx.allocLocal("__call_a", t)
                    storeLocal(mv, t, s)
                    s to t
                }
                // Load receiver
                loadLocal(mv, KobolType.JavaObjectType, receiverSlot)
                // Push method name
                mv.visitLdcInsn(methodName)
                // Build Object[] for varargs
                if (argSlots.size in 0..5) mv.visitInsn(ICONST_0 + argSlots.size)
                else mv.visitIntInsn(BIPUSH, argSlots.size)
                mv.visitTypeInsn(ANEWARRAY, "java/lang/Object")
                for ((i, pair) in argSlots.withIndex()) {
                    val slot = pair.first; val t = pair.second
                    mv.visitInsn(DUP)
                    if (i in 0..5) mv.visitInsn(ICONST_0 + i)
                    else mv.visitIntInsn(BIPUSH, i)
                    loadLocal(mv, t, slot)
                    // Box primitives
                    when (t) {
                        is KobolType.IntegerType -> mv.visitMethodInsn(
                            INVOKESTATIC, "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", false)
                        is KobolType.SmallIntType -> mv.visitMethodInsn(
                            INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false)
                        is KobolType.BooleanType -> mv.visitMethodInsn(
                            INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false)
                        else -> { /* already reference type */ }
                    }
                    mv.visitInsn(AASTORE)
                }
                // Call KobolRuntime.invokeInstanceMethod
                mv.visitMethodInsn(INVOKESTATIC, "dev/kobol/runtime/KobolRuntime",
                    "invokeInstanceMethod",
                    "(Ljava/lang/Object;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/Object;", false)
                // Cast and/or store result
                if (stmt.giving != null) {
                    val targetType = resolveRefFinalType(ctx, stmt.giving)
                    val targetDesc = jvmDescriptor(targetType)
                    if (targetDesc.startsWith("L") && targetDesc != "Ljava/lang/Object;") {
                        mv.visitTypeInsn(CHECKCAST, targetDesc.removePrefix("L").removeSuffix(";"))
                    }
                    emitStore(ctx, stmt.giving)
                } else {
                    mv.visitInsn(POP)
                }
            } else {
                val lowPath   = ownerParts.joinToString("/") { it.lowercase() }
                val staticFqn = STDLIB_OWNERS[lowPath] ?: ownerParts.joinToString("/")
                doStaticCall(staticFqn)
            }
        }
    }

internal fun AsmEmitter.emitLog(ctx: MethodContext, stmt: LogStatement) {
        val mv = ctx.mv
        if (!emitsLog) return
        mv.visitFieldInsn(GETSTATIC, ctx.owner, LOGGER_FIELD, "L$SLF4J_LOGGER;")
        val methodName = stmt.level.name.lowercase()  // trace/debug/info/warn/error
        if (stmt.kvPairs.isEmpty()) {
            // Simple: logger.info("message")
            emitExprAsString(ctx, stmt.message)
            mv.visitMethodInsn(INVOKEINTERFACE, SLF4J_LOGGER, methodName,
                "(Ljava/lang/String;)V", true)
        } else {
            // Structured: build a message with key=value pairs appended
            // logger.info("message key1={} key2={}", val1, val2)
            mv.visitTypeInsn(NEW, "java/lang/StringBuilder")
            mv.visitInsn(DUP)
            mv.visitMethodInsn(INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false)
            emitExprAsString(ctx, stmt.message)
            appendString(mv)
            stmt.kvPairs.forEach { kv ->
                mv.visitLdcInsn(" ${kv.key}=")
                appendString(mv)
                emitExpr(ctx, kv.value)
                appendValue(mv, inferExprType(kv.value))
            }
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "toString",
                "()Ljava/lang/String;", false)
            mv.visitMethodInsn(INVOKEINTERFACE, SLF4J_LOGGER, methodName,
                "(Ljava/lang/String;)V", true)
        }
    }

    // -------------------------------------------------------------------------
    // CONCURRENT block
    // -------------------------------------------------------------------------

    /**
     * Each concurrent branch becomes a synthetic private static method
     * `__concBranch_N_M()V` on the outer class.  We use invokedynamic (LambdaMetafactory)
     * to wrap each into a Runnable and submit them all to
     * `KobolConcurrent.runAll(Array<Runnable>)` on virtual threads.
     */
