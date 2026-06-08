package dev.kobol.codegen

import dev.kobol.parser.ast.ConfigItem
import dev.kobol.semantic.KobolType
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes.*

// =============================================================================
//  CONFIG section bytecode emission
//
//  Emits JVM bytecode to:
//    1. Declare a private static field for each CONFIG item (emitConfigField).
//    2. Load the value from the environment (or .env file) and store it into
//       the field at program startup (emitConfigLoad, called from emitMainEntry).
//
//  All env-var resolution and type coercion delegate to KobolEnv (runtime module),
//  which has @JvmStatic methods — so every call is a clean INVOKESTATIC.
//
//  Resolution order for each CONFIG item:
//    1. System environment variable
//    2. .env file in the working directory (or -Dkobol.env.file=path)
//    3. DEFAULT value (if declared)
//    4. Zero / empty (if neither REQUIRED nor DEFAULT — TypeChecker warns)
// =============================================================================

private const val KOBOL_ENV = "dev/kobol/runtime/KobolEnv"

/** Declare a `private static` field for [item] in the program class. */
internal fun AsmEmitter.emitConfigField(cw: ClassWriter, item: ConfigItem, owner: String) {
    val kType = checker.toKobolType(item.type)
    val desc  = jvmDescriptor(kType)
    cw.visitField(ACC_PRIVATE or ACC_STATIC, javaIdent(item.name), desc, null, null).visitEnd()
}

/**
 * Emit the loading code for [item] into [ctx]'s method visitor.
 *
 * For a REQUIRED INTEGER the generated pseudo-Java is:
 * ```java
 * port = KobolEnv.toLong(KobolEnv.require("PORT", "port"), "port");
 * ```
 * For an optional INTEGER with DEFAULT 8080:
 * ```java
 * String __raw = KobolEnv.getenv("PORT");
 * port = __raw != null ? KobolEnv.toLong(__raw, "port") : 8080L;
 * ```
 */
