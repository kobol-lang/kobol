package dev.kobol.codegen

import dev.kobol.codegen.AsmEmitter.MethodContext
import dev.kobol.parser.ast.*
import dev.kobol.semantic.KobolType
import dev.kobol.semantic.Symbol
import dev.kobol.semantic.TypeChecker
import org.objectweb.asm.*
import org.objectweb.asm.Opcodes.*
import java.io.File

internal fun AsmEmitter.emitWithPrecision(ctx: MethodContext, stmt: WithPrecisionStatement) {
        val mv = ctx.mv
        val tryStart  = Label()
        val tryEnd    = Label()
        val catchAll  = Label()
        val afterCatch = Label()
        mv.visitTryCatchBlock(tryStart, tryEnd, catchAll, null) // catch Throwable
        // push context — with or without an explicit rounding mode
        mv.visitLdcInsn(stmt.precisionName)
        if (stmt.roundingMode != null) {
            mv.visitLdcInsn(stmt.roundingMode)
            mv.visitMethodInsn(INVOKESTATIC, MATH_CTX, "pushWithRounding", "(Ljava/lang/String;Ljava/lang/String;)V", false)
        } else {
            mv.visitMethodInsn(INVOKESTATIC, MATH_CTX, "push", "(Ljava/lang/String;)V", false)
        }
        mv.visitLabel(tryStart)
        emitBlock(ctx, stmt.body)
        mv.visitLabel(tryEnd)
        // normal exit: pop
        mv.visitMethodInsn(INVOKESTATIC, MATH_CTX, "pop", "()V", false)
        mv.visitJumpInsn(GOTO, afterCatch)
        // exception exit: pop then rethrow
        mv.visitLabel(catchAll)
        mv.visitMethodInsn(INVOKESTATIC, MATH_CTX, "pop", "()V", false)
        mv.visitInsn(ATHROW)
        mv.visitLabel(afterCatch)
    }

internal fun AsmEmitter.emitIf(ctx: MethodContext, stmt: IfStatement) {
        val mv     = ctx.mv
        val lElse  = Label()
        val lEnd   = Label()
        emitExpr(ctx, stmt.condition)
        mv.visitJumpInsn(IFEQ, lElse)
        emitBlock(ctx, stmt.thenBranch)
        mv.visitJumpInsn(GOTO, lEnd)
        mv.visitLabel(lElse)
        stmt.elseIfClauses.forEach { ei ->
            val lEiEnd = Label()
            emitExpr(ctx, ei.condition)
            mv.visitJumpInsn(IFEQ, lEiEnd)
            emitBlock(ctx, ei.body)
            mv.visitJumpInsn(GOTO, lEnd)
            mv.visitLabel(lEiEnd)
        }
        stmt.elseBranch?.let { emitBlock(ctx, it) }
        mv.visitLabel(lEnd)
    }

internal fun AsmEmitter.emitWhile(ctx: MethodContext, stmt: WhileStatement) {
        val mv    = ctx.mv
        val lTop  = Label(); val lEnd = Label()
        mv.visitLabel(lTop)
        emitExpr(ctx, stmt.condition)
        mv.visitJumpInsn(IFEQ, lEnd)
        emitBlock(ctx, stmt.body)
        mv.visitJumpInsn(GOTO, lTop)
        mv.visitLabel(lEnd)
    }

