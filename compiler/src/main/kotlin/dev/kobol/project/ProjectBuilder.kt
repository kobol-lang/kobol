package dev.kobol.project

import dev.kobol.CompilerOptions
import dev.kobol.compileFile
import dev.kobol.lexer.LexException
import dev.kobol.lexer.Lexer
import dev.kobol.parser.ParseException
import dev.kobol.parser.Parser
import dev.kobol.semantic.KobolType
import dev.kobol.semantic.ModuleRegistry
import dev.kobol.semantic.Symbol
import java.io.File
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import java.util.zip.ZipEntry

/**
 * Compiles a Kobol project described by a [ProjectDescriptor].
 *
 * Responsibilities:
 *  1. Discover all .kbl source files under [ProjectDescriptor.sourceDir]
 *  2. Compile each file into [ProjectDescriptor.classesDir]
 *  3. Optionally assemble a fat-jar into [ProjectDescriptor.libsDir]
 *
 * Dependency resolution (Maven coordinates in kobol.toml) is delegated to
 * Gradle when a `build.gradle.kts` is present.  Without Gradle the classpath
 * is assembled from local `.jar` files under `lib/` and `build/libs/` only.
 */
object ProjectBuilder {

    /** Build the project; returns true on success. */
    fun build(descriptor: ProjectDescriptor, checkOnly: Boolean = false): Boolean {
        val sourceDir = File(descriptor.sourceDir)
        if (!sourceDir.exists()) {
            System.err.println("kobol: source directory '${descriptor.sourceDir}' not found")
            return false
        }

        // Resolve Maven dependencies declared in kobol.toml before compiling
        resolveDependencies(descriptor)

        val kblFiles = sourceDir.walkTopDown()
            .filter { it.isFile && it.extension == "kbl" }
            .toList()

        if (kblFiles.isEmpty()) {
            println("kobol: no .kbl files found in '${descriptor.sourceDir}'")
            return true
        }

        descriptor.classesDir.mkdirs()

        // Pre-pass: parse all source files and build a ModuleRegistry so that
        // cross-module PERFORM calls can be resolved during compilation.
        val registry = buildModuleRegistry(kblFiles)

        var success = true
        for (file in kblFiles) {
            val options = CompilerOptions(
                sourceFile = file,
                outputDir  = descriptor.classesDir,
                checkOnly  = checkOnly,
                verbose    = false,
            )
            val ok = compileFile(file, options, registry)
            if (!ok) {
                success = false
                System.err.println("kobol: compilation failed for ${file.name}")
            }
        }

        if (success && !checkOnly) {
            when {
                descriptor.library -> assembleLibraryJar(descriptor)
                descriptor.fatJar  -> assembleFatJar(descriptor)
            }
        }

        return success
    }

