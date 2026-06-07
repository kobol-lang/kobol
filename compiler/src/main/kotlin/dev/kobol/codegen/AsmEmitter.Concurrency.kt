package dev.kobol.codegen

import dev.kobol.codegen.AsmEmitter.MethodContext
import dev.kobol.parser.ast.*
import dev.kobol.semantic.KobolType
import dev.kobol.semantic.Symbol
import dev.kobol.semantic.TypeChecker
import org.objectweb.asm.*
import org.objectweb.asm.Opcodes.*
import java.io.File

internal fun AsmEmitter.emitConcurrentBlock(ctx: MethodContext, stmt: ConcurrentBlock) {
        val mv    = ctx.mv
        val owner = ctx.owner
        val id    = concBlockCounter++

        // Register synthetic branch methods (emitted later in emitProgramClass)
        val methodNames = stmt.branches.mapIndexed { branchIdx, branchStmts ->
            val name = "__concBranch_${id}_${branchIdx}"
            pendingSyntheticMethods.add(Triple(name, owner, branchStmts))
            name
        }

        if (methodNames.isEmpty()) return

        // Create Runnable[] array
        mv.visitLdcInsn(methodNames.size)
        mv.visitTypeInsn(ANEWARRAY, "java/lang/Runnable")

        val bsm = Handle(H_INVOKESTATIC,
            "java/lang/invoke/LambdaMetafactory", "metafactory",
            "(Ljava/lang/invoke/MethodHandles\$Lookup;Ljava/lang/String;" +
            "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;" +
            "Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)" +
            "Ljava/lang/invoke/CallSite;", false)

        methodNames.forEachIndexed { i, methodName ->
            mv.visitInsn(DUP)
            mv.visitLdcInsn(i)
            mv.visitInvokeDynamicInsn(
                "run", "()Ljava/lang/Runnable;", bsm,
                Type.getType("()V"),
                Handle(H_INVOKESTATIC, owner, methodName, "()V", false),
                Type.getType("()V")
            )
            mv.visitInsn(AASTORE)
        }

        val runMethod = if (stmt.failMode == ConcurrentFailMode.FAIL_FAST) "runAllFailFast" else "runAll"
        mv.visitMethodInsn(INVOKESTATIC, KOBOL_CONCURRENT, runMethod,
            "([Ljava/lang/Runnable;)V", false)
    }

    /**
     * Emit a synthetic private static method that runs the given branch statements.
     * The method references the outer class for GETSTATIC/INVOKESTATIC but has no
     * access to local variables of the enclosing procedure.
     */
