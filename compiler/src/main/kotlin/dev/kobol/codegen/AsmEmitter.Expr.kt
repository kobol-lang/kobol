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
                    UnaryOp.NEGATE -> {
                        val t = inferExprType(expr.operand)
                        if (t is KobolType.IntegerType) mv.visitInsn(LNEG) else mv.visitInsn(INEG)
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
                // Allocate new record instance — field init done separately at statement level
                val className = "${ctx.owner}\$${javaClass(expr.typeName)}"
                mv.visitTypeInsn(NEW, className)
                mv.visitInsn(DUP)
                mv.visitMethodInsn(INVOKESPECIAL, className, "<init>", "()V", false)
            }

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
                else -> { /* default */ }
            }
            return
        }

        if (expr.op == BinaryOp.AND || expr.op == BinaryOp.OR) {
            val lEnd = Label()
            emitExpr(ctx, expr.left)
            if (expr.op == BinaryOp.AND) mv.visitJumpInsn(IFEQ, lEnd)
            else mv.visitJumpInsn(IFNE, lEnd)
            emitExpr(ctx, expr.right)
            mv.visitLabel(lEnd)
            return
        }

        // String (in)equality: use String.equals, not the long-compare path below
        // (which would emit LCMP on object references — invalid bytecode).
        if ((expr.op == BinaryOp.EQ || expr.op == BinaryOp.NEQ) &&
            (lType is KobolType.TextType || rType is KobolType.TextType)) {
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
                // Cast to double, call Math.pow, cast back
                mv.visitInsn(L2D); mv.visitInsn(L2D)
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "pow", "(DD)D", false)
                mv.visitInsn(D2L)
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
            "LENGTH"     -> { args.forEach { emitExpr(ctx, it) }; mv.visitMethodInsn(INVOKESTATIC, TEXT_OWN, "length", "($S)J", false) }
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
                        if (args.size == 3) {
                            args.forEach { emitExpr(ctx, it) }
                            mv.visitMethodInsn(INVOKESTATIC, MATH_OWN, "roundWithMode", "(${BD}J$S)$BD", false)
                        } else {
                            args.forEach { emitExpr(ctx, it) }
                            mv.visitMethodInsn(INVOKESTATIC, MATH_OWN, "round", "(${BD}J)$BD", false)
                        }
                    }
                    "ROUND-WITH-MODE" -> { args.forEach { emitExpr(ctx, it) }; mv.visitMethodInsn(INVOKESTATIC, MATH_OWN, "roundWithMode", "(${BD}J$S)$BD", false) }
                    "SCALE-OF"        -> { emitExpr(ctx, args[0]); mv.visitMethodInsn(INVOKESTATIC, MATH_OWN, "scaleOf",      "($BD)J", false) }
                    "PRECISION-OF"    -> { emitExpr(ctx, args[0]); mv.visitMethodInsn(INVOKESTATIC, MATH_OWN, "precisionOf",  "($BD)J", false) }
                    "TRUNCATE" -> { args.forEach { emitExpr(ctx, it) }; mv.visitMethodInsn(INVOKESTATIC, MATH_OWN, "truncate", "(${BD}J)$BD", false) }
                    "FLOOR"    -> { emitExpr(ctx, args[0]); mv.visitMethodInsn(INVOKESTATIC, MATH_OWN, "floor", "($BD)$BD", false) }
                    "CEIL"     -> { emitExpr(ctx, args[0]); mv.visitMethodInsn(INVOKESTATIC, MATH_OWN, "ceil",  "($BD)$BD", false) }
                    "SQRT"     -> { emitExpr(ctx, args[0]); mv.visitMethodInsn(INVOKESTATIC, MATH_OWN, "sqrt",  "($BD)$BD", false) }
                    "SIGN" -> {
                        val t = inferExprType(args[0])
                        emitExpr(ctx, args[0])
                        if (t is KobolType.IntegerType) mv.visitMethodInsn(INVOKESTATIC, MATH_OWN, "sign", "(J)I", false)
                        else                            mv.visitMethodInsn(INVOKESTATIC, MATH_OWN, "sign", "($BD)I", false)
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
                        args.forEach { emitExpr(ctx, it) }
                        mv.visitMethodInsn(INVOKESTATIC, MATH_OWN, "power", "(JJ)J", false)
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
                    // Supported form: FILTER WHERE <field-or-condition>. The predicate is a
                    // bare reference to a boolean field or CONDITION on the element.
                    val member = (stage.condition as? Reference)?.takeIf { !it.isFieldAccess }?.name
                    if (member != null) {
                        mv.visitLdcInsn(member)
                        mv.visitMethodInsn(INVOKESTATIC, RT, "filterByMember",
                            "(Ljava/util/List;Ljava/lang/String;)Ljava/util/List;", false)
                    }
                    // Complex predicates are not yet supported → pass through unfiltered.
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
                else -> { /* TransformStage: advanced — skip for now */ }
            }
        }

        if (hasSumStage) {
            // Determine element type from source expression to pick the right sum method
            val elemType = (inferExprType(expr.source) as? KobolType.ListType)?.elementType
                ?: KobolType.UnknownType
            when (elemType) {
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
            // For dotted assignment (rec.field = expr): load the record, then set field
            // This is complex — simplified: swap value into field using PUTFIELD
            val recordLocal = ctx.getLocal(ref.parts[0])
            val kType0 = resolveSymbolType(ctx, ref.parts[0])
            val innerType = (kType0 as? KobolType.RecordRefType)?.name
            if (innerType != null) {
                val innerClass = "${ctx.owner}\$${javaClass(innerType)}"
                val fieldName  = javaIdent(ref.parts[1])
                val recSym = checker.symbols.resolve(innerType) as? Symbol.RecordSymbol
                val fieldType  = recSym?.fields?.get(ref.parts[1]) ?: KobolType.UnknownType
                // Stack: [value] → need [ref, value] for PUTFIELD.
                // Load the base object from local (if it's a local var) or static field.
                if (recordLocal != null) {
                    loadLocal(mv, kType0, recordLocal.slot)
                } else {
                    mv.visitFieldInsn(GETSTATIC, ctx.owner, javaIdent(ref.parts[0]), recDesc(ctx.owner, kType0))
                }
                // Stack now: [value, ref] → rotate to [ref, value].
                // Use wide path (DUP_X2+POP) for category-2 values (long/INTEGER),
                // narrow path (SWAP) for category-1 values (references, booleans, etc).
                if (isWide(fieldType)) {
                    mv.visitInsn(DUP_X2)   // [ref, value, ref]
                    mv.visitInsn(POP)      // [ref, value]
                } else {
                    mv.visitInsn(SWAP)     // [ref, value]
                }
                mv.visitFieldInsn(PUTFIELD, innerClass, fieldName, jvmDescriptor(fieldType))
            } else {
                // Cannot resolve record — discard value to keep stack balanced.
                val valType = resolveSymbolType(ctx, name)
                mv.visitInsn(if (isWide(valType)) POP2 else POP)
            }
        }
    }

    // -------------------------------------------------------------------------
    // JVM type helpers
    // -------------------------------------------------------------------------

