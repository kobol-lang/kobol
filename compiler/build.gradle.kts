plugins {
    kotlin("jvm")
    application
    id("org.graalvm.buildtools.native")
}

dependencies {
    implementation(project(":runtime"))
    implementation(project(":stdlib"))

    // JVM bytecode generation (Phase 5-B)
    implementation(libs.asm.core)
    implementation(libs.asm.util)

    // Kotlin @Metadata decode for compile-time interop nullability (challenge F15)
    implementation(libs.kotlin.metadata)

    // LSP server (Phase 7)
    implementation(libs.lsp4j)

    // SLF4J API — emitted by compiled Kobol LOG statements (Phase 10)
    implementation(libs.slf4j.api)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("dev.kobol.MainKt")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(21)
}

tasks.jar {
    archiveFileName.set("kobolc.jar")
    manifest {
        attributes["Main-Class"] = "dev.kobol.MainKt"
    }
    // Fat jar: include all runtime deps
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    // Strip signature files — signed transitive jars (e.g. LSP4J) break fat-jar execution
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/*.EC")
}

// ---------------------------------------------------------------------------
// installLocal — installs kobol as a native-feeling CLI tool
//
//   ./gradlew :compiler:installLocal
//
// Automatically adds ~/.kobol/bin to PATH in the user's shell config so
// `kobol` works in new terminals without any manual steps.
// ---------------------------------------------------------------------------
val installDir = file("${System.getProperty("user.home")}/.kobol")

/** Idempotently appends [line] to [rcFile], creating the file if absent. */
fun appendToShellRc(rcFile: File, marker: String, line: String) {
    val text = if (rcFile.exists()) rcFile.readText() else ""
    if (marker in text) return           // already configured
    rcFile.appendText("\n# Kobol — added by installLocal\n$line\n")
    println("  → Updated ${rcFile.path}")
}

/** Unix: detect shell and patch the right rc file. */
fun patchUnixPath(binDir: File) {
    val home = System.getProperty("user.home")
    val shell = System.getenv("SHELL") ?: ""
    val binPath = binDir.absolutePath
    val marker = binPath  // presence of the path is the idempotency check

    when {
        shell.endsWith("fish") -> {
            // fish uses a different syntax and its own path helper
            val fishConfig = File("$home/.config/fish/config.fish")
            fishConfig.parentFile.mkdirs()
            appendToShellRc(fishConfig, marker, "fish_add_path \"$binPath\"")
        }
        shell.endsWith("zsh") -> {
            // zsh reads .zshrc for interactive shells
            appendToShellRc(File("$home/.zshrc"), marker, "export PATH=\"\$PATH:$binPath\"")
        }
        else -> {
            // bash: prefer .bash_profile on macOS, .bashrc on Linux
            val os = System.getProperty("os.name", "").lowercase()
            val rcFile = if (os.contains("mac")) File("$home/.bash_profile") else File("$home/.bashrc")
            appendToShellRc(rcFile, marker, "export PATH=\"\$PATH:$binPath\"")
        }
    }
}

/** Windows: add binDir to the current user's persistent PATH via PowerShell. */
fun patchWindowsPath(binDir: File) {
    // Keep the real (single-backslash) path and embed it as a PowerShell single-quoted
    // literal so backslashes need no escaping. Compare per-entry (not substring) to avoid
    // both false "already present" and duplicate appends. Built as a single line so the
    // -Command parser never sees a multi-line block (the old here-string version emitted
    // doubled backslashes and crashed with "Unexpected token '}'", so PATH was never set).
    val literal = binDir.absolutePath.replace("'", "''")
    val ps = listOf(
        "\$bin = '$literal'",
        "\$p = [Environment]::GetEnvironmentVariable('PATH','User'); if (-not \$p) { \$p = '' }",
        // if/else MUST stay one statement — `}; else` is a PowerShell parse error.
        "if (\$p.Split(';') -contains \$bin) { Write-Host '  -> Already in User PATH (Windows)' }" +
            " else { [Environment]::SetEnvironmentVariable('PATH', (\$p.TrimEnd(';') + ';' + \$bin).TrimStart(';'), 'User');" +
            " Write-Host '  -> Added to User PATH (Windows)' }",
    ).joinToString("; ")
    val proc = ProcessBuilder("powershell", "-NoProfile", "-Command", ps)
        .redirectErrorStream(true)
        .start()
    print(proc.inputStream.bufferedReader().readText())
    proc.waitFor()
}

tasks.register("installLocal") {
    group = "distribution"
    description = "Installs the kobol CLI into ~/.kobol and configures PATH automatically"
    dependsOn(tasks.jar)

    doLast {
        val libDir = file("$installDir/lib")
        val binDir = file("$installDir/bin")
        libDir.mkdirs()
        binDir.mkdirs()

        // Copy the fat jar
        val jar = tasks.jar.get().archiveFile.get().asFile
        val destJar = file("$libDir/kobolc.jar")
        jar.copyTo(destJar, overwrite = true)

        // Write the Unix wrapper script
        val wrapper = file("$binDir/kobol")
        wrapper.writeText("#!/bin/sh\nexec java \${JAVA_OPTS} -jar \"$destJar\" \"\$@\"\n")
        wrapper.setExecutable(true)

        // Write the Windows batch wrapper
        file("$binDir/kobol.bat").writeText("@echo off\njava %JAVA_OPTS% -jar \"$destJar\" %*\n")

        // Auto-configure PATH
        val os = System.getProperty("os.name", "").lowercase()
        if (os.contains("windows")) patchWindowsPath(binDir) else patchUnixPath(binDir)

        println("""
            |
            |✓ Installed kobol ${project.version} to $installDir
            |  Restart your terminal (or run: source ~/.zshrc) then:
            |
            |    kobol --help
        """.trimMargin())
    }
}

graalvmNative {
    toolchainDetection.set(true)
    binaries {
        named("main") {
            imageName.set("kobol")
            mainClass.set("dev.kobol.MainKt")
            // Hard fail if reflection is missing rather than fall back to JVM
            buildArgs.add("--no-fallback")
            buildArgs.add("--no-server")
            buildArgs.add("-H:+ReportExceptionStackTraces")
            // Kotlin stdlib can be fully initialized at build time
            buildArgs.add("--initialize-at-build-time=kotlin")
            // Enable HTTPS for kobol add / kobol deps
            buildArgs.add("--enable-url-protocols=https,http")
            // Optimise for startup speed rather than peak throughput
            buildArgs.add("-O2")
            // Reflection / resource metadata shipped alongside the source
            buildArgs.add(
                "-H:ReflectionConfigurationFiles=${projectDir}/src/graalvm/reflect-config.json"
            )
            buildArgs.add(
                "-H:ResourceConfigurationFiles=${projectDir}/src/graalvm/resource-config.json"
            )
            buildArgs.add(
                "-H:SerializationConfigurationFiles=${projectDir}/src/graalvm/serialization-config.json"
            )
        }
    }
    metadataRepository {
        enabled.set(true)
    }
}
