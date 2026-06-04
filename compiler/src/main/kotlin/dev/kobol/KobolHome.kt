package dev.kobol

import java.io.File

/**
 * Resolves the classpath that a *child* JVM needs to run a compiled Kobol program.
 *
 * A compiled program references `dev.kobol.runtime.*` and `dev.kobol.stdlib.*`
 * (plus their transitive deps: kotlin-stdlib, slf4j, javalin). Those classes live
 * in the kobol distribution, NOT in the user's `build/` output. `kobol run` spawns
 * `java -cp <build> + <this> MainClass`, so this list must point at jar(s) carrying
 * the runtime + stdlib.
 *
 * Two distribution shapes, two resolution strategies:
 *
 *  - **Fat-jar / JVM mode** (`installLocal` wrapper, `java -jar kobolc.jar`):
 *    our own `java.class.path` already carries runtime + stdlib. Reuse it.
 *
 *  - **Native image** (Homebrew, Chocolatey, release tarball): runtime + stdlib are
 *    baked into the native binary and are invisible to a child `java`. The binary
 *    therefore ships a `lib/kobolc.jar` next to it; we locate it via the executable
 *    path. `java.class.path` here is the binary itself and is useless as a classpath.
 */
object KobolHome {

    /** True when running as a GraalVM native image (set by the GraalVM runtime). */
    private val isNativeImage: Boolean
        get() = System.getProperty("org.graalvm.nativeimage.imagecode") != null

    /**
     * Classpath entries carrying `dev.kobol.runtime` + `dev.kobol.stdlib`.
     * Empty only when a native binary cannot locate its bundled `lib/` — callers
     * should surface [missingRuntimeMessage] rather than spawn a doomed JVM.
     */
    fun runtimeClasspath(): List<String> {
        // 1. Explicit override always wins (packagers / unusual layouts).
        System.getenv("KOBOL_HOME")?.let { home ->
            jarsIn(File(home, "lib"))?.let { return it }
        }

        // 2. Native binary: find the jar shipped alongside the executable.
        if (isNativeImage) {
            return executableDir()?.let { binDir ->
                // tarball layout: <root>/kobol + <root>/lib/*.jar
                jarsIn(File(binDir, "lib"))
                // Homebrew/libexec layout: <prefix>/bin/kobol + <prefix>/lib/*.jar
                    ?: jarsIn(File(binDir.parentFile, "lib"))
            } ?: emptyList()
        }

        // 3. Fat-jar / classes mode: our own classpath already has everything.
        return System.getProperty("java.class.path")
            .split(File.pathSeparator)
            .filter { it.isNotBlank() }
    }

    /** Human-readable diagnostic for the native-binary-without-runtime-jar case. */
    val missingRuntimeMessage: String
        get() = buildString {
            appendLine("kobol: cannot locate the Kobol runtime library (kobolc.jar).")
            appendLine("       The compiled program needs dev.kobol.runtime / dev.kobol.stdlib on its classpath.")
            appendLine("       Reinstall kobol, or set KOBOL_HOME to a directory containing lib/kobolc.jar.")
        }.trimEnd()

    /**
     * Directory holding the running native executable, or null if undeterminable.
     * Canonicalized so a symlinked launcher (e.g. Homebrew's `bin/kobol` →
     * `libexec/kobol`) resolves to the real install dir where `lib/` lives.
     */
    private fun executableDir(): File? = runCatching {
        ProcessHandle.current().info().command()
            .map { File(it).canonicalFile.parentFile }
            .orElse(null)
    }.getOrNull()

    /** Absolute paths of every `*.jar` in [dir], or null if [dir] holds none. */
    private fun jarsIn(dir: File): List<String>? =
        dir.takeIf { it.isDirectory }
            ?.listFiles { f -> f.isFile && f.extension == "jar" }
            ?.takeIf { it.isNotEmpty() }
            ?.map { it.absolutePath }
}
