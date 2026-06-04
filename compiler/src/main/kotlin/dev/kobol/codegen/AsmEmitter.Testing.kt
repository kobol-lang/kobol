package dev.kobol.codegen

import dev.kobol.codegen.AsmEmitter.MethodContext
import dev.kobol.parser.ast.*
import dev.kobol.semantic.KobolType
import dev.kobol.semantic.Symbol
import dev.kobol.semantic.TypeChecker
import org.objectweb.asm.*
import org.objectweb.asm.Opcodes.*
import java.io.File

internal fun AsmEmitter.emitTestMethod(cw: ClassWriter, test: TestDecl, owner: String) {
        val methodName = "__test_${sanitiseTestName(test.name)}"
        val mv = cw.visitMethod(ACC_PRIVATE or ACC_STATIC, methodName, "()V", null, null)
        mv.visitCode()
        val syntheticProc = ProcedureDecl(methodName, emptyList(), null, test.body,
            test.pos)
        val ctx = MethodContext(mv, owner, syntheticProc)
        emitBlock(ctx, test.body)
        mv.visitInsn(RETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    /** Emit one private static void __tabletest_<name>_row<n>() method for a single ROW. */
internal fun AsmEmitter.emitTableTestRowMethod(cw: ClassWriter, tt: TableTestDecl, rowIdx: Int, owner: String) {
        val methodName = "__tabletest_${sanitiseTestName(tt.name)}_row$rowIdx"
        val mv = cw.visitMethod(ACC_PRIVATE or ACC_STATIC, methodName, "()V", null, null)
        mv.visitCode()
        val allStmts = tt.whenBlock + tt.thenBlock
        val syntheticProc = ProcedureDecl(methodName, emptyList(), null, allStmts, tt.pos)
        val ctx = MethodContext(mv, owner, syntheticProc)
        val row = tt.rows[rowIdx]
        // Allocate local slots for each column and load the row's value
        for ((colIdx, colName) in tt.columns.withIndex()) {
            val colExpr = row[colIdx]
            val colType = inferExprType(colExpr)
            emitExpr(ctx, colExpr)
            val slot = ctx.allocLocal(colName, colType)
            storeLocal(mv, colType, slot)
        }
        emitBlock(ctx, tt.whenBlock)
        emitBlock(ctx, tt.thenBlock)
        mv.visitInsn(RETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    /**
     * Emit:  public static int runAll()
     * Calls each test/table-test method wrapped in try/catch; reports via KobolTest;
     * returns number of failures.
     */
internal fun AsmEmitter.emitRunAllTests(cw: ClassWriter, program: Program, owner: String) {
        val KOBOL_TEST = "dev/kobol/runtime/KobolTest"
        val mv = cw.visitMethod(ACC_PUBLIC or ACC_STATIC, "runAll", "()I", null, null)
        mv.visitCode()
        // KobolTest.begin(suiteName)
        mv.visitLdcInsn(program.name)
        mv.visitMethodInsn(INVOKESTATIC, KOBOL_TEST, "begin", "(Ljava/lang/String;)V", false)

        // Collect all test method names
        val testMethods = mutableListOf<String>()
        val testNames   = mutableListOf<String>()
        for (test in program.tests) {
            testMethods.add("__test_${sanitiseTestName(test.name)}")
            testNames.add(test.name)
        }
        for (tt in program.tableTests) {
            for (rowIdx in tt.rows.indices) {
                testMethods.add("__tabletest_${sanitiseTestName(tt.name)}_row$rowIdx")
                testNames.add("${tt.name} [row $rowIdx]")
            }
        }

        for ((methodName, testName) in testMethods.zip(testNames)) {
            val tryStart = Label(); val tryEnd = Label(); val catchHandler = Label(); val afterCatch = Label()
            mv.visitTryCatchBlock(tryStart, tryEnd, catchHandler, "java/lang/AssertionError")
            mv.visitLabel(tryStart)
            mv.visitMethodInsn(INVOKESTATIC, owner, methodName, "()V", false)
            mv.visitLabel(tryEnd)
            // pass: KobolTest.pass(name)
            mv.visitLdcInsn(testName)
            mv.visitMethodInsn(INVOKESTATIC, KOBOL_TEST, "pass", "(Ljava/lang/String;)V", false)
            mv.visitJumpInsn(GOTO, afterCatch)
            // fail: KobolTest.fail(name, e)
            mv.visitLabel(catchHandler)
            mv.visitLdcInsn(testName)
            mv.visitInsn(SWAP)   // stack: name, e → e, name → name, e (swap AssertionError to arg2)
            mv.visitMethodInsn(INVOKESTATIC, KOBOL_TEST, "fail",
                "(Ljava/lang/String;Ljava/lang/Throwable;)V", false)
            mv.visitLabel(afterCatch)
        }

        // return KobolTest.finish()
        mv.visitMethodInsn(INVOKESTATIC, KOBOL_TEST, "finish", "()I", false)
        mv.visitInsn(IRETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

internal fun AsmEmitter.sanitiseTestName(name: String): String =
        name.replace(Regex("[^A-Za-z0-9_]"), "_").take(64)

    // ─────────────────────────────────────────────────────────────────────────
    // Group 11 — HTTP client
    // ─────────────────────────────────────────────────────────────────────────