    /**
     * Parse every source file and, for those that declare a MODULE with EXPORTs,
     * register the exported procedure signatures in a shared [ModuleRegistry].
     */
    private fun buildModuleRegistry(files: List<File>): ModuleRegistry {
        val registry = ModuleRegistry()
        for (file in files) {
            val rawText = file.readText()
            val src = if (rawText.startsWith("#!")) rawText.substringAfter('\n') else rawText
            val program = try {
                val tokens = Lexer(src, file.name).tokenize()
                Parser(tokens, file.name).parseProgram()
            } catch (e: LexException) {
                System.err.println("kobol: warning: skipping '${file.name}' from module registry (lex error): ${e.message}")
                continue
            } catch (e: ParseException) {
                System.err.println("kobol: warning: skipping '${file.name}' from module registry (parse error): ${e.message}")
                continue
            }

            val modDecl = program.moduleDecl ?: continue
            val exportedNames = modDecl.exports.map { it.name }.toSet()

            // Derive the JVM class name from PROGRAM name (same logic as AsmEmitter.javaClass)
            val jvmClassName = program.name.split("-").joinToString("") { part ->
                val hasUpper = part.any { it.isUpperCase() }
                val hasLower = part.any { it.isLowerCase() }
                if (hasUpper && hasLower) part.replaceFirstChar { c -> c.uppercase() }
                else part.lowercase().replaceFirstChar { c -> c.uppercase() }
            }

            // Collect exported procedure signatures (best-effort: types parsed from AST)
            val procedures = mutableMapOf<String, ModuleRegistry.ModuleProcedure>()
            for (proc in program.procedures) {
                if (!proc.exported && proc.name !in exportedNames) continue
                val params = proc.params.map { p ->
                    Symbol.ProcedureSymbol.Param(p.name, astTypeToKobol(p.type))
                }
                val returnType = proc.returnType?.let { astTypeToKobol(it) }
                procedures[proc.name.uppercase()] = ModuleRegistry.ModuleProcedure(params, returnType)
            }

            // Collect exported record types
            val records = mutableMapOf<String, Symbol.RecordSymbol>()
            for (rec in program.records) {
                if (rec.name !in exportedNames) continue
                val fields = rec.fields.associateTo(LinkedHashMap()) { f -> f.name to astTypeToKobol(f.type) }
                records[rec.name.uppercase()] = Symbol.RecordSymbol(rec.name, fields, emptyMap(), rec.pos)
            }

            // Collect exported variant types
            val variants = mutableMapOf<String, Symbol.VariantSymbol>()
            for (v in program.variants) {
                if (v.name !in exportedNames) continue
                val cases = v.cases.map { c ->
                    val caseFields = c.fields.associateTo(LinkedHashMap()) { f -> f.name to astTypeToKobol(f.type) }
                    Symbol.VariantSymbol.CaseInfo(c.name, caseFields)
                }
                variants[v.name.uppercase()] = Symbol.VariantSymbol(v.name, cases, v.pos)
            }

            registry.register(ModuleRegistry.ModuleInfo(
                moduleName   = modDecl.name,
                version      = modDecl.version,
                jvmClassName = jvmClassName,
                procedures   = procedures,
                records      = records,
                variants     = variants,
            ))
        }
        return registry
    }

    /**
     * Convert an AST [TypeSpec] to a [KobolType] without a full TypeChecker context.
     * Only handles primitive / common types — record references become [KobolType.RecordRefType].
     */
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

    /** Run the compiled program by invoking its main class. */
    fun run(descriptor: ProjectDescriptor, extraArgs: List<String> = emptyList()): Int {
        val mainClass = descriptor.name.split("-").joinToString("") {
            it.lowercase().replaceFirstChar { c -> c.uppercase() }
        }
        // Classpath: compiled classes + kobol runtime/stdlib jar(s) + project lib/*.jar
        val classPaths = mutableListOf(descriptor.classesDir.absolutePath)
        val runtimeCp = dev.kobol.KobolHome.runtimeClasspath()
        if (runtimeCp.isEmpty()) { System.err.println(dev.kobol.KobolHome.missingRuntimeMessage); return 1 }
        classPaths.addAll(runtimeCp)
        File("lib").walkTopDown()
            .filter { it.isFile && it.extension == "jar" }
            .mapTo(classPaths) { it.absolutePath }
        val javaArgs = mutableListOf("java", "-cp", classPaths.joinToString(File.pathSeparator), mainClass) + extraArgs
        val process = ProcessBuilder(javaArgs)
            .inheritIO()
            .start()
        return process.waitFor()
    }

