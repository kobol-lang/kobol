package dev.kobol.project

import java.io.File

/**
 * Data model for `kobol.toml` — the user-facing project descriptor.
 *
 * Modelled on Cargo.toml: one file describes the entire project.
 */
data class ProjectDescriptor(
    // [project]
    val name: String,
    val version: String    = "0.1.0",
    val description: String = "",
    val main: String       = "Main",

    // [build]
    val sourceDir: String  = "src/main",
    val testDir: String    = "src/test",
    val outputDir: String  = "build",
    val javaTarget: String = "21",
    val fatJar: Boolean    = true,
    /** When true: build a thin (library) jar instead of a fat (standalone) jar.
     *  Enables `kobol publish` to push the artifact to a Maven repository. */
    val library: Boolean   = false,

    // [dependencies]  key = Maven coordinate, value = version
    val dependencies: Map<String, String> = emptyMap(),

    // [repositories]  key = name, value = URL
    val repositories: Map<String, String> = emptyMap(),

    // [server]
    val serverPort: Int    = 8080,
) {
    val sourceFile: File   get() = File(sourceDir)
    val outputDirFile: File get() = File(outputDir)
    val classesDir: File   get() = File("$outputDir/classes")
    val libsDir: File      get() = File("$outputDir/libs")
}
