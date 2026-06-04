package dev.kobol.runtime

import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

/**
 * Runtime helper for CONCURRENT blocks.
 *
 * Uses Java 21 virtual threads (Project Loom) for true M:N concurrency.
 * Each branch runs in its own virtual thread — lightweight and GC-managed.
 */
object KobolConcurrent {

    /**
     * Run all [tasks] concurrently on virtual threads.
     * Waits for ALL branches to complete before returning.
     * If any branch throws, the first exception is re-raised after all threads finish.
     *
     * Used for:  CONCURRENT: ... WAIT ALL
     */
    @JvmStatic
    fun runAll(tasks: Array<Runnable>) {
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        val futures  = tasks.map { task -> executor.submit(task) }
        var firstError: Throwable? = null
        for (f in futures) {
            try {
                f.get()
            } catch (e: ExecutionException) {
                if (firstError == null) firstError = e.cause ?: e
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                if (firstError == null) firstError = e
            }
        }
        executor.shutdown()
        firstError?.let { throw RuntimeException("CONCURRENT branch failed", it) }
    }

    /**
     * Run all [tasks] concurrently; cancel all remaining on the FIRST failure.
     *
     * Used for:  CONCURRENT SCOPE "...": ... WAIT ALL OR FAIL
     */
    @JvmStatic
    fun runAllFailFast(tasks: Array<Runnable>) {
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        val futures  = tasks.map { task -> executor.submit(task) }
        try {
            for (f in futures) f.get()  // throws on first failure
        } catch (e: ExecutionException) {
            futures.forEach { it.cancel(true) }
            executor.shutdownNow()
            throw RuntimeException("CONCURRENT branch failed (fail-fast)", e.cause ?: e)
        } catch (e: InterruptedException) {
            futures.forEach { it.cancel(true) }
            executor.shutdownNow()
            Thread.currentThread().interrupt()
            throw RuntimeException("CONCURRENT interrupted", e)
        } finally {
            executor.shutdown()
        }
    }

    /**
     * Start a single virtual thread for an ASYNC PROCEDURE invocation.
     * The runnable is expected to complete a CompletableFuture internally.
     */
    @JvmStatic
    fun startVirtual(task: Runnable) {
        Thread.ofVirtual().start(task)
    }
}