internal fun AsmEmitter.emitForEach(ctx: MethodContext, stmt: ForEachStatement) {
        val mv     = ctx.mv
        val lTop   = Label(); val lEnd = Label()

        // FOR EACH rec IN <FileName>: read the file's records into a List, iterate it.
        // The element type is the file's RECORD type, not a DATA-section list element.
        val fileDecl = (stmt.iterable as? Reference)
            ?.takeIf { !it.isFieldAccess }
            ?.let { fileDecls[it.name.uppercase()] }

        // Get iterator from iterable
        if (fileDecl != null) {
            emitFileReadAll(ctx, fileDecl)   // leaves a java/util/List on the stack
        } else {
            emitExpr(ctx, stmt.iterable)
        }
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/List", "iterator",
            "()Ljava/util/Iterator;", true)
        val iterSlot = ctx.allocLocal("__iter_${stmt.variable}", KobolType.JavaObjectType())
        mv.visitVarInsn(ASTORE, iterSlot)

        val elemKType = if (fileDecl != null) {
            KobolType.RecordRefType(fileDecl.recordType)
        } else {
            val iterType = checker.typeOf(stmt.iterable)
            (iterType as? KobolType.ListType)?.elementType ?: KobolType.JavaObjectType()
        }
        val elemSlot = ctx.allocLocal(stmt.variable, elemKType)

        mv.visitLabel(lTop)
        mv.visitVarInsn(ALOAD, iterSlot)
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z", true)
        mv.visitJumpInsn(IFEQ, lEnd)

        mv.visitVarInsn(ALOAD, iterSlot)
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Iterator", "next", "()Ljava/lang/Object;", true)
        castFromObject(mv, elemKType, ctx.owner)
        storeLocal(mv, elemKType, elemSlot)

        emitBlock(ctx, stmt.body)
        mv.visitJumpInsn(GOTO, lTop)
        mv.visitLabel(lEnd)
    }

private const val KOBOL_FILES = "dev/kobol/runtime/KobolFiles"

/** Emit `KobolFiles.readAll(name, format, RecordClass)` — leaves a java/util/List on the stack. */
internal fun AsmEmitter.emitFileReadAll(ctx: MethodContext, decl: FileDecl) {
        val mv = ctx.mv
        mv.visitLdcInsn(decl.rawName)
        mv.visitLdcInsn(decl.format.name)
        mv.visitLdcInsn(Type.getObjectType("${ctx.owner}\$${javaClass(decl.recordType)}"))
        mv.visitMethodInsn(INVOKESTATIC, KOBOL_FILES, "readAll",
            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Class;)Ljava/util/List;", false)
    }

internal fun AsmEmitter.emitOpen(ctx: MethodContext, stmt: OpenStatement) {
        val decl = fileDecls[stmt.fileName.uppercase()] ?: return
        val mv = ctx.mv
        mv.visitLdcInsn(decl.rawName)
        mv.visitLdcInsn(stmt.mode.name)     // INPUT / OUTPUT / EXTEND
        mv.visitLdcInsn(decl.format.name)   // CSV / TEXT / FIXED
        mv.visitLdcInsn(Type.getObjectType("${ctx.owner}\$${javaClass(decl.recordType)}"))
        mv.visitMethodInsn(INVOKESTATIC, KOBOL_FILES, "openFile",
            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/Class;)V", false)
    }

internal fun AsmEmitter.emitWrite(ctx: MethodContext, stmt: WriteStatement) {
        val decl = fileDecls[stmt.fileName.uppercase()] ?: return
        val mv = ctx.mv
        mv.visitLdcInsn(decl.rawName)
        loadRef(ctx, stmt.from)             // the record object to write
        mv.visitMethodInsn(INVOKESTATIC, KOBOL_FILES, "write",
            "(Ljava/lang/String;Ljava/lang/Object;)V", false)
    }

internal fun AsmEmitter.emitClose(ctx: MethodContext, stmt: CloseStatement) {
        val decl = fileDecls[stmt.fileName.uppercase()] ?: return
        ctx.mv.visitLdcInsn(decl.rawName)
        ctx.mv.visitMethodInsn(INVOKESTATIC, KOBOL_FILES, "closeFile",
            "(Ljava/lang/String;)V", false)
    }

