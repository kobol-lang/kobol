package dev.kobol.gradle

import dev.kobol.KobolCompiler
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*

/**
 * Compiles all `.kbl` files in [sourceDir] to JVM bytecode under [outputDir].
 *
 * Marked [@CacheableTask] so Gradle can restore outputs from the build cache when
 * inputs (source files + compiler classpath) haven't changed.
 *
 * Registered as `compileKobol` (main sources) and `compileTestKobol` (test sources)
 * by [KobolPlugin].  Supports Gradle's incremental build — the task is skipped when
 * source files and the output directory are both up-to-date.
 */
@CacheableTask
abstract class KobolCompileTask : DefaultTask() {

    /** Root directory containing `.kbl` source files. */
    @get:InputDirectory
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDir: DirectoryProperty

    /** Directory where compiled `.class` files are written. */
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    /** Emit verbose compiler output (file names, timing). */
    @get:Input
    abstract val verbose: Property<Boolean>

    /** Project name — captured at configuration time to avoid project access in task action. */
    @get:Input
    abstract val projectName: Property<String>

    /** Project version string — captured at configuration time. */
    @get:Input
    abstract val projectVersion: Property<String>

    @TaskAction
    fun compile() {
        val src = sourceDir.orNull?.asFile
        val out = outputDir.get().asFile

        if (src == null || !src.exists()) {
            logger.lifecycle("kobol: source dir not configured or does not exist — skipping")
            return
        }

        val kblFiles = src.walkTopDown()
            .filter { it.isFile && it.extension == "kbl" }
            .toList()

        if (kblFiles.isEmpty()) {
            logger.lifecycle("kobol: no .kbl files in $src — skipping")
            return
        }

        logger.lifecycle("kobol: compiling ${kblFiles.size} file(s) from $src → $out")

        val result = KobolCompiler.compile(
            sourceFiles = kblFiles,
            outputDir   = out,
            checkOnly   = false,
            verbose     = verbose.get(),
        )

        result.warnings.forEach { logger.warn(it) }

        if (!result.success) {
            result.errors.forEach { logger.error(it) }
            throw GradleException("Kobol compilation failed: ${result.errors.size} error(s)")
        }

        logger.lifecycle("kobol: compiled ${result.outputFiles.size} class file(s)")
    }
}
