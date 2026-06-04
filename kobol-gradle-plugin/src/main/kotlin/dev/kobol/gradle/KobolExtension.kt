package dev.kobol.gradle

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property

/**
 * DSL extension for the `dev.kobol` Gradle plugin.
 *
 * ```kotlin
 * kobol {
 *     version      = "0.1.0"          // kobol runtime version; defaults to bundled version
 *     mainSourceDir = file("src/kbl") // override default src/main/kobol
 *     verbose      = true
 * }
 * ```
 */
abstract class KobolExtension {

    /** Kobol runtime version to add as an `implementation` dependency. */
    abstract val version: Property<String>

    /** Source directory for main Kobol files. Default: `src/main/kobol`. */
    abstract val mainSourceDir: DirectoryProperty

    /** Source directory for test Kobol files. Default: `src/test/kobol`. */
    abstract val testSourceDir: DirectoryProperty

    /** Emit verbose compiler output (file names, timing). Default: false. */
    abstract val verbose: Property<Boolean>
}
