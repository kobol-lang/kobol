package dev.kobol.codegen

import dev.kobol.codegen.AsmEmitter.MethodContext
import dev.kobol.parser.ast.*
import dev.kobol.semantic.KobolType
import dev.kobol.semantic.Symbol
import dev.kobol.semantic.TypeChecker
import org.objectweb.asm.*
import org.objectweb.asm.Opcodes.*
import java.io.File

/**
 * Emit one synthetic boolean predicate method per record CONDITION.
 *
 * A `CONDITION HighValue WHEN balance > 50000` declared inside a RECORD becomes an
 * instance method `cond$highvalue()Z` on the record class. Bare field references in
 * the WHEN expression (`balance`, `credit-limit`, …) are rewritten to `__self.field`
 * so they read `this`'s fields. `cust.HighValue` then compiles to a virtual call.
 */
internal fun AsmEmitter.emitRecordConditionMethods(cw: ClassWriter, rec: RecordDecl, outerName: String) {
        val conditions = rec.fields.flatMap { it.conditions }
        if (conditions.isEmpty()) return
        val fieldNames = rec.fields.map { it.name }.toSet()

        conditionSelfRecord = rec.name
        try {
        for (cond in conditions) {
            val mv = cw.visitMethod(ACC_PUBLIC, condMethodName(cond.name), "()Z", null, null)
            mv.visitCode()
            val ctx = MethodContext(mv, outerName, ProcedureDecl(cond.name, emptyList(), null, emptyList(), rec.pos))
            // Slot 0 is `this` (the record instance) for an instance method.
            ctx.defineLocal("__self", KobolType.RecordRefType(rec.name), 0)
            emitExpr(ctx, rewriteFieldRefsToSelf(cond.expr, fieldNames))
            mv.visitInsn(IRETURN)
            mv.visitMaxs(0, 0)
            mv.visitEnd()
        }
        } finally {
            conditionSelfRecord = null
        }
    }

/** Rewrite bare references to record fields into `__self.field` accesses. */
private fun rewriteFieldRefsToSelf(expr: Expression, fieldNames: Set<String>): Expression =
    rewriteBareFieldRefs(expr, fieldNames, listOf("__self"))

internal fun AsmEmitter.emitVariantClasses(variantDecl: VariantDecl, outerName: String): Map<String, ByteArray> {
        val result = LinkedHashMap<String, ByteArray>()
        val baseName = "$outerName\$${javaClass(variantDecl.name)}"

        // Abstract base class
        val baseCw = ClassWriter(ClassWriter.COMPUTE_FRAMES)
        baseCw.visit(JVM_VERSION, ACC_PUBLIC or ACC_ABSTRACT or ACC_STATIC,
            baseName, null, "java/lang/Object", null)
        val baseInit = baseCw.visitMethod(ACC_PROTECTED, "<init>", "()V", null, null)
        baseInit.visitCode()
        baseInit.visitVarInsn(ALOAD, 0)
        baseInit.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
        baseInit.visitInsn(RETURN)
        baseInit.visitMaxs(0, 0)
        baseInit.visitEnd()
        baseCw.visitEnd()
        result[baseName] = baseCw.toByteArray()

        // Concrete case classes
        for (case in variantDecl.cases) {
            val caseName = "${baseName}Case${javaClass(case.name)}"
            val caseCw = ClassWriter(ClassWriter.COMPUTE_FRAMES)
            caseCw.visit(JVM_VERSION, ACC_PUBLIC or ACC_STATIC or ACC_FINAL,
                caseName, null, baseName, null)

            // Fields
            for (f in case.fields) {
                val kType = checker.toKobolType(f.type)
                caseCw.visitField(ACC_PUBLIC, javaIdent(f.name), jvmDescriptor(kType), null, null).visitEnd()
            }

            // Constructor that takes all fields as args
            val paramDesc = case.fields.joinToString("") { jvmDescriptor(checker.toKobolType(it.type)) }
            val caseInit  = caseCw.visitMethod(ACC_PUBLIC, "<init>", "($paramDesc)V", null, null)
            caseInit.visitCode()
            caseInit.visitVarInsn(ALOAD, 0)
            caseInit.visitMethodInsn(INVOKESPECIAL, baseName, "<init>", "()V", false)
            var slot = 1
            for (f in case.fields) {
                val kType = checker.toKobolType(f.type)
                caseInit.visitVarInsn(ALOAD, 0)
                loadLocal(caseInit, kType, slot)
                caseInit.visitFieldInsn(PUTFIELD, caseName, javaIdent(f.name), jvmDescriptor(kType))
                slot += if (kType is KobolType.IntegerType) 2 else 1
            }
            // No-arg constructor for cases without fields
            if (case.fields.isEmpty()) {
                caseInit.visitInsn(RETURN)
            } else {
                caseInit.visitInsn(RETURN)
            }
            caseInit.visitMaxs(0, 0)
            caseInit.visitEnd()

            // toString
            emitVariantCaseToString(caseCw, caseName, case)

            caseCw.visitEnd()
            result[caseName] = caseCw.toByteArray()
        }
        return result
    }

internal fun AsmEmitter.emitVariantCaseToString(cw: ClassWriter, className: String, case: VariantCase) {
        val mv = cw.visitMethod(ACC_PUBLIC, "toString", "()Ljava/lang/String;", null, null)
        mv.visitCode()
        if (case.fields.isEmpty()) {
            mv.visitLdcInsn(case.name)
            mv.visitInsn(ARETURN)
        } else {
            mv.visitTypeInsn(NEW, "java/lang/StringBuilder")
            mv.visitInsn(DUP)
            mv.visitMethodInsn(INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false)
            mv.visitLdcInsn("${case.name}(")
            appendString(mv)
            case.fields.forEachIndexed { i, f ->
                if (i > 0) { mv.visitLdcInsn(", "); appendString(mv) }
                mv.visitLdcInsn("${f.name}="); appendString(mv)
                mv.visitVarInsn(ALOAD, 0)
                val kType = checker.toKobolType(f.type)
                mv.visitFieldInsn(GETFIELD, className, javaIdent(f.name), jvmDescriptor(kType))
                appendValue(mv, kType)
            }
            mv.visitLdcInsn(")")
            appendString(mv)
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "toString",
                "()Ljava/lang/String;", false)
            mv.visitInsn(ARETURN)
        }
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    // -------------------------------------------------------------------------
    // Utility: scan for LOG statements
    // -------------------------------------------------------------------------

