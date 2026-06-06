package dev.kobol

import dev.kobol.codegen.AsmEmitter
import dev.kobol.diagnostic.Diagnostic
import dev.kobol.lexer.LexException
import dev.kobol.lexer.Lexer
import dev.kobol.parser.ParseException
import dev.kobol.parser.Parser
import dev.kobol.semantic.KobolType
import dev.kobol.semantic.ModuleRegistry
import dev.kobol.semantic.Symbol
import dev.kobol.semantic.TypeChecker
import java.io.File

/**
 * Public programmatic API for invoking the Kobol compiler.
 *
 * Used by [kobol-gradle-plugin] and other tooling that cannot use the internal
 * [compileFile] function from Main.kt.  Provides a stable, type-safe surface
 * that doesn't change when CLI internals are refactored.
 */
object KobolCompiler {

    data class CompileResult(
        val errors: List<String>,
        val warnings: List<String>,
        val outputFiles: List<File>,
    ) {
        val success: Boolean get() = errors.isEmpty()
    }

    /**
     * Compile [sourceFiles] to JVM bytecode under [outputDir].
     *
     * Performs a two-pass compilation:
     *  1. Pre-pass — builds [ModuleRegistry] from files that declare a MODULE
     *  2. Main pass — lexes, parses, type-checks, and emits each file
     *
     * Never throws; all errors are captured in [CompileResult.errors].
     */
    fun compile(
        sourceFiles: List<File>,
        outputDir: File,
        checkOnly: Boolean = false,
        verbose: Boolean = false,
    ): CompileResult {
        if (sourceFiles.isEmpty()) return CompileResult(emptyList(), emptyList(), emptyList())

        outputDir.mkdirs()

        val registry = buildModuleRegistry(sourceFiles)
        val errors   = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val outFiles = mutableListOf<File>()

        for (file in sourceFiles) {
            val rawText = file.readText()
            val src = if (rawText.startsWith("#!")) rawText.substringAfter('\n') else rawText
            val lines = src.lines()

            val lexer = Lexer(src, file.name)
            val tokens = try {
                lexer.tokenize()
            } catch (e: LexException) {
                errors += e.message ?: "lex error in ${file.name}"
                continue
            }
            // Surface comment-tag diagnostics (TODO/FIXME/HACK/XXX) collected during lexing.
            warnings += lexer.diagnostics.warnings.map(Diagnostic::render)
            warnings += lexer.diagnostics.infos.map(Diagnostic::render)

            val program = try {
                Parser(tokens, file.name).parseProgram()
            } catch (e: ParseException) {
                errors += e.message ?: "parse error in ${file.name}"
                continue
            }

            registry.clearAliases()
            val checker = TypeChecker(lines, registry)
            checker.analyze(program)

            warnings += checker.diagnostics.warnings.map(Diagnostic::render)

            if (checker.diagnostics.hasErrors) {
                errors += checker.diagnostics.errors.map(Diagnostic::render)
                continue
            }

            if (checkOnly) {
                if (verbose) println("kobol: ${file.name} — OK (check-only)")
                continue
            }

            val emitter = AsmEmitter(checker, registry)
            val className = file.nameWithoutExtension.split("-")
                .joinToString("") { it.lowercase().replaceFirstChar { c -> c.uppercase() } }
            val files = emitter.emitToDir(program, outputDir, className)
            outFiles += files
            if (verbose) files.forEach { println("kobol: ${file.name} → ${it.name}") }
        }

        return CompileResult(errors, warnings, outFiles)
    }

    private fun buildModuleRegistry(files: List<File>): ModuleRegistry {
        val registry = ModuleRegistry()
        for (file in files) {
            val rawText = file.readText()
            val src = if (rawText.startsWith("#!")) rawText.substringAfter('\n') else rawText
            val program = try {
                val tokens = Lexer(src, file.name).tokenize()
                Parser(tokens, file.name).parseProgram()
            } catch (_: LexException)  { continue }
              catch (_: ParseException) { continue }

            val modDecl = program.moduleDecl ?: continue
            val exportedNames = modDecl.exports.map { it.name }.toSet()
            val jvmClassName = file.nameWithoutExtension.split("-")
                .joinToString("") { it.lowercase().replaceFirstChar { c -> c.uppercase() } }

            val procedures = mutableMapOf<String, ModuleRegistry.ModuleProcedure>()
            for (proc in program.procedures) {
                if (!proc.exported && proc.name !in exportedNames) continue
                val params = proc.params.map { p -> Symbol.ProcedureSymbol.Param(p.name, astTypeToKobol(p.type)) }
                procedures[proc.name.uppercase()] = ModuleRegistry.ModuleProcedure(params, proc.returnType?.let { astTypeToKobol(it) }, proc.isAsync)
            }

            val records = mutableMapOf<String, Symbol.RecordSymbol>()
            for (rec in program.records) {
                if (rec.name !in exportedNames) continue
                val fields = rec.fields.associateTo(LinkedHashMap()) { f -> f.name to astTypeToKobol(f.type) }
                records[rec.name.uppercase()] = Symbol.RecordSymbol(rec.name, fields, emptyMap(), rec.pos)
            }

            val variants = mutableMapOf<String, Symbol.VariantSymbol>()
            for (v in program.variants) {
                if (v.name !in exportedNames) continue
                val cases = v.cases.map { c ->
                    val cf = c.fields.associateTo(LinkedHashMap()) { f -> f.name to astTypeToKobol(f.type) }
                    Symbol.VariantSymbol.CaseInfo(c.name, cf)
                }
                variants[v.name.uppercase()] = Symbol.VariantSymbol(v.name, cases, v.pos)
            }

            registry.register(ModuleRegistry.ModuleInfo(modDecl.name, modDecl.version, jvmClassName, procedures, records, variants))
        }
        return registry
    }

    private fun astTypeToKobol(spec: dev.kobol.parser.ast.TypeSpec): KobolType = when (spec) {
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
        is dev.kobol.parser.ast.TypeSpec.ListOf       -> KobolType.ListType(astTypeToKobol(spec.elementType))
        is dev.kobol.parser.ast.TypeSpec.MapOf        -> KobolType.MapType(astTypeToKobol(spec.keyType), astTypeToKobol(spec.valueType))
        is dev.kobol.parser.ast.TypeSpec.FutureOf     -> KobolType.FutureType(astTypeToKobol(spec.elementType))
        is dev.kobol.parser.ast.TypeSpec.NamedType    -> KobolType.RecordRefType(spec.name)
    }
}