internal fun AsmEmitter.emitRepeat(ctx: MethodContext, stmt: RepeatStatement) {
        val mv     = ctx.mv
        val lTop   = Label(); val lEnd = Label()
        val countSlot = ctx.allocLocal("__repeat", KobolType.IntegerType)
        mv.visitInsn(LCONST_0)
        mv.visitVarInsn(LSTORE, countSlot)

        // Push limit onto a second local
        val limitSlot = ctx.allocLocal("__limit", KobolType.IntegerType)
        emitExpr(ctx, stmt.count)
        mv.visitVarInsn(LSTORE, limitSlot)

        mv.visitLabel(lTop)
        mv.visitVarInsn(LLOAD, countSlot)
        mv.visitVarInsn(LLOAD, limitSlot)
        mv.visitInsn(LCMP)
        mv.visitJumpInsn(IFGE, lEnd)

        emitBlock(ctx, stmt.body)
        mv.visitVarInsn(LLOAD, countSlot)
        mv.visitInsn(LCONST_1)
        mv.visitInsn(LADD)
        mv.visitVarInsn(LSTORE, countSlot)
        mv.visitJumpInsn(GOTO, lTop)
        mv.visitLabel(lEnd)
    }

// Shared by emitTry (ASM), emitAssertRaises (ASM) and the Java transpiler — one source for
// the Kobol-exception-type → JVM-class mapping (P1). `else` resolves to the sealed base, so it
// catches any RAISE'd exception (RAISE throws KobolException$ApplicationException).
internal fun kobolExceptionJvmName(exType: String): String = when (exType.uppercase()) {
    "FILE-NOT-FOUND" -> "dev/kobol/runtime/KobolException\$FileNotFoundException"
    "IO-ERROR"       -> "dev/kobol/runtime/KobolException\$IoException"
    else             -> "dev/kobol/runtime/KobolException"
}

private fun endsWithExit(stmts: List<Statement>): Boolean = when (stmts.lastOrNull()) {
    is StopRunStatement, is ReturnStatement, is RaiseStatement -> true
    else -> false
}

internal fun AsmEmitter.emitTry(ctx: MethodContext, stmt: TryStatement) {
        val mv        = ctx.mv
        val lTryStart = Label(); val lTryEnd = Label()
        val lFinalEnd = Label()
        val catchLabels = stmt.handlers.map { Label() }

        // Register each handler as a separate catch block so all labels have predecessors.
        if (stmt.handlers.isEmpty()) {
            mv.visitTryCatchBlock(lTryStart, lTryEnd, lFinalEnd, "java/lang/Exception")
        } else {
            stmt.handlers.forEachIndexed { i, h ->
                mv.visitTryCatchBlock(lTryStart, lTryEnd, catchLabels[i], kobolExceptionJvmName(h.exceptionType))
            }
        }

        mv.visitLabel(lTryStart)
        emitBlock(ctx, stmt.body)
        mv.visitLabel(lTryEnd)
        if (!endsWithExit(stmt.body)) mv.visitJumpInsn(GOTO, lFinalEnd)

        stmt.handlers.forEachIndexed { i, h ->
            mv.visitLabel(catchLabels[i])
            val slot = if (h.binding != null) ctx.allocLocal(h.binding, KobolType.JavaObjectType()) else ctx.allocLocal("__ex", KobolType.JavaObjectType())
            mv.visitVarInsn(ASTORE, slot)
            emitBlock(ctx, h.body)
            // Skip GOTO when handler ends with unconditional exit (STOP RUN/RETURN/RAISE)
            // to avoid a dead GOTO jumping into live code, which crashes ASM frame computation.
            if (!endsWithExit(h.body)) mv.visitJumpInsn(GOTO, lFinalEnd)
        }

        mv.visitLabel(lFinalEnd)
        stmt.ensure?.let { emitBlock(ctx, it) }
    }