    /**
     * Resolve Maven dependencies declared in [descriptor].dependencies.
     *
     * Resolution order:
     *   1. Already present in `lib/` → skip.
     *   2. Cached in `~/.kobol/cache/` → copy to `lib/`.
     *   3. Download from Maven Central → cache → copy to `lib/`.
     *
     * URL pattern: https://repo1.maven.org/maven2/{group}/{artifact}/{version}/{artifact}-{version}.jar
     */
    private fun resolveDependencies(descriptor: ProjectDescriptor) {
        if (descriptor.dependencies.isEmpty()) return
        val cacheDir = File(System.getProperty("user.home"), ".kobol/cache")
        val libDir   = File("lib")
        cacheDir.mkdirs()
        libDir.mkdirs()

        for ((coord, version) in descriptor.dependencies) {
            val parts = coord.split(":")
            if (parts.size < 2) { System.err.println("kobol: invalid dependency '$coord' — expected group:artifact"); continue }
            val group    = parts[0]
            val artifact = parts[1]
            val jarName  = "$artifact-$version.jar"
            val inLib    = File(libDir, jarName)

            if (inLib.exists()) continue   // already resolved

            val cached = File(cacheDir, jarName)
            if (!cached.exists()) {
                val groupPath = group.replace('.', '/')
                val url = "https://repo1.maven.org/maven2/$groupPath/$artifact/$version/$jarName"
                print("kobol: downloading $coord:$version ...")
                System.out.flush()
                try {
                    java.net.URI(url).toURL().openStream().use { input ->
                        cached.outputStream().use { output -> input.copyTo(output) }
                    }
                    println(" done")
                } catch (e: Exception) {
                    println()
                    System.err.println("kobol: failed to download $coord:$version from $url — ${e.message}")
                    System.err.println("kobol: place $jarName manually in lib/ to proceed")
                    continue
                }
            }

            cached.copyTo(inLib, overwrite = false)
            println("kobol: resolved $coord:$version → lib/$jarName")
        }
    }

    /**
     * Compile and run all test files found in [ProjectDescriptor.testDir].
     * Returns the total number of test failures (0 means all passed).
     */
    fun test(descriptor: ProjectDescriptor): Int {
        val testSourceDir = File(descriptor.testDir)
        if (!testSourceDir.exists()) {
            println("kobol: no test directory '${descriptor.testDir}' found — skipping tests")
            return 0
        }

        val testFiles = testSourceDir.walkTopDown()
            .filter { it.isFile && it.extension == "kbl" }
            .toList()

        if (testFiles.isEmpty()) {
            println("kobol: no .kbl test files found in '${descriptor.testDir}'")
            return 0
        }

        val testClassesDir = File("${descriptor.outputDir}/test-classes")
        testClassesDir.mkdirs()

        val registry = buildModuleRegistry(testFiles)

        var compileFailures = 0
        val suites = mutableListOf<String>()
        for (file in testFiles) {
            val options = CompilerOptions(
                sourceFile = file,
                outputDir  = testClassesDir,
                checkOnly  = false,
                verbose    = false,
            )
            val ok = compileFile(file, options, registry)
            if (!ok) {
                System.err.println("kobol: test compilation failed for '${file.name}'")
                compileFailures++
                continue
            }
            suites.add(classNameForFile(file))
        }
        // Run every suite in a single child JVM — one process for the whole run,
        // not one per file — then add any compile failures to the tally.
        return compileFailures + runTestSuites(suites, listOf(testClassesDir, descriptor.classesDir))
    }

    /**
     * Run the test suite generated from a single compiled [sourceFile].
     * [mainClassesDir] is added to the classpath so test code can call production procedures.
     */
    fun runTestFile(sourceFile: File, testClassesDir: File, mainClassesDir: File? = null): Int =
        runTestSuites(listOf(classNameForFile(sourceFile)), listOfNotNull(testClassesDir, mainClassesDir))

    /**
     * Derive the generated JVM class name for [sourceFile]. Mirrors the scheme in
     * [compileFile], which names the class after the file (not `PROGRAM`), so the
     * name always matches the `.class` emitted to disk.
     */
    private fun classNameForFile(sourceFile: File): String =
        sourceFile.nameWithoutExtension.split("-")
            .joinToString("") { it.lowercase().replaceFirstChar { c -> c.uppercase() } }

