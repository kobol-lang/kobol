package dev.kobol.semantic

import dev.kobol.lexer.Lexer
import dev.kobol.parser.Parser
import dev.kobol.parser.ast.Program
import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.io.File
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TypeCheckerTest {

    private fun analyze(src: String): TypeChecker {
        val lines   = src.lines()
        val tokens  = Lexer(src, "test.kbl").tokenize()
        val program = Parser(tokens, "test.kbl").parseProgram()
        val checker = TypeChecker(lines)
        checker.analyze(program)
        return checker
    }

    private fun expectClean(src: String): TypeChecker {
        val tc = analyze(src)
        if (tc.diagnostics.hasErrors) {
            val msgs = tc.diagnostics.errors.joinToString("\n") { it.render() }
            error("Expected no errors but got:\n$msgs")
        }
        return tc
    }

    private fun expectErrors(src: String, vararg codes: String): TypeChecker {
        val tc = analyze(src)
        val actual = tc.diagnostics.errors.map { it.code }
        for (code in codes) assertTrue(code in actual, "Expected error $code; got $actual")
        return tc
    }

    // -------------------------------------------------------------------------
    // Clean programs
    // -------------------------------------------------------------------------

    @Test fun `hello world is clean`() {
        expectClean("""
            PROGRAM HelloWorld
            DATA:
              greeting : TEXT = "Hello"
            PROCEDURE Main:
              DISPLAY greeting
              STOP RUN
            END-PROCEDURE
        """.trimIndent())
    }

    // #19: uninitialized temporal types have no implicit default (the spec no longer
    // promises "current date"). Declaration-site warning W019, not a silent null NPE.
    @Test fun `uninitialized DATE warns W019`() {
        val tc = analyze("""
            PROGRAM T
            DATA:
              started : DATE
            PROCEDURE Main:
              DISPLAY "x"
            END-PROCEDURE
        """.trimIndent())
        assertTrue("W019" in tc.diagnostics.warnings.map { it.code }, "expected W019 for uninitialized DATE")
        assertFalse(tc.diagnostics.hasErrors, "W019 is a warning, not an error")
    }

    @Test fun `initialized DATE does not warn W019`() {
        val tc = analyze("""
            PROGRAM T
            DATA:
              started : DATE = TODAY()
            PROCEDURE Main:
              DISPLAY "x"
            END-PROCEDURE
        """.trimIndent())
        assertFalse("W019" in tc.diagnostics.warnings.map { it.code }, "initialized DATE must not warn")
    }

    // F15: a JAVA-OBJECT field defaults to JVM null too (AsmEmitter clinit), so an uninitialized
    // one is the same null-until-assigned landmine as DATE/TIME — feeding it to a Kotlin non-null
    // parameter trips Intrinsics.checkNotNullParameter at the callee. Flag it at the declaration.
    @Test fun `uninitialized JAVA-OBJECT field warns W019 (F15)`() {
        val tc = analyze("""
            PROGRAM T
            IMPORT "java.lang.StringBuilder" AS SB
            DATA:
              buf : SB
            PROCEDURE Main:
              DISPLAY "x"
            END-PROCEDURE
        """.trimIndent())
        assertTrue("W019" in tc.diagnostics.warnings.map { it.code },
            "expected W019 for uninitialized JAVA-OBJECT; warnings=${tc.diagnostics.warnings.map { it.code }}")
        assertFalse(tc.diagnostics.hasErrors, "W019 is a warning, not an error")
    }

    @Test fun `initialized JAVA-OBJECT field does not warn W019 (F15)`() {
        val tc = analyze("""
            PROGRAM T
            IMPORT "java.lang.StringBuilder" AS SB
            DATA:
              buf : SB = NEW SB
            PROCEDURE Main:
              DISPLAY "x"
            END-PROCEDURE
        """.trimIndent())
        assertFalse("W019" in tc.diagnostics.warnings.map { it.code },
            "a NEW-initialized JAVA-OBJECT must not warn; warnings=${tc.diagnostics.warnings.map { it.code }}")
    }

    @Test fun `procedure with parameters is clean`() {
        expectClean("""
            PROGRAM T
            PROCEDURE Greet USING who : TEXT:
              DISPLAY who
            END-PROCEDURE
            PROCEDURE Main:
              PERFORM Greet USING "Alice"
              STOP RUN
            END-PROCEDURE
        """.trimIndent())
    }

    @Test fun `arithmetic is clean`() {
        expectClean("""
            PROGRAM T
            DATA:
              total : DECIMAL(10,2) = 0
              tax   : DECIMAL(10,2) = 0
            PROCEDURE Main:
              ADD tax TO total
              STOP RUN
            END-PROCEDURE
        """.trimIndent())
    }

    @Test fun `record with field access is clean`() {
        expectClean("""
            PROGRAM T
            RECORD Customer:
              name : TEXT
              age  : INTEGER
            END-RECORD
            DATA:
              cust : Customer
            PROCEDURE Main:
              DISPLAY cust
              STOP RUN
            END-PROCEDURE
        """.trimIndent())
    }

    @Test fun `LET type inference infers type from RHS`() {
        val tc = expectClean("""
            PROGRAM T
            PROCEDURE Main:
              LET count = 42
              STOP RUN
            END-PROCEDURE
        """.trimIndent())
        // After analysis, count should be in the scope as INTEGER
        // (we verify via no errors rather than direct symbol inspection)
        assertFalse(tc.diagnostics.hasErrors)
    }

    @Test fun `FOR EACH loop var is scoped`() {
        expectClean("""
            PROGRAM T
            DATA:
              items : LIST OF TEXT
            PROCEDURE Main:
              FOR EACH item IN items:
                DISPLAY item
              END-FOR
              STOP RUN
            END-PROCEDURE
        """.trimIndent())
    }

    @Test fun `TRY with exception handler is clean`() {
        expectClean("""
            PROGRAM T
            PROCEDURE Main:
              TRY:
                STOP RUN
              ON FileError:
                DISPLAY "error"
              END-TRY
            END-PROCEDURE
        """.trimIndent())
    }

    @Test fun `RETURN with matching type is clean`() {
        expectClean("""
            PROGRAM T
            PROCEDURE GetValue RETURNING INTEGER:
              RETURN 42
            END-PROCEDURE
            PROCEDURE Main:
              STOP RUN
            END-PROCEDURE
        """.trimIndent())
    }

    // -------------------------------------------------------------------------
    // Error cases
    // -------------------------------------------------------------------------

    @Test fun `undefined variable emits E001`() {
        expectErrors("""
            PROGRAM T
            PROCEDURE Main:
              DISPLAY unknown-var
              STOP RUN
            END-PROCEDURE
        """.trimIndent(), "E001")
    }

    @Test fun `undefined procedure emits E006`() {
        expectErrors("""
            PROGRAM T
            PROCEDURE Main:
              PERFORM MissingProc
              STOP RUN
            END-PROCEDURE
        """.trimIndent(), "E006")
    }

    @Test fun `wrong argument count emits E008`() {
        expectErrors("""
            PROGRAM T
            PROCEDURE Calculate USING a : INTEGER, b : INTEGER:
              STOP RUN
            END-PROCEDURE
            PROCEDURE Main:
              PERFORM Calculate USING 1
              STOP RUN
            END-PROCEDURE
        """.trimIndent(), "E008")
    }

    @Test fun `RETURN outside returning procedure emits E010`() {
        expectErrors("""
            PROGRAM T
            PROCEDURE Main:
              RETURN 5
              STOP RUN
            END-PROCEDURE
        """.trimIndent(), "E010")
    }

    @Test fun `undefined record in struct literal emits E001`() {
        expectErrors("""
            PROGRAM T
            PROCEDURE Main:
              MOVE NoSuchRecord { x: 1 } TO something
              STOP RUN
            END-PROCEDURE
        """.trimIndent(), "E001")
    }

    @Test fun `did-you-mean is included in error message`() {
        val tc = analyze("""
            PROGRAM T
            DATA:
              invoice-total : DECIMAL(10,2)
            PROCEDURE Main:
              DISPLAY inovice-total
              STOP RUN
            END-PROCEDURE
        """.trimIndent())
        val err = tc.diagnostics.errors.firstOrNull { it.code == "E001" }
        assertTrue(err != null, "Expected E001")
        // "did you mean: invoice-total?" should appear
        assertTrue(err!!.message.contains("invoice", ignoreCase = true),
            "Expected did-you-mean suggestion in: ${err.message}")
    }

    // -------------------------------------------------------------------------
    // Type inference (ER-5)
    // -------------------------------------------------------------------------

    @Test fun `LET infers INTEGER from literal`() {
        val tc = expectClean("""
            PROGRAM T
            PROCEDURE Main:
              LET x = 10
              DISPLAY x
              STOP RUN
            END-PROCEDURE
        """.trimIndent())
        assertFalse(tc.diagnostics.hasErrors)
    }

    @Test fun `LET infers TEXT from string literal`() {
        val tc = expectClean("""
            PROGRAM T
            PROCEDURE Main:
              LET msg = "hello"
              DISPLAY msg
              STOP RUN
            END-PROCEDURE
        """.trimIndent())
        assertFalse(tc.diagnostics.hasErrors)
    }

    // -------------------------------------------------------------------------
    // SLEEP statement (Group 2)
    // -------------------------------------------------------------------------

    @Test fun `SLEEP MILLISECONDS is clean`() = expectClean("""
        PROGRAM T
        DATA:
          delay : INTEGER = 500
        PROCEDURE Main:
          SLEEP delay MILLISECONDS
          STOP RUN
        END-PROCEDURE
    """.trimIndent())

    @Test fun `SLEEP SECONDS literal is clean`() = expectClean("""
        PROGRAM T
        PROCEDURE Main:
          SLEEP 2 SECONDS
          STOP RUN
        END-PROCEDURE
    """.trimIndent())

    @Test fun `SLEEP MINUTES is clean`() = expectClean("""
        PROGRAM T
        PROCEDURE Main:
          SLEEP 1 MINUTES
          STOP RUN
        END-PROCEDURE
    """.trimIndent())

    @Test fun `SLEEP with non-INTEGER raises error`() {
        expectErrors("""
            PROGRAM T
            DATA:
              t : TEXT = "500"
            PROCEDURE Main:
              SLEEP t MILLISECONDS
              STOP RUN
            END-PROCEDURE
        """.trimIndent(), "E101")
    }

    // -------------------------------------------------------------------------
    // DEFINE TYPE aliases (Group 2)
    // -------------------------------------------------------------------------

    @Test fun `DEFINE TYPE alias resolves in DATA section`() = expectClean("""
        PROGRAM T
        DEFINE TYPE Rate IS DECIMAL(18, 8)
        DATA:
          exchange-rate : Rate
        PROCEDURE Main:
          COMPUTE exchange-rate = 1.23456789
          STOP RUN
        END-PROCEDURE
    """.trimIndent())

    @Test fun `DEFINE TYPE alias used as list element type`() = expectClean("""
        PROGRAM T
        DEFINE TYPE ItemCount IS INTEGER
        DATA:
          qty : ItemCount
        PROCEDURE Main:
          COMPUTE qty = 10
          STOP RUN
        END-PROCEDURE
    """.trimIndent())

    // -------------------------------------------------------------------------
    // TEST blocks and ASSERT statement (Group 3)
    // -------------------------------------------------------------------------

    @Test fun `TEST block with ASSERT true literal is clean`() = expectClean("""
        PROGRAM T
        DATA:
          x : INTEGER = 1
        PROCEDURE Main:
          STOP RUN
        END-PROCEDURE
        TEST "one equals one":
          ASSERT x = 1
        END-TEST
    """.trimIndent())

    @Test fun `ASSERT with non-BOOLEAN raises error`() {
        expectErrors("""
            PROGRAM T
            DATA:
              n : INTEGER = 5
            PROCEDURE Main:
              ASSERT n
            END-PROCEDURE
        """.trimIndent(), "E102")
    }

    @Test fun `UUID variable declared and UUID-GENERATE assigned is clean`() = expectClean("""
        PROGRAM T
        DATA:
          id : UUID
        PROCEDURE Main:
          COMPUTE id = UUID-GENERATE()
          STOP RUN
        END-PROCEDURE
    """.trimIndent())

    // IS postfix conditions (Group 4)

    @Test fun `IS POSITIVE used as BOOLEAN expression in IF is clean`() = expectClean("""
        PROGRAM T
        DATA:
          amount : INTEGER = 0
        PROCEDURE Main:
          IF amount IS POSITIVE:
            DISPLAY "positive"
          END-IF
          STOP RUN
        END-PROCEDURE
    """.trimIndent())

    @Test fun `IS NOT ZERO used as BOOLEAN expression is clean`() = expectClean("""
        PROGRAM T
        DATA:
          total : INTEGER = 1
          ok    : BOOLEAN
        PROCEDURE Main:
          COMPUTE ok = total IS NOT ZERO
          STOP RUN
        END-PROCEDURE
    """.trimIndent())

    @Test fun `DISPLAY JSON is clean for TEXT value`() = expectClean("""
        PROGRAM T
        DATA:
          msg : TEXT = "hello"
        PROCEDURE Main:
          DISPLAY JSON msg
          STOP RUN
        END-PROCEDURE
    """.trimIndent())

    @Test fun `named CONDITION used as BOOLEAN in IF is clean`() = expectClean("""
        PROGRAM T
        DATA:
          amount : INTEGER = 50
        CONDITION Is-Valid WHEN amount > 0
        PROCEDURE Main:
          IF Is-Valid:
            DISPLAY "valid"
          END-IF
          STOP RUN
        END-PROCEDURE
    """.trimIndent())

    @Test fun `WRITE JSON PRETTY to file is clean`() = expectClean("""
        PROGRAM T
        DATA:
          msg : TEXT = "hello"
        PROCEDURE Main:
          WRITE JSON msg TO "out.json" PRETTY
          STOP RUN
        END-PROCEDURE
    """.trimIndent())

    @Test fun `WRITE XML to file is clean`() = expectClean("""
        PROGRAM T
        DATA:
          msg : TEXT = "hello"
        PROCEDURE Main:
          WRITE XML msg TO "out.xml"
          STOP RUN
        END-PROCEDURE
    """.trimIndent())

    @Test fun `MATCH range pattern INTEGER is clean`() = expectClean("""
        PROGRAM T
        DATA:
          n : INTEGER = 5
        PROCEDURE Main:
          MATCH n:
            WHEN 1..10:
              DISPLAY "small"
            OTHERWISE:
              DISPLAY "large"
          END-MATCH
          STOP RUN
        END-PROCEDURE
    """.trimIndent())

    @Test fun `WITH PRECISION DECIMAL128 block is clean`() = expectClean("""
        PROGRAM T
        DATA:
          x : DECIMAL(18,8) = 1.5
          y : DECIMAL(18,8) = 0
        PROCEDURE Main:
          WITH PRECISION DECIMAL128:
            COMPUTE y = x * 3
          END-PRECISION
          STOP RUN
        END-PROCEDURE
    """.trimIndent())

    @Test fun `PARSE JSON AS type annotation is clean`() = expectClean("""
        PROGRAM T
        RECORD Invoice FIELDS
          amount : DECIMAL(18,2) = 0
        END-RECORD
        DATA:
          inv  : Invoice
          data : TEXT = "{}"
        PROCEDURE Main:
          PARSE JSON data INTO inv AS Invoice
          STOP RUN
        END-PROCEDURE
    """.trimIndent())

    @Test fun `MATCH type pattern TEXT AS binding is clean`() = expectClean("""
        PROGRAM T
        DATA:
          label : TEXT = "hello"
        PROCEDURE Main:
          MATCH label:
            WHEN TEXT AS s:
              DISPLAY s
            OTHERWISE:
              DISPLAY "?"
          END-MATCH
          STOP RUN
        END-PROCEDURE
    """.trimIndent())

    @Test fun `DIVIDE USING HALF-UP is clean`() = expectClean("""
        PROGRAM T
        DATA:
          total  : DECIMAL(18,2) = 10.0
          count  : DECIMAL(18,2) = 3.0
          result : DECIMAL(18,2) = 0
        PROCEDURE Main:
          DIVIDE total INTO count GIVING result USING HALF-UP
          STOP RUN
        END-PROCEDURE
    """.trimIndent())

    @Test fun `MODULE declaration with EXPORT is clean`() = expectClean("""
        MODULE billing.core VERSION "1.0":
          EXPORT PROCEDURE ApplyDiscount
          EXPORT PROCEDURE CalculateTax
        END-MODULE
        PROGRAM T
        PROCEDURE ApplyDiscount:
          STOP RUN
        END-PROCEDURE
        PROCEDURE CalculateTax:
          STOP RUN
        END-PROCEDURE
    """.trimIndent())

    @Test fun `MODULE EXPORT references non-existent symbol emits E210`() = expectErrors("""
        MODULE billing:
          EXPORT PROCEDURE NonExistent
        END-MODULE
        PROGRAM T
        PROCEDURE Main:
          STOP RUN
        END-PROCEDURE
    """.trimIndent(), "E210")

    @Test fun `IMPORT with VERSION constraint is clean`() = expectClean("""
        IMPORT java.time.LocalDate VERSION "11"
        PROGRAM T
        PROCEDURE Main:
          STOP RUN
        END-PROCEDURE
    """.trimIndent())

    @Test fun `TEST TABLE with COLUMNS and ROW is clean`() = expectClean("""
        PROGRAM T
        DATA:
          result : INTEGER = 0
        PROCEDURE Add USING a : INTEGER, b : INTEGER RETURNING INTEGER:
          RETURN a + b
        END-PROCEDURE
        TEST TABLE "addition table":
          COLUMNS: a, b, expected
          ROW: 1, 2, 3
          ROW: 10, 20, 30
          WHEN:
            COMPUTE result = a + b
          THEN:
            ASSERT result = expected
        END-TEST
    """.trimIndent())

    @Test fun `MOCK statement inside TEST is clean`() = expectClean("""
        PROGRAM T
        DATA:
          rate : INTEGER = 0
        PROCEDURE FetchRate RETURNING INTEGER:
          RETURN 5
        END-PROCEDURE
        TEST "mocked rate":
          MOCK FetchRate RETURNS 99
          COMPUTE rate = FetchRate()
          ASSERT rate = 99
        END-TEST
    """.trimIndent())

    @Test fun `MOCK unknown procedure emits E212`() {
        expectErrors("""
            PROGRAM T
            PROCEDURE Main:
              STOP RUN
            END-PROCEDURE
            TEST "bad mock":
              MOCK DoesNotExist RETURNS 1
              ASSERT TRUE
            END-TEST
        """.trimIndent(), "E212")
    }

    // -------------------------------------------------------------------------
    // Phase 12.2 — MATCH GuardPattern
    // -------------------------------------------------------------------------

    @Test fun `MATCH guard with literal pattern is clean`() {
        expectClean("""
            PROGRAM T
            DATA:
              score : INTEGER = 0
              result : TEXT = ""
            PROCEDURE Main:
              MATCH score:
                WHEN 100 IF score > 0:
                  MOVE "perfect" TO result
                OTHERWISE:
                  MOVE "other" TO result
              END-MATCH
            END-PROCEDURE
        """.trimIndent())
    }

    @Test fun `MATCH guard with range pattern is clean`() {
        expectClean("""
            PROGRAM T
            DATA:
              n : INTEGER = 42
              out : TEXT = ""
            PROCEDURE Main:
              MATCH n:
                WHEN 1..100 IF n > 50:
                  MOVE "high" TO out
                WHEN 1..100:
                  MOVE "low" TO out
                OTHERWISE:
                  MOVE "out" TO out
              END-MATCH
            END-PROCEDURE
        """.trimIndent())
    }

    @Test fun `MATCH guard with non-boolean condition emits E024`() {
        expectErrors("""
            PROGRAM T
            DATA:
              x : INTEGER = 0
            PROCEDURE Main:
              MATCH x:
                WHEN 1 IF x:
                  DISPLAY "hi"
              END-MATCH
            END-PROCEDURE
        """.trimIndent(), "E024")
    }

    // -------------------------------------------------------------------------
    // DEPRECATED attribute
    // -------------------------------------------------------------------------

    @Test fun `PERFORM of deprecated procedure warns W008`() {
        val tc = analyze("""
            PROGRAM DepTest
            PROCEDURE Main:
              PERFORM OldCalc
              STOP RUN
            END-PROCEDURE
            PROCEDURE OldCalc DEPRECATED "use NewCalc instead":
              DISPLAY "old"
            END-PROCEDURE
        """.trimIndent())
        val dep = tc.diagnostics.warnings.firstOrNull { it.code == "W008" }
        assertTrue(dep != null, "Expected W008; got ${tc.diagnostics.warnings.map { it.code }}")
        assertTrue("use NewCalc instead" in dep.message, "message lost: ${dep.message}")
    }

    @Test fun `non-deprecated procedure does not warn`() {
        val tc = analyze("""
            PROGRAM DepTest
            PROCEDURE Main:
              PERFORM DoWork
              STOP RUN
            END-PROCEDURE
            PROCEDURE DoWork:
              DISPLAY "work"
            END-PROCEDURE
        """.trimIndent())
        assertFalse(tc.diagnostics.warnings.any { it.code == "W008" })
    }

    // ─── F14 — CALL in expression position infers the real return type (E2) ───────────

    @Test fun `CALL expression with a real return type-checks clean`() {
        // Math.max(long,long) → long; LET n infers INTEGER from the real descriptor, no annotation.
        expectClean("""
            PROGRAM T
            DATA:
              a : INTEGER = 1
              b : INTEGER = 2
            PROCEDURE Main:
              LET n = CALL Math.max WITH a, b
              DISPLAY "{n}"
            END-PROCEDURE
        """.trimIndent())
    }

    @Test fun `CALL expression on a void method is rejected at compile time, not at run`() {
        // System.gc() returns void — it has no value. Using it in expression position must be a
        // compile error (E232), NOT a clean type-check that crashes when loaded (P3 landmine).
        expectErrors("""
            PROGRAM T
            DATA:
              x : INTEGER = 0
            PROCEDURE Main:
              COMPUTE x = CALL System.gc
              DISPLAY "{x}"
            END-PROCEDURE
        """.trimIndent(), "E232")
    }

    // F16 — a variant value's case fields are NOT addressable as a dotted lvalue (or rvalue):
    // `st.tracking` rejects with E002 ("Cannot access field ... on type VariantRefType"). This is
    // the invariant that makes the "variant list element un-snapshotted" aliasing UNREACHABLE:
    // there is no surface path that mutates a variant instance's field in place, so a variant
    // stored in a list can never be corrupted by a later write (unlike a reused RECORD buffer).
    // Variant fields are read-only, and only via MATCH bindings (which are immutable locals).
    @Test fun `variant case fields are not a dotted lvalue (F16 aliasing unreachable)`() {
        expectErrors("""
            PROGRAM T
            VARIANT OrderStatus IS
              Pending
              | Shipped WITH tracking : TEXT
            DATA:
              st : OrderStatus
            PROCEDURE Main:
              MOVE Shipped("A") TO st
              MOVE "B" TO st.tracking
            END-PROCEDURE
        """.trimIndent(), "E002")
    }

    @Test fun `reading a variant case field by dot is rejected (F16)`() {
        expectErrors("""
            PROGRAM T
            VARIANT OrderStatus IS
              Pending
              | Shipped WITH tracking : TEXT
            DATA:
              st : OrderStatus
              t  : TEXT
            PROCEDURE Main:
              MOVE Shipped("A") TO st
              MOVE st.tracking TO t
            END-PROCEDURE
        """.trimIndent(), "E002")
    }

    // -------------------------------------------------------------------------
    // F27 — user-dependency classpath plumbing (E2 leftover)
    // -------------------------------------------------------------------------

    /** Build a throwaway jar holding `ext/Widget` with `public static long compute(long)`. */
    private fun buildWidgetJar(): File {
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS)
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "ext/Widget", null, "java/lang/Object", null)
        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "compute", "(J)J", null, null)
        mv.visitCode()
        mv.visitVarInsn(Opcodes.LLOAD, 0)
        mv.visitInsn(Opcodes.LRETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
        cw.visitEnd()
        val jar = Files.createTempFile("kobol-f27-dep", ".jar").toFile().apply { deleteOnExit() }
        JarOutputStream(jar.outputStream()).use { jos ->
            jos.putNextEntry(JarEntry("ext/Widget.class"))
            jos.write(cw.toByteArray())
            jos.closeEntry()
        }
        return jar
    }

    // The E2 interop resolver must read the user's resolved project dependencies, not just
    // the JDK + compiler/stdlib runtime classpath. `ext/Widget` lives ONLY in a freshly-built
    // jar (never on the test JVM's classpath, so the resolver's system-resource fallback can't
    // find it) — it resolves iff that jar is wired into the compile classpath.
    @Test fun `interop CALL resolves against a user-dependency jar wired into the compile classpath (F27)`() {
        val jar = buildWidgetJar()
        val prog = """
            PROGRAM T
            IMPORT "ext.Widget" AS Widget
            PROCEDURE Main:
              LET n = CALL Widget.compute WITH 5
              DISPLAY n
            END-PROCEDURE
        """.trimIndent()

        val saved = dev.kobol.KobolHome.interopClasspath
        try {
            // Not wired: the dep is invisible to the compile-time resolver → E236 class-not-found.
            dev.kobol.KobolHome.interopClasspath = emptyList()
            expectErrors(prog, "E236")

            // Wired in (mirrors `kobol build` feeding resolved lib/ jars): resolves clean.
            dev.kobol.KobolHome.interopClasspath = listOf(jar.absolutePath)
            assertTrue(
                jar.absolutePath in dev.kobol.KobolHome.compileClasspath(),
                "compileClasspath must include the wired user dep",
            )
            expectClean(prog)
        } finally {
            dev.kobol.KobolHome.interopClasspath = saved
        }
    }

    // -------------------------------------------------------------------------
    // F15 — Kotlin @Metadata read (nullable-return detection)
    // -------------------------------------------------------------------------

    // A Kotlin method returning `String?` and one returning `String` erase to the SAME JVM
    // descriptor `()Ljava/lang/String;`; the nullable difference lives ONLY in @Metadata. Kobol
    // has no null, so a nullable result silently NPEs when used (type-checks clean = P3 landmine).
    // The resolver must decode the metadata and the type checker must warn (W237) on the nullable
    // call but NOT the non-null one. The fixture is a real compiled Kotlin file facade on the test
    // classpath (`dev/kobol/testfixture/KotlinNullableApiKt`), reachable via the resolver's
    // system-resource fallback.
    @Test fun `interop CALL on a nullable-returning Kotlin method warns W237 (F15)`() {
        val tc = analyze("""
            PROGRAM T
            IMPORT "dev.kobol.testfixture.KotlinNullableApiKt" AS NA
            PROCEDURE Main:
              LET a = CALL NA.maybeNull
              DISPLAY a
            END-PROCEDURE
        """.trimIndent())
        assertFalse(
            tc.diagnostics.hasErrors,
            "the call resolves; nullability is a warning, not an error. Errors:\n" +
                tc.diagnostics.errors.joinToString("\n") { it.render() },
        )
        assertTrue(
            "W237" in tc.diagnostics.warnings.map { it.code },
            "expected W237 for a nullable Kotlin return; warnings=${tc.diagnostics.warnings.map { it.code }}",
        )
    }

    @Test fun `interop CALL on a non-null Kotlin method does not warn W237 (F15)`() {
        val tc = analyze("""
            PROGRAM T
            IMPORT "dev.kobol.testfixture.KotlinNullableApiKt" AS NA
            PROCEDURE Main:
              LET b = CALL NA.alwaysPresent
              DISPLAY b
            END-PROCEDURE
        """.trimIndent())
        assertFalse(tc.diagnostics.hasErrors, "must resolve clean")
        assertFalse(
            "W237" in tc.diagnostics.warnings.map { it.code },
            "a non-null Kotlin return must NOT warn W237",
        )
    }

    // The CALL-STATEMENT GIVING form captures the same nullable return into a variable, so it is
    // the same P3 landmine as the expression form — W237 must fire on every call form (P6), not
    // only `LET x = CALL …`. Fire-and-forget (no GIVING) discards the value, so no warning there.
    @Test fun `interop CALL statement GIVING a nullable Kotlin return warns W237 (F15)`() {
        val tc = analyze("""
            PROGRAM T
            IMPORT "dev.kobol.testfixture.KotlinNullableApiKt" AS NA
            DATA:
              a : TEXT = ""
            PROCEDURE Main:
              CALL NA.maybeNull GIVING a
              DISPLAY a
            END-PROCEDURE
        """.trimIndent())
        assertFalse(
            tc.diagnostics.hasErrors,
            "the call resolves; nullability is a warning, not an error. Errors:\n" +
                tc.diagnostics.errors.joinToString("\n") { it.render() },
        )
        assertTrue(
            "W237" in tc.diagnostics.warnings.map { it.code },
            "expected W237 for a nullable Kotlin return captured into GIVING; warnings=${tc.diagnostics.warnings.map { it.code }}",
        )
    }

    @Test fun `interop CALL statement GIVING a non-null Kotlin return does not warn W237 (F15)`() {
        val tc = analyze("""
            PROGRAM T
            IMPORT "dev.kobol.testfixture.KotlinNullableApiKt" AS NA
            DATA:
              b : TEXT = ""
            PROCEDURE Main:
              CALL NA.alwaysPresent GIVING b
              DISPLAY b
            END-PROCEDURE
        """.trimIndent())
        assertFalse(tc.diagnostics.hasErrors, "must resolve clean")
        assertFalse(
            "W237" in tc.diagnostics.warnings.map { it.code },
            "a non-null Kotlin return must NOT warn W237",
        )
    }

    // ─── F15 #6 — Kotlin `suspend` function → FUTURE bridge (type-check side) ─────────
    // A suspend CALL is bridged to a CompletableFuture; if its result is captured, the GIVING
    // target must be a FUTURE OF T. Capturing it into a non-future (e.g. TEXT) would store a
    // CompletableFuture into the wrong slot and crash at run — reject it (E237), not silently.

    @Test fun `suspend CALL GIVING a non-future target is rejected E237 (F15)`() {
        val tc = analyze("""
            PROGRAM T
            IMPORT "dev.kobol.testfixture.KotlinSuspendApiKt" AS SUS
            DATA:
              wrong : TEXT = ""
            PROCEDURE Main:
              CALL SUS.suspendValue GIVING wrong
              DISPLAY wrong
            END-PROCEDURE
        """.trimIndent())
        assertTrue(
            "E237" in tc.diagnostics.errors.map { it.code },
            "expected E237 for a suspend result captured into a non-FUTURE; errors=${tc.diagnostics.errors.map { it.code }}",
        )
    }

    @Test fun `suspend CALL GIVING a FUTURE target type-checks clean (F15)`() {
        val tc = analyze("""
            PROGRAM T
            IMPORT "dev.kobol.testfixture.KotlinSuspendApiKt" AS SUS
            DATA:
              fut    : FUTURE OF TEXT
              result : TEXT = ""
            PROCEDURE Main:
              CALL SUS.suspendValue GIVING fut
              AWAIT fut INTO result
              DISPLAY result
            END-PROCEDURE
        """.trimIndent())
        assertFalse(
            tc.diagnostics.hasErrors,
            "a suspend result captured into a FUTURE must resolve clean. Errors:\n" +
                tc.diagnostics.errors.joinToString("\n") { it.render() },
        )
    }

    // -------------------------------------------------------------------------
    // F28 — multifile-class-facade nullability (the COMMON library case)
    // -------------------------------------------------------------------------

    // Every published Kotlin library compiles its top-level functions into a MULTIFILE-CLASS
    // FACADE (@Metadata k=4, e.g. kotlin-stdlib `StringsKt`) whose metadata holds only part-class
    // NAMES — the function signatures (and their nullability) live in the part classes (k=5). So
    // decoding the facade's own metadata sees zero functions and W237 silently misses every library
    // top-level nullable fn = P3 false-negative. The resolver must follow the part-class names and
    // union their @Metadata. Fixture `MultiFacade` is a real compiled k=4 facade on the test
    // classpath: `multiMaybeNull():String?` (must warn) + `multiAlwaysPresent():String` (must not).
    @Test fun `interop CALL on a nullable fn of a multifile-class facade warns W237 (F28)`() {
        val tc = analyze("""
            PROGRAM T
            IMPORT "dev.kobol.testfixture.MultiFacade" AS MF
            PROCEDURE Main:
              LET a = CALL MF.multiMaybeNull
              DISPLAY a
            END-PROCEDURE
        """.trimIndent())
        assertFalse(
            tc.diagnostics.hasErrors,
            "the call resolves; nullability is a warning, not an error. Errors:\n" +
                tc.diagnostics.errors.joinToString("\n") { it.render() },
        )
        assertTrue(
            "W237" in tc.diagnostics.warnings.map { it.code },
            "expected W237 for a nullable multifile-facade Kotlin return; warnings=${tc.diagnostics.warnings.map { it.code }}",
        )
    }

    @Test fun `interop CALL on a non-null fn of a multifile-class facade does not warn W237 (F28)`() {
        val tc = analyze("""
            PROGRAM T
            IMPORT "dev.kobol.testfixture.MultiFacade" AS MF
            PROCEDURE Main:
              LET b = CALL MF.multiAlwaysPresent
              DISPLAY b
            END-PROCEDURE
        """.trimIndent())
        assertFalse(tc.diagnostics.hasErrors, "must resolve clean")
        assertFalse(
            "W237" in tc.diagnostics.warnings.map { it.code },
            "a non-null multifile-facade Kotlin return must NOT warn W237",
        )
    }

    // The real-world proof, not just a synthetic fixture: kotlin-stdlib `StringsKt` is itself a
    // multifile-class facade and `toIntOrNull(): Int?` is nullable. Before F28 this resolved + ran
    // but never warned — the exact silent-NPE landmine F28 closes. kotlin-stdlib is on the compile
    // classpath, so this needs no fixture.
    @Test fun `interop CALL on stdlib StringsKt toIntOrNull (nullable) warns W237 (F28)`() {
        val tc = analyze("""
            PROGRAM T
            IMPORT "kotlin.text.StringsKt" AS Strings
            DATA:
              s : TEXT = "42"
            PROCEDURE Main:
              LET n = CALL Strings.toIntOrNull WITH s
              DISPLAY n
            END-PROCEDURE
        """.trimIndent())
        assertFalse(
            tc.diagnostics.hasErrors,
            "the call resolves; nullability is a warning, not an error. Errors:\n" +
                tc.diagnostics.errors.joinToString("\n") { it.render() },
        )
        assertTrue(
            "W237" in tc.diagnostics.warnings.map { it.code },
            "expected W237 for stdlib StringsKt.toIntOrNull (Int?); warnings=${tc.diagnostics.warnings.map { it.code }}",
        )
    }

    // -------------------------------------------------------------------------
    // #5 — Kotlin/Java property accessors via `obj.field`
    // -------------------------------------------------------------------------

    // `obj.field` used to type ANY field of a JAVA-OBJECT as TEXT (blind hardcode). It must resolve
    // the property's getter off the classpath and infer the REAL type — a non-null `String name`
    // stays TEXT, but a nullable `String? nickname` (getter `getNickname():String?`, erased to plain
    // String) must warn W237: Kobol has no null, so the value silently NPEs when used (P3).
    @Test fun `obj field on a nullable Kotlin property warns W237 (#5)`() {
        val tc = analyze("""
            PROGRAM T
            IMPORT "dev.kobol.testfixture.KotlinBean" AS Bean
            PROCEDURE Main:
              LET b = NEW Bean WITH "Ada"
              LET nick = b.nickname
              DISPLAY nick
            END-PROCEDURE
        """.trimIndent())
        assertFalse(
            tc.diagnostics.hasErrors,
            "the property read resolves; nullability is a warning, not an error. Errors:\n" +
                tc.diagnostics.errors.joinToString("\n") { it.render() },
        )
        assertTrue(
            "W237" in tc.diagnostics.warnings.map { it.code },
            "expected W237 for a nullable Kotlin property read; warnings=${tc.diagnostics.warnings.map { it.code }}",
        )
    }

    @Test fun `obj field on a non-null Kotlin property does not warn W237 (#5)`() {
        val tc = analyze("""
            PROGRAM T
            IMPORT "dev.kobol.testfixture.KotlinBean" AS Bean
            PROCEDURE Main:
              LET b = NEW Bean WITH "Ada"
              LET nm = b.name
              DISPLAY nm
            END-PROCEDURE
        """.trimIndent())
        assertFalse(tc.diagnostics.hasErrors, "must resolve clean")
        assertFalse(
            "W237" in tc.diagnostics.warnings.map { it.code },
            "a non-null Kotlin property must NOT warn W237",
        )
    }
}