internal fun AsmEmitter.emitReturn(ctx: MethodContext, stmt: ReturnStatement) {
        val mv = ctx.mv
        if (stmt.value != null) {
            val kType = inferExprType(stmt.value)
            emitExpr(ctx, stmt.value)
            when {
                kType is KobolType.IntegerType || kType is KobolType.SmallIntType -> mv.visitInsn(LRETURN)
                kType is KobolType.BooleanType -> mv.visitInsn(IRETURN)
                else -> mv.visitInsn(ARETURN)
            }
        } else {
            mv.visitInsn(RETURN)
        }
    }

internal fun AsmEmitter.emitStopRun(ctx: MethodContext, stmt: StopRunStatement) {
        val mv = ctx.mv
        if (stmt.exitCode != null) {
            emitExpr(ctx, stmt.exitCode)
            mv.visitInsn(L2I)
        } else {
            mv.visitInsn(ICONST_0)
        }
        mv.visitMethodInsn(INVOKESTATIC, SYSTEM, "exit", "(I)V", false)
        // System.exit never returns, but the JVM verifier treats it as a normal
        // returning call. Emit an unreachable terminator so the basic block ends
        // here and control cannot fall through into following code (e.g. the next
        // catch handler in a multi-ON TRY block). ACONST_NULL;ATHROW is valid in
        // any method signature, void or not.
        mv.visitInsn(ACONST_NULL)
        mv.visitInsn(ATHROW)
    }

internal fun AsmEmitter.emitSleep(ctx: MethodContext, stmt: SleepStatement) {
        val mv = ctx.mv
        emitExpr(ctx, stmt.amount)   // → long on stack
        val factor = when (stmt.unit) {
            SleepUnit.MILLISECONDS -> 1L
            SleepUnit.SECONDS      -> 1000L
            SleepUnit.MINUTES      -> 60_000L
        }
        if (factor != 1L) {
            mv.visitLdcInsn(factor)
            mv.visitInsn(LMUL)
        }
        mv.visitMethodInsn(INVOKESTATIC, "dev/kobol/runtime/KobolRuntime", "sleep", "(J)V", false)
    }

internal fun AsmEmitter.emitAssert(ctx: MethodContext, stmt: AssertStatement) {
        val mv = ctx.mv
        if (stmt.message != null) {
            emitExpr(ctx, stmt.condition)
            emitExprAsString(ctx, stmt.message)
            mv.visitMethodInsn(INVOKESTATIC, "dev/kobol/runtime/KobolTest", "assertTrue", "(ZLjava/lang/String;)V", false)
        } else {
            emitExpr(ctx, stmt.condition)
            mv.visitMethodInsn(INVOKESTATIC, "dev/kobol/runtime/KobolTest", "assertTrue", "(Z)V", false)
        }
    }

internal fun AsmEmitter.emitAssertRaises(ctx: MethodContext, stmt: AssertRaisesStatement) {
        val mv       = ctx.mv
        val exClass  = kobolExceptionJvmName(stmt.exceptionType)
        val lTryStart = Label(); val lTryEnd = Label(); val lCatch = Label(); val lAssert = Label()
        val raisedSlot = ctx.allocLocal("__raised", KobolType.BooleanType)

        // __raised = false
        mv.visitInsn(ICONST_0); mv.visitVarInsn(ISTORE, raisedSlot)
        mv.visitTryCatchBlock(lTryStart, lTryEnd, lCatch, exClass)

        mv.visitLabel(lTryStart)
        emitStatement(ctx, stmt.body)
        mv.visitLabel(lTryEnd)
        // If the body always exits via throw (e.g. RAISE), control reaches the catch, never here;
        // skip the dead GOTO so ASM frame computation stays sound (mirrors emitTry).
        if (!endsWithExit(listOf(stmt.body))) mv.visitJumpInsn(GOTO, lAssert)

        mv.visitLabel(lCatch)
        mv.visitInsn(POP)                              // discard the caught exception ref
        mv.visitInsn(ICONST_1); mv.visitVarInsn(ISTORE, raisedSlot)

        mv.visitLabel(lAssert)
        mv.visitVarInsn(ILOAD, raisedSlot)
        mv.visitLdcInsn("Expected ${stmt.exceptionType} to be raised")
        mv.visitMethodInsn(INVOKESTATIC, "dev/kobol/runtime/KobolTest", "assertTrue", "(ZLjava/lang/String;)V", false)
    }

