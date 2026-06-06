package dev.kobol.semantic

import dev.kobol.lexer.Lexer
import dev.kobol.parser.Parser
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for cross-module PERFORM via ModuleRegistry.
 *
 * These tests simulate a two-program project: a library program declares a MODULE
 * and exports procedures; a consumer program IMPORTs the module and calls its
 * procedures with the "PERFORM Alias.ProcName" syntax.
 */
class CrossModuleTypeCheckTest {

    private fun buildRegistry(libSrc: String): ModuleRegistry {
        val tokens  = Lexer(libSrc, "lib.kbl").tokenize()
        val program = Parser(tokens, "lib.kbl").parseProgram()
        val modDecl = requireNotNull(program.moduleDecl) { "library has no MODULE declaration" }
        val exportedNames = modDecl.exports.map { it.name }.toSet()
        val procedures = mutableMapOf<String, ModuleRegistry.ModuleProcedure>()
        for (proc in program.procedures) {
            if (!proc.exported && proc.name !in exportedNames) continue
            val params = proc.params.map { p ->
                Symbol.ProcedureSymbol.Param(p.name, toKobol(p.type))
            }
            procedures[proc.name.uppercase()] = ModuleRegistry.ModuleProcedure(params, proc.returnType?.let { toKobol(it) }, proc.isAsync)
        }
        val jvmClass = program.name
            .split("-").joinToString("") { it.lowercase().replaceFirstChar { c -> c.uppercase() } }
        val registry = ModuleRegistry()
        registry.register(ModuleRegistry.ModuleInfo(modDecl.name, modDecl.version, jvmClass, procedures))
        return registry
    }

    private fun toKobol(spec: dev.kobol.parser.ast.TypeSpec): KobolType = when (spec) {
        is dev.kobol.parser.ast.TypeSpec.IntegerType  -> KobolType.IntegerType
        is dev.kobol.parser.ast.TypeSpec.SmallIntType -> KobolType.SmallIntType
        is dev.kobol.parser.ast.TypeSpec.BooleanType  -> KobolType.BooleanType
        is dev.kobol.parser.ast.TypeSpec.TextType     -> KobolType.TextType(spec.maxLength)
        is dev.kobol.parser.ast.TypeSpec.DecimalType  -> KobolType.DecimalType(spec.precision, spec.scale)
        is dev.kobol.parser.ast.TypeSpec.MoneyType    -> KobolType.MoneyType(spec.precision, spec.scale)
        is dev.kobol.parser.ast.TypeSpec.DateType     -> KobolType.DateType
        is dev.kobol.parser.ast.TypeSpec.TimeType     -> KobolType.TimeType
        is dev.kobol.parser.ast.TypeSpec.DateTimeType -> KobolType.DateTimeType
        is dev.kobol.parser.ast.TypeSpec.UuidType     -> KobolType.UuidType
        is dev.kobol.parser.ast.TypeSpec.ListOf       -> KobolType.ListType(toKobol(spec.elementType))
        is dev.kobol.parser.ast.TypeSpec.MapOf        -> KobolType.MapType(toKobol(spec.keyType), toKobol(spec.valueType))
        is dev.kobol.parser.ast.TypeSpec.FutureOf     -> KobolType.FutureType(toKobol(spec.elementType))
        is dev.kobol.parser.ast.TypeSpec.NamedType    -> KobolType.RecordRefType(spec.name)
    }

    private fun analyzeConsumer(consumerSrc: String, registry: ModuleRegistry): TypeChecker {
        val lines   = consumerSrc.lines()
        val tokens  = Lexer(consumerSrc, "consumer.kbl").tokenize()
        val program = Parser(tokens, "consumer.kbl").parseProgram()
        registry.clearAliases()
        val checker = TypeChecker(lines, registry)
        checker.analyze(program)
        return checker
    }

    // ─── library source ───────────────────────────────────────────────────────

    private val libSrc = """
        PROGRAM MathUtils
        MODULE kobol.math:
          EXPORT PROCEDURE Double
        END-MODULE
        EXPORT PROCEDURE Double USING n : INTEGER RETURNING INTEGER:
          RETURN n * 2
        END-PROCEDURE
    """.trimIndent()

