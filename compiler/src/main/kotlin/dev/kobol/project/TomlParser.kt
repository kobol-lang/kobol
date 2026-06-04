package dev.kobol.project

import java.io.File

/**
 * Minimal TOML parser for `kobol.toml`.
 *
 * Handles the subset of TOML used by Kobol project files:
 *  - Section headers: [project], [build], [dependencies], [repositories], [server]
 *  - Key = "string value"
 *  - Key = integer
 *  - Key = true | false
 *  - Comments: # ...
 */
object TomlParser {

    fun parse(file: File): ProjectDescriptor = parse(file.readText())

    fun parse(content: String): ProjectDescriptor {
        val sections = mutableMapOf<String, MutableMap<String, String>>()
        var currentSection = "global"
        sections[currentSection] = mutableMapOf()

        for (rawLine in content.lines()) {
            val line = rawLine.trim().let { l ->
                val commentIdx = l.indexOf('#')
                if (commentIdx >= 0) l.substring(0, commentIdx).trim() else l
            }
            if (line.isBlank()) continue

            if (line.startsWith('[') && line.endsWith(']')) {
                currentSection = line.substring(1, line.length - 1).trim()
                sections.getOrPut(currentSection) { mutableMapOf() }
                continue
            }

            val eqIdx = line.indexOf('=')
            if (eqIdx < 0) continue
            val rawKey   = line.substring(0, eqIdx).trim()
            val rawValue = line.substring(eqIdx + 1).trim()
            // Strip surrounding quotes if present
            val key   = rawKey.trim('"')
            val value = rawValue.trim('"')
            sections[currentSection]!![key] = value
        }

        val project  = sections["project"]       ?: emptyMap()
        val build    = sections["build"]         ?: emptyMap()
        val deps     = sections["dependencies"]  ?: emptyMap()
        val repos    = sections["repositories"]  ?: emptyMap()
        val server   = sections["server"]        ?: emptyMap()

        return ProjectDescriptor(
            name        = project["name"]        ?: "unnamed",
            version     = project["version"]     ?: "0.1.0",
            description = project["description"] ?: "",
            main        = project["main"]        ?: "Main",
            sourceDir   = build["source-dir"]    ?: "src/main",
            testDir     = build["test-dir"]      ?: "src/test",
            outputDir   = build["output-dir"]    ?: "build",
            javaTarget  = build["java-target"]   ?: "21",
            fatJar      = build["fat-jar"]?.lowercase() != "false",
            library     = build["library"]?.lowercase() == "true",
            dependencies = deps.toMap(),
            repositories = repos.toMap(),
            serverPort  = server["port"]?.toIntOrNull() ?: 8080,
        )
    }
}