internal fun AsmEmitter.emitRaise(ctx: MethodContext, stmt: RaiseStatement) {
        val mv = ctx.mv
        mv.visitTypeInsn(NEW, KOBOL_EXCEPTION)
        mv.visitInsn(DUP)
        if (stmt.message != null) emitExprAsString(ctx, stmt.message)
        else mv.visitLdcInsn(stmt.exceptionType)
        mv.visitMethodInsn(INVOKESPECIAL, KOBOL_EXCEPTION, "<init>", "(Ljava/lang/String;)V", false)
        mv.visitInsn(ATHROW)
    }

    // -------------------------------------------------------------------------
    // Expression emission  (leaves a value on the operand stack)
    // -------------------------------------------------------------------------

internal fun AsmEmitter.emitValidate(ctx: MethodContext, stmt: ValidateStatement) {
        val mv         = ctx.mv
        val targetType = checker.typeOf(stmt.target) .let {
            if (it == KobolType.UnknownType) resolveSymbolType(ctx, stmt.target.parts[0]) else it
        }
        for (c in stmt.constraints) {
            emitValidationConstraint(ctx, stmt.target, targetType, c)
        }
    }

internal fun AsmEmitter.emitValidationConstraint(ctx: MethodContext, target: Reference,
                                         targetType: KobolType, c: ValidationConstraint) {
        val mv = ctx.mv
        val lOk = Label()
        val failMsg = when (c) {
            is ValidationConstraint.MustBe      -> c.failMsg ?: "Validation failed: ${target.name} must be ${c.op} ${c.value}"
            is ValidationConstraint.MustMatch   -> c.failMsg ?: "Validation failed: ${target.name} must match pattern"
            is ValidationConstraint.MustNotBe   -> c.failMsg ?: "Validation failed: ${target.name} must not be ${c.kind}"
            is ValidationConstraint.MustLength  -> c.failMsg ?: "Validation failed: ${target.name} length must be ${c.op} ${c.length}"
            is ValidationConstraint.MustSatisfy -> c.failMsg ?: "Validation failed: ${target.name} did not satisfy ${c.procName}"
        }
        when (c) {
            is ValidationConstraint.MustBe -> {
                loadRef(ctx, target)
                emitExpr(ctx, c.value)
                when (targetType) {
                    is KobolType.MoneyType, is KobolType.DecimalType -> {
                        // Coerce INTEGER/SMALLINT comparison value to BigDecimal
                        val valType = inferExprType(c.value)
                        when (valType) {
                            is KobolType.IntegerType -> mv.visitMethodInsn(INVOKESTATIC, BIGDECIMAL, "valueOf", "(J)L$BIGDECIMAL;", false)
                            is KobolType.SmallIntType -> { mv.visitInsn(Opcodes.I2L); mv.visitMethodInsn(INVOKESTATIC, BIGDECIMAL, "valueOf", "(J)L$BIGDECIMAL;", false) }
                            else -> {}
                        }
                        mv.visitMethodInsn(INVOKEVIRTUAL, BIGDECIMAL, "compareTo",
                            "(L$BIGDECIMAL;)I", false)
                        emitCompareJump(mv, c.op, lOk)
                    }
                    is KobolType.IntegerType -> {
                        mv.visitInsn(LCMP)
                        emitCompareJump(mv, c.op, lOk)
                    }
                    else -> {
                        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Object", "equals",
                            "(Ljava/lang/Object;)Z", false)
                        mv.visitJumpInsn(IFNE, lOk)
                    }
                }
            }
            is ValidationConstraint.MustNotBe -> {
                loadRef(ctx, target)
                when (c.kind) {
                    "EMPTY" -> {
                        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "isEmpty", "()Z", false)
                        mv.visitJumpInsn(IFEQ, lOk)  // IFEQ: isEmpty returned false (0) → ok
                    }
                    "BLANK" -> {
                        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "isBlank", "()Z", false)
                        mv.visitJumpInsn(IFEQ, lOk)
                    }
                    else    -> { mv.visitJumpInsn(IFNONNULL, lOk) }
                }
            }
            is ValidationConstraint.MustMatch -> {
                loadRef(ctx, target)
                mv.visitLdcInsn(c.pattern)
                mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "matches",
                    "(Ljava/lang/String;)Z", false)
                mv.visitJumpInsn(IFNE, lOk)
            }
            is ValidationConstraint.MustLength -> {
                loadRef(ctx, target)
                mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false)
                mv.visitLdcInsn(c.length)
                mv.visitInsn(ISUB)
                emitIntCompareJump(mv, c.op, lOk)
            }
            is ValidationConstraint.MustSatisfy -> {
                // Call procedure; if it returns false/throws → fail
                val sym = checker.symbols.resolve(c.procName) as? Symbol.ProcedureSymbol
                if (sym != null) {
                    loadRef(ctx, target)
                    val paramDesc = jvmDescriptor(sym.params.firstOrNull()?.type ?: KobolType.UnknownType)
                    mv.visitMethodInsn(INVOKESTATIC, ctx.owner, javaIdent(c.procName),
                        "($paramDesc)Z", false)
                    mv.visitJumpInsn(IFNE, lOk)
                } else {
                    mv.visitJumpInsn(GOTO, lOk)  // unknown proc → skip constraint
                }
            }
        }
        // Throw KobolValidationError
        mv.visitTypeInsn(NEW, KOBOL_VALIDATION_ERROR)
        mv.visitInsn(DUP)
        mv.visitLdcInsn(failMsg)
        mv.visitMethodInsn(INVOKESPECIAL, KOBOL_VALIDATION_ERROR, "<init>",
            "(Ljava/lang/String;)V", false)
        mv.visitInsn(ATHROW)
        mv.visitLabel(lOk)
    }