    /**
     * Run the given generated test [classNames] in a single child JVM via
     * [dev.kobol.runtime.KobolTestRunner], returning the total failure count.
     *
     * A child JVM (not in-process reflection) is required because the `kobol`
     * launcher may be a GraalVM native image, which cannot load/execute `.class`
     * bytecode produced after the image was built. One process runs every suite,
     * so wall-clock is O(JVM startup) once — not once per test file.
     */
    private fun runTestSuites(classNames: List<String>, classDirs: List<File>): Int {
        if (classNames.isEmpty()) return 0

        val runtimeCp = dev.kobol.KobolHome.runtimeClasspath()
        if (runtimeCp.isEmpty()) { System.err.println(dev.kobol.KobolHome.missingRuntimeMessage); return classNames.size }

        val cp = (classDirs.map { it.absolutePath } + runtimeCp).joinToString(File.pathSeparator)
        val javaArgs = listOf("java", "-cp", cp, "dev.kobol.runtime.KobolTestRunner") + classNames
        return ProcessBuilder(javaArgs).inheritIO().start().waitFor()
    }

    /** Scaffold a new Kobol project with `kobol.toml`, src/, and initial source files. */
    fun scaffold(projectName: String, template: String = "default") {
        val dir = File(projectName)
        if (dir.exists()) {
            System.err.println("kobol: directory '$projectName' already exists")
            return
        }
        dir.mkdirs()
        File(dir, "src/main").mkdirs()
        File(dir, "src/test").mkdirs()
        File(dir, "build").mkdirs()

        // kobol.toml
        File(dir, "kobol.toml").writeText("""
            [project]
            name        = "$projectName"
            version     = "0.1.0"
            description = ""
            main        = "Main"

            [build]
            source-dir  = "src/main"
            test-dir    = "src/test"
            output-dir  = "build"
            java-target = "21"
            fat-jar     = true

            [dependencies]
            # "org.postgresql:postgresql" = "42.7.3"
        """.trimIndent())

        // .gitignore
        File(dir, ".gitignore").writeText("build/\n*.class\n")

        when (template) {
            "batch" -> scaffoldBatch(dir, projectName)
            "api"   -> scaffoldApi(dir, projectName)
            "lib", "library" -> scaffoldLibrary(dir, projectName)
            else    -> scaffoldDefault(dir, projectName)
        }

        println("kobol: created project '$projectName'")
        val hint = if (template == "lib" || template == "library")
            "  cd $projectName && kobol build && kobol publish"
        else
            "  cd $projectName && kobol build && kobol run"
        println(hint)
    }

    private fun scaffoldDefault(dir: File, name: String) {
        File(dir, "src/main/Main.kbl").writeText("""
            PROGRAM $name VERSION "0.1.0"

            DATA:
              greeting : TEXT = "World"

            PROCEDURE Main:
              DISPLAY "Hello from $name, {greeting}!"
            END-PROCEDURE
        """.trimIndent())

        File(dir, "src/test/MainTest.kbl").writeText("""
            -- Test suite for $name
            PROGRAM ${name}Test

            PROCEDURE MainTest:
              -- TODO: add assertions
              DISPLAY "Tests passed"
            END-PROCEDURE
        """.trimIndent())
    }

    private fun scaffoldBatch(dir: File, name: String) {
        File(dir, "src/main/Main.kbl").writeText("""
            PROGRAM $name VERSION "0.1.0"

            CONFIG:
              input-file  : TEXT FROM ENV "INPUT_FILE"  REQUIRED
              output-file : TEXT FROM ENV "OUTPUT_FILE" DEFAULT "output.txt"
            END-CONFIG

            DATA:
              record-count : INTEGER = 0

            PROCEDURE Main:
              DISPLAY "Starting batch: {input-file}"
              ADD 1 TO record-count
              DISPLAY "Processed {record-count} record(s)"
              DISPLAY "Output written to {output-file}"
            END-PROCEDURE
        """.trimIndent())
    }

