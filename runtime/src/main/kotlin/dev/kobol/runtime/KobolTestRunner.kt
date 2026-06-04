package dev.kobol.runtime

import kotlin.system.exitProcess

/**
 * Entry point for running compiled Kobol test classes in a child JVM.
 *
 * `kobol test` (project & single-file) spawns:
 *
 *     java -cp <test-classes>:<main-classes>:<runtime-jar> \
 *          dev.kobol.runtime.KobolTestRunner  Suite1 Suite2 ...
 *
 * Each argument is the fully-qualified name of a generated test class that
 * exposes `public static int runAll()`. We run them sequentially in one JVM —
 * one process for the whole suite, not one per file — and exit with the total
 * failure count (clamped to a shell-safe range).
 *
 * Why a child JVM rather than the old in-process `URLClassLoader`: the `kobol`
 * launcher is a GraalVM native image, which is ahead-of-time compiled and cannot
 * load or execute `.class` bytecode produced after the image was built. A real
 * JVM can, so test bytecode must run there.
 */
object KobolTestRunner {

    /** Exit codes above this collide with shell/`waitFor` conventions, so clamp. */
    private const val MAX_EXIT = 125

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.isEmpty()) {
            System.err.println("KobolTestRunner: no test classes supplied")
            exitProcess(2)
        }

        var totalFailures = 0
        for (className in args) {
            totalFailures += runSuite(className)
        }
        exitProcess(totalFailures.coerceIn(0, MAX_EXIT))
    }

    /** Load [className] from the classpath and invoke its static `runAll(): Int`. */
    private fun runSuite(className: String): Int =
        try {
            val cls = Class.forName(className)
            val runAll = cls.getMethod("runAll")
            runAll.invoke(null) as Int
        } catch (_: NoSuchMethodException) {
            println("kobol: '$className' has no runAll() — no tests generated")
            0
        } catch (e: ClassNotFoundException) {
            System.err.println("kobol: could not load test class '$className': ${e.message}")
            1
        } catch (e: ReflectiveOperationException) {
            // Unwrap InvocationTargetException so the real assertion error surfaces.
            val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
            System.err.println("kobol: error running tests in '$className': ${cause.message}")
            1
        }
}