    // ─── tests ────────────────────────────────────────────────────────────────

    @Test fun `registry builds from library source`() {
        val registry = buildRegistry(libSrc)
        val mod = registry.resolveByName("kobol.math")
        assertTrue(mod != null, "Module 'kobol.math' should be registered")
        assertTrue("DOUBLE" in mod!!.procedures, "Procedure DOUBLE should be exported")
    }

    @Test fun `cross-module PERFORM is clean when alias and signature match`() {
        val registry = buildRegistry(libSrc)
        val consumerSrc = """
            PROGRAM Consumer
            IMPORT kobol.math AS Math
            DATA:
              x : INTEGER = 5
            PROCEDURE Main:
              PERFORM Math.Double USING x
              STOP RUN
            END-PROCEDURE
        """.trimIndent()
        val tc = analyzeConsumer(consumerSrc, registry)
        assertFalse(tc.diagnostics.hasErrors,
            "Expected no errors but got: ${tc.diagnostics.errors.map { it.message }}")
    }

    @Test fun `cross-module PERFORM reports E211 for unknown alias`() {
        val registry = buildRegistry(libSrc)
        val consumerSrc = """
            PROGRAM Consumer
            DATA:
              x : INTEGER = 5
            PROCEDURE Main:
              PERFORM UnknownAlias.Double USING x
              STOP RUN
            END-PROCEDURE
        """.trimIndent()
        val tc = analyzeConsumer(consumerSrc, registry)
        val codes = tc.diagnostics.errors.map { it.code }
        assertTrue("E211" in codes, "Expected E211 for unknown alias; got $codes")
    }

    @Test fun `cross-module PERFORM reports E212 for unknown procedure`() {
        val registry = buildRegistry(libSrc)
        val consumerSrc = """
            PROGRAM Consumer
            IMPORT kobol.math AS Math
            DATA:
              x : INTEGER = 5
            PROCEDURE Main:
              PERFORM Math.Triple USING x
              STOP RUN
            END-PROCEDURE
        """.trimIndent()
        val tc = analyzeConsumer(consumerSrc, registry)
        val codes = tc.diagnostics.errors.map { it.code }
        assertTrue("E212" in codes, "Expected E212 for unknown procedure; got $codes")
    }

    @Test fun `cross-module PERFORM reports E213 for wrong arity`() {
        val registry = buildRegistry(libSrc)
        val consumerSrc = """
            PROGRAM Consumer
            IMPORT kobol.math AS Math
            PROCEDURE Main:
              PERFORM Math.Double
              STOP RUN
            END-PROCEDURE
        """.trimIndent()
        val tc = analyzeConsumer(consumerSrc, registry)
        val codes = tc.diagnostics.errors.map { it.code }
        assertTrue("E213" in codes, "Expected E213 for wrong arity; got $codes")
    }

    @Test fun `module import does not trigger W010 unused import warning`() {
        val registry = buildRegistry(libSrc)
        val consumerSrc = """
            PROGRAM Consumer
            IMPORT kobol.math AS Math
            DATA:
              x : INTEGER = 5
            PROCEDURE Main:
              PERFORM Math.Double USING x
              STOP RUN
            END-PROCEDURE
        """.trimIndent()
        val tc = analyzeConsumer(consumerSrc, registry)
        val warnings = tc.diagnostics.warnings.map { it.code }
        assertTrue("W010" !in warnings, "Module import should not trigger W010; got $warnings")
    }

    // ─── Phase 12.1 — version constraint tests ──────────────────────────────

    private val versionedLibSrc = """
        PROGRAM MathUtils
        MODULE kobol.math VERSION "2.1":
          EXPORT PROCEDURE Double
        END-MODULE
        EXPORT PROCEDURE Double USING n : INTEGER RETURNING INTEGER:
          RETURN n * 2
        END-PROCEDURE
    """.trimIndent()

