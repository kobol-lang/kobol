package dev.kobol

import dev.kobol.codegen.AsmEmitter
import dev.kobol.codegen.JavaTranspiler
import dev.kobol.lexer.LexException
import dev.kobol.lexer.Lexer
import dev.kobol.parser.ParseException
import dev.kobol.parser.Parser
import dev.kobol.project.ProjectBuilder
import dev.kobol.project.ProjectDescriptor
import dev.kobol.project.TomlParser
import dev.kobol.repl.Repl
import dev.kobol.semantic.TypeChecker
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY

const val VERSION = "0.1.0-dev"

fun main(args: Array<String>) {
    if (args.isEmpty()) { Repl().run(); return }
    when (args[0]) {
        "--version", "-v" -> println("Kobol compiler $VERSION")
        "--help", "-h"    -> printUsage()
        "--repl"          -> Repl().run()
        "--lsp"           -> dev.kobol.lsp.startLspServer()
        "--check"         -> compile(args.drop(1) + "--check")
        // Project-mode commands
        "new"             -> projectNew(args.drop(1))
        "build"           -> projectBuild(args.drop(1), checkOnly = false)
        "run"             -> projectRun(args.drop(1))
        "test"            -> projectTest(args.drop(1))
        "check"           -> projectBuild(args.drop(1), checkOnly = true)
        "clean"           -> projectClean()
        "deps"            -> projectDeps()
        "add"             -> projectAdd(args.drop(1))
        "lock"            -> projectLock(args.drop(1))
        "publish"         -> projectPublish()
        else              -> compile(args.toList())
    }
}

// =============================================================================
//  Project-mode commands
// =============================================================================

private fun loadDescriptor(): ProjectDescriptor? {
    val toml = File("kobol.toml")
    return if (toml.exists()) TomlParser.parse(toml) else null
}

private fun projectNew(args: List<String>) {
    if (args.isEmpty()) { System.err.println("Usage: kobol new <project-name> [--template batch|api]"); return }
    val name     = args[0]
    val template = args.indexOfFirst { it == "--template" }.let { if (it >= 0) args.getOrNull(it + 1) ?: "default" else "default" }
    ProjectBuilder.scaffold(name, template)
}

private fun projectBuild(args: List<String>, checkOnly: Boolean) {
    val descriptor = loadDescriptor() ?: run {
        // Fallback: if a .kbl file is specified, treat as single-file mode
        val file = args.firstOrNull()?.let { File(it) }
        if (file != null && file.exists()) { compile(listOf(file.path) + args.drop(1)); return }
        System.err.println("kobol: no kobol.toml found. Run 'kobol new <name>' to create a project."); return
    }
    val ok = ProjectBuilder.build(descriptor, checkOnly)
    if (ok) {
        val verb = if (checkOnly) "checked" else "built"
        println("kobol: project '${descriptor.name}' $verb successfully")
    } else {
        System.exit(1)
    }
}

private fun projectRun(args: List<String>) {
    val descriptor = loadDescriptor() ?: run {
        // Single-file run fallback
        val file = args.firstOrNull()?.let { File(it) }
        if (file != null && file.exists()) {
            val tmpDir = File(System.getProperty("java.io.tmpdir"), "kobol-run")
            val ok = compileFile(file, CompilerOptions(file, tmpDir))
            if (ok) {
                val mainClass = file.nameWithoutExtension.split("-")
                    .joinToString("") { it.lowercase().replaceFirstChar { c -> c.uppercase() } }
                // Classpath = compiled classes + this CLI's own classpath (carries dev.kobol.runtime).
                val cp = tmpDir.absolutePath + File.pathSeparator + System.getProperty("java.class.path")
                ProcessBuilder("java", "-cp", cp, mainClass)
                    .inheritIO().start().waitFor()
            }
            return
        }
        System.err.println("kobol: no kobol.toml found"); return
    }
    val ok = ProjectBuilder.build(descriptor)
    if (!ok) { System.exit(1); return }
    val exitCode = ProjectBuilder.run(descriptor, args)
    if (exitCode != 0) System.exit(exitCode)
}

private fun projectTest(args: List<String>) {
    val descriptor = loadDescriptor() ?: run {
        // Single-file test fallback
        val file = args.firstOrNull()?.let { File(it) }
        if (file != null && file.exists()) {
            val tmpDir = File(System.getProperty("java.io.tmpdir"), "kobol-test")
            tmpDir.mkdirs()
            val ok = compileFile(file, CompilerOptions(file, tmpDir))
            if (!ok) { System.exit(1); return }
            val failures = ProjectBuilder.runTestFile(file, tmpDir)
            if (failures != 0) System.exit(failures)
            return
        }
        System.err.println("kobol: no kobol.toml found. Run 'kobol new <name>' to create a project."); return
    }
    val buildOk = ProjectBuilder.build(descriptor)
    if (!buildOk) { System.exit(1); return }
    val failures = ProjectBuilder.test(descriptor)
    if (failures != 0) System.exit(failures)
}

