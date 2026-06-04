package dev.kobol.gradle

import dev.kobol.VERSION
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer

/**
 * Kobol Gradle Plugin (`dev.kobol`).
 *
 * Applying this plugin to a JVM project:
 *  - Registers `compileKobol` and `compileTestKobol` tasks.
 *  - Wires compiled Kobol bytecode into the main and test classpaths.
 *  - Adds `dev.kobol:runtime:<version>` as an `implementation` dependency.
 *
 * Usage in `build.gradle.kts`:
 * ```kotlin
 * plugins {
 *     id("dev.kobol") version "0.1.0"
 * }
 *
 * kobol {
 *     version = "0.1.0"  // optional — defaults to the plugin's bundled version
 * }
 * ```
 *
 * Source layout (conventional, overridable via the `kobol { }` extension):
 * ```
 * src/
 *   main/kobol/   ← .kbl source files
 *   test/kobol/   ← .kbl test files
 * ```
 */
class KobolPlugin : Plugin<Project> {

    companion object {
        const val TASK_GROUP = "kobol"
    }

    override fun apply(project: Project) {
        project.plugins.apply(JavaPlugin::class.java)

        val extension = project.extensions.create("kobol", KobolExtension::class.java).apply {
            version.convention(VERSION)
            mainSourceDir.convention(project.layout.projectDirectory.dir("src/main/kobol"))
            testSourceDir.convention(project.layout.projectDirectory.dir("src/test/kobol"))
            verbose.convention(false)
        }

        // Output dirs.  KobolCompiler writes class files directly to these paths.
        val mainOut = project.layout.buildDirectory.dir("kobol/main/classes")
        val testOut = project.layout.buildDirectory.dir("kobol/test/classes")

        val compileKobol = project.tasks.register("compileKobol", KobolCompileTask::class.java) { task ->
            task.group       = TASK_GROUP
            task.description = "Compile Kobol (.kbl) main source files to JVM bytecode"
            task.sourceDir.set(extension.mainSourceDir)
            task.outputDir.set(mainOut)
            task.verbose.set(extension.verbose)
            task.projectName.set(project.name)
            task.projectVersion.set(project.provider { project.version.toString() })
        }

        val compileTestKobol = project.tasks.register("compileTestKobol", KobolCompileTask::class.java) { task ->
            task.group       = TASK_GROUP
            task.description = "Compile Kobol (.kbl) test source files to JVM bytecode"
            task.sourceDir.set(extension.testSourceDir)
            task.outputDir.set(testOut)
            task.verbose.set(extension.verbose)
            task.projectName.set(project.name)
            task.projectVersion.set(project.provider { project.version.toString() })
            task.dependsOn(compileKobol)
        }

        // Wire into Java lifecycle so `./gradlew build` picks up Kobol classes.
        project.tasks.named(JavaPlugin.CLASSES_TASK_NAME)      { it.dependsOn(compileKobol) }
        project.tasks.named(JavaPlugin.TEST_CLASSES_TASK_NAME) { it.dependsOn(compileTestKobol) }

        // Add Kobol output directories to the appropriate source set outputs so
        // that the compiled classes end up on the runtime and test classpaths.
        val sourceSets = project.extensions.getByType(SourceSetContainer::class.java)
        sourceSets.named(SourceSet.MAIN_SOURCE_SET_NAME) { it.output.dir(mainOut) }
        sourceSets.named(SourceSet.TEST_SOURCE_SET_NAME) { it.output.dir(testOut) }

        // Add the Kobol runtime as a dependency after the project is evaluated so
        // the user can override `kobol.version` before we resolve it.
        project.afterEvaluate {
            project.dependencies.add(
                JavaPlugin.IMPLEMENTATION_CONFIGURATION_NAME,
                "dev.kobol:runtime:${extension.version.get()}",
            )
        }
    }
}
