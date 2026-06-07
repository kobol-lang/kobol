package dev.kobol.codegen

import dev.kobol.codegen.AsmEmitter.MethodContext
import dev.kobol.parser.ast.*
import dev.kobol.semantic.KobolType
import dev.kobol.semantic.Symbol
import dev.kobol.semantic.TypeChecker
import org.objectweb.asm.*
import org.objectweb.asm.Opcodes.*
import java.io.File

internal fun AsmEmitter.emitExprAsString(ctx: MethodContext, expr: Expression) {
        val mv = ctx.mv
        when {
            expr is BuiltinCall && expr.name == "DISPLAY_JSON" -> {
                val JSON_OWN = "dev/kobol/stdlib/KobolJson"
                emitExpr(ctx, expr.args[0])
                when (inferExprType(expr.args[0])) {
                    is KobolType.IntegerType ->
                        mv.visitMethodInsn(INVOKESTATIC, "java/lang/Long",    "valueOf", "(J)Ljava/lang/Long;",        false)
                    is KobolType.BooleanType ->
                        mv.visitMethodInsn(INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;",    false)
                    else -> { /* reference type — already an Object */ }
                }
                mv.visitMethodInsn(INVOKESTATIC, JSON_OWN, "toJson", "(Ljava/lang/Object;)Ljava/lang/String;", false)
            }
            expr is BuiltinCall && expr.name == "DISPLAY_JSON_PRETTY" -> {
                val JSON_OWN = "dev/kobol/stdlib/KobolJson"
                emitExpr(ctx, expr.args[0])
                when (inferExprType(expr.args[0])) {
                    is KobolType.IntegerType ->
                        mv.visitMethodInsn(INVOKESTATIC, "java/lang/Long",    "valueOf", "(J)Ljava/lang/Long;",        false)
                    is KobolType.BooleanType ->
                        mv.visitMethodInsn(INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;",    false)
                    else -> { /* reference type — already an Object */ }
                }
                mv.visitMethodInsn(INVOKESTATIC, JSON_OWN, "toPrettyJson", "(Ljava/lang/Object;)Ljava/lang/String;", false)
            }
            expr is BuiltinCall && expr.name == "DISPLAY_XML" -> {
                val XML_OWN = "dev/kobol/stdlib/KobolXml"
                emitExpr(ctx, expr.args[0])
                when (inferExprType(expr.args[0])) {
                    is KobolType.IntegerType ->
                        mv.visitMethodInsn(INVOKESTATIC, "java/lang/Long",    "valueOf", "(J)Ljava/lang/Long;",     false)
                    is KobolType.BooleanType ->
                        mv.visitMethodInsn(INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false)
                    else -> {}
                }
                mv.visitMethodInsn(INVOKESTATIC, XML_OWN, "toXml", "(Ljava/lang/Object;)Ljava/lang/String;", false)
            }
            expr is BuiltinCall && expr.name == "DISPLAY_XML_PRETTY" -> {
                val XML_OWN = "dev/kobol/stdlib/KobolXml"
                emitExpr(ctx, expr.args[0])
                when (inferExprType(expr.args[0])) {
                    is KobolType.IntegerType ->
                        mv.visitMethodInsn(INVOKESTATIC, "java/lang/Long",    "valueOf", "(J)Ljava/lang/Long;",     false)
                    is KobolType.BooleanType ->
                        mv.visitMethodInsn(INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false)
                    else -> {}
                }
                mv.visitMethodInsn(INVOKESTATIC, XML_OWN, "toPrettyXml", "(Ljava/lang/Object;)Ljava/lang/String;", false)
            }
            expr is BuiltinCall && expr.name == "DISPLAY_STYLED" -> {
                val DISPLAY_OWN = "dev/kobol/runtime/KobolDisplay"
                emitExpr(ctx, expr.args[0])  // text
                emitExpr(ctx, expr.args[1])  // bold (boolean)
                emitExpr(ctx, expr.args[2])  // underline (boolean)
                emitExpr(ctx, expr.args[3])  // color (string)
                mv.visitMethodInsn(INVOKESTATIC, DISPLAY_OWN, "styled",
                    "(Ljava/lang/String;ZZLjava/lang/String;)Ljava/lang/String;", false)
            }
            expr is Literal -> when (expr.kind) {
                LiteralKind.STRING -> mv.visitLdcInsn(expr.value.toString())
                else               -> { emitExpr(ctx, expr); boxAndToString(mv, inferExprType(expr)) }
            }
            expr is StringTemplateExpr -> emitStringTemplate(ctx, expr)
            else -> { emitExpr(ctx, expr); boxAndToString(mv, inferExprType(expr)) }
        }
    }

internal fun AsmEmitter.emitStringTemplate(ctx: MethodContext, expr: StringTemplateExpr) {
        val mv = ctx.mv
        if (expr.parts.isEmpty()) { mv.visitLdcInsn(""); return }

        mv.visitTypeInsn(NEW, "java/lang/StringBuilder")
        mv.visitInsn(DUP)
        mv.visitMethodInsn(INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false)
        for (part in expr.parts) {
            when (part) {
                is StringTemplatePart.RawText -> { mv.visitLdcInsn(part.text); appendString(mv) }
                is StringTemplatePart.Interpolated -> {
                    emitExpr(ctx, part.expr)
                    appendValue(mv, inferExprType(part.expr))
                }
            }
        }
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false)
    }

internal fun AsmEmitter.emitExpr(ctx: MethodContext, expr: Expression) {
        val mv = ctx.mv
        when (expr) {
            is Literal -> emitLiteral(mv, expr)

            is Reference -> loadRef(ctx, expr)

            is BinaryExpr -> emitBinaryExpr(ctx, expr)

            is UnaryExpr -> {
                emitExpr(ctx, expr.operand)
                when (expr.op) {
                    UnaryOp.NEGATE -> when (inferExprType(expr.operand)) {
                        // BigDecimal: -x is BigDecimal.negate(), never the integer `ineg`.
                        is KobolType.MoneyType, is KobolType.DecimalType ->
                            mv.visitMethodInsn(INVOKEVIRTUAL, BIGDECIMAL, "negate", "()L$BIGDECIMAL;", false)
                        is KobolType.IntegerType -> mv.visitInsn(LNEG)   // long
                        else                     -> mv.visitInsn(INEG)   // SMALLINT (int)
                    }
                    UnaryOp.NOT -> {
                        // boolean: 1 → 0, 0 → 1  via XOR with 1
                        mv.visitInsn(ICONST_1); mv.visitInsn(IXOR)
                    }
                }
            }

            is StringTemplateExpr -> emitStringTemplate(ctx, expr)

            is BuiltinCall -> emitBuiltin(ctx, expr)

            is RecordLiteralExpr -> {
                // Allocate the record, then initialise each named field (#15). Previously only the
                // NEW happened and field values were dropped, so `Point { x: 4 }` produced x = 0.
                val className = "${ctx.owner}\$${javaClass(expr.typeName)}"
                mv.visitTypeInsn(NEW, className)
                mv.visitInsn(DUP)
                mv.visitMethodInsn(INVOKESPECIAL, className, "<init>", "()V", false)
                val recSym = checker.symbols.resolve(expr.typeName) as? Symbol.RecordSymbol
                for (f in expr.fields) {
                    val fType = recSym?.fields?.get(f.name) ?: inferExprType(f.value)
                    mv.visitInsn(DUP)                  // keep the instance ref below the value
                    emitExpr(ctx, f.value)
                    val vType = inferExprType(f.value)
                    if (fType is KobolType.MoneyType || fType is KobolType.DecimalType) coerceToDecimalIfNeeded(mv, vType)
                    else coerceIntToTarget(mv, vType, fType)
                    snapshotIfRecord(ctx, vType)   // F2: a record-typed literal field is a "copy site" — snapshot, don't alias the buffer
                    mv.visitFieldInsn(PUTFIELD, className, javaIdent(f.name), jvmDescriptor(fType))
                }
            }

            is NewExpr -> {
                // F30: resolve the constructor off the compile classpath (like a CALL) so the REAL
                // <init> parameter descriptors drive the INVOKESPECIAL — incl. F22's guarded J→I
                // narrowing — instead of the Kobol-side guess. A Kobol INTEGER is a long (J), so the
                // old guess emitted `<init>(…J)` while a real `int`-param ctor is `(…I)` →
                // NoSuchMethodError at run, type-checks clean = P3 landmine (the SAME guess-vs-real
                // descriptor class E2/F13/F22 closed for CALL; the NEW path now shares
                // emitInteropInvoke — no fork, P1; one general rule for every interop link, P6).
                // Constructors are `<init>` methods, read off the classpath like any other.
                //
                // NEW+DUP is the "receiver": it leaves an UNINITIALISED ref that INVOKESPECIAL
                // `<init>` consumes (the dup) together with the args, leaving the constructed
                // instance on the stack. castReceiver=false — a CHECKCAST on an uninitialised NEW
                // ref is a VerifyError. An unreadable/ambiguous owner falls back to the `(<guess>)V`
                // descriptor (today's behaviour) — no regression (P7).
                val owner = resolveConstructorOwner(expr.owner.split("."))
                emitInteropInvoke(
                    ctx, expr.args, "<init>", owner, INVOKESPECIAL, isInterface = false,
                    loadReceiver = { mv.visitTypeInsn(NEW, owner); mv.visitInsn(DUP) },
                    wantDesc = null,
                    fallbackReturnWhenNoWant = "V",
                    castReceiver = false,
                )
            }

            is CallExpr -> emitCallExpr(ctx, expr)

            is NamedArgument -> emitExpr(ctx, expr.value)

            is PipelineExpr -> emitPipeline(ctx, expr)
        }
    }

internal fun AsmEmitter.emitLiteral(mv: MethodVisitor, lit: Literal) {
        when (lit.kind) {
            LiteralKind.INTEGER -> {
                val v = lit.value.toString().toLong()
                mv.visitLdcInsn(v)
            }
            LiteralKind.DECIMAL -> {
                mv.visitTypeInsn(NEW, BIGDECIMAL)
                mv.visitInsn(DUP)
                mv.visitLdcInsn(lit.value.toString())
                mv.visitMethodInsn(INVOKESPECIAL, BIGDECIMAL, "<init>", "(Ljava/lang/String;)V", false)
            }
            LiteralKind.STRING -> mv.visitLdcInsn(lit.value.toString())
            LiteralKind.BOOLEAN -> mv.visitInsn(if (lit.value == true || lit.value.toString() == "TRUE") ICONST_1 else ICONST_0)
        }
    }

    /**
     * Emit a BigDecimal arithmetic operation, using MathContext-aware overload
     * when a WITH PRECISION block is active, falling back to the plain or
     * HALF_EVEN overload otherwise.
     * Stack before call: ..., lhs: BigDecimal, rhs: BigDecimal
     * Stack after call:  ..., result: BigDecimal
     */
internal fun AsmEmitter.emitBigDecimalOp(mv: MethodVisitor, op: String) {
        mv.visitMethodInsn(INVOKESTATIC, MATH_CTX, "current", "()L$JMATH_CTX;", false)
        val lNoCtx = Label(); val lDone = Label()
        mv.visitInsn(DUP)
        mv.visitJumpInsn(IFNULL, lNoCtx)
        // With MathContext
        if (op == "divide") {
            // stack: lhs, rhs, mc — correct for divide(BigDecimal, MathContext)
            mv.visitMethodInsn(INVOKEVIRTUAL, BIGDECIMAL, op, "(L$BIGDECIMAL;L$JMATH_CTX;)L$BIGDECIMAL;", false)
        } else {
            mv.visitMethodInsn(INVOKEVIRTUAL, BIGDECIMAL, op, "(L$BIGDECIMAL;L$JMATH_CTX;)L$BIGDECIMAL;", false)
        }
        mv.visitJumpInsn(GOTO, lDone)
        mv.visitLabel(lNoCtx)
        mv.visitInsn(POP) // pop null mc
        if (op == "divide") {
            mv.visitFieldInsn(GETSTATIC, ROUNDING, "HALF_EVEN", "Ljava/math/RoundingMode;")
            mv.visitMethodInsn(INVOKEVIRTUAL, BIGDECIMAL, op, "(L$BIGDECIMAL;Ljava/math/RoundingMode;)L$BIGDECIMAL;", false)
        } else {
            mv.visitMethodInsn(INVOKEVIRTUAL, BIGDECIMAL, op, "(L$BIGDECIMAL;)L$BIGDECIMAL;", false)
        }
        mv.visitLabel(lDone)
    }

/** Coerce INTEGER or SMALLINT on top of stack to BigDecimal when used in a MONEY/DECIMAL expression. */
internal fun AsmEmitter.coerceToDecimalIfNeeded(mv: MethodVisitor, type: KobolType) {
    when (type) {
        is KobolType.IntegerType  -> mv.visitMethodInsn(INVOKESTATIC, BIGDECIMAL, "valueOf", "(J)L$BIGDECIMAL;", false)
        is KobolType.SmallIntType -> { mv.visitInsn(I2L); mv.visitMethodInsn(INVOKESTATIC, BIGDECIMAL, "valueOf", "(J)L$BIGDECIMAL;", false) }
        else -> { /* already BigDecimal, String, or other — no coercion */ }
    }
}

/**
 * Narrow a Kobol INTEGER (JVM `long`) on top of stack to a SMALLINT (JVM `int`) slot
 * when the receiving target is SMALLINT. The SMALLINT field/slot descriptor is `I`, so an
 * INTEGER-typed source value (e.g. a literal, which is always pushed as `long`) needs L2I
 * before it reaches the slot — otherwise the verifier rejects a `long` in an `int` slot.
 */
internal fun AsmEmitter.coerceIntToTarget(mv: MethodVisitor, sourceType: KobolType, targetType: KobolType) {
    if (targetType is KobolType.SmallIntType && sourceType is KobolType.IntegerType) {
        mv.visitInsn(L2I)
    }
}

/** Emit an argument for a builtin whose parameter is BigDecimal, widening INTEGER/SMALLINT args. */
internal fun AsmEmitter.emitDecimalArg(ctx: MethodContext, arg: Expression) {
    emitExpr(ctx, arg)
    coerceToDecimalIfNeeded(ctx.mv, inferExprType(arg))
}

/**
 * Emit BigDecimal exponentiation. Stack before: ..., base: BigDecimal, exp: BigDecimal.
 * Narrows the exponent to an int with intValueExact() (BigDecimal.pow takes an int exponent;
 * a fractional/oversized exponent THROWS rather than silently flooring — decimal exactness,
 * priority 4), then calls KobolMath.power(BigDecimal, int). Stack after: ..., result: BigDecimal.
 * Shared by the `**` operator and the POWER builtin (#v5) so both lower identically (P6).
 */
internal fun AsmEmitter.emitDecimalPow(mv: MethodVisitor) {
    mv.visitMethodInsn(INVOKEVIRTUAL, BIGDECIMAL, "intValueExact", "()I", false)
    mv.visitMethodInsn(INVOKESTATIC, MATH_OWN, "power", "(L$BIGDECIMAL;I)L$BIGDECIMAL;", false)
}

/** True when the type is a JVM reference (occupies one slot, compared with `.equals`, not LCMP). */
internal fun isJvmReference(type: KobolType): Boolean = when (type) {
    is KobolType.IntegerType, is KobolType.SmallIntType, is KobolType.BooleanType, is KobolType.VoidType -> false
    else -> true
}

internal fun AsmEmitter.emitBinaryExpr(ctx: MethodContext, expr: BinaryExpr) {
        val mv    = ctx.mv
        val lType = inferExprType(expr.left)
        val rType = inferExprType(expr.right)
        val numeric = lType is KobolType.MoneyType || lType is KobolType.DecimalType ||
                      rType is KobolType.MoneyType || rType is KobolType.DecimalType

        if (numeric) {
            emitExpr(ctx, expr.left)
            coerceToDecimalIfNeeded(mv, lType)  // e.g. INTEGER → BigDecimal for MONEY*INTEGER
            emitExpr(ctx, expr.right)
            coerceToDecimalIfNeeded(mv, rType)
            when (expr.op) {
                BinaryOp.ADD      -> emitBigDecimalOp(mv, "add")
                BinaryOp.SUBTRACT -> emitBigDecimalOp(mv, "subtract")
                BinaryOp.MULTIPLY -> emitBigDecimalOp(mv, "multiply")
                BinaryOp.DIVIDE   -> emitBigDecimalOp(mv, "divide")
                // compareTo (not equals) so scale differs but value matches → equal,
                // e.g. 124.99 == 124.990. emitCompareFlag normalizes to a 0/1 boolean.
                BinaryOp.EQ  -> { mv.visitMethodInsn(INVOKEVIRTUAL, BIGDECIMAL, "compareTo", "(L$BIGDECIMAL;)I", false); emitCompareFlag(mv, IFEQ) }
                BinaryOp.NEQ -> { mv.visitMethodInsn(INVOKEVIRTUAL, BIGDECIMAL, "compareTo", "(L$BIGDECIMAL;)I", false); emitCompareFlag(mv, IFNE) }
                BinaryOp.GT  -> { mv.visitMethodInsn(INVOKEVIRTUAL, BIGDECIMAL, "compareTo", "(L$BIGDECIMAL;)I", false); emitCompareFlag(mv, IFGT) }
                BinaryOp.LT  -> { mv.visitMethodInsn(INVOKEVIRTUAL, BIGDECIMAL, "compareTo", "(L$BIGDECIMAL;)I", false); emitCompareFlag(mv, IFLT) }
                BinaryOp.GEQ -> { mv.visitMethodInsn(INVOKEVIRTUAL, BIGDECIMAL, "compareTo", "(L$BIGDECIMAL;)I", false); emitCompareFlag(mv, IFGE) }
                BinaryOp.LEQ -> { mv.visitMethodInsn(INVOKEVIRTUAL, BIGDECIMAL, "compareTo", "(L$BIGDECIMAL;)I", false); emitCompareFlag(mv, IFLE) }
                BinaryOp.POWER -> {
                    // Decimal base ** exponent. BigDecimal supports only integer exponents,
                    // so the exponent (also a BigDecimal here) is narrowed with
                    // intValueExact() — which THROWS on a fractional or oversized exponent
                    // rather than silently flooring or floating it (#10, priority: never
                    // lose decimal exactness). Previously this fell to `else` and emitted
                    // nothing → the result was silently the wrong operand.
                    // Shared with the POWER builtin (#v5) via emitDecimalPow — one lowering, P6.
                    emitDecimalPow(mv)
                }
                else -> { /* default */ }
            }
            return
        }

        if (expr.op == BinaryOp.AND || expr.op == BinaryOp.OR) {
            // Short-circuit AND/OR producing a 0/1 int. Both operands and both
            // exit paths must leave exactly one int on the stack, else the JVM
            // verifier (and ASM COMPUTE_FRAMES) sees mismatched frames at the
            // merge label — the bug behind the #6 `ArrayIndexOutOfBoundsException`.
            val lShort = Label()   // the short-circuit result is known here
            val lEnd   = Label()
            emitExpr(ctx, expr.left)
            // AND: left==0 → result 0 (short).  OR: left!=0 → result 1 (short).
            if (expr.op == BinaryOp.AND) mv.visitJumpInsn(IFEQ, lShort)
            else mv.visitJumpInsn(IFNE, lShort)
            emitExpr(ctx, expr.right)
            // result == right (already a 0/1 int); skip the short-circuit constant
            mv.visitJumpInsn(GOTO, lEnd)
            mv.visitLabel(lShort)
            mv.visitInsn(if (expr.op == BinaryOp.AND) ICONST_0 else ICONST_1)
            mv.visitLabel(lEnd)
            return
        }

        // Reference-type (in)equality: use Object.equals, not the long-compare path below
        // (which would emit LCMP on object references — invalid bytecode → frame crash).
        // Covers String, UUID, dates, records, lists, etc. (numeric Money/Decimal handled above).
        if ((expr.op == BinaryOp.EQ || expr.op == BinaryOp.NEQ) &&
            (isJvmReference(lType) || isJvmReference(rType))) {
            emitExpr(ctx, expr.left)
            emitExpr(ctx, expr.right)
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Object", "equals",
                "(Ljava/lang/Object;)Z", false)
            if (expr.op == BinaryOp.NEQ) { mv.visitInsn(ICONST_1); mv.visitInsn(IXOR) }
            return
        }

        emitExpr(ctx, expr.left)
        emitExpr(ctx, expr.right)

        when (expr.op) {
            BinaryOp.ADD -> if (lType is KobolType.TextType || rType is KobolType.TextType) {
                mv.visitMethodInsn(INVOKEVIRTUAL, STRING, "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
            } else mv.visitInsn(LADD)
            BinaryOp.SUBTRACT -> mv.visitInsn(LSUB)
            BinaryOp.MULTIPLY -> mv.visitInsn(LMUL)
            BinaryOp.DIVIDE   -> mv.visitInsn(LDIV)
            BinaryOp.POWER    -> {
                // Integer exponentiation. Both operands are longs on the stack; the old
                // `L2D; L2D` dance was invalid (the second L2D hit the already-converted
                // double, not the second-from-top long → VerifyError). Reuse the same
                // long power helper the builtin POWER uses (#10).
                mv.visitMethodInsn(INVOKESTATIC, MATH_OWN, "power", "(JJ)J", false)
            }
            BinaryOp.EQ  -> { mv.visitInsn(LCMP); emitCompareFlag(mv, IFEQ) }
            BinaryOp.NEQ -> { mv.visitInsn(LCMP); emitCompareFlag(mv, IFNE) }
            BinaryOp.LT  -> { mv.visitInsn(LCMP); emitCompareFlag(mv, IFLT) }
            BinaryOp.GT  -> { mv.visitInsn(LCMP); emitCompareFlag(mv, IFGT) }
            BinaryOp.LEQ -> { mv.visitInsn(LCMP); emitCompareFlag(mv, IFLE) }
            BinaryOp.GEQ -> { mv.visitInsn(LCMP); emitCompareFlag(mv, IFGE) }
            BinaryOp.AND -> { /* handled above */ }
            BinaryOp.OR  -> { /* handled above */ }
        }
    }

internal fun AsmEmitter.emitCompareFlag(mv: MethodVisitor, opcode: Int) {
        val lTrue = Label(); val lEnd = Label()
        mv.visitJumpInsn(opcode, lTrue)
        mv.visitInsn(ICONST_0); mv.visitJumpInsn(GOTO, lEnd)
        mv.visitLabel(lTrue); mv.visitInsn(ICONST_1)
        mv.visitLabel(lEnd)
    }

    /**
     * Emit a user-defined procedure call from expression context (e.g. COMPUTE x = Proc(args)).
     * Includes mock interception: if KobolMockRegistry.isMocked(name) then return the mock value.
     */
internal fun AsmEmitter.emitUserProcCallExpr(ctx: MethodContext, name: String,
                                     args: List<Expression>, sym: Symbol.ProcedureSymbol) {
        val mv      = ctx.mv
        val retType = sym.returnType ?: return
        val MOCK_REG = "dev/kobol/runtime/KobolMockRegistry"
        val paramDescs = sym.params.joinToString("") { jvmDescriptor(it.type) }
        val retDesc    = jvmDescriptor(retType)
        val labelReal  = Label(); val labelAfter = Label()
        // if (!KobolMockRegistry.isMocked(name)) goto real
        mv.visitLdcInsn(name)
        mv.visitMethodInsn(INVOKESTATIC, MOCK_REG, "isMocked", "(Ljava/lang/String;)Z", false)
        mv.visitJumpInsn(IFEQ, labelReal)
        // Mock path: KobolMockRegistry.get(name) + unbox/cast to retType
        mv.visitLdcInsn(name)
        mv.visitMethodInsn(INVOKESTATIC, MOCK_REG, "get", "(Ljava/lang/String;)Ljava/lang/Object;", false)
        castFromObject(mv, retType, ctx.owner)
        mv.visitJumpInsn(GOTO, labelAfter)
        // Real path: push args and call the real static method
        mv.visitLabel(labelReal)
        args.forEach { emitExpr(ctx, it) }
        mv.visitMethodInsn(INVOKESTATIC, ctx.owner, javaIdent(name), "($paramDescs)$retDesc", false)
        mv.visitLabel(labelAfter)
    }

/**
 * Emit construction of a VARIANT case: `Shipped("X")` (positional) or
 * `Shipped(tracking: "X")` (named) — #18. NEWs the concrete case class and invokes its
 * field constructor. Named arguments are reordered to the declared field order; positional
 * arguments are taken in order. Argument arity / names are validated by the TypeChecker.
 */
internal fun AsmEmitter.emitVariantConstruction(
    ctx: MethodContext, variantName: String, case: VariantCase, args: List<Expression>
) {
    val mv        = ctx.mv
    val caseClass = "${ctx.owner}\$${javaClass(variantName)}Case${javaClass(case.name)}"

    // Resolve each declared field to its argument expression, honouring named args.
    val named = args.any { it is NamedArgument }
    val ordered: List<Expression> = if (named) {
        case.fields.map { f ->
            val match = args.firstOrNull { it is NamedArgument && it.paramName.equals(f.name, ignoreCase = true) }
                ?: error("variant ${case.name}: no argument for field '${f.name}'")
            (match as NamedArgument).value
        }
    } else {
        args.map { if (it is NamedArgument) it.value else it }
    }

    mv.visitTypeInsn(NEW, caseClass)
    mv.visitInsn(DUP)
    for ((i, f) in case.fields.withIndex()) {
        val fType = checker.toKobolType(f.type)
        val arg   = ordered[i]
        emitExpr(ctx, arg)
        val aType = inferExprType(arg)
        if (fType is KobolType.MoneyType || fType is KobolType.DecimalType) coerceToDecimalIfNeeded(mv, aType)
        else coerceIntToTarget(mv, aType, fType)
        snapshotIfRecord(ctx, aType)   // F2: a record-typed case field is a "copy site" — snapshot, don't alias the buffer
    }
    val paramDesc = case.fields.joinToString("") { jvmDescriptor(checker.toKobolType(it.type)) }
    mv.visitMethodInsn(INVOKESPECIAL, caseClass, "<init>", "($paramDesc)V", false)
}

internal fun AsmEmitter.emitBuiltin(ctx: MethodContext, expr: BuiltinCall) {
        val mv   = ctx.mv
        val args = expr.args
        val name = expr.name.uppercase()

        // User-defined procedures have higher priority than stdlib builtins with same name.
        // This also handles MOCK interception at call sites.
        val procSym = checker.symbols.resolve(expr.name) as? Symbol.ProcedureSymbol
            ?: checker.symbols.resolve(name) as? Symbol.ProcedureSymbol
        if (procSym != null && procSym.returnType != null) {
            emitUserProcCallExpr(ctx, expr.name, args, procSym)
            return
        }

        // Variant case construction: Shipped("X") / Shipped(tracking: "X") — #18.
        // Without this, a case name fell through to "unknown builtin" and the args were
        // left dangling, so MOVE stored a non-case value and every MATCH arm missed.
        val caseEntry = variantCaseIndex[name]
        if (caseEntry != null) {
            emitVariantConstruction(ctx, caseEntry.first, caseEntry.second, args)
            return
        }

        // ── KobolText ─────────────────────────────────────────────────────
        val TEXT_OWN = "dev/kobol/stdlib/KobolText"
        val S        = "Ljava/lang/String;"
        val I        = "I"   // JVM int
        val J        = "J"   // JVM long
        val SretS    = "($S)$S"
        val SSretS   = "($S$S)$S"
        val SJretS   = "($S${J})$S"   // (String, long) -> String

        when (name) {
            "UPPERCASE"  -> { args.forEach { emitExpr(ctx, it) }; mv.visitMethodInsn(INVOKESTATIC, TEXT_OWN, "uppercase",  SretS, false) }
            "LOWERCASE"  -> { args.forEach { emitExpr(ctx, it) }; mv.visitMethodInsn(INVOKESTATIC, TEXT_OWN, "lowercase",  SretS, false) }
            "TRIM"       -> { args.forEach { emitExpr(ctx, it) }; mv.visitMethodInsn(INVOKESTATIC, TEXT_OWN, "trim",       SretS, false) }
            "TRIM-LEFT", "TRIM-START"  -> { args.forEach { emitExpr(ctx, it) }; mv.visitMethodInsn(INVOKESTATIC, TEXT_OWN, "trimStart", SretS, false) }
            "TRIM-RIGHT", "TRIM-END"   -> { args.forEach { emitExpr(ctx, it) }; mv.visitMethodInsn(INVOKESTATIC, TEXT_OWN, "trimEnd",   SretS, false) }
            "LENGTH"     -> {
                // LENGTH dispatches on the operand: String → char count; List/Map → element count.
                // Prose `LENGTH xs` previously always emitted String.length → VerifyError on a
                // collection (F3: spec §13 promised `LENGTH <list>`/`<map>` but only the field
                // form `xs.LENGTH` worked). Returns long, matching the String path + field form.
                val t = if (args.isNotEmpty()) inferExprType(args[0]) else KobolType.UnknownType
                emitExpr(ctx, args[0])
                when (t) {
                    is KobolType.ListType -> { mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Collection", "size", "()I", true); mv.visitInsn(I2L) }
                    is KobolType.MapType  -> { mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Map",        "size", "()I", true); mv.visitInsn(I2L) }
                    else                  -> mv.visitMethodInsn(INVOKESTATIC, TEXT_OWN, "length", "($S)J", false)
                }
            }
            "IS-BLANK"   -> { args.forEach { emitExpr(ctx, it) }; mv.visitMethodInsn(INVOKESTATIC, TEXT_OWN, "isBlank",   "($S)Z", false) }
            "IS-EMPTY"   -> {
                val t = if (args.isNotEmpty()) inferExprType(args[0]) else KobolType.UnknownType
                emitExpr(ctx, args[0])
                if (t is KobolType.ListType) mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Collection", "isEmpty", "()Z", true)
                else                         mv.visitMethodInsn(INVOKESTATIC,   TEXT_OWN, "isEmpty", "($S)Z", false)
            }
            "CONTAINS"   -> { args.forEach { emitExpr(ctx, it) }; mv.visitMethodInsn(INVOKESTATIC, TEXT_OWN, "contains",  "($S$S)Z", false) }
            "STARTS-WITH" -> { args.forEach { emitExpr(ctx, it) }; mv.visitMethodInsn(INVOKESTATIC, TEXT_OWN, "startsWith", "($S$S)Z", false) }
            "ENDS-WITH"   -> { args.forEach { emitExpr(ctx, it) }; mv.visitMethodInsn(INVOKESTATIC, TEXT_OWN, "endsWith",   "($S$S)Z", false) }
            "FIND"       -> { args.forEach { emitExpr(ctx, it) }; mv.visitMethodInsn(INVOKESTATIC, TEXT_OWN, "find",      "($S$S)J", false) }
            "REPLACE"    -> { args.forEach { emitExpr(ctx, it) }; mv.visitMethodInsn(INVOKESTATIC, TEXT_OWN, "replace",   "($S$S$S)$S", false) }
            "SUBSTRING"  -> { args.forEach { emitExpr(ctx, it) }; mv.visitMethodInsn(INVOKESTATIC, TEXT_OWN, "substring", "($S${J}J)$S", false) }
            "PAD-LEFT"   -> { args.forEach { emitExpr(ctx, it) }; mv.visitMethodInsn(INVOKESTATIC, TEXT_OWN, "padLeft",   SJretS, false) }
            "PAD-RIGHT"  -> { args.forEach { emitExpr(ctx, it) }; mv.visitMethodInsn(INVOKESTATIC, TEXT_OWN, "padRight",  SJretS, false) }
            "REPEAT"     -> { args.forEach { emitExpr(ctx, it) }; mv.visitMethodInsn(INVOKESTATIC, TEXT_OWN, "repeat",    SJretS, false) }
            "REVERSE"    -> { args.forEach { emitExpr(ctx, it) }; mv.visitMethodInsn(INVOKESTATIC, TEXT_OWN, "reverse",   SretS, false) }
            "SPLIT" -> {
                if (args.size == 3) {
                    args.forEach { emitExpr(ctx, it) }
                    mv.visitMethodInsn(INVOKESTATIC, TEXT_OWN, "split", "($S${S}J)Ljava/util/List;", false)
                } else {
                    args.forEach { emitExpr(ctx, it) }
                    mv.visitMethodInsn(INVOKESTATIC, TEXT_OWN, "split", "($S${S})Ljava/util/List;", false)
                }
            }
            "COMBINE"    -> {
                // 2-string overload: KobolText.combine(a, b)
                if (args.size == 2 && inferExprType(args[0]) is KobolType.TextType && inferExprType(args[1]) is KobolType.TextType) {
                    args.forEach { emitExpr(ctx, it) }
                    mv.visitMethodInsn(INVOKESTATIC, TEXT_OWN, "combine", SSretS, false)
                } else {
                    // Vararg fallback: StringBuilder
                    mv.visitTypeInsn(NEW, "java/lang/StringBuilder")
                    mv.visitInsn(DUP)
                    mv.visitMethodInsn(INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false)
                    args.forEach { emitExprAsString(ctx, it); appendString(mv) }
                    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false)
                }
            }

            // ── KobolMath ──────────────────────────────────────────────────
            else -> {
                val BD       = "Ljava/math/BigDecimal;"
                when (name) {
                    "ABS" -> {
                        val t = inferExprType(args[0])
                        emitExpr(ctx, args[0])
                        if (t is KobolType.IntegerType) mv.visitMethodInsn(INVOKESTATIC, MATH_OWN, "abs", "(J)J", false)
                        else                            mv.visitMethodInsn(INVOKESTATIC, MATH_OWN, "abs", "($BD)$BD", false)
                    }
                    "ROUND"    -> {
                        // arg0 is BigDecimal (widen INTEGER args — #11); arg1 is the scale (long), arg2 the mode (string).
                        emitDecimalArg(ctx, args[0])
                        if (args.size == 3) {
                            emitExpr(ctx, args[1]); emitExpr(ctx, args[2])
                            mv.visitMethodInsn(INVOKESTATIC, MATH_OWN, "roundWithMode", "(${BD}J$S)$BD", false)
                        } else {
                            emitExpr(ctx, args[1])
                            mv.visitMethodInsn(INVOKESTATIC, MATH_OWN, "round", "(${BD}J)$BD", false)
                        }
                    }
                    "ROUND-WITH-MODE" -> { emitDecimalArg(ctx, args[0]); emitExpr(ctx, args[1]); emitExpr(ctx, args[2]); mv.visitMethodInsn(INVOKESTATIC, MATH_OWN, "roundWithMode", "(${BD}J$S)$BD", false) }
                    "SCALE-OF"        -> { emitDecimalArg(ctx, args[0]); mv.visitMethodInsn(INVOKESTATIC, MATH_OWN, "scaleOf",      "($BD)J", false) }
                    "PRECISION-OF"    -> { emitDecimalArg(ctx, args[0]); mv.visitMethodInsn(INVOKESTATIC, MATH_OWN, "precisionOf",  "($BD)J", false) }
                    "TRUNCATE" -> { emitDecimalArg(ctx, args[0]); emitExpr(ctx, args[1]); mv.visitMethodInsn(INVOKESTATIC, MATH_OWN, "truncate", "(${BD}J)$BD", false) }
                    "FLOOR"    -> { emitDecimalArg(ctx, args[0]); mv.visitMethodInsn(INVOKESTATIC, MATH_OWN, "floor", "($BD)$BD", false) }
                    "CEIL"     -> { emitDecimalArg(ctx, args[0]); mv.visitMethodInsn(INVOKESTATIC, MATH_OWN, "ceil",  "($BD)$BD", false) }
                    "SQRT"     -> { emitDecimalArg(ctx, args[0]); mv.visitMethodInsn(INVOKESTATIC, MATH_OWN, "sqrt",  "($BD)$BD", false) }
                    "SIGN" -> {
                        // #v8: SIGN is typed INTEGER (JVM long); KobolMath.sign now returns `long`
                        // (J), so the result drops straight into a long slot — no I2L, no verifier
                        // mismatch. The decimal arg is widened (emitDecimalArg) so SMALLINT also works.
                        val t = inferExprType(args[0])
                        if (t is KobolType.IntegerType) { emitExpr(ctx, args[0]); mv.visitMethodInsn(INVOKESTATIC, MATH_OWN, "sign", "(J)J", false) }
                        else                            { emitDecimalArg(ctx, args[0]); mv.visitMethodInsn(INVOKESTATIC, MATH_OWN, "sign", "($BD)J", false) }
                    }
                    "MAX", "MIN" -> {
                        val t = inferExprType(args[0])
                        val mname = name.lowercase()
                        args.forEach { emitExpr(ctx, it) }
                        if (t is KobolType.IntegerType) mv.visitMethodInsn(INVOKESTATIC, MATH_OWN, mname, "(JJ)J", false)
                        else                            mv.visitMethodInsn(INVOKESTATIC, MATH_OWN, mname, "($BD$BD)$BD", false)
                    }
                    "MOD" -> {
                        val t = inferExprType(args[0])
                        args.forEach { emitExpr(ctx, it) }
                        if (t is KobolType.IntegerType) mv.visitMethodInsn(INVOKESTATIC, MATH_OWN, "mod", "(JJ)J", false)
                        else                            mv.visitMethodInsn(INVOKESTATIC, MATH_OWN, "mod", "($BD$BD)$BD", false)
                    }
                    "POWER" -> {
                        // #v5: POWER is typed DECIMAL by the checker, so route it through the SAME
                        // BigDecimal helper the `**` operator uses (emitDecimalPow) instead of the
                        // integer `power(JJ)J` overload — the old descriptor mismatched the
                        // BigDecimal operands the checker promised (VerifyError at load on every
                        // argument form). Both args are widened to BigDecimal; the exponent is then
                        // narrowed with intValueExact (throws on a fractional/oversized exponent
                        // rather than silently flooring — decimal exactness, priority 4).
                        emitDecimalArg(ctx, args[0])
                        emitDecimalArg(ctx, args[1])
                        emitDecimalPow(mv)
                    }
                    "IS-POSITIVE", "IS-NEGATIVE", "IS-ZERO" -> {
                        val t = inferExprType(args[0])
                        emitExpr(ctx, args[0])
                        val mname = when (name) {
                            "IS-POSITIVE" -> "isPositive"
                            "IS-NEGATIVE" -> "isNegative"
                            else          -> "isZero"
                        }
                        if (t is KobolType.IntegerType) mv.visitMethodInsn(INVOKESTATIC, MATH_OWN, mname, "(J)Z", false)
                        else                            mv.visitMethodInsn(INVOKESTATIC, MATH_OWN, mname, "($BD)Z", false)
                    }

                    // ── KobolDate ──────────────────────────────────────────
                    else -> {
                        val DATE_OWN = "dev/kobol/stdlib/KobolDate"
                        val LD       = "Ljava/time/LocalDate;"
                        when (name) {
                            "TODAY"    -> mv.visitMethodInsn(INVOKESTATIC, DATE_OWN, "today", "()$LD", false)
                            "NOW"      -> mv.visitMethodInsn(INVOKESTATIC, DATE_OWN, "now",   "()Ljava/time/LocalDateTime;", false)
                            "DATE"     -> { args.forEach { emitExpr(ctx, it) }; mv.visitMethodInsn(INVOKESTATIC, DATE_OWN, "date", "(JJJ)$LD", false) }
                            "YEAR"     -> { emitExpr(ctx, args[0]); mv.visitMethodInsn(INVOKESTATIC, DATE_OWN, "year",  "($LD)J", false) }
                            "MONTH"    -> { emitExpr(ctx, args[0]); mv.visitMethodInsn(INVOKESTATIC, DATE_OWN, "month", "($LD)J", false) }
                            "DAY"      -> { emitExpr(ctx, args[0]); mv.visitMethodInsn(INVOKESTATIC, DATE_OWN, "day",   "($LD)J", false) }
                            "ADD-DAYS"   -> { args.forEach { emitExpr(ctx, it) }; mv.visitMethodInsn(INVOKESTATIC, DATE_OWN, "addDays",   "($LD${J})$LD", false) }
                            "ADD-MONTHS" -> { args.forEach { emitExpr(ctx, it) }; mv.visitMethodInsn(INVOKESTATIC, DATE_OWN, "addMonths", "($LD${J})$LD", false) }
                            "ADD-YEARS"  -> { args.forEach { emitExpr(ctx, it) }; mv.visitMethodInsn(INVOKESTATIC, DATE_OWN, "addYears",  "($LD${J})$LD", false) }
                            "DATE-DIFF"  -> { args.forEach { emitExpr(ctx, it) }; mv.visitMethodInsn(INVOKESTATIC, DATE_OWN, "dateDiff",  "($LD$LD)J", false) }
                            "FORMAT-DATE" -> { args.forEach { emitExpr(ctx, it) }; mv.visitMethodInsn(INVOKESTATIC, DATE_OWN, "formatDate", "($LD$S)$S", false) }
                            "PARSE-DATE"  -> { args.forEach { emitExpr(ctx, it) }; mv.visitMethodInsn(INVOKESTATIC, DATE_OWN, "parseDate",  "($S$S)$LD", false) }
                            "ISO-DATE"    -> { emitExpr(ctx, args[0]); mv.visitMethodInsn(INVOKESTATIC, DATE_OWN, "isoDate", "($LD)$S", false) }
                            "IS-BEFORE"   -> { args.forEach { emitExpr(ctx, it) }; mv.visitMethodInsn(INVOKESTATIC, DATE_OWN, "isBefore", "($LD$LD)Z", false) }
                            "IS-AFTER"    -> { args.forEach { emitExpr(ctx, it) }; mv.visitMethodInsn(INVOKESTATIC, DATE_OWN, "isAfter",  "($LD$LD)Z", false) }
                            "IS-EQUAL"    -> { args.forEach { emitExpr(ctx, it) }; mv.visitMethodInsn(INVOKESTATIC, DATE_OWN, "isEqual",  "($LD$LD)Z", false) }

                            // ── KobolConvert ───────────────────────────────
                            else -> {
                                val CONV_OWN = "dev/kobol/stdlib/KobolConvert"
                                when (name) {
                                    "TO-TEXT" -> {
                                        val t = if (args.isNotEmpty()) inferExprType(args[0]) else KobolType.UnknownType
                                        emitExpr(ctx, args[0])
                                        val desc = when (t) {
                                            is KobolType.IntegerType            -> "(J)$S"
                                            is KobolType.DecimalType, is KobolType.MoneyType -> "(Ljava/math/BigDecimal;)$S"
                                            is KobolType.BooleanType            -> "(Z)$S"
                                            is KobolType.DateType               -> "(Ljava/time/LocalDate;)$S"
                                            is KobolType.UuidType               -> { mv.visitMethodInsn(INVOKESTATIC, "dev/kobol/stdlib/KobolUuid", "toText", "(Ljava/util/UUID;)$S", false); return }
                                            else                                -> "(Ljava/lang/Object;)$S"
                                        }
                                        mv.visitMethodInsn(INVOKESTATIC, CONV_OWN, "toText", desc, false)
                                    }
                                    "TO-INTEGER" -> { emitExpr(ctx, args[0]); mv.visitMethodInsn(INVOKESTATIC, CONV_OWN, "toInteger", "($S)J", false) }
                                    "TO-DECIMAL" -> { emitExpr(ctx, args[0]); mv.visitMethodInsn(INVOKESTATIC, CONV_OWN, "toDecimal", "($S)Ljava/math/BigDecimal;", false) }
                                    "TO-DATE"    -> { emitExpr(ctx, args[0]); mv.visitMethodInsn(INVOKESTATIC, CONV_OWN, "toDate",    "($S)Ljava/time/LocalDate;",   false) }
                                    "TO-BOOLEAN" -> {
                                        val t = if (args.isNotEmpty()) inferExprType(args[0]) else KobolType.UnknownType
                                        emitExpr(ctx, args[0])
                                        val desc = if (t is KobolType.IntegerType) "(J)Z" else "($S)Z"
                                        mv.visitMethodInsn(INVOKESTATIC, CONV_OWN, "toBoolean", desc, false)
                                    }
                                    // ── KobolUuid ────────────────────────────────────────────────
                                    "UUID-GENERATE" -> mv.visitMethodInsn(INVOKESTATIC, "dev/kobol/stdlib/KobolUuid", "generate", "()Ljava/util/UUID;", false)
                                    "UUID-NIL"      -> mv.visitMethodInsn(INVOKESTATIC, "dev/kobol/stdlib/KobolUuid", "nil",      "()Ljava/util/UUID;", false)
                                    "UUID-FROM-TEXT" -> { emitExpr(ctx, args[0]); mv.visitMethodInsn(INVOKESTATIC, "dev/kobol/stdlib/KobolUuid", "fromText", "(Ljava/lang/String;)Ljava/util/UUID;", false) }
                                    "UUID-TO-TEXT"   -> { emitExpr(ctx, args[0]); mv.visitMethodInsn(INVOKESTATIC, "dev/kobol/stdlib/KobolUuid", "toText",   "(Ljava/util/UUID;)Ljava/lang/String;", false) }
                                    else -> {
                                        // Unknown builtin — emit args to keep stack consistent
                                        args.forEach { emitExpr(ctx, it) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

internal fun AsmEmitter.emitPipeline(ctx: MethodContext, expr: PipelineExpr) {
        val mv = ctx.mv
        val RT = "dev/kobol/runtime/KobolRuntime"
        // Partition: the final SumStage (if present) is handled after collection
        val hasSumStage = expr.stages.lastOrNull() is PipelineStage.SumStage
        val listStages = if (hasSumStage) expr.stages.dropLast(1) else expr.stages

        // Each stage is a List → List transform via a runtime helper (no lambda codegen needed).
        emitExpr(ctx, expr.source)   // List on stack
        for (stage in listStages) {
            when (stage) {
                is PipelineStage.FilterStage -> {
                    // Supported forms: FILTER WHERE <member>  and  FILTER WHERE NOT <member>,
                    // where <member> is a bare reference to a boolean field/CONDITION on the element.
                    val cond    = stage.condition
                    val negated = cond is UnaryExpr && cond.op == UnaryOp.NOT
                    val ref     = (if (negated) (cond as UnaryExpr).operand else cond) as? Reference
                    val member  = ref?.takeIf { !it.isFieldAccess }?.name
                    if (member != null) {
                        mv.visitLdcInsn(member)
                        mv.visitMethodInsn(INVOKESTATIC, RT, if (negated) "rejectByMember" else "filterByMember",
                            "(Ljava/util/List;Ljava/lang/String;)Ljava/util/List;", false)
                    }
                    // Compound predicates are not yet supported → pass through unfiltered.
                }
                is PipelineStage.SortStage -> {
                    mv.visitLdcInsn(stage.field)
                    mv.visitInsn(if (stage.descending) ICONST_1 else ICONST_0)
                    mv.visitMethodInsn(INVOKESTATIC, RT, "sortByField",
                        "(Ljava/util/List;Ljava/lang/String;Z)Ljava/util/List;", false)
                }
                is PipelineStage.TakeStage -> {
                    emitExpr(ctx, stage.count)   // long
                    mv.visitMethodInsn(INVOKESTATIC, RT, "take",
                        "(Ljava/util/List;J)Ljava/util/List;", false)
                }
                is PipelineStage.TransformStage -> {
                    // TRANSFORM TO field — project each element to that field's value. O(n) single pass.
                    mv.visitLdcInsn(stage.field)
                    mv.visitMethodInsn(INVOKESTATIC, RT, "mapField",
                        "(Ljava/util/List;Ljava/lang/String;)Ljava/util/List;", false)
                }
                else -> { /* SumStage handled below; no other list-stage kinds */ }
            }
        }

        if (hasSumStage) {
            // The element type the SUM reduces over is the TRANSFORMed field's type (if any),
            // not the source element type — e.g. `sales TRANSFORM TO amount SUM` sums MONEY, not Sale.
            val srcElem = (inferExprType(expr.source) as? KobolType.ListType)?.elementType
                ?: KobolType.UnknownType
            val transformField = listStages.filterIsInstance<PipelineStage.TransformStage>().lastOrNull()?.field
            val effectiveElem = if (transformField != null && srcElem is KobolType.RecordRefType) {
                val recSym = checker.symbols.resolve(srcElem.name) as? dev.kobol.semantic.Symbol.RecordSymbol
                recSym?.fields?.get(transformField) ?: KobolType.UnknownType
            } else if (transformField != null) srcElem else srcElem
            when (effectiveElem) {
                is KobolType.IntegerType ->
                    mv.visitMethodInsn(INVOKESTATIC, MATH_OWN, "sumLong",
                        "(Ljava/util/List;)J", false)
                else ->
                    mv.visitMethodInsn(INVOKESTATIC, MATH_OWN, "exactSum",
                        "(Ljava/util/List;)Ljava/math/BigDecimal;", false)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Reference load / store
    // -------------------------------------------------------------------------

internal fun AsmEmitter.loadRef(ctx: MethodContext, ref: Reference) {
        val mv   = ctx.mv
        val name = ref.parts[0]
        // Named condition intercept
        val namedCond = namedConditions[name]
        if (namedCond != null && ref.parts.size == 1) {
            emitExpr(ctx, namedCond)
            return
        }
        // DEFINE constant → emit literal value inline
        val constExpr = constantExprs[name]
        if (constExpr != null) {
            emitExpr(ctx, constExpr)
            return
        }
        // F1: bare-name nullary VARIANT case construction (`MOVE Pending TO st`) — mirrors the
        // call-form path in emitBuiltin. A real local of the same name shadows it (guarded).
        if (ref.parts.size == 1 && ctx.getLocal(name) == null) {
            val caseEntry = variantCaseIndex[name.uppercase()]
            if (caseEntry != null && caseEntry.second.fields.isEmpty()) {
                emitVariantConstruction(ctx, caseEntry.first, caseEntry.second, emptyList())
                return
            }
            // #v1: a bare nullary builtin (`TODAY`, `NOW`, `UUID-GENERATE`, `UUID-NIL`) — the
            // spec's documented field-init form (`started : DATE = TODAY`). Lower it to the
            // builtin call so it yields the typed value (LocalDate/UUID/…) the field expects,
            // instead of a phantom GETSTATIC of an Object-typed field that the verifier rejects
            // at class load. Shadowed by a real local/field/constant of the same name (checked
            // above and in resolveSymbolType). The matching type is supplied by the checker's
            // resolveRefType, kept in lock-step via NULLARY_BUILTINS.
            if (name.uppercase() in dev.kobol.semantic.NULLARY_BUILTINS &&
                checker.symbols.resolve(name) == null) {
                emitBuiltin(ctx, BuiltinCall(name.uppercase(), emptyList(), ref.pos))
                return
            }
        }
        val local = ctx.getLocal(name)
        if (local != null) {
            loadLocal(mv, local.type, local.slot)
        } else {
            // Static field on outer class
            val kType = resolveSymbolType(ctx, name)
            mv.visitFieldInsn(GETSTATIC, ctx.owner, javaIdent(name), recDesc(ctx.owner, kType))
        }
        // Chain field access: rec.field or list.length / text.length
        var currentType = resolveSymbolType(ctx, name)
        for (field in ref.parts.drop(1)) {
            when {
                // list.length  →  (int)List.size()  →  long
                field == "LENGTH" && currentType is KobolType.ListType -> {
                    mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Collection", "size", "()I", true)
                    mv.visitInsn(I2L)
                    currentType = KobolType.IntegerType
                }
                // text.length  →  (int)String.length()  →  long
                field == "LENGTH" && currentType is KobolType.TextType -> {
                    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false)
                    mv.visitInsn(I2L)
                    currentType = KobolType.IntegerType
                }
                currentType is KobolType.RecordRefType -> {
                    val recName = "${ctx.owner}\$${javaClass(currentType.name)}"
                    // The receiver may be erased to Object on the stack — a record reached via a
                    // prior GETFIELD/bound MATCH local has descriptor `Ljava/lang/Object;` (records
                    // erase, kobolDescriptor), so a concrete GETFIELD/INVOKEVIRTUAL below would fail
                    // verification ("Object not assignable to recName"). CHECKCAST to the concrete
                    // record class first; a no-op when the receiver is already concrete (root DATA
                    // field via GETSTATIC). Fixes nested (`a.b.c`) and bound-local record field reads.
                    mv.visitTypeInsn(CHECKCAST, recName)
                    val recSym = checker.symbols.resolve(currentType.name) as? Symbol.RecordSymbol
                    if (recSym != null && recSym.conditions.containsKey(field)) {
                        // Record CONDITION (e.g. cust.HighValue) → call its synthetic
                        // boolean predicate method rather than reading a (non-existent) field.
                        mv.visitMethodInsn(INVOKEVIRTUAL, recName, condMethodName(field), "()Z", false)
                        currentType = KobolType.BooleanType
                    } else {
                        val fieldType = recSym?.fields?.get(field) ?: KobolType.UnknownType
                        mv.visitFieldInsn(GETFIELD, recName, javaIdent(field), jvmDescriptor(fieldType))
                        currentType = fieldType
                    }
                }
                // #5 (F29): `obj.field` on a JAVA-OBJECT with a known owner reads the property's
                // getter — lowered through the SAME emitInteropInvoke a `CALL obj.getField` uses
                // (P1, no fork), so arg-less invoke + return coercion are shared. The receiver is on
                // the stack erased to Object; emitInteropInvoke CHECKCASTs it to the owner. The
                // value lands coerced to the property's Kobol representation (e.g. int→long for
                // INTEGER, double→BigDecimal for DECIMAL — F14 coercion). Opaque owner → break.
                currentType is KobolType.JavaObjectType -> {
                    val owner = (currentType as KobolType.JavaObjectType).ownerName
                        ?.let { dev.kobol.semantic.resolveInteropOwner(it.split("."), importAliasMap) }
                    val getter = owner?.let {
                        dev.kobol.semantic.resolvePropertyGetter(classpathResolver, it, field)
                    }
                    if (owner != null && getter != null) {
                        val propType = dev.kobol.semantic.kobolTypeFromDescriptor(
                            getter.descriptor.substringAfterLast(')')
                        ) ?: KobolType.JavaObjectType()
                        val isIface = owner in JVM_INTERFACE_TYPES
                        emitInteropInvoke(
                            ctx, emptyList(), getter.name, owner,
                            if (isIface) INVOKEINTERFACE else INVOKEVIRTUAL, isInterface = isIface,
                            loadReceiver = { /* receiver already on stack */ },
                            wantDesc = dev.kobol.semantic.kobolDescriptor(propType),
                        )
                        currentType = propType
                    } else break
                }
                else -> break
            }
        }
    }

/**
 * Rescale the BigDecimal on top of stack to a fixed-point target's declared scale,
 * using HALF_UP — COBOL receiving-field semantics (the PICTURE of the receiver fixes
 * the result scale). No-op for non-decimal targets.
 *
 * This is where Kobol keeps COBOL's fixed-point strength: arithmetic runs at full
 * BigDecimal precision (arbitrary-precision, no loss), and only the *store* into a
 * declared MONEY(p,s)/DECIMAL(p,s) field rounds to that field's scale. So
 * `amount * 1.5 / 100` computed as 13.358 lands in a MONEY(10,2) field as 13.36.
 * O(digits) per store — negligible.
 */
internal fun AsmEmitter.rescaleToTarget(mv: MethodVisitor, type: KobolType) {
        val scale = when (type) {
            is KobolType.MoneyType   -> type.scale
            is KobolType.DecimalType -> type.scale
            else -> return
        }
        mv.visitLdcInsn(scale)
        mv.visitFieldInsn(GETSTATIC, ROUNDING, "HALF_UP", "Ljava/math/RoundingMode;")
        mv.visitMethodInsn(INVOKEVIRTUAL, BIGDECIMAL, "setScale",
            "(ILjava/math/RoundingMode;)L$BIGDECIMAL;", false)
    }

internal fun AsmEmitter.emitStore(ctx: MethodContext, ref: Reference) {
        val mv   = ctx.mv
        val name = ref.parts[0]
        // Fixed-point receiving-field rule: rescale BigDecimal value to the target's
        // declared scale while it is still on top of stack (before any ref/value rotation).
        rescaleToTarget(mv, resolveRefFinalType(ctx, ref))
        if (ref.parts.size == 1) {
            val local = ctx.getLocal(name)
            if (local != null) {
                storeLocal(mv, local.type, local.slot)
            } else {
                val kType = resolveSymbolType(ctx, name)
                mv.visitFieldInsn(PUTSTATIC, ctx.owner, javaIdent(name), recDesc(ctx.owner, kType))
            }
        } else {
            // Dotted assignment (`rec.f1.f2…fn = value`). The value is already on the stack.
            // Walk parts[0..n-2] as a record-load chain — mirroring loadRef's RecordRefType arm,
            // including the CHECKCAST that un-erases each intermediate record (a record field's
            // descriptor erases to Object — kobolDescriptor) — leaving the owner of the FINAL field
            // on the stack ABOVE the value, then PUTFIELD the final field using the FINAL field's
            // wideness. (Previously this only handled depth-2 AND picked the rotation by the wrong
            // field's wideness, so a deep write of a long emitted SWAP over a category-2 value →
            // VerifyError; a deep write of a reference wrote the wrong intermediate field.)
            val rootLocal = ctx.getLocal(name)
            val rootType  = resolveSymbolType(ctx, name)
            // Load the root object (it lands on top of the value already on the stack).
            if (rootLocal != null) {
                loadLocal(mv, rootType, rootLocal.slot)
            } else {
                mv.visitFieldInsn(GETSTATIC, ctx.owner, javaIdent(name), recDesc(ctx.owner, rootType))
            }
            val lastIdx = ref.parts.size - 1
            var curType: KobolType = rootType
            var ownerClass: String? = null
            var finalField: String? = null
            var finalType: KobolType = KobolType.UnknownType
            for (i in 1..lastIdx) {
                val rec = curType as? KobolType.RecordRefType ?: break
                val recName = "${ctx.owner}\$${javaClass(rec.name)}"
                mv.visitTypeInsn(CHECKCAST, recName)   // no-op when already concrete (root via GETSTATIC)
                val recSym = checker.symbols.resolve(rec.name) as? Symbol.RecordSymbol
                val fType  = recSym?.fields?.get(ref.parts[i]) ?: KobolType.UnknownType
                if (i == lastIdx) {
                    ownerClass = recName; finalField = javaIdent(ref.parts[i]); finalType = fType
                } else {
                    mv.visitFieldInsn(GETFIELD, recName, javaIdent(ref.parts[i]), jvmDescriptor(fType))
                    curType = fType
                }
            }
            if (ownerClass != null && finalField != null) {
                // Stack: [value, owner] → rotate to [owner, value].
                // Wide path (DUP_X2+POP) for category-2 finals (long/INTEGER), narrow (SWAP) otherwise.
                if (isWide(finalType)) {
                    mv.visitInsn(DUP_X2)   // [owner, value, owner]
                    mv.visitInsn(POP)      // [owner, value]
                } else {
                    mv.visitInsn(SWAP)     // [owner, value]
                }
                mv.visitFieldInsn(PUTFIELD, ownerClass, finalField, jvmDescriptor(finalType))
            } else {
                // Chain did not resolve to a record field — discard both to keep the stack balanced.
                mv.visitInsn(POP)          // owner
                mv.visitInsn(if (isWide(resolveRefFinalType(ctx, ref))) POP2 else POP)  // value
            }
        }
    }

    // -------------------------------------------------------------------------
    // JVM type helpers
    // -------------------------------------------------------------------------

