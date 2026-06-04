package dev.kobol.stdlib

import io.javalin.Javalin
import io.javalin.http.Context
import io.javalin.http.Handler
import io.javalin.http.HandlerType
import io.javalin.router.Endpoint
import java.util.concurrent.CountDownLatch
import java.util.function.Function

/**
 * HTTP server for Kobol REST programs, backed by Javalin 7 (Jetty 12).
 *
 * Kobol syntax:
 *   SERVER AT PORT 8080:
 *     ENDPOINT GET "/hello":
 *       RESPOND WITH "Hello!" AS JSON
 *     ENDPOINT POST "/echo":
 *       RESPOND WITH body AS JSON
 *     ENDPOINT GET '/users/{id}':
 *       RESPOND WITH "user: {id}"
 *   END-SERVER
 *
 * Routes are registered in the Javalin.create { config } block (Javalin 7 requirement).
 * [start] blocks the calling thread until JVM shutdown via CountDownLatch + shutdown hook.
 */
object KobolServer {

    private var app: Javalin? = null

    private data class Route(val method: String, val path: String, val handler: Function<String, String>)
    private val pending = mutableListOf<Route>()

    // Per-request ThreadLocals — populated before each handler invocation
    private val tlBody           = ThreadLocal.withInitial { "" }
    private val tlResponse       = ThreadLocal.withInitial { "" }
    private val tlStatus         = ThreadLocal.withInitial { 200 }
    private val tlPathParams     = ThreadLocal.withInitial<Map<String, String>> { emptyMap() }
    private val tlQueryParams    = ThreadLocal.withInitial<Map<String, List<String>>> { emptyMap() }
    private val tlRequestHeaders = ThreadLocal.withInitial<Map<String, String>> { emptyMap() }

    @JvmStatic fun register(method: String, path: String, handler: Function<String, String>) {
        pending.add(Route(method.uppercase(), path, handler))
    }

    @JvmStatic fun start(port: Int) {
        val routes = pending.toList()

        val javalin = Javalin.create { cfg ->
            cfg.http.defaultContentType = "application/json; charset=utf-8"

            for ((method, path, handler) in routes) {
                val kobolHandler = Handler { ctx: Context ->
                    tlBody.set(ctx.body())
                    tlResponse.set("")
                    tlStatus.set(200)
                    tlPathParams.set(ctx.pathParamMap())
                    tlQueryParams.set(ctx.queryParamMap())
                    tlRequestHeaders.set(ctx.headerMap())
                    val resp = handler.apply(ctx.body())
                    ctx.status(tlStatus.get())
                    ctx.result(resp)
                }
                // Javalin 7: routes via config.routes; typed methods for common verbs
                when (method) {
                    "GET"    -> cfg.routes.get(path,    kobolHandler)
                    "POST"   -> cfg.routes.post(path,   kobolHandler)
                    "PUT"    -> cfg.routes.put(path,    kobolHandler)
                    "DELETE" -> cfg.routes.delete(path, kobolHandler)
                    "PATCH"  -> cfg.routes.patch(path,  kobolHandler)
                    else     -> cfg.routes.addEndpoint(Endpoint(HandlerType.findOrCreate(method), path, kobolHandler))
                }
            }
        }

        javalin.start(port)
        app = javalin
    }

    /** Block calling thread until JVM shutdown. Call after start() in production main. */
    @JvmStatic fun awaitShutdown() {
        val latch = CountDownLatch(1)
        Runtime.getRuntime().addShutdownHook(Thread(latch::countDown, "kobol-server-shutdown"))
        try {
            latch.await()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    @JvmStatic fun stop() {
        app?.stop()
        app = null
        pending.clear()
    }

    @JvmStatic fun respond(value: String)      { tlResponse.set(value) }
    @JvmStatic fun currentResponse(): String   = tlResponse.get()
    @JvmStatic fun currentBody(): String       = tlBody.get()
    @JvmStatic fun respondStatus(code: Int)    { tlStatus.set(code) }
    @JvmStatic fun currentPathParam(name: String): String  = tlPathParams.get()[name] ?: ""
    @JvmStatic fun currentQueryParam(name: String): String = tlQueryParams.get()[name]?.firstOrNull() ?: ""
    @JvmStatic fun currentRequestHeader(name: String): String = tlRequestHeaders.get()[name] ?: ""
}