    private fun scaffoldApi(dir: File, name: String) {
        File(dir, "src/main/Main.kbl").writeText("""
            PROGRAM $name VERSION "0.1.0"

            CONFIG:
              port    : INTEGER FROM ENV "PORT"    DEFAULT 8080
              db-url  : TEXT    FROM ENV "DB_URL"  REQUIRED
            END-CONFIG

            PROCEDURE Main:
              LOG INFO "Starting API server on port {port}"
              DISPLAY "Server ready — listening on :{port}"
            END-PROCEDURE
        """.trimIndent())
    }

    private fun scaffoldLibrary(dir: File, name: String) {
        // Library kobol.toml uses library = true, fat-jar = false
        File(dir, "kobol.toml").writeText("""
            [project]
            name        = "$name"
            version     = "0.1.0"
            description = "A Kobol library"

            [build]
            source-dir  = "src/main"
            test-dir    = "src/test"
            output-dir  = "build"
            java-target = "21"
            fat-jar     = false
            library     = true

            [dependencies]
            # "org.postgresql:postgresql" = "42.7.3"
        """.trimIndent())

        val className = name.split("-").joinToString("") { it.lowercase().replaceFirstChar { c -> c.uppercase() } }
        File(dir, "src/main/$className.kbl").writeText("""
            PROGRAM $name VERSION "0.1.0"

            -- EXPORT makes procedures public static — callable from Java/Kotlin and other Kobol programs
            EXPORT PROCEDURE Greet USING name : TEXT RETURNING TEXT:
              RETURN "Hello, {name}!"
            END-PROCEDURE

            EXPORT PROCEDURE Sum USING a : INTEGER, b : INTEGER RETURNING INTEGER:
              RETURN a + b
            END-PROCEDURE
        """.trimIndent())

        File(dir, "src/test/${className}Test.kbl").writeText("""
            PROGRAM ${name}Test

            PROCEDURE ${className}Test:
              LET result : TEXT = Greet("World")
              ASSERT result = "Hello, World!" MESSAGE "Greet failed"
              LET total : INTEGER = Sum(2, 3)
              ASSERT total = 5 MESSAGE "Sum failed"
              DISPLAY "All tests passed"
            END-PROCEDURE
        """.trimIndent())
    }

    // -------------------------------------------------------------------------
    // Fat-jar assembly
    // -------------------------------------------------------------------------

    private fun assembleFatJar(descriptor: ProjectDescriptor) {
        descriptor.libsDir.mkdirs()
        val jarName = "${descriptor.name}-${descriptor.version}.jar"
        val jarFile = File(descriptor.libsDir, jarName)

        val manifest = Manifest()
        manifest.mainAttributes.apply {
            putValue("Manifest-Version", "1.0")
            putValue("Main-Class", descriptor.main.split("-").joinToString("") {
                it.lowercase().replaceFirstChar { c -> c.uppercase() }
            })
        }

        val seen = mutableSetOf<String>()

        fun addEntry(jos: JarOutputStream, path: String, bytes: ByteArray) {
            if (seen.add(path)) {
                jos.putNextEntry(ZipEntry(path))
                jos.write(bytes)
                jos.closeEntry()
            }
        }

        JarOutputStream(jarFile.outputStream(), manifest).use { jos ->
            // 1. Compiled program classes
            descriptor.classesDir.walkTopDown()
                .filter { it.isFile && it.extension == "class" }
                .forEach { classFile ->
                    val path = classFile.relativeTo(descriptor.classesDir).path.replace(File.separatorChar, '/')
                    addEntry(jos, path, classFile.readBytes())
                }

            // 2. Kobol stdlib/runtime (+ their deps) — resolved via KobolHome so this
            //    works whether kobol runs as a fat jar or a native image. (The old
            //    protectionDomain lookup silently yielded the native binary, not a jar,
            //    producing a runtime-less jar that failed at the consumer side.)
            val runtimeCp = dev.kobol.KobolHome.runtimeClasspath()
            if (runtimeCp.isEmpty()) {
                System.err.println("kobol: warning — runtime/stdlib not bundled; the jar will not run standalone.")
                System.err.println(dev.kobol.KobolHome.missingRuntimeMessage)
            }
            for (entry in runtimeCp) bundleClasspathEntry(jos, File(entry), ::addEntry)

            // 3. Resolved dependencies from lib/
            File("lib").walkTopDown()
                .filter { it.isFile && it.extension == "jar" }
                .forEach { depJar -> bundleClasspathEntry(jos, depJar, ::addEntry) }
        }
        println("kobol: assembled ${jarFile.path}")
    }