internal fun AsmEmitter.emitCompareJump(mv: MethodVisitor, op: String, lOk: Label) {
        // Stack top is int result of compareTo (or LCMP): jump to lOk if op is satisfied
        when (op) {
            ">"  -> mv.visitJumpInsn(IFGT, lOk)
            ">=" -> mv.visitJumpInsn(IFGE, lOk)
            "<"  -> mv.visitJumpInsn(IFLT, lOk)
            "<=" -> mv.visitJumpInsn(IFLE, lOk)
            "="  -> mv.visitJumpInsn(IFEQ, lOk)
            "<>" -> mv.visitJumpInsn(IFNE, lOk)
            else -> mv.visitJumpInsn(IFGE, lOk)
        }
    }

internal fun AsmEmitter.emitIntCompareJump(mv: MethodVisitor, op: String, lOk: Label) {
        // Stack top is int (length - threshold): jump to lOk if op is satisfied
        when (op) {
            ">"  -> mv.visitJumpInsn(IFGT, lOk)
            ">=" -> mv.visitJumpInsn(IFGE, lOk)
            "<"  -> mv.visitJumpInsn(IFLT, lOk)
            "<=" -> mv.visitJumpInsn(IFLE, lOk)
            "="  -> mv.visitJumpInsn(IFEQ, lOk)
            "<>" -> mv.visitJumpInsn(IFNE, lOk)
            else -> mv.visitJumpInsn(IFGE, lOk)
        }
    }

    // -------------------------------------------------------------------------
    // MATCH statement
    // -------------------------------------------------------------------------

