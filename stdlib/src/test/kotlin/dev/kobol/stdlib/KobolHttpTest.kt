package dev.kobol.stdlib

import io.javalin.Javalin
import io.javalin.http.Context
import io.javalin.http.Handler
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration tests for KobolHttp.
 * Each test starts a real local Javalin server, exercises KobolHttp, then tears down.
 */
class KobolHttpTest {

    private var mockServer: Javalin? = null
    private var port = 0

    @BeforeEach fun startMockServer() {
        mockServer = Javalin.create { cfg ->
            cfg.routes.get("/hello", Handler { ctx: Context ->
                ctx.result("Hello, World!")
            })
            cfg.routes.get("/status/{code}", Handler { ctx: Context ->
                ctx.status(ctx.pathParam("code").toInt()).result("status-${ctx.pathParam("code")}")
            })
            cfg.routes.post("/echo", Handler { ctx: Context ->
                ctx.result(ctx.body())
            })
            cfg.routes.put("/resource", Handler { ctx: Context ->
                ctx.result("updated:${ctx.body()}")
            })
            cfg.routes.delete("/resource", Handler { ctx: Context ->
                ctx.result("deleted")
            })
            cfg.routes.patch("/resource", Handler { ctx: Context ->
                ctx.result("patched:${ctx.body()}")
            })
            cfg.routes.get("/header-echo", Handler { ctx: Context ->
                ctx.result(ctx.header("X-Custom") ?: "missing")
            })
        }.start(0)
        port = mockServer!!.port()
    }

    @AfterEach fun stopMockServer() {
        mockServer?.stop()
    }

    // ── GET ──────────────────────────────────────────────────────────────────

    @Test fun `GET returns response body`() {
        val result = KobolHttp.get("http://localhost:$port/hello", null, 5L)
        assertEquals("Hello, World!", result)
    }

    @Test fun `GET with header is sent to server`() {
        val result = KobolHttp.get(
            "http://localhost:$port/header-echo",
            "X-Custom: my-value",
            5L
        )
        assertEquals("my-value", result)
    }

    @Test fun `GET with multi-line headers are all sent`() {
        val app2 = Javalin.create { cfg ->
            cfg.routes.get("/multi", Handler { ctx: Context ->
                val a1 = ctx.header("X-A") ?: ""
                val b1 = ctx.header("X-B") ?: ""
                ctx.result("$a1-$b1")
            })
        }.start(0)
        val p2 = app2.port()
        try {
            val result = KobolHttp.get("http://localhost:$p2/multi", "X-A: foo\nX-B: bar", 5L)
            assertEquals("foo-bar", result)
        } finally {
            app2.stop()
        }
    }

    @Test fun `GET uses default timeout of 30s`() {
        val result = KobolHttp.get("http://localhost:$port/hello", null, 0L)
        assertEquals("Hello, World!", result)
    }

    @Test fun `lastStatus returns HTTP status code`() {
        KobolHttp.get("http://localhost:$port/status/404", null, 5L)
        assertEquals(404L, KobolHttp.lastStatus())
    }

    // ── POST ─────────────────────────────────────────────────────────────────

    @Test fun `POST sends body and returns response`() {
        val result = KobolHttp.post("http://localhost:$port/echo", null, "hello kobol", 5L)
        assertEquals("hello kobol", result)
    }

    @Test fun `POST with null body sends empty body`() {
        val result = KobolHttp.post("http://localhost:$port/echo", null, null, 5L)
        assertEquals("", result)
    }

    // ── PUT ──────────────────────────────────────────────────────────────────

    @Test fun `PUT sends body`() {
        val result = KobolHttp.put("http://localhost:$port/resource", null, "payload", 5L)
        assertEquals("updated:payload", result)
    }

    // ── DELETE ───────────────────────────────────────────────────────────────

    @Test fun `DELETE request succeeds`() {
        val result = KobolHttp.delete("http://localhost:$port/resource", null, 5L)
        assertEquals("deleted", result)
    }

    // ── PATCH ────────────────────────────────────────────────────────────────

    @Test fun `PATCH sends body`() {
        val result = KobolHttp.patch("http://localhost:$port/resource", null, "delta", 5L)
        assertEquals("patched:delta", result)
    }
}