private fun projectClean() {
    val descriptor = loadDescriptor()
    val buildDir   = File(descriptor?.outputDir ?: "build")
    if (buildDir.exists()) {
        buildDir.deleteRecursively()
        println("kobol: cleaned '${buildDir.path}'")
    } else {
        println("kobol: nothing to clean")
    }
}

private fun projectDeps() {
    val descriptor = loadDescriptor() ?: run {
        System.err.println("kobol: no kobol.toml found"); return
    }
    if (descriptor.dependencies.isEmpty()) {
        println("kobol: no dependencies declared in kobol.toml")
        return
    }
    println("Dependencies for '${descriptor.name}':")
    descriptor.dependencies.forEach { (coord, version) ->
        println("  $coord:$version")
    }
    println("\nNote: run 'kobol build' to resolve and download dependencies via Gradle.")
}

private fun projectAdd(args: List<String>) {
    if (args.isEmpty()) {
        System.err.println("Usage: kobol add \"group:artifact:version\""); return
    }
    val toml = File("kobol.toml")
    if (!toml.exists()) { System.err.println("kobol: no kobol.toml found"); return }
    val coord   = args[0]
    val parts   = coord.split(":")
    if (parts.size < 3) { System.err.println("kobol: expected group:artifact:version, got '$coord'"); return }
    val mavenCoord = "${parts[0]}:${parts[1]}"
    val version    = parts[2]
    val content = toml.readText()
    val updated = if (content.contains("[dependencies]")) {
        content.replace("[dependencies]", "[dependencies]\n\"$mavenCoord\" = \"$version\"")
    } else {
        "$content\n\n[dependencies]\n\"$mavenCoord\" = \"$version\"\n"
    }
    toml.writeText(updated)
    println("kobol: added $mavenCoord:$version to kobol.toml")
}

private fun projectPublish() {
    val descriptor = loadDescriptor() ?: run {
        System.err.println("kobol: no kobol.toml found"); return
    }
    ProjectBuilder.publishToMavenLocal(descriptor)
}

private fun projectLock(args: List<String>) {
    val descriptor = loadDescriptor() ?: run {
        System.err.println("kobol: no kobol.toml found"); return
    }
    val update = "--update" in args || "-u" in args
    ProjectBuilder.lock(descriptor, update)
}

private fun compile(args: List<String>) {
    val options = parseOptions(args)
    val source  = options.sourceFile ?: run { printUsage(); return }

    if (!source.exists()) {
        System.err.println("kobolc: error: file not found: ${source.path}")
        System.exit(1); return
    }

    if (options.watch) {
        watchAndCompile(source, options)
        return
    }

    compileFile(source, options)
}

/** Single-shot compile; returns true on success. */
internal fun compileFile(
    source: File,
    options: CompilerOptions,
    moduleRegistry: dev.kobol.semantic.ModuleRegistry = dev.kobol.semantic.ModuleRegistry(),
): Boolean {
    // Strip shebang line so .kbl files can start with #!/usr/bin/env kobol
    val rawText = source.readText()
    val src     = if (rawText.startsWith("#!")) rawText.substringAfter('\n') else rawText
    val lines   = src.lines()

    // -- Lex ---------------------------------------------------------------
    val lexer = Lexer(src, source.name)
    val tokens = try {
        lexer.tokenize()
    } catch (e: LexException) {
        System.err.println(e.message); return false
    }
    lexer.diagnostics.printAll()  // comment-tag diagnostics (TODO/FIXME/HACK/XXX)
    if (options.verbose) println("[tokens] ${tokens.size} tokens")

    // -- Parse -------------------------------------------------------------
    val program = try {
        Parser(tokens, source.name).parseProgram()
    } catch (e: ParseException) {
        System.err.println(e.message); return false
    }
    if (options.verbose) println("[ast] program=${program.name}, procedures=${program.procedures.size}")

    // -- Semantic analysis -------------------------------------------------
    moduleRegistry.clearAliases()  // reset per-program alias bindings
    val checker = TypeChecker(lines, moduleRegistry)
    checker.analyze(program)
    if (checker.diagnostics.hasErrors) {
        checker.diagnostics.printAll(); return false
    }
    checker.diagnostics.printAll()  // warnings

    if (options.checkOnly) { println("kobolc: ${source.name} — OK"); return true }

    // -- Feature detection (dependency manifest) ---------------------------
    val features = FeatureDetector.detect(program)
    if (options.showDeps) features.printSummary(program.name)

    // -- Code generation ---------------------------------------------------
    val javaClass = (options.sourceFile?.nameWithoutExtension ?: program.name)
        .split("-").joinToString("") { it.lowercase().replaceFirstChar { c -> c.uppercase() } }
    val outDir = options.outputDir
    outDir.mkdirs()

    if (options.javaSource) {
        val javaSource = JavaTranspiler(checker, moduleRegistry).transpile(program)
        val javaFile = File(outDir, "$javaClass.java")
        javaFile.writeText(javaSource)
        println("kobolc: generated ${javaFile.path}")
        if (options.verbose) { println("\n── Generated Java Source ──"); println(javaSource) }
    } else {
        val emitter = AsmEmitter(checker, moduleRegistry)
        val files   = emitter.emitToDir(program, outDir, javaClass)
        for (f in files) println("kobolc: compiled to ${f.path}")
        if (options.verbose) println("[codegen] ${files.size} class file(s) emitted")
        // Write the dependency manifest alongside the class files
        val manifest = File(outDir, "kobol-deps.json")
        manifest.writeText(features.toManifestJson(program.name))
        if (options.verbose) println("[deps] manifest written to ${manifest.path}")
    }
    return true
}

