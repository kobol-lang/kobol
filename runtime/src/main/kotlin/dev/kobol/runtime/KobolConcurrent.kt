package dev.kobol.runtime

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED

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

/**
 * Bridges a Kotlin `suspend` function call into a [CompletableFuture] so Kobol's existing
 * FUTURE/AWAIT machinery can consume its result (challenge **F15 #6** — Continuation↔FUTURE bridge).
 *
 * A `suspend fun f(): T` has the JVM shape `f(<params…>, Continuation)Object`: the codegen builds
 * one of these, passes it as the trailing continuation, then calls [settle] with the function's
 * direct return value. Two completion paths, both funnelled into [future]:
 *  - the body never actually suspends → the function returns the result directly and [settle]
 *    completes the future with it (the common case for trivial wrappers / already-available data);
 *  - the body suspends → the function returns the `COROUTINE_SUSPENDED` sentinel and the real
 *    result (or failure) arrives later through [resumeWith].
 *
 * Uses only `kotlin-stdlib` coroutine primitives (`Continuation`, `COROUTINE_SUSPENDED`) — no
 * `kotlinx-coroutines` dependency. The empty [context] means a suspending body has no dispatcher;
 * a function that genuinely parks on another thread still resumes correctly, but there is no
 * structured-concurrency scope (cancellation/timeout are not modelled — see the F15 row).
 */
class KobolContinuation : Continuation<Any?> {
    @JvmField
    val future: CompletableFuture<Any?> = CompletableFuture()

    override val context: CoroutineContext get() = EmptyCoroutineContext

    override fun resumeWith(result: Result<Any?>) {
        result.fold(
            onSuccess = { future.complete(it) },
            onFailure = { future.completeExceptionally(it) },
        )
    }

    /**
     * Settle the bridge with the suspend function's DIRECT return [ret] and hand back the future.
     * If [ret] is the `COROUTINE_SUSPENDED` sentinel the body suspended — the result will arrive via
     * [resumeWith], so leave the future pending. Otherwise the body completed synchronously and [ret]
     * IS the result (boxed). The `isDone` guard tolerates a body that resumed inline before returning.
     */
    fun settle(ret: Any?): CompletableFuture<Any?> {
        if (ret !== COROUTINE_SUSPENDED && !future.isDone) future.complete(ret)
        return future
    }
}