    @Test fun `IMPORT with exact version match is clean`() {
        val registry = buildRegistry(versionedLibSrc)
        val consumerSrc = """
            PROGRAM Consumer
            IMPORT kobol.math VERSION "2.1" AS Math
            DATA:
              x : INTEGER = 5
            PROCEDURE Main:
              PERFORM Math.Double USING x
              STOP RUN
            END-PROCEDURE
        """.trimIndent()
        val tc = analyzeConsumer(consumerSrc, registry)
        assertFalse(tc.diagnostics.hasErrors, "Expected no errors; got ${tc.diagnostics.errors}")
    }

    @Test fun `IMPORT with wildcard major version is clean`() {
        val registry = buildRegistry(versionedLibSrc)
        val consumerSrc = """
            PROGRAM Consumer
            IMPORT kobol.math VERSION "2.x" AS Math
            DATA:
              x : INTEGER = 5
            PROCEDURE Main:
              PERFORM Math.Double USING x
              STOP RUN
            END-PROCEDURE
        """.trimIndent()
        val tc = analyzeConsumer(consumerSrc, registry)
        assertFalse(tc.diagnostics.hasErrors, "Expected no errors; got ${tc.diagnostics.errors}")
    }

    // ─── cross-module PERFORM … GIVING (no silent drop) ─────────────────────

    @Test fun `cross-module PERFORM GIVING on sync procedure raises E215 (not silently dropped)`() {
        val registry = buildRegistry(libSrc)  // Double is a sync RETURNING procedure
        val consumerSrc = """
            PROGRAM Consumer
            IMPORT kobol.math AS Math
            DATA:
              x : INTEGER = 5
              result : INTEGER = 0
            PROCEDURE Main:
              PERFORM Math.Double USING x GIVING result
              STOP RUN
            END-PROCEDURE
        """.trimIndent()
        val tc = analyzeConsumer(consumerSrc, registry)
        val codes = tc.diagnostics.errors.map { it.code }
        assertTrue("E215" in codes,
            "Sync cross-module GIVING must error E215, not be silently dropped; got $codes")
    }

    @Test fun `cross-module PERFORM GIVING on async procedure raises E218 (capture unsupported)`() {
        val asyncLibSrc = """
            PROGRAM JobUtils
            MODULE kobol.jobs:
              EXPORT PROCEDURE Fetch
            END-MODULE
            EXPORT ASYNC PROCEDURE Fetch USING n : INTEGER RETURNING INTEGER:
              RETURN n * 2
            END-PROCEDURE
        """.trimIndent()
        val registry = buildRegistry(asyncLibSrc)
        val consumerSrc = """
            PROGRAM Consumer
            IMPORT kobol.jobs AS Jobs
            DATA:
              x : INTEGER = 5
              result : INTEGER = 0
            PROCEDURE Main:
              PERFORM Jobs.Fetch USING x GIVING result
              STOP RUN
            END-PROCEDURE
        """.trimIndent()
        val tc = analyzeConsumer(consumerSrc, registry)
        val codes = tc.diagnostics.errors.map { it.code }
        assertTrue("E218" in codes,
            "Async cross-module GIVING capture must error E218, not be silently dropped; got $codes")
    }

    // F5: version-mismatch was renumbered E215 → E219 so E215 is unambiguously sync-GIVING misuse.
    @Test fun `IMPORT with mismatched version emits E219`() {
        val registry = buildRegistry(versionedLibSrc)
        val consumerSrc = """
            PROGRAM Consumer
            IMPORT kobol.math VERSION "3.0" AS Math
            DATA:
              x : INTEGER = 5
            PROCEDURE Main:
              PERFORM Math.Double USING x
              STOP RUN
            END-PROCEDURE
        """.trimIndent()
        val tc = analyzeConsumer(consumerSrc, registry)
        val codes = tc.diagnostics.errors.map { it.code }
        assertTrue("E219" in codes, "Expected E219 for version mismatch; got $codes")
        assertTrue("E215" !in codes, "E215 must no longer be used for version mismatch (now sync-GIVING only); got $codes")
    }
}