/** Watch [source] for changes and recompile on every save (ER-7). */
private fun watchAndCompile(source: File, options: CompilerOptions) {
    val nonWatch = options.copy(watch = false)
    println("kobolc: watching ${source.canonicalPath}  (Ctrl+C to stop)")
    compileFile(source, nonWatch)

    val watchService = FileSystems.getDefault().newWatchService()
    val dir = source.canonicalFile.parentFile.toPath()
    dir.register(watchService, ENTRY_MODIFY)

    while (true) {
        val key = watchService.take()
        for (event in key.pollEvents()) {
            @Suppress("UNCHECKED_CAST")
            val changed = dir.resolve(event.context() as java.nio.file.Path)
            if (changed.fileName.toString() == source.name) {
                // Clear terminal screen
                print("\u001B[2J\u001B[H")
                println("kobolc: ${source.name} changed — recompiling…\n")
                val ok = compileFile(source, nonWatch)
                println(if (ok) "\u001B[32m✓ OK\u001B[0m" else "\u001B[31m✗ FAILED\u001B[0m")
            }
        }
        if (!key.reset()) break
    }
}

private fun printUsage() {
    println("""
        Kobol $VERSION  —  A modern COBOL-inspired language for the JVM

        Project commands (requires kobol.toml in current directory):
          kobol new <name>           Scaffold a new project
          kobol new <name> --template batch|api|lib
          kobol build                Compile all sources
          kobol run                  Build + run the main program
          kobol test                 Build + run all tests in src/test/
          kobol test <file.kbl>      Run tests in a single file
          kobol check                Type-check only (no code gen)
          kobol clean                Remove the build directory
          kobol deps                 List declared dependencies
          kobol add "g:a:version"    Add a Maven dependency to kobol.toml
          kobol lock                 Generate / refresh kobol.lock (SHA-256 checksums)
          kobol publish              Install library jar to ~/.m2 (requires library = true)

        Single-file mode:
          kobol <source.kbl> [options]
          kobol                      Launch interactive REPL
          kobol --repl               Launch REPL explicitly
          kobol --lsp                Launch LSP server on stdin/stdout

        Options (single-file mode):
          --output, -o <dir>   Output directory (default: .)
          --java-source        Emit Java source instead of bytecode
          --check              Type-check only; do not generate code
          --watch              Recompile on file change
          --verbose            Print AST and generated source
          --deps               Analyse and print dependency/jlink manifest
          --version            Print compiler version
          --help               Print this help
    """.trimIndent())
}

data class CompilerOptions(
    val sourceFile: File?,
    val outputDir: File      = File("."),
    val javaSource: Boolean  = false,
    val checkOnly: Boolean   = false,
    val verbose: Boolean     = false,
    val watch: Boolean       = false,
    val showDeps: Boolean    = false,
)

private fun parseOptions(args: List<String>): CompilerOptions {
    var outputDir  = File(".")
    var javaSource = false
    var checkOnly  = false
    var verbose    = false
    var watch      = false
    var showDeps   = false
    var sourceFile: File? = null
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--output", "-o" -> { outputDir = File(args[++i]) }
            "--java-source"  -> { javaSource = true }
            "--check"        -> { checkOnly  = true }
            "--verbose"      -> { verbose    = true }
            "--watch"        -> { watch      = true }
            "--deps"         -> { showDeps   = true }
            else -> {
                if (!args[i].startsWith("-")) sourceFile = File(args[i])
                else System.err.println("kobolc: unknown option: ${args[i]}")
            }
        }
        i++
    }
    return CompilerOptions(sourceFile, outputDir, javaSource, checkOnly, verbose, watch, showDeps)
}