internal fun AsmEmitter.emitSyntheticBranchMethod(cw: ClassWriter, methodName: String,
                                          owner: String, stmts: List<Statement>) {
        val mv = cw.visitMethod(ACC_PRIVATE or ACC_STATIC or ACC_SYNTHETIC, methodName, "()V", null, null)
        mv.visitCode()
        val syntheticProc = ProcedureDecl(methodName, emptyList(), null, stmts,
            stmts.firstOrNull()?.pos ?: dev.kobol.lexer.SourcePosition("<synthetic>", 0, 0))
        val ctx = MethodContext(mv, owner, syntheticProc)
        emitBlock(ctx, stmts)
        mv.visitInsn(RETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    /**
     * PARALLEL FOR EACH — runs loop body on virtual threads using KobolConcurrent.runAll.
     *
     * Strategy:
     *  1. Emit a synthetic `__parallelBody_N(ElemType)V` method.
     *  2. Iterate the list, building an ArrayList<Runnable> using LambdaMetafactory to capture each element.
     *  3. Convert to Runnable[] and call KobolConcurrent.runAll.
     */
internal fun AsmEmitter.emitParallelForEach(ctx: MethodContext, stmt: ParallelForEachStatement) {
        val mv     = ctx.mv
        val owner  = ctx.owner
        val id     = concBlockCounter++

        val iterType   = checker.typeOf(stmt.iterable)
        val elemKType  = (iterType as? KobolType.ListType)?.elementType ?: KobolType.JavaObjectType()
        val elemDesc   = jvmDescriptor(elemKType)

        // --- Emit synthetic body method ---
        val bodyName = "__parallelBody_$id"
        pendingSyntheticParallelMethods.add(Triple(bodyName, owner, Pair(stmt.variable, stmt)))

        // --- Build ArrayList<Runnable> by iterating the list ---
        // new ArrayList<>()
        mv.visitTypeInsn(NEW, ARRAYLIST)
        mv.visitInsn(DUP)
        mv.visitMethodInsn(INVOKESPECIAL, ARRAYLIST, "<init>", "()V", false)
        val listSlot = ctx.allocLocal("__pfe_list_$id", KobolType.JavaObjectType())
        mv.visitVarInsn(ASTORE, listSlot)

        // iterator
        emitExpr(ctx, stmt.iterable)
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/List", "iterator", "()Ljava/util/Iterator;", true)
        val iterSlot = ctx.allocLocal("__pfe_iter_$id", KobolType.JavaObjectType())
        mv.visitVarInsn(ASTORE, iterSlot)

        val lTop = Label(); val lEnd = Label()
        mv.visitLabel(lTop)
        mv.visitVarInsn(ALOAD, iterSlot)
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z", true)
        mv.visitJumpInsn(IFEQ, lEnd)

        mv.visitVarInsn(ALOAD, iterSlot)
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Iterator", "next", "()Ljava/lang/Object;", true)
        castFromObject(mv, elemKType, ctx.owner)

        // Create capturing Runnable via LambdaMetafactory(elem) → Runnable
        val bsm = Handle(H_INVOKESTATIC,
            "java/lang/invoke/LambdaMetafactory", "metafactory",
            "(Ljava/lang/invoke/MethodHandles\$Lookup;Ljava/lang/String;" +
            "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;" +
            "Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)" +
            "Ljava/lang/invoke/CallSite;", false)

        // Stack: [elem]  →  invokedynamic captures elem, returns Runnable
        mv.visitInvokeDynamicInsn(
            "run", "($elemDesc)Ljava/lang/Runnable;", bsm,
            Type.getType("()V"),
            Handle(H_INVOKESTATIC, owner, bodyName, "($elemDesc)V", false),
            Type.getType("()V")
        )

        // list.add(runnable)
        mv.visitVarInsn(ALOAD, listSlot)
        mv.visitInsn(SWAP)
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/List", "add", "(Ljava/lang/Object;)Z", true)
        mv.visitInsn(POP)

        mv.visitJumpInsn(GOTO, lTop)
        mv.visitLabel(lEnd)

        // Convert list to Runnable[] and call KobolConcurrent.runAll
        mv.visitVarInsn(ALOAD, listSlot)
        mv.visitLdcInsn(0)
        mv.visitTypeInsn(ANEWARRAY, "java/lang/Runnable")
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/List", "toArray",
            "([Ljava/lang/Object;)[Ljava/lang/Object;", true)
        mv.visitTypeInsn(CHECKCAST, "[Ljava/lang/Runnable;")
        mv.visitMethodInsn(INVOKESTATIC, KOBOL_CONCURRENT, "runAll", "([Ljava/lang/Runnable;)V", false)
    }

    /**
     * Emit a synthetic method that executes the body of a PARALLEL FOR EACH
     * with one captured element parameter.
     */
internal fun AsmEmitter.emitSyntheticParallelBodyMethod(cw: ClassWriter, methodName: String,
                                                owner: String, variable: String,
                                                stmt: ParallelForEachStatement) {
        val iterType  = checker.typeOf(stmt.iterable)
        val elemKType = (iterType as? KobolType.ListType)?.elementType ?: KobolType.JavaObjectType()
        val elemDesc  = jvmDescriptor(elemKType)

        val mv = cw.visitMethod(ACC_PRIVATE or ACC_STATIC or ACC_SYNTHETIC,
            methodName, "($elemDesc)V", null, null)
        mv.visitCode()
        val syntheticProc = ProcedureDecl(methodName, emptyList(), null, stmt.body,
            stmt.pos)
        val ctx = MethodContext(mv, owner, syntheticProc)
        ctx.defineLocal(variable, elemKType, 0)
        emitBlock(ctx, stmt.body)
        mv.visitInsn(RETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    // -------------------------------------------------------------------------
    // VALIDATE statement
    // -------------------------------------------------------------------------