    /**
     * Copy the contents of a classpath [entry] (a `.jar` or a class directory) into
     * the open [jos]. Skips `MANIFEST.MF` and de-dupes via the supplied [addEntry].
     */
    private fun bundleClasspathEntry(
        jos: JarOutputStream,
        entry: File,
        addEntry: (JarOutputStream, String, ByteArray) -> Unit,
    ) {
        when {
            entry.isFile && entry.extension == "jar" ->
                java.util.jar.JarFile(entry).use { jf ->
                    jf.entries().asSequence()
                        .filter { !it.isDirectory && it.name != "META-INF/MANIFEST.MF" }
                        .forEach { e -> addEntry(jos, e.name, jf.getInputStream(e).readBytes()) }
                }
            entry.isDirectory ->
                entry.walkTopDown()
                    .filter { it.isFile && it.name != "MANIFEST.MF" }
                    .forEach { cf ->
                        val path = cf.relativeTo(entry).path.replace(File.separatorChar, '/')
                        addEntry(jos, path, cf.readBytes())
                    }
        }
    }

    // -------------------------------------------------------------------------
    // Library jar assembly (thin jar — no bundled dependencies)
    // -------------------------------------------------------------------------

    /**
     * Assembles a thin (library) jar containing only the compiled Kobol classes.
     * Dependencies are NOT bundled — consumers declare them in their own build.
     *
     * Output: `build/libs/<name>-<version>.jar`
     */
    private fun assembleLibraryJar(descriptor: ProjectDescriptor) {
        descriptor.libsDir.mkdirs()
        val jarName = "${descriptor.name}-${descriptor.version}.jar"
        val jarFile = File(descriptor.libsDir, jarName)

        val manifest = Manifest()
        manifest.mainAttributes.apply {
            putValue("Manifest-Version", "1.0")
            putValue("Implementation-Title",   descriptor.name)
            putValue("Implementation-Version", descriptor.version)
            putValue("Kobol-Library", "true")
        }

        JarOutputStream(jarFile.outputStream(), manifest).use { jos ->
            descriptor.classesDir.walkTopDown()
                .filter { it.isFile && it.extension == "class" }
                .forEach { classFile ->
                    val entryPath = classFile.relativeTo(descriptor.classesDir).path
                        .replace(File.separatorChar, '/')
                    jos.putNextEntry(ZipEntry(entryPath))
                    jos.write(classFile.readBytes())
                    jos.closeEntry()
                }
        }
        println("kobol: assembled library jar ${jarFile.path}")
    }

    // -------------------------------------------------------------------------
    // Maven publish (local repository)
    // -------------------------------------------------------------------------