internal fun AsmEmitter.emitMatch(ctx: MethodContext, stmt: MatchStatement) {
        val mv       = ctx.mv
        val lEnd     = Label()
        val subjType = inferExprType(stmt.subject)
        emitExpr(ctx, stmt.subject)
        val subjSlot = ctx.allocLocal("__match_subj", subjType)
        storeLocal(mv, subjType, subjSlot)

        for (clause in stmt.whenClauses) {
            val lNext = Label()
            emitPatternCondition(ctx, clause.pattern, subjSlot, subjType, lNext)
            emitBlock(ctx, clause.body)
            mv.visitJumpInsn(GOTO, lEnd)
            mv.visitLabel(lNext)
        }
        stmt.otherwise?.let { emitBlock(ctx, it) }
        mv.visitLabel(lEnd)
    }

    /**
     * Emits bytecode for a single pattern arm:
     *   - pushes subject + comparands onto the operand stack
     *   - jumps to [lNext] when the pattern does NOT match
     *   - falls through when it DOES match (body follows immediately after the call)
     * Allocates binding locals into [ctx] so the body can read them.
     *
     * GuardPattern chains: inner check then guard expression check (both jump to lNext on failure).
     * O(1) per pattern (constant number of JVM instructions regardless of input size).
     */
    private fun AsmEmitter.emitPatternCondition(
        ctx: MethodContext,
        pattern: MatchPattern,
        subjSlot: Int,
        subjType: KobolType,
        lNext: Label,
    ) {
        val mv = ctx.mv
        when (pattern) {
            is MatchPattern.VariantPattern -> {
                loadLocal(mv, subjType, subjSlot)
                val variantTypeName = (subjType as? KobolType.VariantRefType)?.name ?: ""
                val caseClassName   = "${javaClass(variantTypeName)}Case${javaClass(pattern.caseName)}"
                val outerName       = ctx.owner
                mv.visitTypeInsn(INSTANCEOF, "$outerName\$$caseClassName")
                mv.visitJumpInsn(IFEQ, lNext)
                if (pattern.bindings.isNotEmpty()) {
                    val fullCaseName = "$outerName\$$caseClassName"
                    loadLocal(mv, subjType, subjSlot)
                    mv.visitTypeInsn(CHECKCAST, fullCaseName)
                    val castSlot = ctx.allocLocal("__match_case_${pattern.caseName}", KobolType.JavaObjectType())
                    mv.visitVarInsn(ASTORE, castSlot)
                    val variantSym = checker.symbols.resolve(variantTypeName) as? Symbol.VariantSymbol
                    val caseInfo   = variantSym?.cases?.find { it.name == pattern.caseName }
                    for (binding in pattern.bindings) {
                        val fieldType = caseInfo?.fields?.get(binding) ?: KobolType.UnknownType
                        mv.visitVarInsn(ALOAD, castSlot)
                        mv.visitFieldInsn(GETFIELD, fullCaseName, javaIdent(binding), jvmDescriptor(fieldType))
                        val bindSlot = ctx.allocLocal(binding, fieldType)
                        storeLocal(mv, fieldType, bindSlot)
                    }
                }
            }
            is MatchPattern.LiteralPattern -> {
                loadLocal(mv, subjType, subjSlot)
                emitExpr(ctx, pattern.value)
                when (subjType) {
                    is KobolType.IntegerType -> {
                        mv.visitInsn(LCMP)
                        mv.visitJumpInsn(IFNE, lNext)
                    }
                    else -> {
                        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Object", "equals",
                            "(Ljava/lang/Object;)Z", false)
                        mv.visitJumpInsn(IFEQ, lNext)
                    }
                }
            }
            is MatchPattern.RangePattern -> {
                when (subjType) {
                    is KobolType.IntegerType -> {
                        emitExpr(ctx, pattern.from)
                        loadLocal(mv, subjType, subjSlot)
                        mv.visitInsn(LCMP)
                        mv.visitJumpInsn(IFGT, lNext)
                        loadLocal(mv, subjType, subjSlot)
                        emitExpr(ctx, pattern.to)
                        mv.visitInsn(LCMP)
                        mv.visitJumpInsn(IFGT, lNext)
                    }
                    // #v3: DECIMAL/MONEY subject — the subject is a BigDecimal, so compare the
                    // bounds with BigDecimal.compareTo, NOT String.compareTo (the old `else` path
                    // emitted String.compareTo on a BigDecimal → VerifyError at load on the
                    // language's flagship MONEY type). Bounds are widened so an INTEGER literal
                    // bound (`WHEN 1000 .. 9999.99`) coerces to BigDecimal first. Mirrors the
                    // integer path: jump to lNext when from > subj or subj > to (out of range).
                    is KobolType.DecimalType, is KobolType.MoneyType -> {
                        emitDecimalArg(ctx, pattern.from)
                        loadLocal(mv, subjType, subjSlot)
                        mv.visitMethodInsn(INVOKEVIRTUAL, BIGDECIMAL, "compareTo", "(L$BIGDECIMAL;)I", false)
                        mv.visitJumpInsn(IFGT, lNext)
                        loadLocal(mv, subjType, subjSlot)
                        emitDecimalArg(ctx, pattern.to)
                        mv.visitMethodInsn(INVOKEVIRTUAL, BIGDECIMAL, "compareTo", "(L$BIGDECIMAL;)I", false)
                        mv.visitJumpInsn(IFGT, lNext)
                    }
                    else -> {
                        emitExpr(ctx, pattern.from)
                        loadLocal(mv, subjType, subjSlot)
                        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "compareTo",
                            "(Ljava/lang/String;)I", false)
                        mv.visitJumpInsn(IFGT, lNext)
                        loadLocal(mv, subjType, subjSlot)
                        emitExpr(ctx, pattern.to)
                        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "compareTo",
                            "(Ljava/lang/String;)I", false)
                        mv.visitJumpInsn(IFGT, lNext)
                    }
                }
            }
            is MatchPattern.TypePattern -> {
                val jvmClass = when (pattern.typeName.uppercase()) {
                    "TEXT"             -> "java/lang/String"
                    "INTEGER"         -> "java/lang/Long"
                    "DECIMAL", "MONEY" -> "java/math/BigDecimal"
                    "BOOLEAN"          -> "java/lang/Boolean"
                    else               -> "java/lang/Object"
                }
                loadLocal(mv, subjType, subjSlot)
                mv.visitTypeInsn(INSTANCEOF, jvmClass)
                mv.visitJumpInsn(IFEQ, lNext)
                if (pattern.binding != null) {
                    loadLocal(mv, subjType, subjSlot)
                    mv.visitTypeInsn(CHECKCAST, jvmClass)
                    val bindSlot = ctx.allocLocal(pattern.binding, subjType)
                    storeLocal(mv, subjType, bindSlot)
                }
            }
            is MatchPattern.GuardPattern -> {
                // §22.4 guard over a record subject (`WHEN Invoice WITH amount > 10000 …`): the
                // checker rewrote bare field names to `subject.field` and stashed the resolved,
                // already-typed guard. The case name is a static type assertion on the subject's
                // sole record type — always true, records have no subtypes — so skip the inner
                // check. Otherwise emit the inner pattern check then the guard as usual.
                val resolved = checker.resolvedGuards[pattern]
                if (resolved != null) {
                    emitExpr(ctx, resolved)
                } else {
                    emitPatternCondition(ctx, pattern.inner, subjSlot, subjType, lNext)
                    emitExpr(ctx, pattern.guard)
                }
                mv.visitJumpInsn(IFEQ, lNext)
            }
        }
    }

    // -------------------------------------------------------------------------
    // VARIANT class hierarchy
    // -------------------------------------------------------------------------

    /**
     * Emits a sealed-style class hierarchy for a VARIANT declaration:
     *   abstract class OuterProgram$VariantName (base)
     *   static final class OuterProgram$VariantNameCasePending extends base
     *   static final class OuterProgram$VariantNameCaseActive extends base  (with fields)
     */
