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
        if (kType is KobolType.MoneyType || kType is KobolType.DecimalType) {
            coerceToDecimalIfNeeded(ctx.mv, inferExprType(stmt.initializer))
        } else {
            coerceIntToTarget(ctx.mv, inferExprType(stmt.initializer), kType)
        }
        val slot = ctx.allocLocal(stmt.name, kType)
        storeLocal(ctx.mv, kType, slot)
    }

internal fun AsmEmitter.emitMove(ctx: MethodContext, stmt: MoveStatement) {
        val mv = ctx.mv
        emitExpr(ctx, stmt.from)
        // Coerce the source value to the receiving field's representation:
        //   INTEGER/SMALLINT → BigDecimal  when target is MONEY/DECIMAL
        //   INTEGER (long)   → int (L2I)    when target is SMALLINT
        val fromType   = inferExprType(stmt.from)
        val targetType = resolveRefFinalType(ctx, stmt.to)
        if (targetType is KobolType.MoneyType || targetType is KobolType.DecimalType) {
            coerceToDecimalIfNeeded(mv, fromType)
        } else {
            coerceIntToTarget(mv, fromType, targetType)
        }
        snapshotIfRecord(ctx, fromType)   // F2: MOVE rec TO rec snapshots the source, not aliases it
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

/**
 * Value semantics (#23, F2): a record is a mutable buffer. Storing it into a holder
 * (list element, map value, another record variable) must snapshot it via the synthetic
 * `copy()` instead of aliasing the live buffer — otherwise later mutation of the source
 * leaks into the already-stored value. `valueType` is the static type of the value sitting
 * on the stack top; emits `copy()` only for records (scalars, String, BigDecimal are
 * immutable → no copy needed). Shared by every record-store site so the rule is one place.
 */
internal fun AsmEmitter.snapshotIfRecord(ctx: MethodContext, valueType: KobolType) {
    if (valueType is KobolType.RecordRefType) {
        val recClass = "${ctx.owner}\$${javaClass(valueType.name)}"
        ctx.mv.visitMethodInsn(INVOKEVIRTUAL, recClass, "copy", "()L$recClass;", false)
    }
}

// PUT value TO map WITH KEY key — Map.put(key, value), discarding the returned prior value (#12).
// Key/value are boxed by their own emitted expression type (a long must become a Long before
// Map.put(Object,Object)). resolveSymbolType, not inferExprType, is needed for the value type
// only at GET (a bare DATA-item Reference does not resolve through inferExprType).
internal fun AsmEmitter.emitMapPut(ctx: MethodContext, stmt: MapPutStatement) {
    val mv = ctx.mv
    val valueType = inferExprType(stmt.value)
    loadRef(ctx, stmt.map)                                   // Map
    emitExpr(ctx, stmt.key)
    boxValue(mv, inferExprType(stmt.key))
    emitExpr(ctx, stmt.value)
    snapshotIfRecord(ctx, valueType)                         // F2: snapshot record values, don't alias the buffer
    boxValue(mv, valueType)
    mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Map", "put",
        "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true)
    mv.visitInsn(POP)                                        // discard prior mapping
}

// GET map KEY key INTO dest — Map.get(key) → cast/unbox to the value type → store (#12).
internal fun AsmEmitter.emitMapGet(ctx: MethodContext, stmt: MapGetStatement) {
    val mv = ctx.mv
    val mapType   = resolveSymbolType(ctx, stmt.map.parts[0]) as? KobolType.MapType
    val valueType = mapType?.valueType ?: resolveSymbolType(ctx, stmt.into.parts[0])
    loadRef(ctx, stmt.map)
    emitExpr(ctx, stmt.key)
    boxValue(mv, inferExprType(stmt.key))
    mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Map", "get",
        "(Ljava/lang/Object;)Ljava/lang/Object;", true)
    castFromObject(mv, valueType, ctx.owner)                 // Object → typed value (unboxes primitives)
    emitStore(ctx, stmt.into)
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
        // The result type is the destination's type — it decides decimal vs long arithmetic.
        // (e.g. `MULTIPLY qty BY unit-price GIVING line-total` stores into a MONEY dest even
        // though one operand is an INTEGER. Keying on the dest, then coercing each operand,
        // makes both `... GIVING` directions agree with COMPUTE's working path — challenge #9.)
        val destType = run {
            val baseType = resolveSymbolType(ctx, dest.parts[0])
            if (baseType is KobolType.RecordRefType && dest.parts.size > 1) {
                val recSym = checker.symbols.resolve(baseType.name) as? dev.kobol.semantic.Symbol.RecordSymbol
                recSym?.fields?.get(dest.parts[1]) ?: baseType
            } else baseType
        }
        val operandType = inferExprType(operand)

        // ADD item TO list — list append (O(1) amortized for ArrayList)
        if (op == "+" && kType is KobolType.ListType) {
            loadRef(ctx, target)                   // ArrayList on stack
            emitExpr(ctx, operand)                 // item on stack
            // Box primitives: ArrayList.add(Object)
            when (val et = kType.elementType) {
                is KobolType.IntegerType  -> mv.visitMethodInsn(INVOKESTATIC, "java/lang/Long",    "valueOf", "(J)Ljava/lang/Long;",    false)
                is KobolType.SmallIntType -> mv.visitMethodInsn(INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false)
                is KobolType.BooleanType  -> mv.visitMethodInsn(INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false)
                is KobolType.RecordRefType -> snapshotIfRecord(ctx, et)   // #23/F2: snapshot the buffer, shared helper
                else -> { /* immutable reference types (String, BigDecimal) need no copy */ }
            }
            // List.add is an interface method; field type is Ljava/util/List; not ArrayList
            mv.visitMethodInsn(INVOKEINTERFACE, "java/util/List", "add", "(Ljava/lang/Object;)Z", true)
            mv.visitInsn(POP)   // discard boolean return
            return              // no emitStore — list mutated in place
        }

        if (destType is KobolType.MoneyType || destType is KobolType.DecimalType) {
            loadRef(ctx, target)
            coerceToDecimalIfNeeded(mv, kType)        // target may be INTEGER (e.g. int * decimal GIVING money)
            emitExpr(ctx, operand)
            coerceToDecimalIfNeeded(mv, operandType)  // operand may be INTEGER — #9 long → BigDecimal
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
            // An exported ASYNC proc's JVM entry returns CompletableFuture (see emitAsyncProcedure),
            // NOT its declared inner type — mirror the local async PERFORM path. The declared
            // returnType descriptor would link to a method that does not exist (F9 landmine).
            if (proc.isAsync) {
                stmt.args.forEach { emitExpr(ctx, it) }
                mv.visitMethodInsn(INVOKESTATIC, targetClass, methodName,
                    "($paramDescs)L$COMPLETABLE_FUTURE;", false)
                // Mirror the local ASYNC path: store the future into GIVING (F10 — consumed by
                // AWAIT) or discard it for fire-and-forget. No cross-module asymmetry.
                if (stmt.giving != null) emitStore(ctx, stmt.giving) else mv.visitInsn(POP)
                return
            }
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

/**
 * Resolve a `NEW Owner …` owner to a JVM internal class name (F12). Consults the SAME maps as
 * [emitCall]'s static-owner resolution — `importAliasMap`, `STDLIB_OWNERS`, `JAVA_LANG_OWNERS` —
 * so the alias source of truth never forks. Order differs from CALL only because a constructor
 * has no instance-receiver interpretation (CALL checks a local/field between alias and java.lang).
 * Unknown owner falls back to its original-case name so the resulting `NoClassDefFoundError`
 * names the real class, surfacing a missing IMPORT rather than a misleading lowercase.
 */
internal fun AsmEmitter.resolveConstructorOwner(ownerParts: List<String>): String =
    dev.kobol.semantic.resolveInteropOwner(ownerParts, importAliasMap)

/**
 * Shared classpath-aware interop call emit for BOTH the static (INVOKESTATIC) and the
 * instance-receiver (INVOKEVIRTUAL/INVOKEINTERFACE) paths — one lowering, no static/instance
 * copy-paste of the descriptor logic (P1).
 *
 * Reads the real method off the compile classpath via [ClasspathSymbolResolver.resolveByArgs]:
 *  - ranks overloads by per-arg [JvmCoercion] cost and emits the needed widening per arg (**F13**),
 *  - links the method's REAL return descriptor and converts it into the declared `GIVING` target
 *    (**F14** static / **F21** instance).
 * Resolved + storable → use it; class unreadable / ambiguous / un-coercible return → fall back to
 * the Kobol-side descriptor guess (no regression). [loadReceiver] is null for a static call and,
 * for an instance call, pushes the receiver before the arguments.
 */
private fun AsmEmitter.emitInteropCall(
    ctx: MethodContext,
    stmt: CallStatement,
    methodName: String,
    owner: String,
    invokeOpcode: Int,
    isInterface: Boolean,
    loadReceiver: (() -> Unit)?,
) {
    val giving = stmt.giving
    // The no-GIVING default return differs by call kind to preserve prior behaviour: a static call
    // historically guessed Object, an instance call guessed void (both get discarded below).
    val wantDesc = when {
        giving != null       -> jvmDescriptor(resolveSymbolType(ctx, giving.parts[0]))
        loadReceiver != null -> null   // fire-and-forget instance call
        else                 -> null   // fire-and-forget static call
    }
    val left = emitInteropInvoke(
        ctx, stmt.args, methodName, owner, invokeOpcode, isInterface, loadReceiver, wantDesc,
        // Fire-and-forget fallback return matches the legacy guess: static→Object, instance→void.
        fallbackReturnWhenNoWant = if (loadReceiver != null) "V" else "Ljava/lang/Object;",
    )
    val mv = ctx.mv
    if (giving != null) emitStore(ctx, giving)
    else if (left != "V") {
        // Size-aware discard: a real long/double return is category-2 (POP2).
        if (Type.getType(left).size == 2) mv.visitInsn(POP2) else mv.visitInsn(POP)
    }
}

/**
 * Shared classpath-aware interop invoke core (**E2**), used by the CALL statement ([emitInteropCall])
 * AND the CALL expression ([emitCallExpr]) so the resolve/coerce/varargs logic is never forked (P1).
 *
 * Resolves the real method off the compile classpath ([ClasspathSymbolResolver.resolveByArgs]),
 * emits the receiver (if [loadReceiver]) + per-arg widening (**F13**) + varargs packing (**F24**),
 * invokes, and coerces the method's REAL return to [wantDesc]. Returns the JVM descriptor of the
 * value left on the stack ("V" when nothing was left).
 *
 * @param wantDesc the descriptor the caller wants left on the stack (the GIVING target, or a CALL
 *   expression's inferred result type). null = fire-and-forget: leave the real/guessed return as-is.
 * @param fallbackReturnWhenNoWant the return descriptor to assume in the un-resolved fallback when
 *   [wantDesc] is null (the legacy static→Object / instance→void guess).
 */
/**
 * Narrow an instance-call receiver to its resolved [owner] class before the INVOKEVIRTUAL/
 * INVOKEINTERFACE (**F26**). The receiver's *declared* slot type is `Ljava/lang/Object;` whenever it
 * crossed a procedure boundary (a `USING` param / `GIVING` result / DATA field typed by an imported
 * class) — the verifier sees `Object`, not the concrete class, so the call would `VerifyError`. The
 * CHECKCAST is a no-op for a local NEW receiver (its frame type already IS the class) and for a
 * primitively-typed receiver (`owner` would be that exact class), so it is safe to emit always.
 * Skipped only for a static call (no receiver) or an opaque `java/lang/Object` owner (nothing to narrow).
 */
private fun checkcastReceiver(mv: org.objectweb.asm.MethodVisitor, loadReceiver: (() -> Unit)?, owner: String) {
    if (loadReceiver != null && owner != "java/lang/Object")
        mv.visitTypeInsn(org.objectweb.asm.Opcodes.CHECKCAST, owner)
}

internal fun AsmEmitter.emitInteropInvoke(
    ctx: MethodContext,
    args: List<dev.kobol.parser.ast.Expression>,
    methodName: String,
    owner: String,
    invokeOpcode: Int,
    isInterface: Boolean,
    loadReceiver: (() -> Unit)?,
    wantDesc: String?,
    fallbackReturnWhenNoWant: String = "Ljava/lang/Object;",
    // F30: a constructor call's "receiver" is an uninitialised NEW ref (NEW+DUP). A CHECKCAST on an
    // uninitialised object is a VerifyError, so the NEW path passes castReceiver=false. Every other
    // (instance) call keeps the default — the F26 receiver narrowing.
    castReceiver: Boolean = true,
): String {
    val mv = ctx.mv
    val argDescs   = args.map { jvmDescriptor(inferExprType(it)) }
    val resolution = classpathResolver.resolveByArgs(owner, methodName, argDescs)
        as? dev.kobol.semantic.ClasspathSymbolResolver.CallResolution.Resolved
    val resolved = resolution?.sig

    if (resolved != null) {
        val params      = Type.getArgumentTypes(resolved.descriptor)
        val paramDescs  = params.map { it.descriptor }
        val realRetDesc = Type.getReturnType(resolved.descriptor).descriptor
        val coerceOk = when {
            wantDesc == null   -> true                 // fire-and-forget: any return ok
            realRetDesc == "V" -> false                // want a value from a void method → fall back
            else -> dev.kobol.semantic.JvmCoercion.cost(realRetDesc, wantDesc) != null
        }
        if (coerceOk) {
            loadReceiver?.invoke()
            if (castReceiver) checkcastReceiver(mv, loadReceiver, owner)
            val varargsFixed = resolution.varargsFixed
            if (varargsFixed == null) {
                args.forEachIndexed { i, arg ->
                    emitExpr(ctx, arg)
                    dev.kobol.semantic.JvmCoercion.emit(mv, argDescs[i], paramDescs[i])
                }
            } else {
                // F24 varargs: emit the fixed leading args, then pack the rest into the trailing
                // array parameter (reference element type — primitive-element varargs are excluded
                // by the resolver, so ANEWARRAY/AASTORE always apply).
                for (i in 0 until varargsFixed) {
                    emitExpr(ctx, args[i])
                    dev.kobol.semantic.JvmCoercion.emit(mv, argDescs[i], paramDescs[i])
                }
                val elem     = params[params.size - 1].elementType
                val elemDesc = elem.descriptor
                val count    = args.size - varargsFixed
                mv.visitLdcInsn(count)
                mv.visitTypeInsn(ANEWARRAY, elem.internalName)
                for (k in 0 until count) {
                    mv.visitInsn(DUP)
                    mv.visitLdcInsn(k)
                    val ai = varargsFixed + k
                    emitExpr(ctx, args[ai])
                    dev.kobol.semantic.JvmCoercion.emit(mv, argDescs[ai], elemDesc)
                    mv.visitInsn(AASTORE)
                }
            }
            mv.visitMethodInsn(invokeOpcode, owner, methodName, resolved.descriptor, isInterface)
            return if (wantDesc != null) {
                dev.kobol.semantic.JvmCoercion.emit(mv, realRetDesc, wantDesc); wantDesc
            } else realRetDesc
        }
    }

    // Fallback: unresolved / ambiguous / unreadable / un-coercible return → Kobol-side guess.
    loadReceiver?.invoke()
    if (castReceiver) checkcastReceiver(mv, loadReceiver, owner)
    val argDesc = argDescs.joinToString("")
    args.forEach { emitExpr(ctx, it) }
    val retDesc = wantDesc ?: fallbackReturnWhenNoWant
    mv.visitMethodInsn(invokeOpcode, owner, methodName, "($argDesc)$retDesc", isInterface)
    return retDesc
}

internal fun AsmEmitter.emitCall(ctx: MethodContext, stmt: CallStatement) {
        val mv = ctx.mv
        // stmt.method has original case from Token.rawValue ("LocalDate.now", "kobol.security.sha256").
        // Owner dispatch is decided by the SHARED resolver (resolveInteropTarget) so the statement
        // path, the CALL-expression path, and the type checker can never disagree on the target (F14).
        val parts      = stmt.method.split(".")
        val methodName = parts.last()
        val ownerParts = parts.dropLast(1)
        val alias      = ownerParts.firstOrNull()?.uppercase().orEmpty()

        // Static interop CALL (INVOKESTATIC) → shared classpath-aware emit (no receiver).
        fun doStaticCall(owner: String) =
            emitInteropCall(ctx, stmt, methodName, owner, INVOKESTATIC, isInterface = false, loadReceiver = null)

        // Receiver type for a single-part owner that names a known local/field (else null). The
        // shared resolver uses it to detect an instance call and to resolve a NEW value's carried
        // owner (F21) — checked AFTER alias/stdlib but BEFORE java.lang so a same-named var wins.
        val receiverType: KobolType? = if (ownerParts.size == 1)
            ctx.getLocal(alias)?.type ?: resolveSymbolType(ctx, alias).takeIf { it !is KobolType.UnknownType }
            else null

        when (val target = dev.kobol.semantic.resolveInteropTarget(ownerParts, importAliasMap, receiverType)) {
            is dev.kobol.semantic.InteropTarget.Static -> doStaticCall(target.owner)
            is dev.kobol.semantic.InteropTarget.Instance -> {
                // Instance interop CALL (F21) → shared classpath-aware emit, receiver pushed first.
                val isIface = target.owner in JVM_INTERFACE_TYPES
                emitInteropCall(
                    ctx, stmt, methodName, target.owner,
                    if (isIface) INVOKEINTERFACE else INVOKEVIRTUAL, isInterface = isIface,
                    loadReceiver = { loadRef(ctx, dev.kobol.parser.ast.Reference(listOf(alias), stmt.pos)) },
                )
            }
            dev.kobol.semantic.InteropTarget.Unresolvable -> doStaticCall(ownerParts.joinToString("/"))
            dev.kobol.semantic.InteropTarget.Reflective -> {
                // Multi-part alias.STATIC_FIELD.method(args) — reflective dispatch (statement-only).
                val aliasFqn = importAliasMap[alias]!!
                run {
                // Emit: GETSTATIC to load the static field as receiver,
                // then use KobolRuntime.invokeInstanceMethod for reflective dispatch.
                val fieldParts = ownerParts.drop(1)  // [field1, ...]
                val staticFieldName = fieldParts[0].replace("-", "")
                val fieldDesc = "L$aliasFqn;"
                mv.visitFieldInsn(GETSTATIC, aliasFqn, staticFieldName, fieldDesc)
                // Save receiver in a temp local
                val receiverSlot = ctx.allocLocal("__call_rx", KobolType.JavaObjectType())
                storeLocal(mv, KobolType.JavaObjectType(), receiverSlot)
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
                loadLocal(mv, KobolType.JavaObjectType(), receiverSlot)
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
                }   // run
            }       // InteropTarget.Reflective
        }           // when (target)
    }

/**
 * Emit a CALL in EXPRESSION position (F14) — leaves the method's result on the stack, coerced to
 * the type the checker already inferred for this node ([inferExprType]). Reuses the SAME owner
 * resolution ([resolveInteropTarget]) and the SAME classpath-aware invoke core ([emitInteropInvoke])
 * as the statement path, so the value left on the stack matches the static type with no fork. The
 * reflective `alias.FIELD.method` form is statement-only; it cannot appear here.
 */
internal fun AsmEmitter.emitCallExpr(ctx: MethodContext, expr: dev.kobol.parser.ast.CallExpr) {
    val parts      = expr.method.split(".")
    val methodName = parts.last()
    val ownerParts = parts.dropLast(1)
    val alias      = ownerParts.firstOrNull()?.uppercase().orEmpty()
    val resultDesc = jvmDescriptor(inferExprType(expr))

    val receiverType: KobolType? = if (ownerParts.size == 1)
        ctx.getLocal(alias)?.type ?: resolveSymbolType(ctx, alias).takeIf { it !is KobolType.UnknownType }
        else null

    when (val target = dev.kobol.semantic.resolveInteropTarget(ownerParts, importAliasMap, receiverType)) {
        is dev.kobol.semantic.InteropTarget.Static ->
            emitInteropInvoke(ctx, expr.args, methodName, target.owner, INVOKESTATIC, false, null, resultDesc)
        is dev.kobol.semantic.InteropTarget.Instance -> {
            val isIface = target.owner in JVM_INTERFACE_TYPES
            emitInteropInvoke(
                ctx, expr.args, methodName, target.owner,
                if (isIface) INVOKEINTERFACE else INVOKEVIRTUAL, isIface,
                { loadRef(ctx, dev.kobol.parser.ast.Reference(listOf(alias), expr.pos)) }, resultDesc,
            )
        }
        // Reflective/Unresolvable in expression position: the type checker rejects these, so this is
        // a defensive guess that still links a method with the inferred result type (no crash).
        else -> emitInteropInvoke(
            ctx, expr.args, methodName, ownerParts.joinToString("/"), INVOKESTATIC, false, null, resultDesc,
        )
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