    /**
     * Installs the library jar into the local Maven repository
     * (`~/.m2/repository/<groupId>/<artifactId>/<version>/`).
     *
     * Also writes a minimal POM so that downstream Maven/Gradle projects can
     * resolve the artifact without extra configuration.
     *
     * The `groupId` is derived from `kobol.toml` as:
     *  - explicit `group` key under `[project]` if present, OR
     *  - `dev.kobol.<name>` as a safe default.
     */
    fun publishToMavenLocal(descriptor: ProjectDescriptor) {
        if (!descriptor.library) {
            System.err.println("kobol: 'publish' requires 'library = true' in kobol.toml [build]")
            return
        }

        // Build first if the jar doesn't exist yet
        val jarName = "${descriptor.name}-${descriptor.version}.jar"
        val jarFile = File(descriptor.libsDir, jarName)
        if (!jarFile.exists()) {
            println("kobol: no library jar found — building first…")
            if (!build(descriptor)) { System.err.println("kobol: build failed"); return }
        }

        val groupId    = "dev.kobol.libs"       // default group; could be made configurable
        val artifactId = descriptor.name
        val version    = descriptor.version
        val m2Root     = File(System.getProperty("user.home"), ".m2/repository")
        val groupPath  = groupId.replace('.', '/')
        val destDir    = File(m2Root, "$groupPath/$artifactId/$version")
        destDir.mkdirs()

        // Copy jar
        val destJar = File(destDir, "$artifactId-$version.jar")
        jarFile.copyTo(destJar, overwrite = true)

        // Write POM
        val pom = File(destDir, "$artifactId-$version.pom")
        pom.writeText(buildPom(groupId, artifactId, version, descriptor))

        // SHA-1 checksums (Maven repo protocol requirement)
        writeChecksum(destJar,  "$artifactId-$version.jar.sha1",  destDir, "SHA-1")
        writeChecksum(pom,      "$artifactId-$version.pom.sha1",  destDir, "SHA-1")
        writeChecksum(destJar,  "$artifactId-$version.jar.md5",   destDir, "MD5")
        writeChecksum(pom,      "$artifactId-$version.pom.md5",   destDir, "MD5")

        println("kobol: published $groupId:$artifactId:$version → ${destDir.path}")
        println("  To use in a Gradle project:")
        println("    implementation(\"$groupId:$artifactId:$version\")")
        println("  To use in a Maven project:")
        println("    <dependency>")
        println("      <groupId>$groupId</groupId>")
        println("      <artifactId>$artifactId</artifactId>")
        println("      <version>$version</version>")
        println("    </dependency>")
    }

    private fun buildPom(
        groupId: String, artifactId: String, version: String,
        descriptor: ProjectDescriptor,
    ): String = """<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                             http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>$groupId</groupId>
  <artifactId>$artifactId</artifactId>
  <version>$version</version>
  <packaging>jar</packaging>
  <name>${descriptor.name}</name>
  <description>${descriptor.description}</description>
  <dependencies>
    <!-- Kobol runtime — required at runtime by all Kobol libraries -->
    <dependency>
      <groupId>dev.kobol</groupId>
      <artifactId>runtime</artifactId>
      <version>0.1.0</version>
    </dependency>
  </dependencies>
</project>
""".trimIndent()

    private fun writeChecksum(source: File, checksumFileName: String, destDir: File, algorithm: String) {
        val md  = java.security.MessageDigest.getInstance(algorithm)
        val hex = md.digest(source.readBytes()).joinToString("") { "%02x".format(it) }
        File(destDir, checksumFileName).writeText(hex)
    }

    // -------------------------------------------------------------------------
    // Lock-file generation
    // -------------------------------------------------------------------------