internal fun AsmEmitter.emitConfigLoad(ctx: AsmEmitter.MethodContext, item: ConfigItem, owner: String) {
    val mv        = ctx.mv
    val kType     = checker.toKobolType(item.type)
    val fieldDesc = jvmDescriptor(kType)
    val fieldName = javaIdent(item.name)

    if (item.required) {
        // KobolEnv.require(envVar, configName) → String on stack
        mv.visitLdcInsn(item.envVar)
        mv.visitLdcInsn(item.name)
        mv.visitMethodInsn(INVOKESTATIC, KOBOL_ENV,
            "require", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", false)
        // String → target type
        emitStringToKobolType(mv, kType, item.name)
        mv.visitFieldInsn(PUTSTATIC, owner, fieldName, fieldDesc)

    } else {
        // String raw = KobolEnv.getenv(envVar)   — may be null
        mv.visitLdcInsn(item.envVar)
        mv.visitMethodInsn(INVOKESTATIC, KOBOL_ENV,
            "getenv", "(Ljava/lang/String;)Ljava/lang/String;", false)

        val rawSlot   = ctx.allocLocal("__cfg_${item.name}", KobolType.TextType(null))
        val hasValue  = Label()
        val done      = Label()

        mv.visitVarInsn(ASTORE, rawSlot)
        mv.visitVarInsn(ALOAD,  rawSlot)
        mv.visitJumpInsn(IFNONNULL, hasValue)

        // null path — push default or zero
        if (item.default != null) emitExpr(ctx, item.default)
        else emitZeroDefault(mv, kType)
        mv.visitJumpInsn(GOTO, done)

        // non-null path — convert String to target type
        mv.visitLabel(hasValue)
        mv.visitVarInsn(ALOAD, rawSlot)
        emitStringToKobolType(mv, kType, item.name)

        mv.visitLabel(done)
        mv.visitFieldInsn(PUTSTATIC, owner, fieldName, fieldDesc)
    }
}

/**
 * §27.1 ACCEPT — read a raw String (terminal prompt or CLI argument), coerce it to the target's
 * declared type via the same [emitStringToKobolType] helper CONFIG uses, then store into the target.
 */
internal fun AsmEmitter.emitAccept(ctx: AsmEmitter.MethodContext, stmt: dev.kobol.parser.ast.AcceptStatement) {
    val mv         = ctx.mv
    val targetType = checker.typeOfReference(stmt.target)
    if (stmt.fromTerminal) {
        emitExpr(ctx, stmt.source)
        mv.visitMethodInsn(INVOKESTATIC, KOBOL_ENV, "acceptTerminal",
            "(Ljava/lang/String;)Ljava/lang/String;", false)
    } else {
        emitExpr(ctx, stmt.source)
        if (stmt.default != null) emitExprAsString(ctx, stmt.default) else mv.visitLdcInsn("")
        mv.visitMethodInsn(INVOKESTATIC, KOBOL_ENV, "acceptArgument",
            "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", false)
    }
    emitStringToKobolType(mv, targetType, stmt.target.parts.last())
    emitStore(ctx, stmt.target)
}

// -------------------------------------------------------------------------
//  Helpers
// -------------------------------------------------------------------------

/**
 * Assumes a `String` is on top of the operand stack.
 * Emits the call to the appropriate [KobolEnv.toXxx] static method,
 * leaving the converted value on the stack.
 */
internal fun emitStringToKobolType(mv: MethodVisitor, kType: KobolType, configName: String) {
    when (kType) {
        is KobolType.TextType -> { /* String is already the right type */ }

        is KobolType.IntegerType -> {
            mv.visitLdcInsn(configName)
            mv.visitMethodInsn(INVOKESTATIC, KOBOL_ENV,
                "toLong", "(Ljava/lang/String;Ljava/lang/String;)J", false)
        }

        is KobolType.DecimalType, is KobolType.MoneyType -> {
            mv.visitLdcInsn(configName)
            mv.visitMethodInsn(INVOKESTATIC, KOBOL_ENV,
                "toDecimal", "(Ljava/lang/String;Ljava/lang/String;)Ljava/math/BigDecimal;", false)
        }

        is KobolType.BooleanType -> {
            mv.visitLdcInsn(configName)
            mv.visitMethodInsn(INVOKESTATIC, KOBOL_ENV,
                "toBoolean", "(Ljava/lang/String;Ljava/lang/String;)Z", false)
        }

        is KobolType.DateType -> {
            mv.visitLdcInsn(configName)
            mv.visitMethodInsn(INVOKESTATIC, KOBOL_ENV,
                "toDate", "(Ljava/lang/String;Ljava/lang/String;)Ljava/time/LocalDate;", false)
        }

        is KobolType.TimeType -> {
            mv.visitLdcInsn(configName)
            mv.visitMethodInsn(INVOKESTATIC, KOBOL_ENV,
                "toTime", "(Ljava/lang/String;Ljava/lang/String;)Ljava/time/LocalTime;", false)
        }

        is KobolType.DateTimeType -> {
            mv.visitLdcInsn(configName)
            mv.visitMethodInsn(INVOKESTATIC, KOBOL_ENV,
                "toDateTime", "(Ljava/lang/String;Ljava/lang/String;)Ljava/time/LocalDateTime;", false)
        }

        else -> { /* unsupported CONFIG type — left as String; TypeChecker should have flagged this */ }
    }
}

/** Pushes a zero / empty default for [kType] onto the stack. */
private fun emitZeroDefault(mv: MethodVisitor, kType: KobolType) {
    when (kType) {
        is KobolType.TextType    -> mv.visitLdcInsn("")
        is KobolType.IntegerType -> mv.visitInsn(LCONST_0)
        is KobolType.BooleanType -> mv.visitInsn(ICONST_0)
        is KobolType.DecimalType, is KobolType.MoneyType ->
            mv.visitFieldInsn(GETSTATIC, "java/math/BigDecimal", "ZERO", "Ljava/math/BigDecimal;")
        else -> mv.visitInsn(ACONST_NULL)
    }
}