    /**
     * Writes (or refreshes) `kobol.lock` in the current working directory.
     *
     * The lock file records each declared Maven coordinate together with the
     * resolved full version and the SHA-256 checksum of the primary jar.
     * On a subsequent `kobol build` the checksums can be verified to guarantee
     * reproducible, offline-safe builds.
     *
     * When [update] is false the command is a no-op if `kobol.lock` already
     * exists and every declared dependency already has an entry — the lock is
     * only refreshed when [update] is true or when new dependencies were added.
     */
    fun lock(descriptor: ProjectDescriptor, update: Boolean = false) {
        val lockFile = File("kobol.lock")

        // Parse existing lock so we only re-resolve what's missing/changed
        val existing = if (lockFile.exists()) parseLock(lockFile.readText()) else emptyMap()

        if (!update && existing.keys.containsAll(descriptor.dependencies.keys)) {
            println("kobol: kobol.lock is up-to-date (use --update to force refresh)")
            return
        }

        val resolved = mutableMapOf<String, LockEntry>()

        for ((coord, version) in descriptor.dependencies) {
            val key = "$coord:$version"
            if (!update && existing.containsKey(coord)) {
                resolved[coord] = existing.getValue(coord)
                continue
            }

            // Attempt to resolve via the local Maven cache first, then central
            val sha256 = resolveChecksum(coord, version)
            resolved[coord] = LockEntry(version = version, sha256 = sha256 ?: "unresolved")
        }

        writeLock(lockFile, resolved)
        println("kobol: wrote kobol.lock  (${resolved.size} entr${if (resolved.size == 1) "y" else "ies"})")
    }

    private data class LockEntry(val version: String, val sha256: String)

    /** Parse the simple TOML-like lock format back into a map. */
    private fun parseLock(content: String): Map<String, LockEntry> {
        val result = mutableMapOf<String, LockEntry>()
        for (line in content.lines()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("\"") && trimmed.contains("=")) {
                val eqIdx = trimmed.indexOf('=')
                val coord = trimmed.substring(1, trimmed.indexOf('"', 1))
                val rest  = trimmed.substring(eqIdx + 1).trim()
                val version = extractTomlField(rest, "version") ?: continue
                val sha256  = extractTomlField(rest, "sha256")  ?: "unresolved"
                result[coord] = LockEntry(version, sha256)
            }
        }
        return result
    }

    private fun extractTomlField(inline: String, field: String): String? {
        val pattern = Regex("""$field\s*=\s*"([^"]+)"""")
        return pattern.find(inline)?.groupValues?.get(1)
    }

    private fun writeLock(file: File, entries: Map<String, LockEntry>) {
        val sb = StringBuilder()
        sb.appendLine("# kobol.lock — generated by 'kobol lock'")
        sb.appendLine("# DO NOT edit manually. Commit this file to source control.")
        sb.appendLine()
        sb.appendLine("[resolved]")
        for ((coord, entry) in entries.entries.sortedBy { it.key }) {
            sb.appendLine(""""$coord" = { version = "${entry.version}", sha256 = "${entry.sha256}" }""")
        }
        file.writeText(sb.toString())
    }

    /**
     * Looks up the SHA-256 of the primary jar for a Maven coordinate.
     *
     * Resolution order:
     *  1. Local Maven repository cache  (~/.m2/repository)
     *  2. Maven Central SHA-1 API (converted to match — we fetch the .sha256 file)
     *
     * Returns null when the artifact cannot be located in either location.
     */
    private fun resolveChecksum(coord: String, version: String): String? {
        val parts = coord.split(":")
        if (parts.size < 2) return null
        val (group, artifact) = parts
        val groupPath = group.replace('.', '/')
        val jarName   = "$artifact-$version.jar"

        // 1. Local Maven cache
        val localRepo = File(System.getProperty("user.home"), ".m2/repository")
        val localJar  = File(localRepo, "$groupPath/$artifact/$version/$jarName")
        if (localJar.exists()) {
            return sha256Hex(localJar.readBytes())
        }

        // 2. Maven Central — fetch pre-computed .sha256 file
        return try {
            val sha256Url = "https://repo1.maven.org/maven2/$groupPath/$artifact/$version/$jarName.sha256"
            val content   = java.net.URI(sha256Url).toURL().readText().trim()
            // The file contains just the hex digest
            if (content.length == 64 && content.all { it.isLetterOrDigit() }) content else null
        } catch (_: Exception) {
            null
        }
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        return md.digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
